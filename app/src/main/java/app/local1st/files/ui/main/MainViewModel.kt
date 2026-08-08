package app.local1st.files.ui.main

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.local1st.files.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.ops.BackgroundJob
import app.local1st.files.core.ops.BackgroundJobs
import app.local1st.files.core.ops.FileOp
import app.local1st.files.core.ops.OpsService
import app.local1st.files.core.util.AabConverter
import app.local1st.files.core.util.ApkInstaller
import app.local1st.files.core.util.AppComponents
import app.local1st.files.core.util.ComponentType
import app.local1st.files.core.prefs.Favorite
import app.local1st.files.core.prefs.SessionState
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.ExternalOpenKind
import app.local1st.files.core.util.ExternalOpenResolver
import app.local1st.files.core.util.InstallPhase
import app.local1st.files.core.util.InstallProgress
import app.local1st.files.core.util.IntentUtils
import app.local1st.files.core.util.XapkObbInstaller
import app.local1st.files.di.Graph
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import app.local1st.files.ui.browser.PaneController
import app.local1st.files.ui.browser.RestoreListingSession
import app.local1st.files.ui.dialogs.DialogRequest
import app.local1st.files.ui.viewer.ViewerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel

/** How long an install job waits for the user to answer the system's confirmation prompt. */
private const val CONFIRMATION_TIMEOUT_MS = 5L * 60 * 1000
private const val WIDE_PANES_MIN_WIDTH_DP = 700
private const val INITIAL_PANE_LAYOUT_TIMEOUT_MS = 1_000L

private data class PaneInitialLayout(val pane: Int, val treeVersion: Long)

class MainViewModel : ViewModel() {
    private fun text(@androidx.annotation.StringRes id: Int, vararg formatArgs: Any): String =
        Graph.appContext.getString(id, *formatArgs)

    private val startupSessionLoad = Graph.claimStartupSession()
    private val preloadedSession = startupSessionLoad?.snapshot
    private val preloadedActivePane = preloadedSession?.activePane?.coerceIn(0, 1) ?: 0
    private val phoneStartupSnapshot = Graph.appContext.resources.configuration.screenWidthDp <
        WIDE_PANES_MIN_WIDTH_DP

    /** Index of the pane operations act from (its selection) and into (the other one). */
    val activePane = MutableStateFlow(preloadedActivePane)

    val panes = listOf(
        PaneController(
            paneId = 0,
            scope = viewModelScope,
            initialRenderSnapshot = preloadedSession?.panes?.getOrNull(0)?.renderSnapshot
                ?.takeIf { phoneStartupSnapshot && preloadedActivePane == 0 },
            initialFocusedId = preloadedSession?.panes?.getOrNull(0)?.focusedId,
        ),
        PaneController(
            paneId = 1,
            scope = viewModelScope,
            initialRenderSnapshot = preloadedSession?.panes?.getOrNull(1)?.renderSnapshot
                ?.takeIf { phoneStartupSnapshot && preloadedActivePane == 1 },
            initialFocusedId = preloadedSession?.panes?.getOrNull(1)?.focusedId,
        ),
    )

    val dialog = MutableStateFlow<DialogRequest?>(null)

    private val navigation = AppNavigationState()
    val screenBackStack = navigation.backStack

    fun navigateBack(expectedEntryId: Long? = null): Boolean =
        navigation.navigateBack(expectedEntryId)

    fun openSettings() {
        navigation.navigate(AppScreen.Settings)
    }

    private fun showViewer(request: ViewerRequest) {
        val replacingViewer = screenBackStack.last().screen is AppScreen.Viewer
        navigation.navigate(AppScreen.Viewer(request), replaceTop = replacingViewer)
    }

    private fun showDestinationPicker(transfer: PendingTransfer) {
        navigation.navigate(AppScreen.DestinationPicker(transfer))
    }

    val snackbar = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val activeCtrl: PaneController get() = panes[activePane.value]
    val inactiveCtrl: PaneController get() = panes[1 - activePane.value]

    /** Startup restore, or null while storage access is still missing (see [onStorageAccessGranted]). */
    private var restoreJob: Job? = null

    /** The active pane's critical restore is published; the startup overlay may now settle. */
    val sessionReady = MutableStateFlow(
        phoneStartupSnapshot && panes[preloadedActivePane].state.value.snapshotOnly,
    )

    /** Buffered because a PaneView can finish layout just before the restore coroutine awaits it. */
    private val paneInitialLayouts = Channel<PaneInitialLayout>(Channel.BUFFERED)

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
        // App-scoped jobs (installs) outlive this ViewModel, so whichever instance is on screen
        // when one finishes shows its outcome.
        viewModelScope.launch {
            BackgroundJobs.messages.collect { snackbar.tryEmit(it) }
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
        viewModelScope.launch {
            Graph.settings.privilegedTransport.drop(1).collect { preference ->
                PrivilegedAccess.preference = preference
                // A transport change can alter both root:// capabilities and the apps://
                // Android/data fallback, so neither scheme may retain the old transport's data.
                panes.forEach {
                    it.invalidateScheme(XId.SCHEME_ROOT)
                    it.invalidateScheme(XId.SCHEME_APPS)
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
        sessionReady.value = false
        viewModelScope.launch {
            try {
                started.join()
                restorePaneCriticalPaths { _, pane, paneRoots, listings ->
                    pane.restoreAfterGrant(paneRoots, listings)
                }
            } finally {
                sessionReady.value = true
            }
        }
    }

    fun onPaneInitialLayoutReady(pane: Int, treeVersion: Long) {
        paneInitialLayouts.trySend(PaneInitialLayout(pane, treeVersion))
    }

    private suspend fun awaitPaneInitialLayout(pane: Int, treeVersion: Long) {
        withTimeoutOrNull(INITIAL_PANE_LAYOUT_TIMEOUT_MS) {
            while (true) {
                val ready = paneInitialLayouts.receive()
                if (ready.pane == pane && ready.treeVersion == treeVersion) return@withTimeoutOrNull
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
        try {
            val session = startupSessionLoad?.deferred?.await() ?: Graph.settings.loadSession()
            activePane.value = session.activePane.coerceIn(0, panes.lastIndex)

            // On phones the visual cache is safe to show before any filesystem result: its rows
            // are non-interactive and fresh restore below replaces them. Waiting for its measured
            // frame prevents the fresh tree from overtaking the frame it was meant to accelerate.
            if (Graph.appContext.resources.configuration.screenWidthDp < WIDE_PANES_MIN_WIDTH_DP) {
                val active = activePane.value
                val saved = session.panes.getOrNull(active)
                val snapshotVersion = panes[active].showSessionRenderSnapshot(
                    snapshot = saved?.renderSnapshot,
                    savedFocused = saved?.focusedId,
                )
                if (snapshotVersion != null) {
                    sessionReady.value = true
                    awaitPaneInitialLayout(active, snapshotVersion)
                }
            }

            // Restore inputs must be settled first: the root gate (a saved root:// position
            // stats through it) and the favorites cache (saved ids may live under a pinned root).
            PrivilegedAccess.enabled = Graph.settings.rootEnabled.first()
            PrivilegedAccess.preference = Graph.settings.privilegedTransport.first()
            Graph.favorites.first { it != null }
            restorePaneCriticalPaths { i, pane, paneRoots, listings ->
                val saved = session.panes.getOrNull(i)
                pane.restore(
                    savedExpanded = saved?.expandedIds.orEmpty(),
                    savedFocused = saved?.focusedId,
                    savedDirectories = saved?.directories.orEmpty(),
                    paneRoots = paneRoots,
                    listings = listings,
                )
            }
            startSessionPersistence()
        } finally {
            // Do not leave the app behind a permanent loading surface if a future restore input
            // starts throwing unexpectedly; each pane already degrades individual IO failures.
            sessionReady.value = true
        }
    }

    /**
     * Loads the common root snapshot once and gives both panes one cold-start listing generation.
     * A phone exposes the active pane as soon as its focused path is ready; its inactive pane then
     * hydrates behind that first usable frame. Wide layouts wait for both visible critical paths.
     */
    private suspend fun restorePaneCriticalPaths(
        restorePane: suspend (
            index: Int,
            pane: PaneController,
            paneRoots: List<XEntry>,
            listings: RestoreListingSession,
        ) -> Job?,
    ) {
        val paneRoots = withContext(Dispatchers.IO) {
            runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
        }
        val listings = RestoreListingSession(
            keyOf = { entry ->
                "${Graph.fsRegistry.resolveScheme(entry)}\u0000${entry.id}"
            },
            readFresh = { entry ->
                runCatching { Graph.fsRegistry.forEntry(entry).list(entry) }
            },
        )
        var listingsHandedOff = false
        try {
            val background = arrayOfNulls<Job>(panes.size)
            val wide = Graph.appContext.resources.configuration.screenWidthDp >=
                WIDE_PANES_MIN_WIDTH_DP
            if (wide) {
                coroutineScope {
                    panes.forEachIndexed { index, pane ->
                        launch {
                            background[index] = restorePane(index, pane, paneRoots, listings)
                        }
                    }
                }
                sessionReady.value = true
            } else {
                val active = activePane.value.coerceIn(0, panes.lastIndex)
                background[active] = restorePane(active, panes[active], paneRoots, listings)
                sessionReady.value = true
                awaitPaneInitialLayout(active, panes[active].state.value.treeVersion)
                val inactive = 1 - active
                background[inactive] = restorePane(inactive, panes[inactive], paneRoots, listings)
            }
            val pending = background.filterNotNull()
            if (pending.isNotEmpty()) {
                // A slow off-path directory must not keep restoreSession alive: doing so delays
                // both the auto-saver and any later storage re-grant forever. Completion handlers
                // retain this one fresh-listing generation until every background merge is done.
                listingsHandedOff = true
                val remaining = AtomicInteger(pending.size)
                pending.forEach { job ->
                    job.invokeOnCompletion {
                        if (remaining.decrementAndGet() == 0) listings.close()
                    }
                }
            }
        } finally {
            if (!listingsHandedOff) listings.close()
        }
    }

    @OptIn(FlowPreview::class)
    private fun startSessionPersistence() {
        persistenceStarted = true
        viewModelScope.launch {
            combine(panes[0].sessionState, panes[1].sessionState, activePane) { p0, p1, active ->
                SessionState(
                    panes = listOf(p0, p1),
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
            panes = panes.map(PaneController::sessionSnapshot),
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
                        if (add) text(R.string.favorites_added, entry.name)
                        else text(R.string.favorites_removed, entry.name),
                    )
                },
                onFailure = { snackbar.tryEmit(text(R.string.favorites_update_failed)) },
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
                showViewer(
                    ViewerRequest.Image(
                        items = siblings,
                        startIndex = siblings.indexOfFirst { it.id == entry.id }.coerceAtLeast(0),
                    ),
                )
            }
            FileCategory.TEXT -> showViewer(ViewerRequest.Text(entry))
            FileCategory.AUDIO -> showViewer(
                ViewerRequest.Media(entry, pane.siblings(entry, FileCategory.AUDIO)),
            )
            FileCategory.VIDEO -> showViewer(
                ViewerRequest.Media(entry, pane.siblings(entry, FileCategory.VIDEO)),
            )
            FileCategory.DATABASE -> showViewer(ViewerRequest.Hex(entry))
            FileCategory.APK, FileCategory.ARCHIVE ->
                dialog.value = DialogRequest.EntryMenu(entry)
            FileCategory.PDF -> showViewer(ViewerRequest.Pdf(entry))
            FileCategory.GENERIC -> {
                if (!IntentUtils.openWith(Graph.appContext, entry)) {
                    showViewer(ViewerRequest.Hex(entry))
                }
            }
        }
    }

    fun openAsHex(entry: XEntry) {
        showViewer(ViewerRequest.Hex(entry))
    }

    fun openWith(entry: XEntry) {
        if (entry.localPath == null) {
            snackbar.tryEmit(text(R.string.open_with_requires_local_file))
            return
        }
        if (!IntentUtils.openWith(Graph.appContext, entry)) {
            snackbar.tryEmit(text(R.string.no_app_can_open, entry.name))
        }
    }

    /** Routes an ACTION_VIEW delivered through one of the user-enabled manifest aliases. */
    fun openExternalIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW || intent.data == null) return
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { ExternalOpenResolver.resolve(Graph.appContext, intent) }
            }
            resolved.fold(
                onSuccess = { (kind, entry) ->
                    when (kind) {
                        ExternalOpenKind.ARCHIVE -> dialog.value = DialogRequest.EntryMenu(entry)
                        ExternalOpenKind.IMAGE -> showViewer(ViewerRequest.Image(listOf(entry), 0))
                        ExternalOpenKind.VIDEO -> showViewer(ViewerRequest.Media(entry, listOf(entry)))
                    }
                },
                onFailure = { error ->
                    snackbar.tryEmit(error.message ?: text(R.string.generic_error))
                },
            )
        }
    }

    /** Expand an app and its base APK so the APK's zip contents show inline. */
    fun openAppAsZip(app: XEntry) {
        activeCtrl.revealAppApk(app)
    }

    /** Open the rich in-app details screen for an installed app. */
    fun showAppDetails(packageName: String) {
        navigation.navigate(AppScreen.AppInfo(packageName))
    }

    /** Launch a single activity component (from the component row's menu). */
    fun launchComponent(entry: XEntry) {
        val c = AppComponents.parseId(entry.id) ?: return
        if (c.type != ComponentType.ACTIVITY) return
        if (!IntentUtils.launchActivity(Graph.appContext, c.packageName, c.className)) {
            snackbar.tryEmit(text(R.string.cannot_launch_component, entry.name))
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
                if (ok) text(R.string.shortcut_requested, entry.name)
                else text(R.string.shortcut_not_supported),
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
                    snackbar.tryEmit(text(if (enabled) R.string.component_enabled else R.string.component_disabled, entry.name))
                    XId.parent(entry.id)?.let { parent -> panes.forEach { it.refresh(parent) } }
                },
                onFailure = { snackbar.tryEmit(it.message ?: text(R.string.cannot_change, entry.name)) },
            )
        }
    }

    /**
     * Installs an APK, split bundle, XAPK with expansion files, or converts an AAB first.
     *
     * The pipeline runs on the app scope as a [BackgroundJob] so [OpsService] keeps the process
     * alive and shows progress: converting a bundle takes about a minute of solid CPU, and
     * writing a multi-gigabyte XAPK takes longer still — leaving the app used to kill both.
     * The system shows its own confirm UI and reports the result.
     */
    fun installPackage(entry: XEntry) {
        val path = entry.localPath ?: run { snackbar.tryEmit(text(R.string.nothing_to_install)); return }
        val label = entry.name.substringBeforeLast('.').ifBlank { entry.name }
        // The registry is app-wide, so it also guards against a second install of the same entry.
        val job = BackgroundJobs.start(entry.id, label, text(R.string.preparing_install, label))
            ?: run { snackbar.tryEmit(text(R.string.already_installing, entry.name)); return }
        // Start the service while the tap that got us here still makes the app foreground-eligible:
        // once it is backgrounded the system refuses to start new foreground services.
        OpsService.start(Graph.appContext)

        val work = Graph.appScope.launch(Dispatchers.IO) {
            val progress = InstallProgress(
                onPhase = { phase -> job.message(text(phaseMessage(phase))) },
                onBytes = { done, total -> job.bytes(done, total) },
                isCancelled = { job.isCancelled() },
            )
            try {
                val file = File(path)
                when (entry.extension) {
                    "apk" -> {
                        val source = ApkInstaller.ApkSource(file.name, file.length()) { FileInputStream(file) }
                        val verdict = CompletableDeferred<Boolean>()
                        val session = ApkInstaller.install(
                            Graph.appContext, label, listOf(source), progress,
                        ) { verdict.complete(it) }
                        awaitVerdict(job, session, verdict, unwindOnGiveUp = false) {}
                    }
                    "aab" -> {
                        val verdict = CompletableDeferred<Boolean>()
                        val session = AabConverter.install(
                            Graph.appContext, file, label, progress,
                            onResult = { verdict.complete(it) },
                        ) { BackgroundJobs.messages.tryEmit(text(R.string.aab_key_regenerated)) }
                        awaitVerdict(job, session, verdict, unwindOnGiveUp = false) {}
                    }
                    else -> installBundle(entry, file, label, job, progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XapkObbInstaller.UnknownSourcesPermissionException) {
                BackgroundJobs.messages.tryEmit(e.message ?: text(R.string.enable_unknown_apps))
                Graph.appContext.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${Graph.appContext.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                val message = generateSequence<Throwable>(e) { it.cause }
                    .mapNotNull { it.message }
                    .firstOrNull()
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(180)
                    ?: text(R.string.generic_error)
                BackgroundJobs.messages.tryEmit(text(R.string.install_failed, message))
            } finally {
                BackgroundJobs.finish(job)
            }
        }
        job.attach(work)
    }

    /**
     * Holds the job open until the system reports what the user chose. That keeps the process
     * (and the duplicate-install guard the job doubles as) alive across the confirmation prompt.
     *
     * Giving up — the user cancelled, or nobody answered in [CONFIRMATION_TIMEOUT_MS] — drops the
     * session when leaving it committed would contradict the state we just unwound
     * ([unwindOnGiveUp]) or the cancellation the user asked for. An unanswered prompt with
     * nothing staged behind it is left alone: a late tap should still be able to install.
     */
    private suspend fun awaitVerdict(
        job: BackgroundJob,
        sessionId: Int,
        pending: CompletableDeferred<Boolean>,
        unwindOnGiveUp: Boolean,
        settle: (Boolean) -> Unit,
    ) {
        job.message(text(R.string.awaiting_install_confirmation))
        var verdict: Boolean? = null
        try {
            verdict = withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { pending.await() }
        } finally {
            // Cancelling the deferred routes any later result to the install's own fallback.
            pending.cancel()
            when {
                verdict != null -> settle(verdict)
                job.isCancelled() || unwindOnGiveUp -> {
                    ApkInstaller.abandon(Graph.appContext, sessionId)
                    settle(false)
                }
                else -> Unit
            }
        }
    }

    /**
     * Installs every APK member of a bundle (base + splits) together, placing an XAPK's
     * expansion files first. With OBBs the job outlives the commit: their backups can only be
     * finalized once the system reports the outcome, so the process has to stay up until then.
     */
    private suspend fun installBundle(
        entry: XEntry,
        file: File,
        label: String,
        job: BackgroundJob,
        progress: InstallProgress,
    ) {
        ZipFile(file).use { zip ->
            val obbs = if (entry.extension == "xapk") XapkObbInstaller.findObbs(zip) else emptyList()
            val placement = XapkObbInstaller.place(Graph.appContext, zip, obbs, progress)
            var submitted = false
            try {
                val apks = buildList {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && e.name.substringAfterLast('/').endsWith(".apk", true)) {
                            add(ApkInstaller.ApkSource(e.name, e.size) { zip.getInputStream(e) })
                        }
                    }
                }
                if (apks.isEmpty()) throw IllegalArgumentException("No APK inside ${entry.name}")
                val settled = AtomicBoolean(false)
                fun settle(success: Boolean) {
                    if (!settled.compareAndSet(false, true)) return
                    if (success) placement.commit() else placement.cleanUp()
                }
                val pending = CompletableDeferred<Boolean>()
                val session = ApkInstaller.install(Graph.appContext, label, apks, progress) { success ->
                    // A result that arrives after we stopped waiting still has to land: the
                    // OBB backups can only be dropped or restored once we know the outcome.
                    if (!pending.complete(success)) {
                        Graph.appScope.launch(Dispatchers.IO) { settle(success) }
                    }
                }
                submitted = true
                awaitVerdict(job, session, pending, unwindOnGiveUp = obbs.isNotEmpty(), settle = ::settle)
            } finally {
                if (!submitted) placement.cleanUp()
            }
        }
    }

    @androidx.annotation.StringRes
    private fun phaseMessage(phase: InstallPhase): Int = when (phase) {
        InstallPhase.BUILDING_APKS -> R.string.building_apks
        InstallPhase.EXTRACTING_OBB -> R.string.extracting_obb
        InstallPhase.WRITING_APKS -> R.string.writing_apks
    }

    fun openAsText(entry: XEntry) {
        showViewer(ViewerRequest.Text(entry))
    }

    // ---- file operations ----

    /**
     * Opens the destination picker for the current selection. The picker starts at the
     * other pane's folder (the X-plore dual-pane default) but lets the user browse anywhere,
     * so the destination is explicit instead of silently landing in the hidden pane.
     */
    fun copySelection(move: Boolean, sources: List<XEntry> = activeCtrl.selectionEntries()) {
        if (sources.isEmpty()) return
        showDestinationPicker(
            PendingTransfer(
                sources = sources,
                move = move,
                startDirId = inactiveCtrl.focusedDirEntry()?.id,
            ),
        )
    }

    /** Called by the picker once the user confirms a destination folder. */
    fun confirmTransfer(destDir: XEntry) {
        val current = screenBackStack.last()
        val transfer = (current.screen as? AppScreen.DestinationPicker)?.transfer ?: return
        navigateBack(current.id)
        if (!isValidDest(destDir)) {
            snackbar.tryEmit(text(R.string.cannot_write, destDir.name))
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
                    onFailure = { snackbar.tryEmit(it.message ?: text(R.string.cannot_create_folder)) },
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
        showDestinationPicker(
            PendingTransfer(
                sources = sources,
                move = false,
                startDirId = activeCtrl.focusedDirEntry()?.id,
                compress = true,
            ),
        )
    }

    fun performCompress(sources: List<XEntry>, destDir: XEntry, archiveName: String) {
        dialog.value = null
        Graph.opEngine.submit(FileOp.Compress(sources, destDir, archiveName))
        activeCtrl.clearSelection()
    }

    /** Choose a folder to extract [archive] into (a new subfolder named after it). */
    fun extractArchive(archive: XEntry) {
        showDestinationPicker(
            PendingTransfer(
                sources = listOf(archive),
                move = false,
                startDirId = inactiveCtrl.focusedDirEntry()?.id,
                extractArchiveName = archive.name.substringBeforeLast('.').ifBlank { archive.name },
            ),
        )
    }

    fun requestNewFolder() {
        val parent = activeCtrl.focusedDirEntry() ?: return
        if (!isValidDest(parent)) {
            snackbar.tryEmit(text(R.string.cannot_create_folder_in, parent.name))
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
                onFailure = { snackbar.tryEmit(it.message ?: text(R.string.cannot_create_folder)) },
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
                onFailure = { snackbar.tryEmit(it.message ?: text(R.string.rename_failed)) },
            )
        }
    }

    fun shareSelection(entries: List<XEntry> = activeCtrl.selectionEntries()) {
        val files = entries.filter { !it.isDir }
        if (files.isEmpty()) {
            snackbar.tryEmit(text(R.string.select_file_to_share))
            return
        }
        if (files.any { it.localPath == null }) {
            snackbar.tryEmit(text(R.string.share_requires_local_files))
            return
        }
        if (!IntentUtils.share(Graph.appContext, files)) {
            snackbar.tryEmit(text(R.string.cannot_share_files))
        }
    }

    fun openSearch() {
        activeCtrl.focusedDirEntry()?.let { navigation.navigate(AppScreen.Search(it)) }
    }

    /** Navigate the active pane to a search hit and close the search screen. */
    fun revealSearchHit(entryId: String) {
        val current = screenBackStack.last()
        if (current.screen is AppScreen.Search) navigateBack(current.id)
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
