package app.local1st.files.ui.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.TrashFileSystem
import app.local1st.files.core.fs.TrashPaths
import app.local1st.files.core.fs.XEntry
import app.local1st.files.R
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.EntryIcon
import app.local1st.files.ui.browser.pathInsidePaneRoots
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.main.PendingTransfer
import app.local1st.files.ui.main.isFileOperationDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional full-screen folder chooser reached from an entry's long-press menu. The primary
 * copy/move actions use the other pane directly; this screen is the explicit-location escape hatch.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun DestinationPickerScreen(
    vm: MainViewModel,
    transfer: PendingTransfer,
    onBack: () -> Unit,
) {
    val t = transfer
    val scope = rememberCoroutineScope()

    // null = the top-level roots list; otherwise the directory being shown.
    var current by remember(t) { mutableStateOf<XEntry?>(null) }
    var folders by remember { mutableStateOf<List<XEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var nameDialog by remember { mutableStateOf(false) }
    var listedId by remember(t) { mutableStateOf<String?>(null) }
    var hasListing by remember(t) { mutableStateOf(false) }
    val volumeRoots = Graph.roots.mountedVolumes.collectAsStateWithLifecycle().value.map { it.path }
    val volumeEpoch by Graph.roots.volumeEpoch.collectAsStateWithLifecycle()
    val binTitle = stringResource(R.string.recycle_bin)

    fun goUp(from: XEntry) {
        scope.launch { current = withContext(Dispatchers.IO) { parentOf(from) } }
    }

    // Nested folder navigation consumes Back locally. At the picker root the host handles it,
    // including the system predictive-back animation to the browser destination.
    BackHandler(enabled = current != null) {
        current?.let(::goUp)
    }

    LaunchedEffect(t) {
        current = t.startDirId?.let { id ->
            withContext(Dispatchers.IO) {
                pickerStartDir(resolvePickerDir(id), Graph.freshMountedVolumePaths())
            }
        }
    }

    LaunchedEffect(current, reloadTick, volumeEpoch) {
        val loadId = current?.id
        val refreshInPlace = hasListing && listedId == loadId
        if (!refreshInPlace) loading = true
        error = null
        val cur = current
        if (cur == null) {
            // Roots (volumes, Root) keep their natural order.
            folders = withContext(Dispatchers.IO) { pickerRootFolders() }
            listedId = loadId
            hasListing = true
            loading = false
            return@LaunchedEffect
        }
        val listing = withContext(Dispatchers.IO) {
            runCatching { Graph.fsRegistry.forEntry(cur).list(cur) }
        }
        if (listing.isFailure) {
            val roots = withContext(Dispatchers.IO) {
                runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
            }
            val topLevelIds = roots.mapTo(HashSet()) { it.id }
            if (pathInsidePaneRoots(cur.id, topLevelIds) == null) {
                // Volume unmounted. Publish roots in this frame so the header never
                // reads "This device" above the vanished folder's rows.
                folders = roots.filter(::isPickerRoot)
                listedId = null
                hasListing = true
                current = null
                loading = false
                return@LaunchedEffect
            }
        }
        folders = listing.fold(
            onSuccess = { list ->
                list.filter { it.isDir }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            },
            onFailure = { error = it.message ?: Graph.appContext.getString(R.string.cannot_open_folder); emptyList() },
        )
        listedId = loadId
        hasListing = true
        loading = false
    }

    val canConfirm = isFileOperationDestination(current, volumeRoots)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (t.move) stringResource(R.string.move_to_title)
                            else stringResource(R.string.copy_to_title),
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
                        onClick = onBack,
                    )
                },
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
            ) {
                Text(
                    current?.let { pickerPathLabel(it, volumeRoots, binTitle) }
                        ?: stringResource(R.string.this_device),
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
                                        label = "..",
                                        onClick = { current?.let { goUp(it) } },
                                    ) {
                                        Icon(
                                            Icons.Outlined.ArrowUpward,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                            items(folders, key = { it.id }) { folder ->
                                PickerRow(label = folder.name, onClick = { current = folder }) {
                                    EntryIcon(
                                        folder,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
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
                        // canConfirm already implies a non-null, writable directory.
                        enabled = canConfirm,
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
                            if (t.move) stringResource(R.string.move_here)
                            else stringResource(R.string.copy_here),
                        )
                    }
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
    val parentId = pickerParentId(dir, Graph.freshMountedVolumePaths()) ?: return null
    if (parentId == XId.TRASH_ROOT) {
        return runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
            .firstOrNull { it.id == XId.TRASH_ROOT }
            ?: TrashFileSystem.rootEntry()
    }
    return resolvePickerDir(parentId)
}

/**
 * Parent shown by Copy to / Move to. A top-level trashed folder's file parent is the
 * hidden bucket; climbing that path would list `files` and `info` instead of the bin.
 */
/** Copy to / Move to cannot confirm a bin directory, so do not open already inside one. */
internal fun pickerStartDir(resolved: XEntry?, volumeRoots: List<String>): XEntry? {
    if (resolved == null) return null
    if (resolved.id == XId.TRASH_ROOT || resolved.kind == EntryKind.RECYCLE_BIN) return null
    if (resolved.scheme == XId.SCHEME_FILE &&
        TrashPaths.isInsideVolumeBin(resolved.localPath ?: resolved.path, volumeRoots)
    ) {
        return null
    }
    return resolved
}

internal fun pickerParentId(dir: XEntry, volumeRoots: List<String>): String? {
    if (dir.kind == EntryKind.VOLUME_INTERNAL || dir.kind == EntryKind.VOLUME_SD ||
        dir.kind == EntryKind.VOLUME_USB || dir.kind == EntryKind.ROOT ||
        dir.kind == EntryKind.LOCATION || dir.kind == EntryKind.RECYCLE_BIN
    ) return null
    val fileParent = XId.parent(dir.id)
    if (dir.scheme != XId.SCHEME_FILE) return fileParent
    val path = dir.localPath ?: dir.path
    if (!TrashPaths.isInsideVolumeBin(path, volumeRoots)) return fileParent
    if (TrashPaths.topLevel(path) != null || !underVisibleTrashPayload(path)) return XId.TRASH_ROOT
    return fileParent
}

private fun underVisibleTrashPayload(path: String): Boolean {
    var cursor = path.trimEnd('/')
    val slash = cursor.lastIndexOf('/')
    if (slash <= 0) return false
    cursor = cursor.substring(0, slash)
    while (cursor.isNotEmpty()) {
        if (TrashPaths.topLevel(cursor) != null) return true
        val cut = cursor.lastIndexOf('/')
        if (cut <= 0) return false
        cursor = cursor.substring(0, cut)
    }
    return false
}

/**
 * [XFileSystem.stat] returns null for live Android/data (and similar) folders whose
 * [XFileSystem.list] still works through the privileged fallback. Only treat a path
 * as gone when it is no longer under a mounted pane root.
 */
private fun resolvePickerDir(id: String): XEntry? {
    if (id == XId.TRASH_ROOT) {
        runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
            .firstOrNull { it.id == XId.TRASH_ROOT }
            ?.let { return it }
    }
    runCatching { Graph.fsRegistry.forId(id).stat(id) }.getOrNull()?.let { return it }
    val roots = runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
    return pickerDirFromId(id, roots)
}

private fun pickerRootFolders(): List<XEntry> =
    runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList()).filter(::isPickerRoot)

internal fun isPickerRoot(entry: XEntry): Boolean =
    entry.isDir && entry.kind != EntryKind.APPS_ROOT && entry.kind != EntryKind.RECYCLE_BIN

/** Placeholder used when stat is blind but the id still sits on a mounted volume. */
internal fun pickerDirFromId(id: String, roots: List<XEntry>): XEntry? {
    val topLevelIds = roots.mapTo(HashSet()) { it.id }
    if (pathInsidePaneRoots(id, topLevelIds) == null) return null
    roots.firstOrNull { it.id == id }?.let { return it }
    val path = id.substringAfter("://").trimEnd('/')
    val scheme = XId.schemeOf(id)
    val name = when (scheme) {
        XId.SCHEME_SAF -> {
            val docs = XId.safDocumentIds(id)
            if (docs.isEmpty()) path.ifEmpty { "Location" } else docs.last()
        }
        else -> path.substringAfterLast('/').ifEmpty { "/" }
    }
    val kind = when {
        scheme == XId.SCHEME_ROOT && (path.isEmpty() || path == "/") -> EntryKind.ROOT
        scheme == XId.SCHEME_SAF && XId.safDocumentIds(id).isEmpty() -> EntryKind.LOCATION
        else -> EntryKind.DIR
    }
    return XEntry(
        id = id,
        name = name,
        isDir = true,
        kind = kind,
        localPath = if (scheme == XId.SCHEME_FILE) path.ifEmpty { "/" } else null,
    )
}

internal fun pickerPathLabel(dir: XEntry, volumeRoots: List<String>, binName: String): String =
    when (dir.scheme) {
        XId.SCHEME_ROOT -> "root:" + dir.path
        XId.SCHEME_SAF, XId.SCHEME_TRASH -> dir.name
        XId.SCHEME_FILE -> visibleBinLabel(dir.localPath ?: dir.path, volumeRoots, binName) ?: dir.path
        else -> dir.path
    }

/**
 * Subtitle for a search hit. A bin parent shows the recycle-bin name and the
 * visible folder chain, never `.xfiles-trash/files/<id>`.
 */
internal fun searchHitLocation(parentId: String, volumeRoots: List<String>, binName: String): String {
    if (XId.schemeOf(parentId) == XId.SCHEME_TRASH) return binName
    val path = parentId.substringAfter("://")
    return visibleBinLabel(path, volumeRoots, binName) ?: path
}

/**
 * Details location. A bin row's badge is the original folder; every other badge
 * (free space, root mode, "Not available") is not where the entry is.
 */
internal fun detailLocationOf(
    entry: XEntry,
    path: String,
    volumeRoots: List<String>,
    binName: String,
): String {
    val binLabel = visibleBinLabel(path, volumeRoots, binName) ?: return entry.id
    return entry.badge?.takeIf { it.isNotBlank() } ?: binLabel
}

/** Folder chain under the bin name. Hides `.xfiles-trash/files/<id>`. */
internal fun visibleBinLabel(path: String, volumeRoots: List<String>, binName: String): String? {
    val normalized = if (path.length > 1) path.trimEnd('/') else path
    val inside = TrashPaths.isConcealedBinPath(normalized, volumeRoots)
    if (!inside) return null
    val marker = "/${TrashPaths.DIR_NAME}/files/"
    val idx = normalized.indexOf(marker)
    if (idx < 0) return binName
    val rest = normalized.substring(idx + marker.length)
    val slash = rest.indexOf('/')
    if (slash <= 0 || slash == rest.lastIndex) return binName
    val id = rest.substring(0, slash)
    if (!TrashPaths.isId(id)) return binName
    val relative = rest.substring(slash + 1)
    if (relative.isEmpty()) return binName
    return "$binName/$relative"
}

@Composable
private fun PickerRow(
    label: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
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
