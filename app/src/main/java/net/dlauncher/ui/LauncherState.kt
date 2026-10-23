package net.dlauncher.ui

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dlauncher.BuildConfig
import net.dlauncher.auth.*
import net.dlauncher.data.*
import net.dlauncher.launch.LaunchBridge
import net.dlauncher.launch.LaunchSpecBuilder
import java.io.File
import java.util.UUID

class LauncherState(ctx: Context, private val root: File, private val scope: CoroutineScope, private val bridge: LaunchBridge) {
    private val api = MojangApi()
    private val installer = Installer(api, root)
    private val store = InstallationStore(File(root, "installations.json"))
    private val accounts = AccountStore(ctx)
    private val auth = MicrosoftAuth(BuildConfig.MS_CLIENT_ID)

    var installations by mutableStateOf(store.load()); private set
    var selectedId by mutableStateOf(installations.firstOrNull()?.id)
    var account by mutableStateOf(accounts.load()); private set
    var versions by mutableStateOf<List<VersionRef>>(emptyList()); private set
    var news by mutableStateOf<List<PatchNote>>(emptyList()); private set
    var status by mutableStateOf("Ready"); private set
    var progress by mutableFloatStateOf(0f); private set
    var busy by mutableStateOf(false); private set
    var deviceCode by mutableStateOf<MicrosoftAuth.DeviceCode?>(null); private set

    init {
        scope.launch { runCatching { versions = api.manifest().versions } }
        scope.launch { runCatching { news = api.patchNotes() } }
    }

    private fun save(list: List<Installation>) { installations = list; store.save(list) }
    private fun setAccount(a: Account) { account = a; accounts.save(a) }

    fun delete(i: Installation) {
        save(installations - i)
        if (selectedId == i.id) selectedId = installations.firstOrNull()?.id
        File(root, "instances/${i.id}").deleteRecursively()
    }

    fun addInstallation(name: String, mc: String, fabric: Boolean, ramMb: Int, jvm: String) = scope.launch {
        busy = true
        try {
            val vid = if (fabric) { status = "Installing Fabric loader…"; installer.installFabric(mc) } else mc
            val inst = Installation(UUID.randomUUID().toString().take(8), name.ifBlank { vid }, vid, ramMb, jvm)
            save(installations + inst); selectedId = inst.id; status = "Created ${inst.name}"
        } catch (e: Throwable) { status = "Error: ${e.message}" }
        busy = false
    }

    fun play(inst: Installation) = scope.launch {
        val acc0 = account ?: run { status = "Sign in first"; return@launch }
        busy = true; progress = 0f
        try {
            val acc = auth.ensureFresh(acc0).also { if (it != acc0) setAccount(it) }
            status = "Resolving ${inst.versionId}…"
            val installed = installer.install(installer.resolve(inst.versionId)) { d, t ->
                progress = d / t.toFloat(); status = "Downloading $d / $t"
            }
            val gameDir = File(root, "instances/${inst.id}").apply { mkdirs() }
            val spec = LaunchSpecBuilder.build(installed, root, gameDir, acc, inst.ramMb, inst.jvmArgs)
            save(installations.map { if (it.id == inst.id) it.copy(lastPlayed = System.currentTimeMillis()) else it })
            status = "Launching with Java ${spec.javaMajor}…"
            bridge.launch(spec)
        } catch (e: Throwable) { status = "Error: ${e.message}" }
        busy = false
    }

    fun loginMicrosoft() = scope.launch {
        try {
            val dc = auth.requestDeviceCode(); deviceCode = dc
            val acc = auth.pollForLogin(dc); setAccount(acc); status = "Signed in as ${acc.name}"
        } catch (e: Throwable) { status = "Login failed: ${e.message}" }
        deviceCode = null
    }

    fun loginOffline(name: String) { setAccount(Account(name, offlineUuid(name).toString())); status = "Offline as $name" }
    fun logout() { accounts.clear(); account = null }
}
