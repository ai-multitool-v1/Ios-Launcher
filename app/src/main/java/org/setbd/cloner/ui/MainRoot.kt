package org.setbd.cloner.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import org.setbd.cloner.data.SettingsStore
import org.setbd.cloner.ui.clone.CloneInfoScreen
import org.setbd.cloner.ui.home.HomeScreen
import org.setbd.cloner.ui.importing.ImportScreen
import org.setbd.cloner.ui.settings.SettingsScreen
import org.setbd.cloner.ui.splash.SplashScreen
import org.setbd.cloner.util.ClonerLog

/** Lightweight in-app navigation targets. */
sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Home : Screen("home")
    data object Import : Screen("import")
    data object Settings : Screen("settings")
    data class CloneInfo(val cloneId: Long) : Screen("info/$cloneId")

    companion object {
        fun fromRoute(route: String): Screen = when {
            route.startsWith("info/") ->
                Screen.CloneInfo(route.removePrefix("info/").toLongOrNull() ?: 0L)
            route == "import" -> Import
            route == "settings" -> Settings
            route == "home" -> Home
            else -> Splash
        }
    }
}

/**
 * Root composable: splash → home, plus import / settings / clone-info
 * destinations with back-stack behavior.
 */
@Composable
fun MainRoot(viewModel: ClonerViewModel) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf("splash") }
    var screen = Screen.fromRoute(route)
    val snackbarHostState = remember { SnackbarHostState() }
    val telegramUrl by viewModel.telegramUrl.collectAsState()

    // Auto-advance from splash.
    LaunchedEffect(Unit) {
        if (route == "splash") {
            delay(2400)
            route = "home"
        }
    }

    // Launch feedback → snackbar.
    val feedback by viewModel.lastLaunchFeedback.collectAsState()
    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeLaunchFeedback()
        }
    }

    // Import result → snackbar.
    val importState by viewModel.importState.collectAsState()
    LaunchedEffect(importState) {
        when (val state = importState) {
            is ClonerViewModel.ImportState.Success -> {
                snackbarHostState.showSnackbar("Imported as ${state.clone.displayName}")
                viewModel.dismissImportState()
            }
            is ClonerViewModel.ImportState.Failure -> {
                snackbarHostState.showSnackbar("Import failed: ${state.message}")
                viewModel.dismissImportState()
            }
            else -> Unit
        }
    }

    // Update availability → snackbar routing to settings.
    val updateState by viewModel.updateState.collectAsState()
    val autoCheck by viewModel.autoUpdateCheck.collectAsState()
    LaunchedEffect(autoCheck) {
        if (autoCheck && route != "splash" && updateState is ClonerViewModel.UpdateState.Idle) {
            delay(1500)
            viewModel.checkForUpdates()
        }
    }
    LaunchedEffect(updateState) {
        if (updateState is ClonerViewModel.UpdateState.Available) {
            val result = snackbarHostState.showSnackbar(
                "Update available on GitHub",
                actionLabel = "Open"
            )
            if (result == SnackbarResult.ActionPerformed) {
                route = "settings"
            }
        }
    }

    BackHandler(enabled = route != "home" && route != "splash") {
        route = "home"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Crossfade(
            targetState = screen,
            modifier = Modifier.padding(padding),
            label = "screen"
        ) { current ->
            when (current) {
                is Screen.Splash -> SplashScreen(
                    developerCredit = "Developed by ${SettingsStore.BrandingDefaults.DEVELOPER}",
                    telegramLabel = "@setbd_dev",
                    onTelegram = { openTelegram(context, telegramUrl) }
                )
                is Screen.Home -> HomeScreen(
                    viewModel = viewModel,
                    onImport = { route = "import" },
                    onSettings = { route = "settings" },
                    onCloneInfo = { cloneId -> route = "info/$cloneId" }
                )
                is Screen.Import -> ImportScreen(
                    viewModel = viewModel,
                    onClose = { route = "home" }
                )
                is Screen.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    onClose = { route = "home" }
                )
                is Screen.CloneInfo -> CloneInfoScreen(
                    viewModel = viewModel,
                    cloneId = current.cloneId,
                    onClose = { route = "home" }
                )
            }
        }
    }
}

internal fun openTelegram(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { ClonerLog.w("MainRoot", "cannot open telegram url $url") }
}
