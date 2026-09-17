package org.setbd.cloner.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.setbd.cloner.data.model.CloneInfo

/**
 * Long-press action sheet for a clone: Launch / Rename / Change icon /
 * Duplicate / Delete / Clone info, plus pin-to-home and enable-disable.
 */
@Composable
fun CloneOptionsSheet(
    clone: CloneInfo,
    onLaunch: () -> Unit,
    onRename: () -> Unit,
    onChangeIcon: () -> Unit,
    onDuplicate: () -> Unit,
    onInfo: () -> Unit,
    onPin: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = clone.displayName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OptionRow(Icons.Filled.PlayArrow, "Launch", onLaunch)
        OptionRow(Icons.Filled.Edit, "Rename", onRename)
        OptionRow(Icons.Filled.Image, "Change icon", onChangeIcon)
        OptionRow(Icons.Filled.ContentCopy, "Duplicate", onDuplicate)
        OptionRow(Icons.Filled.PushPin, "Add to home screen", onPin)
        OptionRow(Icons.Filled.Info, "Clone info", onInfo)
        OptionRow(
            if (clone.enabled) Icons.Filled.ToggleOn else Icons.Filled.ToggleOff,
            if (clone.enabled) "Disable clone" else "Enable clone",
            onToggleEnabled
        )
        OptionRow(Icons.Filled.Delete, "Delete", onDelete, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

@Composable
fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename clone") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
