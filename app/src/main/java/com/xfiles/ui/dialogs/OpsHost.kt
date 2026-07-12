package com.xfiles.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.core.ops.ConflictChoice
import com.xfiles.core.ops.ConflictResolution
import com.xfiles.core.ops.OpState
import com.xfiles.di.Graph
import com.xfiles.ui.main.MainViewModel

/** Floating progress cards + conflict dialogs for running file operations. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpsHost(vm: MainViewModel) {
    val ops by Graph.opEngine.active.collectAsState()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 96.dp),
    ) {
        ops.forEach { op ->
            key(op.id) {
            val progress by op.progress.collectAsState()
            val finished = progress.state == OpState.DONE || progress.state == OpState.CANCELLED
            if (!finished) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(progress.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                progress.currentItem,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { op.cancel() }) {
                            Icon(Icons.Outlined.Close, "Cancel")
                        }
                    }
                    if (progress.state == OpState.SCANNING) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            val conflict by op.pendingConflict.collectAsState()
            conflict?.let { c ->
                var applyToAll by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Name conflict") },
                    text = {
                        Column {
                            Text("“${c.existingName}” already exists in the destination.")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                                Text("Apply to all")
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                op.resolveConflict(ConflictResolution(ConflictChoice.SKIP, applyToAll))
                            }) { Text("Skip") }
                            TextButton(onClick = {
                                op.resolveConflict(ConflictResolution(ConflictChoice.RENAME, applyToAll))
                            }) { Text("Keep both") }
                            Button(onClick = {
                                op.resolveConflict(ConflictResolution(ConflictChoice.OVERWRITE, applyToAll))
                            }) { Text("Overwrite") }
                        }
                    },
                )
            }
            }
            }
        }
    }
}
