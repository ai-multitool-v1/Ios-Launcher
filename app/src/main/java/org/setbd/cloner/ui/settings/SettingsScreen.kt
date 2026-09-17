package org.setbd.cloner.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.setbd.cloner.BuildConfig
import org.setbd.cloner.data.SettingsStore
import org.setbd.cloner.data.ThemeMode
import org.setbd.cloner.ui.ClonerViewModel
import org.setbd.cloner.ui.openTelegram

/**
 * Settings: appearance, engine compatibility behavior, updates, storage,
 * about (developer credit + Telegram) and the honest capabilities card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ClonerViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsState()
    val fallback by viewModel.fallbackLaunch.collectAsState()
    val autoUpdate by viewModel.autoUpdateCheck.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val telegramUrl by viewModel.telegramUrl.collectAsState()
    var showThemePicker by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val update = (updateState as? ClonerViewModel.UpdateState.Available)?.update

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Appearance") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Theme", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    TextButton(onClick = { showThemePicker = true }) { Text("Change") }
                }
            }

            SectionCard("Engine behavior") {
                ToggleRow(
                    title = "Compatibility fallback",
                    subtitle = "When the container can't virtualize an app, open the original installed app instead (clearly announced).",
                    checked = fallback,
                    onChange = { viewModel.setFallbackLaunch(it) }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Disabling makes the engine strict: unsupported apps show an " +
                        "explicit failure and are never opened outside the container.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            SectionCard("Updates") {
                ToggleRow(
                    title = "Automatic update check",
                    subtitle = "Check GitHub Releases when the app opens.",
                    checked = autoUpdate,
                    onChange = { viewModel.setAutoUpdateCheck(it) }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Button(onClick = { viewModel.checkForUpdates() }) {
                    Text("Check for updates now")
                }
                Text(
                    "APKs are hosted on GitHub: ${BuildConfig.GITHUB_REPO}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            SectionCard("About") {
                Text(
                    "SETBD Cloner",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "Developed by ${SettingsStore.BrandingDefaults.DEVELOPER}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { openTelegram(context, telegramUrl) }) {
                    Text("Telegram: ${telegramUrl.removePrefix("https://t.me")}")
                }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/${BuildConfig.GITHUB_REPO}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Text("GitHub repository")
                }
            }

            SectionCard("What this container can and cannot do") {
                Text(
                    "SETBD Cloner runs supported apps inside its own process with " +
                        "per-clone isolated storage, using only documented Android " +
                        "mechanisms available to normal apps. It does NOT modify the " +
                        "system, bypass signatures, defeat Play Integrity, or touch " +
                        "other apps. Apps that verify their environment, use split " +
                        "APKs, or rely on guest services/providers may not work — " +
                        "this is a platform boundary, not a bug.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    viewModel.setThemeMode(mode)
                                    showThemePicker = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) { Text("Done") }
            }
        )
    }

    if (update != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateState() },
            title = { Text("Update ${update.versionName} available") },
            text = {
                Text(update.changelog.take(500))
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.downloadUpdate(update)
                    viewModel.dismissUpdateState()
                }) { Text("Download & install") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdateState() }) { Text("Later") }
            }
        )
    } else if (updateState is ClonerViewModel.UpdateState.UpToDate) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateState() },
            title = { Text("You're up to date") },
            text = { Text("Version ${BuildConfig.VERSION_NAME} is the latest release.") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissUpdateState() }) { Text("OK") }
            }
        )
    } else if (updateState is ClonerViewModel.UpdateState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateState() },
            title = { Text("Update check failed") },
            text = { Text((updateState as ClonerViewModel.UpdateState.Error).message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissUpdateState() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
