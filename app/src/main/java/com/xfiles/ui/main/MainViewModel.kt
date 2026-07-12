package com.xfiles.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.fs.EntryKind
import com.xfiles.core.fs.XEntry
import com.xfiles.core.fs.XId
import com.xfiles.core.ops.FileOp
import com.xfiles.core.util.FileCategory
import com.xfiles.core.util.FileTypes
import com.xfiles.core.util.IntentUtils
import com.xfiles.di.Graph
import com.xfiles.ui.browser.PaneController
import com.xfiles.ui.dialogs.DialogRequest
import com.xfiles.ui.viewer.ViewerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    val panes = listOf(
        PaneController(0, viewModelScope),
        PaneController(1, viewModelScope),
    )

    /** Index of the pane operations act from (its selection) and into (the other one). */
    val activePane = MutableStateFlow(0)

    val dialog = MutableStateFlow<DialogRequest?>(null)
    val viewer = MutableStateFlow<ViewerRequest?>(null)
    val showSettings = MutableStateFlow(false)

    /** Root container for the search overlay, or null when search is closed. */
    val searchRoot = MutableStateFlow<XEntry?>(null)

    val snackbar = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val activeCtrl: PaneController get() = panes[activePane.value]
    val inactiveCtrl: PaneController get() = panes[1 - activePane.value]

    init {
        viewModelScope.launch {
            Graph.opEngine.events.collect { event ->
                panes.forEach { it.refreshDirty(event.dirtyDirIds) }
                snackbar.tryEmit(event.message)
            }
        }
    }

    fun setActivePane(index: Int) {
        activePane.value = index
    }

    // ---- opening entries ----

    fun openEntry(pane: PaneController, entry: XEntry) {
        if (entry.isContainer) {
            pane.toggleExpand(entry)
            return
        }
        if (entry.kind == EntryKind.APP) {
            dialog.value = DialogRequest.EntryMenu(entry)
            return
        }
        when (FileTypes.categoryOf(entry.name, entry.mime)) {
            FileCategory.IMAGE -> {
                val siblings = pane.siblings(entry, FileCategory.IMAGE)
                viewer.value = ViewerRequest.Image(
                    items = siblings,
                    startIndex = siblings.indexOfFirst { it.id == entry.id }.coerceAtLeast(0),
                )
            }
            FileCategory.TEXT -> viewer.value = ViewerRequest.Text(entry)
            FileCategory.AUDIO -> viewer.value =
                ViewerRequest.Media(entry, pane.siblings(entry, FileCategory.AUDIO))
            FileCategory.VIDEO -> viewer.value =
                ViewerRequest.Media(entry, pane.siblings(entry, FileCategory.VIDEO))
            FileCategory.DATABASE -> viewer.value = ViewerRequest.Hex(entry)
            FileCategory.APK, FileCategory.ARCHIVE ->
                dialog.value = DialogRequest.EntryMenu(entry)
            FileCategory.PDF, FileCategory.GENERIC -> {
                if (!IntentUtils.openWith(Graph.appContext, entry)) {
                    viewer.value = ViewerRequest.Hex(entry)
                }
            }
        }
    }

    fun openAsHex(entry: XEntry) {
        viewer.value = ViewerRequest.Hex(entry)
    }

    fun openAsText(entry: XEntry) {
        viewer.value = ViewerRequest.Text(entry)
    }

    // ---- file operations ----

    /** A copy/move awaiting a destination folder chosen in the [DestinationPicker]. */
    val pendingTransfer = MutableStateFlow<PendingTransfer?>(null)

    /**
     * Opens the destination picker for the current selection. The picker starts at the
     * other pane's folder (the X-plore dual-pane default) but lets the user browse anywhere,
     * so the destination is explicit instead of silently landing in the hidden pane.
     */
    fun copySelection(move: Boolean, sources: List<XEntry> = activeCtrl.selectionEntries()) {
        if (sources.isEmpty()) return
        pendingTransfer.value = PendingTransfer(
            sources = sources,
            move = move,
            startDirId = inactiveCtrl.focusedDirEntry()?.id,
        )
    }

    fun cancelTransfer() {
        pendingTransfer.value = null
    }

    /** Called by the picker once the user confirms a destination folder. */
    fun confirmTransfer(destDir: XEntry) {
        val transfer = pendingTransfer.value ?: return
        pendingTransfer.value = null
        if (!isValidDest(destDir)) {
            snackbar.tryEmit("Cannot write to ${destDir.name}")
            return
        }
        Graph.opEngine.submit(FileOp.Copy(transfer.sources, destDir, transfer.move))
        activeCtrl.clearSelection()
    }

    fun requestDelete(entries: List<XEntry> = activeCtrl.selectionEntries()) {
        if (entries.isEmpty()) return
        dialog.value = DialogRequest.ConfirmDelete(entries)
    }

    fun performDelete(entries: List<XEntry>) {
        dialog.value = null
        Graph.opEngine.submit(FileOp.Delete(entries))
        activeCtrl.clearSelection()
    }

    fun requestCompress() {
        val sources = activeCtrl.selectionEntries()
        val dest = inactiveCtrl.focusedDirEntry() ?: return
        if (sources.isEmpty()) return
        dialog.value = DialogRequest.CompressTo(sources, dest)
    }

    fun performCompress(sources: List<XEntry>, destDir: XEntry, archiveName: String) {
        dialog.value = null
        Graph.opEngine.submit(FileOp.Compress(sources, destDir, archiveName))
        activeCtrl.clearSelection()
    }

    /** Extract an archive into a new folder (named after it) in the other pane. */
    fun extractArchive(archive: XEntry) {
        val dest = inactiveCtrl.focusedDirEntry() ?: return
        if (!isValidDest(dest)) {
            snackbar.tryEmit("Cannot write to ${dest.name}")
            return
        }
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val archiveRoot = archive.copy(kind = EntryKind.ARCHIVE)
                    val children = Graph.fsRegistry.forEntry(archiveRoot).list(archiveRoot)
                    val folderName = archive.name.substringBeforeLast('.').ifBlank { archive.name }
                    val folder = Graph.fsRegistry.forEntry(dest).mkdir(dest, folderName)
                    folder to children
                }
            }
            prepared.fold(
                onSuccess = { (folder, children) ->
                    if (children.isEmpty()) {
                        snackbar.tryEmit("${archive.name} is empty")
                    } else {
                        inactiveCtrl.expand(folder)
                        Graph.opEngine.submit(FileOp.Copy(children, folder, move = false))
                    }
                },
                onFailure = { snackbar.tryEmit(it.message ?: "Cannot extract ${archive.name}") },
            )
        }
    }

    fun requestNewFolder() {
        val parent = activeCtrl.focusedDirEntry() ?: return
        if (!isValidDest(parent)) {
            snackbar.tryEmit("Cannot create folder in ${parent.name}")
            return
        }
        dialog.value = DialogRequest.NewFolder(parent)
    }

    fun performNewFolder(parent: XEntry, name: String) {
        dialog.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Graph.fsRegistry.forEntry(parent).mkdir(parent, name) }
            }
            result.fold(
                onSuccess = {
                    activeCtrl.expand(parent)
                    panes.forEach { it.refresh(parent.id) }
                },
                onFailure = { snackbar.tryEmit(it.message ?: "Cannot create folder") },
            )
        }
    }

    fun requestRename(entry: XEntry) {
        dialog.value = DialogRequest.Rename(entry)
    }

    fun performRename(entry: XEntry, newName: String) {
        dialog.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Graph.fsRegistry.forId(entry.id).rename(entry, newName) }
            }
            result.fold(
                onSuccess = {
                    XId.parent(entry.id)?.let { parent -> panes.forEach { it.refresh(parent) } }
                },
                onFailure = { snackbar.tryEmit(it.message ?: "Rename failed") },
            )
        }
    }

    fun shareSelection(entries: List<XEntry> = activeCtrl.selectionEntries()) {
        IntentUtils.share(Graph.appContext, entries.filter { !it.isDir })
    }

    fun openSearch() {
        searchRoot.value = activeCtrl.focusedDirEntry()
    }

    /** Navigate the active pane to a search hit and close the overlay. */
    fun revealSearchHit(entryId: String) {
        searchRoot.value = null
        activeCtrl.revealPath(entryId)
    }

    private fun isValidDest(dest: XEntry): Boolean =
        dest.canWrite && dest.kind != EntryKind.APPS_ROOT && dest.kind != EntryKind.APP
}

/** A copy or move whose destination folder is being chosen. */
data class PendingTransfer(
    val sources: List<XEntry>,
    val move: Boolean,
    val startDirId: String?,
)
