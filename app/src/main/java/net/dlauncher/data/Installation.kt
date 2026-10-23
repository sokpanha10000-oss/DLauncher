package net.dlauncher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One "installation" = one version + settings + its own game folder (instances/<id>) for saves/mods. */
@Serializable
data class Installation(
    val id: String,
    val name: String,
    val versionId: String,
    val ramMb: Int = 2048,
    val jvmArgs: String = "",
    val lastPlayed: Long = 0,
)

class InstallationStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    fun load(): List<Installation> =
        runCatching { json.decodeFromString<List<Installation>>(file.readText()) }.getOrDefault(emptyList())
    fun save(list: List<Installation>) { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(list)) }
}
