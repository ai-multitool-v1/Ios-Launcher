package org.setbd.cloner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleShortcutIntent(intent)
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
