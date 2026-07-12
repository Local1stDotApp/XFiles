package app.local1st.files.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.ops.FileOp
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.IntentUtils
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.PaneController
import app.local1st.files.ui.dialogs.DialogRequest
import app.local1st.files.ui.viewer.ViewerRequest
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
        val extractName = transfer.extractArchiveName
        if (extractName != null) {
            val archive = transfer.sources.first()
            viewModelScope.launch {
                val folder = withContext(Dispatchers.IO) {
                    runCatching {
                        // Never merge into a pre-existing folder: pick a free name.
                        val fs = Graph.fsRegistry.forEntry(destDir)
                        val taken = fs.list(destDir).map { it.name }.toSet()
                        var name = extractName
                        var i = 1
                        while (name in taken) name = "$extractName ($i)".also { i++ }
                        fs.mkdir(destDir, name)
                    }
                }
                folder.fold(
                    onSuccess = { Graph.opEngine.submit(FileOp.Extract(archive, it)) },
                    onFailure = { snackbar.tryEmit(it.message ?: "Cannot create folder") },
                )
            }
        } else {
            Graph.opEngine.submit(FileOp.Copy(transfer.sources, destDir, transfer.move))
        }
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

    /** Choose a folder to extract [archive] into (a new subfolder named after it). */
    fun extractArchive(archive: XEntry) {
        pendingTransfer.value = PendingTransfer(
            sources = listOf(archive),
            move = false,
            startDirId = inactiveCtrl.focusedDirEntry()?.id,
            extractArchiveName = archive.name.substringBeforeLast('.').ifBlank { archive.name },
        )
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

/** A copy/move/extract whose destination folder is being chosen. */
data class PendingTransfer(
    val sources: List<XEntry>,
    val move: Boolean,
    val startDirId: String?,
    /** Non-null for an extract: the subfolder name to create at the destination. */
    val extractArchiveName: String? = null,
)
