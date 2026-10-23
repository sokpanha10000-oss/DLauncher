package net.dlauncher.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Resolves version JSONs (incl. inheritsFrom), installs Fabric, downloads game files. */
class Installer(private val api: MojangApi, private val root: File) {

    suspend fun resolve(id: String): VersionMeta {
        val f = File(root, "versions/$id/$id.json")
        val meta: VersionMeta = if (f.exists()) api.json.decodeFromString(f.readText()) else {
            val ref = api.manifest().versions.firstOrNull { it.id == id } ?: error("Unknown version $id")
            val txt = api.text(ref.url)
            f.parentFile?.mkdirs(); f.writeText(txt)
            api.json.decodeFromString(txt)
        }
        return meta.inheritsFrom?.let { merge(resolve(it), meta) } ?: meta
    }

    private fun merge(p: VersionMeta, c: VersionMeta) = c.copy(
        mainClass = c.mainClass.ifEmpty { p.mainClass },
        downloads = c.downloads ?: p.downloads,
        assetIndex = c.assetIndex ?: p.assetIndex,
        javaVersion = c.javaVersion ?: p.javaVersion,
        type = c.type ?: p.type,
        libraries = c.libraries + p.libraries,
        arguments = mergeArgs(p.arguments, c.arguments),
        minecraftArguments = c.minecraftArguments ?: p.minecraftArguments,
    )

    private fun mergeArgs(p: JsonObject?, c: JsonObject?): JsonObject? {
        if (p == null || c == null) return p ?: c
        return buildJsonObject {
            for (k in listOf("jvm", "game"))
                put(k, JsonArray((p[k] as? JsonArray).orEmpty() + (c[k] as? JsonArray).orEmpty()))
        }
    }

    /** Writes Fabric's generated version JSON and returns its id. */
    suspend fun installFabric(mc: String): String {
        val loaders = api.json.parseToJsonElement(api.text("https://meta.fabricmc.net/v2/versions/loader/$mc")).jsonArray
        require(loaders.isNotEmpty()) { "Fabric doesn't support $mc" }
        val pick = loaders.firstOrNull { it.jsonObject["loader"]?.jsonObject?.get("stable")?.jsonPrimitive?.booleanOrNull == true } ?: loaders.first()
        val lv = pick.jsonObject["loader"]!!.jsonObject["version"]!!.jsonPrimitive.content
        val txt = api.text("https://meta.fabricmc.net/v2/versions/loader/$mc/$lv/profile/json")
        val id = api.json.parseToJsonElement(txt).jsonObject["id"]!!.jsonPrimitive.content
        File(root, "versions/$id/$id.json").apply { parentFile?.mkdirs(); writeText(txt) }
        return id
    }

    suspend fun install(meta: VersionMeta, onProgress: (done: Int, total: Int) -> Unit): InstalledVersion {
        val client = meta.downloads?.client ?: error("Version has no client download")
        val ai = meta.assetIndex ?: error("Version has no asset index")
        val jarId = meta.inheritsFrom ?: meta.id
        val clientJar = File(root, "versions/$jarId/$jarId.jar")

        // child libs come first, so distinctBy keeps the loader's newer versions
        val libs = meta.libraries.filter { it.allowed() }
            .distinctBy { l -> l.name.split(":").let { listOf(it[0], it[1], it.getOrNull(3)) } }
            .mapNotNull { it.artifact() }
        val indexFile = File(root, "assets/indexes/${ai.id}.json")
        api.fetch(ai.url, indexFile)
        val assets = api.json.decodeFromString<AssetIndex>(indexFile.readText()).objects.values.distinctBy { it.hash }

        val total = 1 + libs.size + assets.size
        val done = AtomicInteger(0)
        fun tick() = onProgress(done.incrementAndGet(), total)
        api.fetch(client.url, clientJar, client.sha1, client.size); tick()

        val gate = Semaphore(8)
        coroutineScope {
            val jobs = libs.map { a ->
                async { gate.withPermit { api.fetch(a.url, File(root, "libraries/${a.path}"), a.sha1, a.size) }; tick() }
            } + assets.map { o ->
                val p = o.hash.take(2) + "/" + o.hash
                async { gate.withPermit { api.fetch("${MojangApi.RESOURCES_URL}/$p", File(root, "assets/objects/$p"), o.hash, o.size) }; tick() }
            }
            jobs.awaitAll()
        }
        return InstalledVersion(meta, clientJar, libs.map { File(root, "libraries/${it.path}") }, ai.id)
    }
}
