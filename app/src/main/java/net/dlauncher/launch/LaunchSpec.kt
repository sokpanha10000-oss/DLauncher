package net.dlauncher.launch

import kotlinx.serialization.json.*
import net.dlauncher.auth.Account
import net.dlauncher.data.InstalledVersion
import net.dlauncher.data.rulesAllow
import java.io.File

/** Everything a JVM runtime needs. Handles both 1.13+ "arguments" and legacy "minecraftArguments". */
data class LaunchSpec(
    val javaMajor: Int,          // 8, 16/17 or 21: pick the matching bundled runtime
    val mainClass: String,
    val classpath: List<File>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val gameDir: File,
)

interface LaunchBridge { suspend fun launch(spec: LaunchSpec) }

/** TODO: wire to the Pojav runtime in your fork (see docs/FORK_PLAN.md). Use Pojav's LWJGL natives + renderer. */
class PojavLaunchBridge : LaunchBridge {
    override suspend fun launch(spec: LaunchSpec) {
        throw NotImplementedError("Needs the Pojav runtime: Java ${spec.javaMajor}, main ${spec.mainClass}")
    }
}

object LaunchSpecBuilder {
    fun build(v: InstalledVersion, root: File, gameDir: File, acc: Account, ramMb: Int, extraJvm: String): LaunchSpec {
        val meta = v.meta
        val cp = v.libraries + v.clientJar
        val assets = File(root, "assets").path
        val vars = mapOf(
            "auth_player_name" to acc.name, "auth_uuid" to acc.uuid, "auth_access_token" to acc.accessToken,
            "user_type" to if (acc.type == "msa") "msa" else "legacy", "user_properties" to "{}",
            "clientid" to "", "auth_xuid" to "",
            "version_name" to meta.id, "version_type" to (meta.type ?: "release"),
            "game_directory" to gameDir.path, "assets_root" to assets, "game_assets" to assets,
            "assets_index_name" to v.assetIndexId, "natives_directory" to File(root, "natives/${meta.id}").path,
            "launcher_name" to "DLauncher", "launcher_version" to "0.2.0",
            "classpath" to cp.joinToString(":") { it.path }, "classpath_separator" to ":",
            "library_directory" to File(root, "libraries").path,
            "resolution_width" to "854", "resolution_height" to "480",
        )
        fun sub(s: String) = Regex("\\$\\{(\\w+)}").replace(s) { vars[it.groupValues[1]] ?: it.value }

        val game = if (meta.arguments != null) args(meta.arguments["game"])
                   else meta.minecraftArguments.orEmpty().split(" ").filter { it.isNotBlank() }
        val jvm = if (meta.arguments != null) args(meta.arguments["jvm"])
                  else listOf("-Djava.library.path=\${natives_directory}", "-cp", "\${classpath}")

        return LaunchSpec(
            javaMajor = meta.javaVersion?.majorVersion ?: 8,
            mainClass = meta.mainClass,
            classpath = cp,
            jvmArgs = listOf("-Xmx${ramMb}M") + extraJvm.split(" ").filter { it.isNotBlank() } + jvm.map(::sub),
            gameArgs = game.map(::sub),
            gameDir = gameDir,
        )
    }

    private fun args(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().flatMap { el ->
        when (el) {
            is JsonPrimitive -> listOf(el.content)
            is JsonObject -> if (!rulesAllow(el["rules"] as? JsonArray)) emptyList() else when (val v = el["value"]) {
                is JsonArray -> v.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(v.content)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
