package net.dlauncher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class Manifest(val latest: Latest, val versions: List<VersionRef>)
@Serializable data class Latest(val release: String, val snapshot: String)
@Serializable data class VersionRef(val id: String, val type: String, val url: String)

@Serializable data class Download(val url: String, val sha1: String = "", val size: Long = 0, val path: String? = null)
@Serializable data class ClientDownloads(val client: Download)
@Serializable data class AssetIndexRef(val id: String, val url: String)
@Serializable data class LibDownloads(val artifact: Download? = null)
@Serializable data class JavaVersion(val majorVersion: Int = 8)

@Serializable
data class Library(
    val name: String,
    val url: String? = null,                 // Fabric/Quilt style maven base
    val downloads: LibDownloads? = null,     // Mojang style
    val rules: JsonArray? = null,
)

@Serializable
data class VersionMeta(
    val id: String,
    val type: String? = null,
    val mainClass: String = "",
    val inheritsFrom: String? = null,
    val downloads: ClientDownloads? = null,
    val assetIndex: AssetIndexRef? = null,
    val libraries: List<Library> = emptyList(),
    val arguments: JsonObject? = null,       // 1.13+
    val minecraftArguments: String? = null,  // legacy
    val javaVersion: JavaVersion? = null,
)

@Serializable data class AssetIndex(val objects: Map<String, AssetObj>)
@Serializable data class AssetObj(val hash: String, val size: Long)
data class PatchNote(val title: String, val version: String, val type: String, val text: String)
data class InstalledVersion(val meta: VersionMeta, val clientJar: java.io.File, val libraries: List<java.io.File>, val assetIndexId: String)

/** Android is treated as Linux. Rules carrying "features" never match (we have no demo/resolution features). */
fun rulesAllow(rules: JsonArray?): Boolean {
    if (rules == null) return true
    var ok = false
    for (r in rules) {
        val o = r.jsonObject
        val os = o["os"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        if (o["features"] == null && (os == null || os == "linux")) ok = o["action"]?.jsonPrimitive?.content == "allow"
    }
    return ok
}

fun Library.allowed() = rulesAllow(rules)

fun Library.artifact(): Download? {
    downloads?.artifact?.let { return it }
    val base = url ?: return null
    val p = name.split(":")
    val cls = p.getOrNull(3)?.let { "-$it" } ?: ""
    val path = p[0].replace('.', '/') + "/${p[1]}/${p[2]}/${p[1]}-${p[2]}$cls.jar"
    return Download(base.trimEnd('/') + "/" + path, path = path)
}
