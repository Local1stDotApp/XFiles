package app.local1st.files.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.ops.FileOp
import app.local1st.files.core.prefs.SortBy
import app.local1st.files.core.util.Format
import app.local1st.files.core.util.IntentUtils
import app.local1st.files.di.Graph
import app.local1st.files.ui.main.MainViewModel
import kotlinx.coroutines.launch

/**
 * Renders the dialog requested via [MainViewModel.dialog].
 * (Baseline implementation; visual polish iterates here.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDialogs(vm: MainViewModel) {
    val request by vm.dialog.collectAsState()
    val dismiss = { vm.dialog.value = null }

    when (val req = request) {
        null -> Unit

        is DialogRequest.ConfirmDelete -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Delete") },
            text = {
                val names = req.entries.take(3).joinToString(", ") { it.name }
                val extra = if (req.entries.size > 3) " and ${req.entries.size - 3} more" else ""
                Text("Delete $names$extra? This cannot be undone.")
            },
            confirmButton = {
                Button(onClick = { vm.performDelete(req.entries) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )

        is DialogRequest.Rename -> NameDialog(
            title = "Rename",
            initial = req.entry.name,
            confirmLabel = "Rename",
            onDismiss = dismiss,
            onConfirm = { vm.performRename(req.entry, it) },
        )

        is DialogRequest.NewFolder -> NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = dismiss,
            onConfirm = { vm.performNewFolder(req.parent, it) },
        )

        is DialogRequest.CompressTo -> NameDialog(
            title = "Create zip in ${req.destDir.name}",
            initial = (req.sources.firstOrNull()?.name?.substringBeforeLast('.') ?: "archive") + ".zip",
            confirmLabel = "Compress",
            onDismiss = dismiss,
            onConfirm = { vm.performCompress(req.sources, req.destDir, it) },
        )

        is DialogRequest.Details -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(req.entry.name) },
            text = {
                Column {
                    Text("Location: ${req.entry.id}")
                    if (!req.entry.isDir) Text("Size: ${Format.bytes(req.entry.size)}")
                    Text("Modified: ${Format.dateTime(req.entry.mtime)}")
                    req.entry.mime?.let { Text("Type: $it") }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
        )

        DialogRequest.SortOptions -> SortDialog(onDismiss = dismiss)

        is DialogRequest.EntryMenu -> ModalBottomSheet(onDismissRequest = dismiss) {
            EntryMenuContent(vm, req, dismiss)
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank() && !text.contains('/'),
                onClick = { onConfirm(text.trim()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SortDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sortBy by Graph.settings.sortBy.collectAsState(initial = SortBy.NAME)
    val descending by Graph.settings.sortDescending.collectAsState(initial = false)
    val dirsFirst by Graph.settings.dirsFirst.collectAsState(initial = true)
    val showHidden by Graph.settings.showHidden.collectAsState(initial = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort & view") },
        text = {
            Column {
                SortBy.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = sortBy == option,
                            onClick = { scope.launch { Graph.settings.setSortBy(option) } },
                        )
                        Text(option.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
                ToggleRow("Descending", descending) { scope.launch { Graph.settings.setSortDescending(it) } }
                ToggleRow("Folders first", dirsFirst) { scope.launch { Graph.settings.setDirsFirst(it) } }
                ToggleRow("Show hidden", showHidden) { scope.launch { Graph.settings.setShowHidden(it) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun EntryMenuContent(
    vm: MainViewModel,
    req: DialogRequest.EntryMenu,
    dismiss: () -> Unit,
) {
    val entry = req.entry
    val context = Graph.appContext
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            entry.name,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (entry.kind == EntryKind.APP) {
            MenuItem("Launch") { IntentUtils.launchApp(context, entry.path); dismiss() }
            MenuItem("Open as zip") { vm.openAppAsZip(entry); dismiss() }
            MenuItem("Details") { vm.showAppDetails(entry.path); dismiss() }
            MenuItem("System info") { IntentUtils.appInfo(context, entry.path); dismiss() }
            entry.localPath?.let {
                MenuItem("Copy APK to other pane") {
                    vm.inactiveCtrl.focusedDirEntry()?.let { dest ->
                        Graph.opEngine.submit(FileOp.Copy(listOf(entry), dest, move = false))
                    }
                    dismiss()
                }
            }
            MenuItem("Uninstall") { IntentUtils.uninstall(context, entry.path); dismiss() }
            return@Column
        }

        MenuItem("Details") { vm.dialog.value = DialogRequest.Details(entry) }

        if (!entry.isDir) {
            MenuItem("Open with…") { IntentUtils.openWith(context, entry); dismiss() }
            MenuItem("Open as text") { vm.openAsText(entry); dismiss() }
            MenuItem("Open as hex") { vm.openAsHex(entry); dismiss() }
            MenuItem("Share") { vm.shareSelection(listOf(entry)); dismiss() }
        }
        // Explicit copy/move to a chosen folder (works from a single item too).
        MenuItem("Copy to…") { vm.copySelection(move = false, sources = listOf(entry)); dismiss() }
        // Move deletes the source, so only when the source itself is writable (not a read-only
        // root entry or an archive member).
        if (entry.canWrite) {
            MenuItem("Move to…") { vm.copySelection(move = true, sources = listOf(entry)); dismiss() }
        }
        MenuItem("Zip…") { vm.requestCompress(listOf(entry)); dismiss() }
        if (entry.kind == EntryKind.ARCHIVE) {
            MenuItem("Extract to…") {
                vm.extractArchive(entry)
                dismiss()
            }
            if (entry.extension == "apk") {
                entry.localPath?.let { MenuItem("Install") { IntentUtils.installApk(context, it); dismiss() } }
            }
        }
        if (entry.canWrite) {
            MenuItem("Rename") { vm.requestRename(entry) }
            MenuItem("Delete") { vm.requestDelete(listOf(entry)) }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}
