package app.local1st.files.ui.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.R
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.EntryIcons
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen folder chooser shown when the user copies/moves. It starts at the other
 * pane's folder (the dual-pane default) but lets the user browse anywhere and confirm an
 * explicit "Copy here" / "Move here" — so the destination is never a hidden surprise.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DestinationPicker(vm: MainViewModel) {
    val transfer by vm.pendingTransfer.collectAsState()
    val t = transfer ?: return
    val scope = rememberCoroutineScope()

    // null = the top-level roots list; otherwise the directory being shown.
    var current by remember(t) { mutableStateOf<XEntry?>(null) }
    var folders by remember { mutableStateOf<List<XEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var nameDialog by remember { mutableStateOf(false) }

    fun goUp(from: XEntry) {
        scope.launch { current = withContext(Dispatchers.IO) { parentOf(from) } }
    }

    BackHandler {
        val cur = current
        if (cur == null) vm.cancelTransfer() else goUp(cur)
    }

    LaunchedEffect(t) {
        current = t.startDirId?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching { Graph.fsRegistry.forId(id).stat(id) }.getOrNull()
            }
        }
    }

    LaunchedEffect(current, reloadTick) {
        loading = true
        error = null
        val cur = current
        folders = if (cur == null) {
            // Roots (volumes, Root) keep their natural order.
            withContext(Dispatchers.IO) {
                runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
            }.filter { it.isDir && it.kind != EntryKind.APPS_ROOT }
        } else {
            withContext(Dispatchers.IO) {
                runCatching { Graph.fsRegistry.forEntry(cur).list(cur) }
            }.fold(
                onSuccess = { list ->
                    list.filter { it.isDir }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                },
                onFailure = { error = it.message ?: Graph.appContext.getString(R.string.cannot_open_folder); emptyList() },
            )
        }
        loading = false
    }

    val canConfirm = current != null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                t.compress -> stringResource(R.string.compress_to)
                                t.extractArchiveName != null -> stringResource(R.string.extract_to)
                                t.move -> stringResource(R.string.move_to_title)
                                else -> stringResource(R.string.copy_to_title)
                            },
                        )
                        Text(
                            pluralStringResource(R.plurals.item_count_plural, t.sources.size, t.sources.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TooltipIconButton(
                        stringResource(R.string.cancel),
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        onClick = { vm.cancelTransfer() },
                    )
                },
            )

            Text(
                current?.let { pathLabel(it) } ?: stringResource(R.string.this_device),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            HorizontalDivider()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (current != null) {
                            item("__up__") {
                                PickerRow(
                                    icon = Icons.Outlined.ArrowUpward,
                                    label = "..",
                                    onClick = { current?.let { goUp(it) } },
                                )
                            }
                        }
                        items(folders, key = { it.id }) { folder ->
                            PickerRow(
                                icon = EntryIcons.forEntry(folder),
                                label = folder.name,
                                onClick = { current = folder },
                            )
                        }
                        if (folders.isEmpty() && current != null) {
                            item("__empty__") {
                                Text(
                                    error ?: stringResource(R.string.no_subfolders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { nameDialog = true },
                    enabled = canConfirm && current!!.canWrite,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_folder))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { current?.let { vm.confirmTransfer(it) } },
                    enabled = canConfirm,
                ) {
                    Text(
                        when {
                            t.compress -> stringResource(R.string.zip_here)
                            t.extractArchiveName != null -> stringResource(R.string.extract_here)
                            t.move -> stringResource(R.string.move_here)
                            else -> stringResource(R.string.copy_here)
                        },
                    )
                }
            }
        }
    }

    if (nameDialog) {
        NewFolderNameDialog(
            onDismiss = { nameDialog = false },
            onConfirm = { name ->
                nameDialog = false
                val parent = current ?: return@NewFolderNameDialog
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { Graph.fsRegistry.forEntry(parent).mkdir(parent, name) }
                    }
                    result.fold(
                        onSuccess = { current = it },
                        onFailure = {
                            vm.snackbar.tryEmit(it.message ?: Graph.appContext.getString(R.string.cannot_create_folder))
                            reloadTick++
                        },
                    )
                }
            },
        )
    }
}

private fun parentOf(dir: XEntry): XEntry? {
    // A top-level volume/root has no browsable parent in the picker → back to the roots list.
    if (dir.kind == EntryKind.VOLUME_INTERNAL || dir.kind == EntryKind.VOLUME_SD ||
        dir.kind == EntryKind.VOLUME_USB || dir.kind == EntryKind.ROOT
    ) return null
    val parentId = XId.parent(dir.id) ?: return null
    return Graph.fsRegistry.forId(parentId).stat(parentId)
}

private fun pathLabel(dir: XEntry): String = when (dir.scheme) {
    XId.SCHEME_ROOT -> "root:" + dir.path
    else -> dir.path
}

@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NewFolderNameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_folder)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            val trimmed = text.trim()
            Button(
                enabled = trimmed.isNotEmpty() && trimmed != "." && trimmed != ".." &&
                    !trimmed.contains('/') && !trimmed.contains('\\'),
                onClick = { onConfirm(trimmed) },
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
