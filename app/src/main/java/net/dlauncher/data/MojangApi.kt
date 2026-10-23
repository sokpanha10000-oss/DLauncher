package net.dlauncher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class MojangApi(private val http: OkHttpClient = OkHttpClient()) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun manifest(): Manifest = json.decodeFromString(text(MANIFEST_URL))

    suspend fun patchNotes(): List<PatchNote> =
        json.parseToJsonElement(text(PATCH_NOTES_URL)).jsonObject["entries"]?.jsonArray.orEmpty().take(30).map { e ->
            val o = e.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
            PatchNote(s("title"), s("version"), s("type"), s("shortText"))
        }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "HTTP ${it.code} for $url" }
            it.body!!.string()
        }
    }

    /** Skips the download when the file exists and matches size (or sha1 if no size is known). */
    suspend fun fetch(url: String, dest: File, sha1: String = "", size: Long = 0) = withContext(Dispatchers.IO) {
        if (dest.exists()) {
            if (size > 0 && dest.length() == size) return@withContext
            if (size == 0L && (sha1.isEmpty() || dest.sha1() == sha1)) return@withContext
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} for $url" }
            r.body!!.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        }
        check(sha1.isEmpty() || tmp.sha1() == sha1) { "Checksum mismatch: $url" }
        check(tmp.renameTo(dest)) { "Could not save ${dest.name}" }
    }

    private fun File.sha1() =
        MessageDigest.getInstance("SHA-1").digest(readBytes()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val RESOURCES_URL = "https://resources.download.minecraft.net"
        const val PATCH_NOTES_URL = "https://launchercontent.mojang.com/v2/javaPatchNotes.json"
    }
}
