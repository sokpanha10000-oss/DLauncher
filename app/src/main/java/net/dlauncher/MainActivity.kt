package net.dlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import net.dlauncher.launch.PojavLaunchBridge
import net.dlauncher.ui.DLauncherApp
import net.dlauncher.ui.LauncherState
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = File(getExternalFilesDir(null) ?: filesDir, "game")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val scope = rememberCoroutineScope()
                val state = remember { LauncherState(applicationContext, root, scope, PojavLaunchBridge()) }
                DLauncherApp(state)
            }
        }
    }
}
