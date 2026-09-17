package org.setbd.cloner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.setbd.cloner.core.ShortcutManagerImpl
import org.setbd.cloner.ui.ClonerViewModel
import org.setbd.cloner.ui.MainRoot
import org.setbd.cloner.ui.theme.SetbdClonerTheme
import org.setbd.cloner.util.ClonerLog

/**
 * Single-activity host. Also the routing target for every clone shortcut —
 * shortcut intents carry the clone id and launch the virtual instance.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ClonerViewModel by viewModels()

    /**
     * Batched host-permission grant. Guest apps run inside this process, so
     * every clone inherits these grants — one dialog run at first launch,
     * exactly the standard Android flow, no bypasses.
     */
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.count { it.value }
        ClonerLog.i("MainActivity", "host permission batch resolved: $granted/${grants.size} granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleShortcutIntent(intent)
        maybeRequestHostPermissions()
        setContent {
            SetbdClonerTheme(themeMode = viewModel.themeMode.value) {
                MainRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun maybeRequestHostPermissions() {
        val missing = viewModel.hostPermissionsToRequest()
        if (missing.isEmpty()) return
        ClonerLog.i("MainActivity", "requesting ${missing.size} host permissions for guests")
        permissionRequest.launch(missing.toTypedArray())
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ShortcutManagerImpl.ACTION_LAUNCH_CLONE) {
            val cloneId = intent.getLongExtra(ShortcutManagerImpl.EXTRA_CLONE_ID, -1L)
            if (cloneId > 0) {
                ClonerLog.i("MainActivity", "shortcut launch for clone=$cloneId")
                viewModel.launchFromShortcut(cloneId)
            }
            // Consume so the intent is not re-handled on configuration changes.
            intent.action = null
        }
    }
}
