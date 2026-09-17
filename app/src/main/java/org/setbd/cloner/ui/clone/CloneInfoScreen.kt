package org.setbd.cloner.ui.clone

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.data.model.CloneLifecycle
import org.setbd.cloner.ui.ClonerViewModel
import org.setbd.cloner.util.BitmapUtils
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Per-clone detail screen: identity, lifecycle state, isolated storage usage,
 * permission mediation (host-level grants for guest needs) and POC support
 * notes, presented honestly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneInfoScreen(
    viewModel: ClonerViewModel,
    cloneId: Long,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var clone by remember { mutableStateOf<CloneInfo?>(null) }
    var usage by remember { mutableStateOf(0L) }

    LaunchedEffect(cloneId) {
        clone = viewModel.cloneInfo(cloneId)
        clone?.let { usage = viewModel.storageUsage(it) }
    }

    val current = clone

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(current?.displayName ?: "Clone info") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (current == null) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Clone not found.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Identity") {
                InfoRow("Clone ID", current.cloneId.toString())
                InfoRow("Original package", current.originalPackageName)
                InfoRow("Version", current.versionName.ifBlank { "unknown" })
                InfoRow("App label", current.appLabel)
            }

            SectionCard("Lifecycle") {
                InfoRow("State", current.lifecycleState.name)
                InfoRow("Created", formatTime(current.creationTime))
                InfoRow("Last launch", formatTime(current.lastLaunchTime))
                if (current.degraded) {
                    Text(
                        "Guest application failed to initialize — clone runs degraded.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            SectionCard("Isolated storage") {
                InfoRow("Namespace", File(current.storagePath).name)
                InfoRow("Usage", formatBytes(usage))
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.launchClone(current) }) {
                        Text("Launch")
                    }
                    OutlinedButton(onClick = { viewModel.clearGuestData(current) }) {
                        Text("Clear app data")
                    }
                }
            }

            val requested = viewModel.guestPermissions(current)
            val missing = viewModel.missingPermissions(current)
            if (requested.isNotEmpty()) {
                SectionCard("Permissions (mediated at host level)") {
                    Text(
                        "Guests run inside this app's process, so Android grants " +
                            "their runtime permissions through the host. " +
                            "Grant the ones this clone needs:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Missing: ${missing.ifEmpty { listOf("none") }.joinToString()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (missing.isEmpty()) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    if (missing.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            (context as? android.app.Activity)?.let { activity ->
                                viewModel.requestPermissions(activity, missing)
                            }
                        }) {
                            Text("Grant permissions")
                        }
                    }
                }
            }

            SectionCard("Container support (honest POC scope)") {
                SupportLine(working = true, "Activities launch in isolated clones with separate data")
                SupportLine(working = true, "Original app icon, label, version and signatures preserved")
                SupportLine(working = false, "Services, receivers and content providers of the guest")
                SupportLine(working = false, "Split-APK / dynamic-feature apps")
                SupportLine(working = false, "Apps with integrity or signature checks (banking, some messengers)")
                Text(
                    "See docs/COMPATIBILITY.md for the full Android limitation analysis.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun SupportLine(working: Boolean, text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            if (working) "✔" else "✖",
            color = if (working) androidx.compose.ui.graphics.Color(0xFF4ADE80)
            else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(24.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) "never" else DateFormat.getDateTimeInstance().format(Date(millis))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}
