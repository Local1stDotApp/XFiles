package app.local1st.files.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.ops.FileOp
import app.local1st.files.core.util.ApkInstaller
import app.local1st.files.core.util.AppComponents
import app.local1st.files.core.util.ComponentType
import app.local1st.files.core.prefs.Favorite
import app.local1st.files.core.prefs.SessionPane
import app.local1st.files.core.prefs.SessionState
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.IntentUtils
import app.local1st.files.di.Graph
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import app.local1st.files.ui.browser.PaneController
import app.local1st.files.ui.dialogs.DialogRequest
import app.local1st.files.ui.viewer.ViewerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

    /** Package name whose rich app-details screen is showing, or null when closed. */
    val appDetails = MutableStateFlow<String?>(null)

    /** Root container for the search overlay, or null when search is closed. */
    val searchRoot = MutableStateFlow<XEntry?>(null)

    val snackbar = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val activeCtrl: PaneController get() = panes[activePane.value]
    val inactiveCtrl: PaneController get() = panes[1 - activePane.value]

    /** Startup restore, or null while storage access is still missing (see [onStorageAccessGranted]). */
    private var restoreJob: Job? = null

    /** True once the auto-saver runs; gates the final flush in [onCleared]. */
    private var persistenceStarted = false

    init {
        // Without storage access every listing fails, so restoring now would only degrade
        // the saved session (and the saver would then persist the degraded state). The
        // permission gate covers the UI until onStorageAccessGranted starts the restore.
        if (hasStorageAccess(Graph.appContext)) {
            restoreJob = viewModelScope.launch { restoreSession() }
        }
        viewModelScope.launch {
            Graph.opEngine.events.collect { event ->
                panes.forEach { it.refreshDirty(event.dirtyDirIds) }
                snackbar.tryEmit(event.message)
            }
        }
        // Root browsing is a Settings switch: mirror it into the fs gate (set before reloading,
        // so paneRoots sees the new value) and rebuild pane roots when it flips. Skip the initial
        // emission — restoreSession applies it and builds the roots itself.
        viewModelScope.launch {
            Graph.settings.rootEnabled.drop(1).collect { enabled ->
                PrivilegedAccess.enabled = enabled
                // Invalidate before reloading: cached root:// listings must not stay
                // browsable after disabling (nor keep gate errors after re-enabling),
                // and pinned root:// favorites survive the roots rebuild.
                panes.forEach {
                    it.invalidateScheme(XId.SCHEME_ROOT)
                    it.reloadRoots()
                }
            }
        }
        // Rebuild roots when favorites change so pinned shortcuts (dis)appear immediately.
        viewModelScope.launch {
            Graph.favorites.filterNotNull().distinctUntilChanged().drop(1).collect {
                panes.forEach { it.reloadRoots() }
            }
        }
    }

    /**
     * Storage access transitioned to granted. First grant (or grant after launching
     * without it): run the deferred session restore. Re-grant mid-session (access was
     * revoked and given back): keep the live session, just re-list everything through
     * the now-working permission.
     */
    fun onStorageAccessGranted() {
        val started = restoreJob
        if (started == null) {
            restoreJob = viewModelScope.launch { restoreSession() }
            return
        }
        viewModelScope.launch {
            started.join()
            coroutineScope {
                panes.forEach { pane -> launch { pane.restoreAfterGrant() } }
            }
        }
    }

    /**
     * Reopens the app where it was left: active pane, expanded tree, and focused dir
     * per pane, each validated against what still exists (see PaneController.restore).
     * Only after restoring does the debounced auto-save start, so a half-restored
     * state never overwrites the saved one.
     */
    private suspend fun restoreSession() {
        // Restore inputs must be settled first: the root gate (a saved root:// position
        // stats through it) and the favorites cache (saved ids may live under a pinned root).
        PrivilegedAccess.enabled = Graph.settings.rootEnabled.first()
        Graph.favorites.first { it != null }
        val session = Graph.settings.loadSession()
        activePane.value = session.activePane.coerceIn(0, panes.lastIndex)
        coroutineScope {
            panes.forEachIndexed { i, pane ->
                val saved = session.panes.getOrNull(i)
                launch { pane.restore(saved?.expandedIds.orEmpty(), saved?.focusedId) }
            }
        }
        startSessionPersistence()
    }

    @OptIn(FlowPreview::class)
    private fun startSessionPersistence() {
        persistenceStarted = true
        viewModelScope.launch {
            combine(panes[0].sessionState, panes[1].sessionState, activePane) { p0, p1, active ->
                SessionState(
                    panes = listOf(
                        SessionPane(p0.first, p0.second),
                        SessionPane(p1.first, p1.second),
                    ),
                    activePane = active,
                )
            }
                .debounce(500)
                .distinctUntilChanged()
                // A failed write (disk full, ...) must not crash the app or kill the
                // collector — losing one save beats losing the auto-save for the session.
                .collect { runCatching { Graph.settings.saveSession(it) } }
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, dropping any save still sitting in
        // the 500ms debounce — flush the final position on the app scope so the last
        // navigation before exit survives. Skipped if restore never ran (nothing to save,
        // and a blank flush would erase the real saved session).
        if (!persistenceStarted) return
        val state = SessionState(
            panes = panes.map { val (e, f) = it.sessionSnapshot(); SessionPane(e, f) },
            activePane = activePane.value,
        )
        Graph.appScope.launch { runCatching { Graph.settings.saveSession(state) } }
    }

    fun setActivePane(index: Int) {
        activePane.value = index
    }

    /** Pin or unpin an entry as a top-level favorite shortcut. */
    fun toggleFavorite(entry: XEntry) {
        viewModelScope.launch {
            val current = Graph.settings.favorites.first()
            val add = current.none { it.id == entry.id }
            val updated =
                if (add) current + Favorite(entry.id, entry.isDir)
                else current.filter { it.id != entry.id }
            runCatching { Graph.settings.setFavorites(updated) }.fold(
                onSuccess = {
                    snackbar.tryEmit(
                        if (add) "Added ${entry.name} to favorites"
                        else "Removed ${entry.name} from favorites",
                    )
                },
                onFailure = { snackbar.tryEmit("Couldn't update favorites") },
            )
        }
    }

    // ---- opening entries ----

    fun openEntry(pane: PaneController, entry: XEntry) {
        if (entry.isContainer) {
            // Apps and archives (incl. APKs) expand in place; long-press opens their menu.
            pane.toggleExpand(entry)
            return
        }
        if (entry.kind == EntryKind.APP_COMPONENT) {
            // A component leaf isn't a byte stream: its menu carries Launch / shortcut / copy.
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

    /** Expand an app and its base APK so the APK's zip contents show inline. */
    fun openAppAsZip(app: XEntry) {
        activeCtrl.revealAppApk(app)
    }

    /** Open the rich in-app details screen for an installed app. */
    fun showAppDetails(packageName: String) {
        appDetails.value = packageName
    }

    /** Launch a single activity component (from the component row's menu). */
    fun launchComponent(entry: XEntry) {
        val c = AppComponents.parseId(entry.id) ?: return
        if (c.type != ComponentType.ACTIVITY) return
        if (!IntentUtils.launchActivity(Graph.appContext, c.packageName, c.className)) {
            snackbar.tryEmit("Can't launch ${entry.name} — it may not be exported")
        }
    }

    /** Ask the launcher to pin a home-screen shortcut that opens this activity directly. */
    fun createComponentShortcut(entry: XEntry) {
        val c = AppComponents.parseId(entry.id) ?: return
        if (c.type != ComponentType.ACTIVITY) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = IntentUtils.createActivityShortcut(
                Graph.appContext, c.packageName, c.className, entry.name,
            )
            snackbar.tryEmit(
                if (ok) "Shortcut requested for ${entry.name}"
                else "Your launcher doesn't support pinning shortcuts",
            )
        }
    }

    /**
     * Enables or disables one component of an app: our own package via [android.content.pm.PackageManager],
     * any other package via root `pm enable`/`pm disable`. Refreshes the tree so the badge updates.
     */
    fun setComponentEnabled(entry: XEntry, enabled: Boolean) {
        val c = AppComponents.parseId(entry.id) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppComponents.setEnabled(Graph.appContext, c, enabled) }
            }
            result.fold(
                onSuccess = {
                    snackbar.tryEmit("${if (enabled) "Enabled" else "Disabled"} ${entry.name}")
                    XId.parent(entry.id)?.let { parent -> panes.forEach { it.refresh(parent) } }
                },
                onFailure = { snackbar.tryEmit(it.message ?: "Cannot change ${entry.name}") },
            )
        }
    }

    /**
     * Installs an `.apk`, or a split bundle (`.apks`) by feeding base + every split into one
     * [ApkInstaller] session — the only way to install a split app. Reads bytes off the main
     * thread; the system shows its own confirm UI and the result arrives as a toast.
     */
    fun installPackage(entry: XEntry) {
        val path = entry.localPath ?: run { snackbar.tryEmit("Nothing to install"); return }
        val label = entry.name.substringBeforeLast('.').ifBlank { entry.name }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                snackbar.tryEmit("Preparing to install $label…")
                val file = File(path)
                if (entry.extension == "apk") {
                    val source = ApkInstaller.ApkSource(file.name, file.length()) { FileInputStream(file) }
                    ApkInstaller.install(Graph.appContext, label, listOf(source))
                } else {
                    // A bundle: install every APK member (base + splits) together.
                    ZipFile(file).use { zip ->
                        val apks = buildList {
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val e = entries.nextElement()
                                if (!e.isDirectory && e.name.substringAfterLast('/').endsWith(".apk", true)) {
                                    add(ApkInstaller.ApkSource(e.name, e.size) { zip.getInputStream(e) })
                                }
                            }
                        }
                        if (apks.isEmpty()) {
                            snackbar.tryEmit("No APK inside ${entry.name}")
                            return@use
                        }
                        ApkInstaller.install(Graph.appContext, label, apks)
                    }
                }
            } catch (e: Exception) {
                snackbar.tryEmit("Install failed: ${e.message ?: "error"}")
            }
        }
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
        if (transfer.compress) {
            // Destination now chosen explicitly in the picker; ask for the archive name next.
            dialog.value = DialogRequest.CompressTo(transfer.sources, destDir)
            activeCtrl.clearSelection()
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

    /** Pick a destination folder for a new zip (like copy/move), then ask for its name. */
    fun requestCompress(sources: List<XEntry> = activeCtrl.selectionEntries()) {
        if (sources.isEmpty()) return
        pendingTransfer.value = PendingTransfer(
            sources = sources,
            move = false,
            startDirId = activeCtrl.focusedDirEntry()?.id,
            compress = true,
        )
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

/** A copy/move/extract/compress whose destination folder is being chosen. */
data class PendingTransfer(
    val sources: List<XEntry>,
    val move: Boolean,
    val startDirId: String?,
    /** Non-null for an extract: the subfolder name to create at the destination. */
    val extractArchiveName: String? = null,
    /** True when picking where to create a new zip; the name is asked afterwards. */
    val compress: Boolean = false,
)
