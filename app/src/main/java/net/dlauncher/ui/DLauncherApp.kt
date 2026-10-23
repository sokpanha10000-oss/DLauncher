package net.dlauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun DLauncherApp(s: LauncherState) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Play" to "▶", "Installs" to "▤", "News" to "✦", "Account" to "☺")
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> PlayTab(s); 1 -> InstallsTab(s); 2 -> NewsTab(s); else -> AccountTab(s) }
        }
    }
}

@Composable
fun PlayTab(s: LauncherState) {
    val sel = s.installations.firstOrNull { it.id == s.selectedId }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("DLauncher", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        Box {
            OutlinedButton({ menu = true }) { Text(sel?.let { "${it.name} (${it.versionId})" } ?: "No installation: create one in Installs") }
            DropdownMenu(menu, { menu = false }) {
                s.installations.forEach { i -> DropdownMenuItem({ Text(i.name) }, { s.selectedId = i.id; menu = false }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(s.account?.let { "Playing as ${it.name}" } ?: "Not signed in (Account tab)")
        if (s.busy) LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth().padding(vertical = 12.dp))
        Text(s.status, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button({ sel?.let { s.play(it) } }, Modifier.fillMaxWidth().height(56.dp), enabled = sel != null && s.account != null && !s.busy) { Text("PLAY") }
    }
}

@Composable
fun InstallsTab(s: LauncherState) {
    var dialog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(s.installations, key = { it.id }) { i ->
                ListItem(
                    headlineContent = { Text(i.name) },
                    supportingContent = { Text("${i.versionId} • ${i.ramMb} MB") },
                    leadingContent = { if (i.id == s.selectedId) Text("✓") },
                    trailingContent = { TextButton({ s.delete(i) }) { Text("Delete") } },
                    modifier = Modifier.clickable { s.selectedId = i.id },
                )
            }
        }
        FloatingActionButton({ dialog = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+") }
    }
    if (dialog) NewInstallDialog(s) { dialog = false }
}

@Composable
fun NewInstallDialog(s: LauncherState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("New installation") }
    var snaps by remember { mutableStateOf(false) }
    var ver by remember { mutableStateOf(s.versions.firstOrNull { it.type == "release" }?.id ?: "") }
    var fabric by remember { mutableStateOf(false) }
    var ram by remember { mutableFloatStateOf(2048f) }
    var jvm by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New installation") },
        confirmButton = {
            TextButton(onClick = { s.addInstallation(name, ver, fabric, ram.toInt(), jvm); onClose() }, enabled = ver.isNotEmpty()) { Text("Create") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Box {
                    OutlinedButton({ menu = true }) { Text("Version: $ver") }
                    DropdownMenu(menu, { menu = false }) {
                        s.versions.filter { snaps || it.type == "release" }.take(60).forEach { v ->
                            DropdownMenuItem({ Text(v.id) }, { ver = v.id; menu = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(snaps, { snaps = it }); Text("Show snapshots") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(fabric, { fabric = it }); Text("Install Fabric loader") }
                Text("RAM: ${ram.toInt()} MB")
                Slider(ram, { ram = it }, valueRange = 1024f..4096f, steps = 5)
                OutlinedTextField(jvm, { jvm = it }, label = { Text("Extra JVM args") })
            }
        },
    )
}

@Composable
fun NewsTab(s: LauncherState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.news.isEmpty()) item { Text("No news loaded (offline?)") }
        items(s.news) { n ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium)
                    Text("${n.type} ${n.version}".trim(), style = MaterialTheme.typography.labelMedium)
                    if (n.text.isNotBlank()) Text(n.text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun AccountTab(s: LauncherState) {
    val uri = LocalUriHandler.current
    var offline by remember { mutableStateOf("") }
    val a = s.account
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (a == null) "Not signed in" else "${a.name} (${if (a.type == "msa") "Microsoft" else "Offline"})", style = MaterialTheme.typography.titleMedium)
        s.deviceCode?.let { dc ->
            Card { Column(Modifier.padding(16.dp)) {
                Text("Open ${dc.verificationUri} and enter:")
                Text(dc.userCode, style = MaterialTheme.typography.headlineMedium)
                Button({ uri.openUri(dc.verificationUri) }) { Text("Open page") }
            } }
        }
        Button({ s.loginMicrosoft() }, enabled = s.deviceCode == null) { Text("Sign in with Microsoft") }
        HorizontalDivider()
        OutlinedTextField(offline, { offline = it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(16) },
            label = { Text("Offline username (singleplayer only)") }, singleLine = true)
        OutlinedButton({ s.loginOffline(offline) }, enabled = offline.length >= 3) { Text("Use offline account") }
        if (a != null) TextButton({ s.logout() }) { Text("Sign out") }
        Text(s.status, style = MaterialTheme.typography.bodySmall)
    }
}
