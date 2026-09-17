package org.setbd.cloner.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.ui.ClonerViewModel

/**
 * Mini-launcher home: one grid entry per clone, searchable, long-press for
 * per-clone management. Every tap launches the CLONE — never the original
 * installed application.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: ClonerViewModel,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onCloneInfo: (Long) -> Unit
) {
    val clones by viewModel.clones.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CloneInfo?>(null) }
    var renameTarget by remember { mutableStateOf<CloneInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<CloneInfo?>(null) }

    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        selected?.let { clone ->
            uri?.let { viewModel.setCustomIcon(clone, it) }
        }
        selected = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("SETBD Cloner") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add clone") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search clones") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            val filtered = clones.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.originalPackageName.contains(query, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                EmptyHome(query = query, onImport = onImport)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.cloneId }) { clone ->
                        CloneGridItem(
                            clone = clone,
                            viewModel = viewModel,
                            onClick = { viewModel.launchClone(clone) },
                            onLongPress = { selected = clone }
                        )
                    }
                }
            }
        }
    }

    selected?.let { clone ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            CloneOptionsSheet(
                clone = clone,
                onLaunch = {
                    viewModel.launchClone(clone)
                    selected = null
                },
                onRename = {
                    renameTarget = clone
                    selected = null
                },
                onChangeIcon = {
                    iconPicker.launch("image/*")
                },
                onDuplicate = {
                    viewModel.duplicateClone(clone)
                    selected = null
                },
                onInfo = {
                    onCloneInfo(clone.cloneId)
                    selected = null
                },
                onPin = {
                    viewModel.pinShortcut(clone)
                    selected = null
                },
                onToggleEnabled = {
                    viewModel.setEnabled(clone, !clone.enabled)
                    selected = null
                },
                onDelete = {
                    deleteTarget = clone
                    selected = null
                }
            )
        }
    }

    renameTarget?.let { clone ->
        RenameDialog(
            initialName = clone.displayName,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameClone(clone, name)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { clone ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete clone?") },
            text = {
                Text(
                    "\"${clone.displayName}\" and all of its isolated data " +
                        "(${clone.originalPackageName}) will be removed permanently."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteClone(clone)
                    deleteTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyHome(query: String, onImport: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (query.isBlank()) "No clones yet" else "No clones match \"$query\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Import an app to create its first isolated instance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onImport) {
                Text("Import an app")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloneGridItem(
    clone: CloneInfo,
    viewModel: ClonerViewModel,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val iconBitmap by produceState<Bitmap?>(initialValue = null, clone) {
        value = viewModel.cloneIcon(clone)
    }
    val running = clone.lifecycleState.isRunningLike
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap!!.asImageBitmap(),
                        contentDescription = clone.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(58.dp)
                            .alpha(if (clone.enabled) 1f else 0.4f)
                            .padding(6.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier.size(58.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            if (running) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(Color(0xFF4ADE80))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = clone.displayName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onBackground
                .copy(alpha = if (clone.enabled) 1f else 0.45f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}
