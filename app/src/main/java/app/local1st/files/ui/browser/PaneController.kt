package app.local1st.files.ui.browser

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.SessionDirectory
import app.local1st.files.core.prefs.SessionPane
import app.local1st.files.core.prefs.SessionRenderNode
import app.local1st.files.core.prefs.SessionRenderSnapshot
import app.local1st.files.core.prefs.SortBy
import app.local1st.files.core.prefs.MAX_SESSION_RENDER_NODES
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.R
import app.local1st.files.di.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A row to bring on screen, and whether a user-requested reveal should show the travel. */
data class ScrollRequest(val id: String, val animate: Boolean)

private data class SortSpec(
    val by: SortBy = SortBy.NAME,
    val descending: Boolean = false,
    val dirsFirst: Boolean = true,
    val showHidden: Boolean = false,
)

private data class PaneTree(
    val roots: List<XEntry> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val children: Map<String, List<XEntry>> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

private class RestoreBuffer {
    val children = LinkedHashMap<String, List<XEntry>>()
    val errors = LinkedHashMap<String, String>()
    val routes = LinkedHashMap<String, String>()

    fun findEntry(roots: List<XEntry>, id: String): XEntry? {
        roots.firstOrNull { it.id == id }?.let { return it }
        children.values.forEach { entries ->
            entries.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    fun retainListings(ids: Set<String>) {
        children.keys.retainAll(ids)
        errors.keys.retainAll(ids)
        routes.keys.retainAll(ids)
    }
}

private data class FlattenedTree(
    val version: Long = 0,
    val nodes: List<TreeNode> = emptyList(),
)

private data class InitialListPosition(val treeVersion: Long, val index: Int)
private data class StartupRenderState(
    val version: Long,
    val nodes: List<TreeNode>,
    val initialIndex: Int,
)

private fun startupRenderStateOf(snapshot: SessionRenderSnapshot?): StartupRenderState? {
    snapshot ?: return null
    val nodes = snapshot.toTreeNodes()
    if (nodes.isEmpty()) return null
    return StartupRenderState(
        version = -1L,
        nodes = nodes,
        initialIndex = snapshot.initialIndex.coerceIn(nodes.indices),
    )
}

private fun StartupRenderState.toPaneUiState(focusedId: String?): PaneUiState = PaneUiState(
    nodes = nodes,
    focusedDirId = focusedId,
    loadingRoots = false,
    initialScrollIndex = initialIndex,
    treeVersion = version,
    startupSettled = false,
    snapshotOnly = true,
)
private data class ExpansionGroup(val paneRoots: Boolean, val parentId: String?)

private const val RESTORE_PARALLELISM = 4

/**
 * One cold-start generation's fresh directory reads. Results are shared only between the two
 * panes participating in that restore, then this object becomes unreachable. This coalesces an
 * identical path without turning the controller's UI cache into a persistent filesystem cache.
 */
internal class RestoreListingSession(
    maxParallelism: Int = RESTORE_PARALLELISM,
    private val keyOf: (XEntry) -> String = { it.id },
    private val readFresh: suspend (XEntry) -> Result<List<XEntry>>,
) {
    private sealed interface Lookup {
        data class Ready(val result: Result<List<XEntry>>) : Lookup
        data class Pending(val result: Deferred<Result<List<XEntry>>>) : Lookup
    }

    private val reads = Semaphore(maxParallelism)
    private val lock = Mutex()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completed = HashMap<String, Result<List<XEntry>>>()
    private val inFlight = HashMap<String, Deferred<Result<List<XEntry>>>>()

    suspend fun list(entry: XEntry): Result<List<XEntry>> {
        val key = keyOf(entry)
        val lookup = lock.withLock {
            completed[key]?.let { return@withLock Lookup.Ready(it) }
            inFlight[key]?.let { return@withLock Lookup.Pending(it) }
            val pending = workerScope.async {
                reads.withPermit {
                    try {
                        readFresh(entry)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }
            inFlight[key] = pending
            Lookup.Pending(pending)
        }
        if (lookup is Lookup.Ready) return lookup.result
        lookup as Lookup.Pending
        val result = lookup.result.await()
        lock.withLock {
            completed[key] = result
            if (inFlight[key] === lookup.result) inFlight.remove(key)
        }
        return result
    }

    fun close() = workerScope.cancel()
}

/**
 * Actual row parents from the loaded tree. Most files match [XId.parent], but virtual filesystems
 * can insert visual containers that are not encoded in a child's id. For example, an installed
 * app (`apps://<package>`) is displayed below `apps://@user`, not directly below `apps://`.
 */
internal fun visualParentsOf(children: Map<String, List<XEntry>>): Map<String, String> = buildMap {
    children.forEach { (parentId, entries) ->
        entries.forEach { entry ->
            if (entry.id !in this) put(entry.id, parentId)
        }
    }
}

/** The visual parent of an expansion. Pane roots are siblings even when their URI parents differ. */
private fun expansionParent(
    id: String,
    topLevelIds: Set<String>,
    visualParents: Map<String, String>,
): ExpansionGroup =
    if (id in topLevelIds) ExpansionGroup(paneRoots = true, parentId = null)
    else ExpansionGroup(paneRoots = false, parentId = visualParents[id] ?: XId.parent(id))

/** Expands [openingId] and closes every other expanded directory beside it. */
internal fun expandWithCollapsedSiblings(
    expandedIds: Set<String>,
    openingId: String,
    topLevelIds: Set<String>,
    visualParents: Map<String, String> = emptyMap(),
): Set<String> {
    val parent = expansionParent(openingId, topLevelIds, visualParents)
    return expandedIds.filterTo(LinkedHashSet()) { id ->
        id == openingId || expansionParent(id, topLevelIds, visualParents) != parent
    } + openingId
}

/** Number of parent steps from [id] to [ancestor], or null when it is not an ancestor. */
private fun ancestorDistance(
    ancestor: String,
    id: String,
    visualParents: Map<String, String>,
): Int? {
    var current: String? = id
    var distance = 0
    while (current != null) {
        if (current == ancestor) return distance
        current = visualParents[current] ?: XId.parent(current)
        distance++
    }
    return null
}

/**
 * Reconciles an existing tree when accordion navigation is enabled. The branch nearest the current
 * focus wins in each sibling group; unrelated hidden groups use a stable id order.
 */
internal fun retainOneExpandedSiblingPerGroup(
    expandedIds: Set<String>,
    focusedId: String?,
    topLevelIds: Set<String>,
    visualParents: Map<String, String> = emptyMap(),
): Set<String> = buildSet {
    expandedIds.groupBy { expansionParent(it, topLevelIds, visualParents) }.values.forEach { siblings ->
        val keep = siblings.minWithOrNull(
            compareBy<String> { id ->
                focusedId?.let { ancestorDistance(id, it, visualParents) } ?: Int.MAX_VALUE
            }.thenBy { it },
        )
        if (keep != null) add(keep)
    }
}

/** Restored row, falling back through hidden/missing ancestors and finally to row zero. */
internal fun restoredListIndex(visibleIds: List<String>, targetId: String?): Int {
    var candidate = targetId
    while (candidate != null) {
        val index = visibleIds.indexOf(candidate)
        if (index >= 0) return index
        candidate = XId.parent(candidate)
    }
    return 0
}

/** Keeps only a first-viewport-sized window around the row restored on the next launch. */
internal fun sessionRenderSnapshotFor(
    nodes: List<TreeNode>,
    focusedId: String?,
): SessionRenderSnapshot? {
    if (nodes.isEmpty()) return null
    val targetIndex = restoredListIndex(nodes.map { it.entry.id }, focusedId)
    val maxStart = (nodes.size - MAX_SESSION_RENDER_NODES).coerceAtLeast(0)
    val start = (targetIndex - 4).coerceIn(0, maxStart)
    val window = nodes.subList(start, minOf(nodes.size, start + MAX_SESSION_RENDER_NODES))
    return SessionRenderSnapshot(
        nodes = window.map { node ->
            SessionRenderNode(
                entry = node.entry,
                key = node.key,
                depth = node.depth,
                expanded = node.expanded,
                guides = node.guides,
                isLastChild = node.isLastChild,
            )
        },
        initialIndex = targetIndex - start,
    )
}

private fun SessionRenderSnapshot.toTreeNodes(): List<TreeNode> = nodes.map { node ->
    TreeNode(
        entry = node.entry,
        key = node.key,
        depth = node.depth,
        expanded = node.expanded,
        loading = false,
        guides = node.guides,
        isLastChild = node.isLastChild,
        error = null,
    )
}

/** Archive roots are routing nodes, not rows: the archive file itself visually owns top entries. */
private fun isSyntheticArchiveRoot(id: String): Boolean =
    XId.schemeOf(id) == XId.SCHEME_ZIP && XId.zipInnerPath(id).isEmpty()

/**
 * Semantic id path beginning at the closest current pane root. Scheme-only routing nodes are
 * omitted so the result follows the rows the user can actually expand.
 */
internal fun pathInsidePaneRoots(id: String, topLevelIds: Set<String>): List<String>? {
    val reversed = ArrayList<String>()
    var current: String? = id
    while (current != null) {
        if (!isSyntheticArchiveRoot(current)) reversed += current
        if (current in topLevelIds) return reversed.asReversed()
        current = XId.parent(current)
    }
    return null
}

/** Removes stale protocol ancestors and ids from volumes/favorites that are no longer pane roots. */
internal fun reachableExpandedIds(
    expandedIds: Set<String>,
    topLevelIds: Set<String>,
): Set<String> = expandedIds.filterTo(LinkedHashSet()) { id ->
    !isSyntheticArchiveRoot(id) && pathInsidePaneRoots(id, topLevelIds) != null
}

/**
 * Builds the small routing snapshot persisted for the next cold start. Fresh entries already in
 * the tree win over saved hints; focused ancestors are ordered first so the storage size cap always
 * preserves the first-frame path.
 */
internal fun sessionDirectoriesFor(
    expandedIds: Set<String>,
    focusedId: String?,
    paneRoots: List<XEntry>,
    children: Map<String, List<XEntry>>,
    savedHints: Collection<SessionDirectory> = emptyList(),
): List<SessionDirectory> {
    val topLevelIds = paneRoots.mapTo(HashSet()) { it.id }
    val entries = LinkedHashMap<String, XEntry>()
    savedHints.forEach { entries[it.id] = it.toEntry() }
    paneRoots.forEach { entries[it.id] = it }
    children.values.forEach { listing -> listing.forEach { entries[it.id] = it } }

    val wanted = LinkedHashSet<String>()
    focusedId?.let { pathInsidePaneRoots(it, topLevelIds) }?.let(wanted::addAll)
    expandedIds.sorted().forEach { id ->
        pathInsidePaneRoots(id, topLevelIds)?.let(wanted::addAll)
    }
    return wanted.mapNotNull { id ->
        entries[id]?.takeIf(XEntry::isContainer)?.let(SessionDirectory::fromEntry)
    }
}

/** Containers on the focused path that can be listed immediately from persisted descriptors. */
internal fun restorePathHints(
    focusedPath: List<String>?,
    desiredExpanded: Set<String>,
    paneRoots: List<XEntry>,
    savedDirectories: List<SessionDirectory>,
): List<XEntry> {
    if (focusedPath == null) return emptyList()
    val rootsById = paneRoots.associateBy { it.id }
    val savedById = savedDirectories.associateBy { it.id }
    return focusedPath.mapIndexedNotNull { index, id ->
        val needsListing = index < focusedPath.lastIndex || id in desiredExpanded
        if (!needsListing) return@mapIndexedNotNull null
        (rootsById[id] ?: savedById[id]?.toEntry())?.takeIf(XEntry::isContainer)
    }
}

/** A single archive parse is shared by all of its inner directories, so list those serially. */
private fun restoreReadGroup(entry: XEntry): String = when {
    entry.scheme == XId.SCHEME_ZIP -> "archive:${XId.zipArchivePath(entry.id)}"
    entry.scheme == XId.SCHEME_FILE && entry.kind == EntryKind.ARCHIVE ->
        "archive:${entry.localPath ?: entry.path}"
    else -> "entry:${entry.id}"
}

/** Starts independent directory reads together while preventing duplicate parses of one archive. */
internal suspend fun prefetchRestoreListings(
    entries: List<XEntry>,
    listings: RestoreListingSession,
): List<Pair<XEntry, Result<List<XEntry>>>> = coroutineScope {
    entries.distinctBy { it.id to restoreReadGroup(it) }
        .groupBy(::restoreReadGroup)
        .values
        .map { group ->
            async {
                group.map { entry -> entry to listings.list(entry) }
            }
        }
        .awaitAll()
        .flatten()
}

/**
 * State machine of one browser pane: an X-plore style tree where containers
 * expand in place. Not an AAC ViewModel — two of these live inside MainViewModel.
 */
class PaneController(
    val paneId: Int,
    private val scope: CoroutineScope,
    initialRenderSnapshot: SessionRenderSnapshot? = null,
    initialFocusedId: String? = null,
) {
    private val registry get() = Graph.fsRegistry
    private val initialStartupRender = startupRenderStateOf(initialRenderSnapshot)

    private val tree = MutableStateFlow(PaneTree())
    /** Persisted expansion can include branches still being hydrated off the first-frame path. */
    private val sessionExpanded = MutableStateFlow<Set<String>>(emptySet())
    /** Kept until fresh listings replace them, so an early auto-save cannot erase restore hints. */
    private val savedDirectoryHints = MutableStateFlow<Map<String, SessionDirectory>>(emptyMap())
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val focusedDirId = MutableStateFlow(initialFocusedId)
    private val loadingRoots = MutableStateFlow(true)
    private val flattenVersion = MutableStateFlow(0L)
    private val initialListPosition = MutableStateFlow<InitialListPosition?>(null)
    private val startupRender = MutableStateFlow(initialStartupRender)
    private val collapseSiblingFolders = MutableStateFlow<Boolean?>(null)
    private val startupSettled = MutableStateFlow(false)
    private var restoreGeneration = 0L
    private var startupRenderGeneration = if (initialStartupRender == null) 0L else 1L
    private var backgroundRestoreJob: Job? = null

    // Declared BEFORE `nodes`: its eager stateIn starts flatten() on another thread
    // during construction, so everything flatten touches must already be initialized.
    private val sortedListings = HashMap<String, SortedListing>()

    /** A row the pane list should bring on screen (consumed by the UI). */
    val scrollTo = MutableSharedFlow<ScrollRequest>(extraBufferCapacity = 1)

    private val sortSpec: StateFlow<SortSpec?> = combine(
        Graph.settings.sortBy,
        Graph.settings.sortDescending,
        Graph.settings.dirsFirst,
        Graph.settings.showHidden,
    ) { by, desc, dirsFirst, hidden -> SortSpec(by, desc, dirsFirst, hidden) }
        // Null distinguishes the real persisted defaults from the placeholder before DataStore's
        // first read. Startup does not expose the tree until this becomes non-null.
        .stateIn(scope, SharingStarted.Eagerly, null)

    // Flattening (filter + per-dir sort) depends only on tree state, not on selection/focus,
    // so it lives in its own flow computed off the main thread. Selection toggles then only
    // re-run the cheap outer combine instead of re-sorting the whole visible tree.
    private val flattenedTree: StateFlow<FlattenedTree> = combine(
        tree,
        flattenVersion,
        sortSpec,
    ) { tree, version, sort ->
        FlattenedTree(
            version = version,
            nodes = flatten(
                tree.roots,
                tree.expanded,
                tree.children,
                tree.loading,
                tree.errors,
                sort ?: SortSpec(),
            ),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, FlattenedTree())

    val state: StateFlow<PaneUiState> = combine(
        combine(flattenedTree, selection) { tree, sel -> tree to sel },
        combine(focusedDirId, loadingRoots) { focus, lr -> focus to lr },
        combine(initialListPosition, startupSettled) { initial, settled -> initial to settled },
        startupRender,
    ) { (tree, sel), (focus, lr), (initial, settled), startup ->
        val freshIndex = initial?.takeIf { it.treeVersion == tree.version }?.index
        val useStartup = freshIndex == null && startup != null
        PaneUiState(
            nodes = if (useStartup) checkNotNull(startup).nodes else tree.nodes,
            selection = sel,
            focusedDirId = focus,
            loadingRoots = if (useStartup) false else lr,
            initialScrollIndex = if (useStartup) checkNotNull(startup).initialIndex else freshIndex,
            treeVersion = if (useStartup) checkNotNull(startup).version else tree.version,
            startupSettled = !useStartup && settled,
            snapshotOnly = useStartup,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        initialStartupRender?.toPaneUiState(initialFocusedId) ?: PaneUiState(),
    )

    init {
        scope.launch {
            Graph.settings.collapseSiblingFolders.collect { enabled ->
                val previous = collapseSiblingFolders.value
                collapseSiblingFolders.value = enabled
                if (previous != null && previous != enabled) {
                    finishStartupRestoreForInteraction()
                    sessionExpanded.value = tree.value.expanded
                }
                if (enabled) {
                    val topLevelIds = tree.value.roots.mapTo(HashSet()) { it.id }
                    tree.update { current ->
                        val visualParents = visualParentsOf(current.children)
                        current.copy(
                            expanded = retainOneExpandedSiblingPerGroup(
                                expandedIds = current.expanded,
                                focusedId = focusedDirId.value,
                                topLevelIds = topLevelIds,
                                visualParents = visualParents,
                            ),
                        )
                    }
                    val current = tree.value
                    sessionExpanded.value = retainOneExpandedSiblingPerGroup(
                        expandedIds = sessionExpanded.value,
                        focusedId = focusedDirId.value,
                        topLevelIds = topLevelIds,
                        visualParents = visualParentsOf(current.children),
                    )
                }
            }
        }
    }

    /** Browsing position plus the minimal descriptors needed for a parallel cold restore. */
    val sessionState = combine(
        combine(sessionExpanded, focusedDirId) { expanded, focused -> expanded to focused },
        combine(tree, savedDirectoryHints) { currentTree, hints -> currentTree to hints },
        flattenedTree,
    ) { (expanded, focused), (currentTree, hints), flattened ->
        SessionPane(
            expandedIds = expanded,
            focusedId = focused,
            directories = sessionDirectoriesFor(
                expandedIds = expanded,
                focusedId = focused,
                paneRoots = currentTree.roots,
                children = currentTree.children,
                savedHints = hints.values,
            ),
            renderSnapshot = sessionRenderSnapshotFor(flattened.nodes, focused),
        )
    }

    /** Synchronous [sessionState] snapshot, for the final flush when the ViewModel is cleared. */
    fun sessionSnapshot(): SessionPane {
        val currentTree = tree.value
        return SessionPane(
            expandedIds = sessionExpanded.value,
            focusedId = focusedDirId.value,
            directories = sessionDirectoriesFor(
                expandedIds = sessionExpanded.value,
                focusedId = focusedDirId.value,
                paneRoots = currentTree.roots,
                children = currentTree.children,
                savedHints = savedDirectoryHints.value.values,
            ),
            renderSnapshot = sessionRenderSnapshotFor(flattenedTree.value.nodes, focusedDirId.value),
        )
    }

    /** Publishes a visual-only cache immediately; fresh restore replaces it before interaction. */
    internal fun showSessionRenderSnapshot(
        snapshot: SessionRenderSnapshot?,
        savedFocused: String?,
    ): Long? {
        startupRender.value?.let { return it.version }
        snapshot ?: return null
        val nodes = snapshot.toTreeNodes()
        if (nodes.isEmpty()) return null
        val version = -(++startupRenderGeneration)
        focusedDirId.value = savedFocused
        startupRender.value = StartupRenderState(
            version = version,
            nodes = nodes,
            initialIndex = snapshot.initialIndex.coerceIn(nodes.indices),
        )
        return version
    }

    // No initial load here: MainViewModel always drives startup through [restore],
    // which falls back to the plain first-root expansion when nothing was saved.

    // ---- loading ----

    fun reloadRoots(expandFirst: Boolean = false) {
        scope.launch {
            finishStartupRestoreForInteraction()
            loadingRoots.value = true
            val list = withContext(Dispatchers.IO) {
                runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
            }
            tree.update { it.copy(roots = list) }
            loadingRoots.value = false
            if (expandFirst) expandFirstRoot()
        }
    }

    private fun expandFirstRoot() {
        tree.value.roots.firstOrNull()?.let { first ->
            if (focusedDirId.value == null) {
                focusedDirId.value = first.id
                expand(first)
            }
        }
    }

    // ---- session restore ----

    /**
     * Loads roots and restores the previous session's expansion and focus.
     * Every step degrades gracefully: expanded dirs that vanished are simply not
     * re-expanded, a dead focused dir falls back to its nearest surviving ancestor,
     * and when nothing is restorable the pane starts fresh (first root expanded).
     */
    internal suspend fun restore(
        savedExpanded: Set<String>,
        savedFocused: String?,
        savedDirectories: List<SessionDirectory>,
        paneRoots: List<XEntry>,
        listings: RestoreListingSession,
    ): Job? {
        backgroundRestoreJob?.cancel()
        val generation = ++restoreGeneration
        backgroundRestoreJob = null
        startupSettled.value = false
        initialListPosition.value = null
        loadingRoots.value = true
        // A numeric initial list index is only correct after the persisted sort/filter and the
        // accordion preference have emitted their real values (including real default values).
        sortSpec.first { it != null }
        val collapseSiblings = collapseSiblingFolders.first { it != null } == true
        val topLevelIds = paneRoots.mapTo(HashSet()) { it.id }
        var desiredExpanded = reachableExpandedIds(savedExpanded, topLevelIds)
        val criticalExpanded = LinkedHashSet<String>()
        val buffer = RestoreBuffer()
        savedDirectoryHints.value = savedDirectories
            .filter { it.toEntry().isContainer }
            .associateBy { it.id }

        // Resolve the saved focus by listing its visual parent chain from the nearest pane root.
        // The last entry found is the nearest surviving ancestor, so no separate stat pass is
        // needed. Saved descriptors let all required directories begin fresh IO together; walking
        // the new parent results below still rejects deleted or replaced path components.
        var target: XEntry? = null
        val focusedPath = savedFocused?.let { pathInsidePaneRoots(it, topLevelIds) }
        prefetchRestoreListings(
            entries = restorePathHints(
                focusedPath = focusedPath,
                desiredExpanded = desiredExpanded,
                paneRoots = paneRoots,
                savedDirectories = savedDirectories,
            ),
            listings = listings,
        ).forEach { (entry, result) -> applyRestoreResult(entry, result, buffer) }
        if (focusedPath != null) {
            for ((index, id) in focusedPath.withIndex()) {
                val entry = buffer.findEntry(paneRoots, id) ?: break
                if (!entry.isContainer) break
                target = entry
                val mustReachChild = index < focusedPath.lastIndex
                if (mustReachChild || id in desiredExpanded) {
                    criticalExpanded += id
                    loadForRestore(entry, buffer, listings)
                }
            }
            desiredExpanded = desiredExpanded + criticalExpanded
        }
        if (collapseSiblings) {
            desiredExpanded = retainOneExpandedSiblingPerGroup(
                expandedIds = desiredExpanded,
                focusedId = target?.id,
                topLevelIds = topLevelIds,
                visualParents = visualParentsOf(buffer.children),
            )
            criticalExpanded.retainAll(desiredExpanded)
        }

        // No usable saved focus: restore one saved root first, or start fresh at the first root.
        if (target == null) {
            val first = paneRoots.firstOrNull { it.isContainer && it.id in desiredExpanded }
                ?: paneRoots.firstOrNull { it.isContainer }
            if (first != null) {
                target = first
                desiredExpanded = if (collapseSiblings) {
                    expandWithCollapsedSiblings(
                        expandedIds = desiredExpanded,
                        openingId = first.id,
                        topLevelIds = topLevelIds,
                        visualParents = visualParentsOf(buffer.children),
                    )
                } else {
                    desiredExpanded + first.id
                }
                criticalExpanded += first.id
                loadForRestore(first, buffer, listings)
            }
        }

        // A stale descriptor deeper than the first missing component may have completed in
        // parallel. It is only a hint, so never publish or persist a listing that was not reached
        // through fresh parent results.
        buffer.retainListings(criticalExpanded)

        sessionExpanded.value = desiredExpanded
        focusedDirId.value = target?.id
        tree.value = PaneTree(
            roots = paneRoots,
            expanded = criticalExpanded,
            children = buffer.children.toMap(),
            errors = buffer.errors.toMap(),
        )
        settleInitialPosition(target?.id)

        if (desiredExpanded == criticalExpanded) {
            startupSettled.value = true
            return null
        }

        // Remaining branches hydrate off the first-frame path. Their listings are applied as one
        // tree mutation, with item placement animation still disabled for that reconciliation.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val hydratedIds = restoreExpandedTree(
                    paneRoots = paneRoots,
                    desiredExpanded = desiredExpanded,
                    savedDirectories = savedDirectories,
                    buffer = buffer,
                    listings = listings,
                )
                if (restoreGeneration != generation) return@launch
                buffer.retainListings(criticalExpanded + hydratedIds)
                val loadedIds = buffer.children.keys
                tree.update { current ->
                    val mergedErrors = current.errors.toMutableMap()
                    loadedIds.forEach(mergedErrors::remove)
                    mergedErrors.putAll(buffer.errors)
                    current.copy(
                        expanded = desiredExpanded,
                        children = current.children + buffer.children,
                        errors = mergedErrors,
                    )
                }
            } finally {
                if (restoreGeneration == generation) {
                    backgroundRestoreJob = null
                    startupSettled.value = true
                }
            }
        }
        backgroundRestoreJob = job
        job.start()
        return job
    }

    private suspend fun loadForRestore(
        entry: XEntry,
        buffer: RestoreBuffer,
        listings: RestoreListingSession,
    ): List<XEntry> {
        if (hasCompatibleRestoreListing(entry, buffer)) return checkNotNull(buffer.children[entry.id])
        discardRestoreListing(entry.id, buffer)
        return applyRestoreResult(entry, listings.list(entry), buffer)
    }

    private fun hasCompatibleRestoreListing(entry: XEntry, buffer: RestoreBuffer): Boolean =
        entry.id in buffer.children && buffer.routes[entry.id] == registry.resolveScheme(entry)

    private fun discardRestoreListing(id: String, buffer: RestoreBuffer) {
        buffer.children.remove(id)
        buffer.errors.remove(id)
        buffer.routes.remove(id)
    }

    private fun applyRestoreResult(
        entry: XEntry,
        result: Result<List<XEntry>>,
        buffer: RestoreBuffer,
    ): List<XEntry> {
        val kids = result.getOrDefault(emptyList())
        buffer.children[entry.id] = kids
        buffer.routes[entry.id] = registry.resolveScheme(entry)
        result.exceptionOrNull()?.let { error ->
            buffer.errors[entry.id] = error.message
                ?: Graph.appContext.getString(R.string.cannot_read, entry.name)
        } ?: buffer.errors.remove(entry.id)
        return kids
    }

    /** Breadth-first restore with bounded per-level concurrency; the shared session caps both panes. */
    private suspend fun restoreExpandedTree(
        paneRoots: List<XEntry>,
        desiredExpanded: Set<String>,
        savedDirectories: List<SessionDirectory>,
        buffer: RestoreBuffer,
        listings: RestoreListingSession,
    ): Set<String> {
        val topLevelIds = paneRoots.mapTo(HashSet()) { it.id }
        val rootsById = paneRoots.associateBy { it.id }
        val savedById = savedDirectories.associateBy { it.id }
        val hintedEntries = desiredExpanded.sorted().mapNotNull { id ->
            val path = pathInsidePaneRoots(id, topLevelIds) ?: return@mapNotNull null
            if (path.dropLast(1).any { it !in desiredExpanded }) return@mapNotNull null
            (rootsById[id] ?: savedById[id]?.toEntry())?.takeIf(XEntry::isContainer)
        }.filterNot { hasCompatibleRestoreListing(it, buffer) }
        prefetchRestoreListings(hintedEntries, listings).forEach { (entry, result) ->
            applyRestoreResult(entry, result, buffer)
        }

        val queue = ArrayDeque(paneRoots.filter { it.isContainer && it.id in desiredExpanded })
        val visited = HashSet<String>()
        while (queue.isNotEmpty()) {
            val batch = ArrayList<XEntry>(RESTORE_PARALLELISM)
            while (queue.isNotEmpty() && batch.size < RESTORE_PARALLELISM) {
                val dir = queue.removeFirst()
                if (visited.add(dir.id)) batch += dir
            }
            if (batch.isEmpty()) continue

            val uncached = batch.filterNot { hasCompatibleRestoreListing(it, buffer) }
            uncached.forEach { discardRestoreListing(it.id, buffer) }
            val results = coroutineScope {
                uncached.map { dir -> async { dir.id to listings.list(dir) } }.awaitAll()
            }.toMap()
            batch.forEach { dir ->
                val kids = buffer.children[dir.id]
                    ?: applyRestoreResult(dir, checkNotNull(results[dir.id]), buffer)
                kids.forEach { kid ->
                    if (kid.isContainer && kid.id in desiredExpanded) queue += kid
                }
            }
        }
        return visited
    }

    /**
     * Publishes the settled flattened tree and its restored row as one versioned snapshot. This is
     * the draw barrier that lets LazyColumn start at the right row instead of drawing row 0 first.
     */
    private suspend fun settleInitialPosition(targetId: String?) {
        val version = flattenVersion.value + 1
        flattenVersion.value = version
        val settled = flattenedTree.first { it.version == version }
        val index = restoredListIndex(settled.nodes.map { it.entry.id }, targetId)
        initialListPosition.value = InitialListPosition(version, index)
        loadingRoots.value = false
        state.first { ui ->
            ui.treeVersion == version && !ui.loadingRoots && ui.initialScrollIndex != null
        }
        startupRender.value = null
    }

    /**
     * Re-runs [restore] from the current in-memory state after storage access was granted
     * mid-session. Pre-grant listings all failed (and were cached as errors), so drop the
     * caches and replay the expansion/focus the user still has — unlike a blind [reset],
     * this keeps the restored session instead of wiping it (and letting the auto-save
     * persist the wipe).
     */
    internal suspend fun restoreAfterGrant(
        paneRoots: List<XEntry>,
        listings: RestoreListingSession,
    ): Job? {
        val session = sessionSnapshot()
        return restore(
            savedExpanded = session.expandedIds,
            savedFocused = session.focusedId,
            savedDirectories = session.directories,
            paneRoots = paneRoots,
            listings = listings,
        )
    }

    /** User input wins over a pending startup merge; never reopen a branch behind their gesture. */
    private fun finishStartupRestoreForInteraction() {
        val pending = backgroundRestoreJob ?: return
        restoreGeneration++
        backgroundRestoreJob = null
        pending.cancel()
        startupSettled.value = true
    }

    /**
     * Drops cached listings/errors for every id under [scheme] and re-lists the dirs still
     * expanded. Call when a filesystem-wide gate flips (root browsing toggled): without this,
     * listings cached while the gate was open stay browsable after it closes, and gate-error
     * rows cached while it was closed outlive re-opening it.
     */
    fun invalidateScheme(scheme: String) {
        finishStartupRestoreForInteraction()
        val prefix = "$scheme://"
        val snapshot = tree.value
        val ids = snapshot.children.keys.filter { it.startsWith(prefix) }.toSet()
        if (ids.isEmpty()) return
        val reload = ids.filter { it in snapshot.expanded }.mapNotNull(::findEntry)
        tree.update { current ->
            current.copy(
                children = current.children - ids,
                errors = current.errors - ids,
            )
        }
        reload.forEach(::load)
    }

    /** True when [id] is one of the pane's current top-level roots. */
    fun isTopLevelRoot(id: String): Boolean = tree.value.roots.any { it.id == id }

    /** Ids whose reload was requested while a load was already in flight; re-run on completion. */
    private val reloadRequested = HashSet<String>()

    private fun load(entry: XEntry) {
        if (entry.id in tree.value.loading) {
            // Don't drop the request: a refresh during an in-flight load must re-list.
            reloadRequested += entry.id
            return
        }
        tree.update { current ->
            current.copy(
                loading = current.loading + entry.id,
                errors = current.errors - entry.id,
            )
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { registry.forEntry(entry).list(entry) }
            }
            val kids = result.getOrDefault(emptyList())
            val error = result.exceptionOrNull()?.let {
                it.message ?: Graph.appContext.getString(R.string.cannot_read_folder)
            }
            tree.update { current ->
                current.copy(
                    children = current.children + (entry.id to kids),
                    loading = current.loading - entry.id,
                    errors = if (error == null) current.errors - entry.id
                    else current.errors + (entry.id to error),
                )
            }

            // Cascade into remembered-but-unloaded sub-expansions after the parent listing lands.
            val current = tree.value
            kids.forEach { kid ->
                if (kid.isContainer && kid.id in current.expanded &&
                    current.children[kid.id] == null && kid.id !in current.loading
                ) {
                    load(kid)
                }
            }
            if (reloadRequested.remove(entry.id)) load(entry)
        }
    }

    // ---- navigation / expansion ----

    fun toggleExpand(entry: XEntry) {
        if (!entry.isContainer) return
        if (entry.id in tree.value.expanded) collapse(entry) else expand(entry)
    }

    fun expand(entry: XEntry) {
        if (!entry.isContainer) return
        finishStartupRestoreForInteraction()
        markExpanded(entry.id)
        focusedDirId.value = entry.id
        // Reload when uncached or when the last attempt failed (e.g. before permission grant).
        val current = tree.value
        if (current.children[entry.id] == null || entry.id in current.errors) load(entry)
    }

    /** Applies the optional accordion policy at the one expansion mutation point. */
    private fun markExpanded(id: String) {
        val current = tree.value
        val updated = with(current) {
            if (collapseSiblingFolders.value == true) {
                expandWithCollapsedSiblings(
                    expandedIds = expanded,
                    openingId = id,
                    topLevelIds = roots.mapTo(HashSet()) { it.id },
                    visualParents = visualParentsOf(children),
                )
            } else {
                expanded + id
            }
        }
        tree.update { it.copy(expanded = updated) }
        sessionExpanded.value = updated
    }

    fun collapse(entry: XEntry) {
        finishStartupRestoreForInteraction()
        val updated = tree.value.expanded - entry.id
        tree.update { it.copy(expanded = updated) }
        sessionExpanded.value = updated
        focusedDirId.update { focus ->
            if (focus != null && (focus == entry.id || focus.startsWith(entry.id + "/") ||
                        isAncestorOf(entry.id, focus))
            ) entry.id else focus
        }
    }

    private fun isAncestorOf(ancestorId: String, id: String): Boolean {
        var cur: String? = XId.parent(id)
        while (cur != null) {
            if (cur == ancestorId) return true
            cur = XId.parent(cur)
        }
        return false
    }

    fun focus(entry: XEntry) {
        finishStartupRestoreForInteraction()
        focusedDirId.value = if (entry.isContainer) entry.id else XId.parent(entry.id)
    }

    /** Expand the ancestor chain of [id], then scroll to it. See [ScrollRequest] for [animate]. */
    fun revealPath(id: String, animate: Boolean = true) {
        finishStartupRestoreForInteraction()
        scope.launch {
            revealPathNow(id)
            scrollTo.tryEmit(ScrollRequest(id, animate))
        }
    }

    /** Reveals only the visual path below a real pane root; protocol ancestors stay out of state. */
    private suspend fun revealPathNow(id: String) {
        val topLevelIds = tree.value.roots.mapTo(HashSet()) { it.id }
        val path = pathInsidePaneRoots(id, topLevelIds) ?: return
        for (ancestorId in path.dropLast(1)) {
            val entry = findEntry(ancestorId)
                ?: withContext(Dispatchers.IO) {
                    runCatching { registry.forId(ancestorId).stat(ancestorId) }.getOrNull()
                }
                ?: continue
            if (!entry.isContainer) continue
            markExpanded(entry.id)
            if (tree.value.children[entry.id] == null) loadNow(entry)
        }
        focusedDirId.value = findEntry(id)?.takeIf { it.isContainer }?.id
            ?: path.dropLast(1).lastOrNull()
    }

    /**
     * Expand [app] and then its base APK child so the APK's zip contents show inline
     * (the explicit "Open as zip" action). No-op if the app exposes no browsable APK.
     */
    fun revealAppApk(app: XEntry) {
        finishStartupRestoreForInteraction()
        scope.launch {
            markExpanded(app.id)
            focusedDirId.value = app.id
            val kids = loadNow(app)
            val apk = kids.firstOrNull {
                it.kind == EntryKind.ARCHIVE && it.name == "base.apk"
            } ?: kids.firstOrNull { it.kind == EntryKind.ARCHIVE }
                ?: return@launch
            markExpanded(apk.id)
            loadNow(apk)
            focusedDirId.value = apk.id
            scrollTo.tryEmit(ScrollRequest(apk.id, animate = true))
        }
    }

    /** Lists [entry]'s children synchronously (awaiting IO) and caches them; returns the list. */
    private suspend fun loadNow(entry: XEntry): List<XEntry> {
        tree.value.children[entry.id]?.let { if (entry.id !in tree.value.errors) return it }
        tree.update { current ->
            current.copy(
                loading = current.loading + entry.id,
                errors = current.errors - entry.id,
            )
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { registry.forEntry(entry).list(entry) }
        }
        val kids = result.getOrDefault(emptyList())
        val error = result.exceptionOrNull()?.let {
            it.message ?: Graph.appContext.getString(R.string.cannot_read, entry.name)
        }
        tree.update { current ->
            current.copy(
                children = current.children + (entry.id to kids),
                loading = current.loading - entry.id,
                errors = if (error == null) current.errors - entry.id
                else current.errors + (entry.id to error),
            )
        }
        return kids
    }

    // ---- refresh ----

    fun refresh(dirId: String) {
        finishStartupRestoreForInteraction()
        val entry = findEntry(dirId) ?: return
        if (tree.value.children.containsKey(dirId)) load(entry)
    }

    fun refreshDirty(ids: Set<String>) {
        ids.forEach { refresh(it) }
        // A delete/move may have removed the focused dir. Once the triggered reloads settle,
        // if the focused id no longer exists, fall back to the nearest surviving ancestor.
        scope.launch {
            tree.map { it.loading }.first { it.isEmpty() }
            val focus = focusedDirId.value ?: return@launch
            if (findEntry(focus) == null) {
                var candidate: String? = XId.parent(focus)
                while (candidate != null && findEntry(candidate) == null) {
                    candidate = XId.parent(candidate)
                }
                focusedDirId.value = candidate
            }
        }
    }

    fun refreshAllExpanded() {
        finishStartupRestoreForInteraction()
        reloadRoots()
        val current = tree.value
        current.children.keys.filter { it in current.expanded }.forEach { id ->
            findEntry(id)?.let { load(it) }
        }
    }

    // ---- selection ----

    /** Volumes and the apps/root pseudo-nodes are tree scaffolding, not operable entries. */
    private fun selectable(entry: XEntry): Boolean = when (entry.kind) {
        EntryKind.VOLUME_INTERNAL, EntryKind.VOLUME_SD, EntryKind.VOLUME_USB,
        EntryKind.APPS_ROOT, EntryKind.APP_COMPONENT_GROUP, EntryKind.APP_COMPONENT,
        EntryKind.ROOT,
        -> false
        else -> true
    }

    /** The rows shown directly under [entry]; empty for files, closed dirs and unlisted ones. */
    private fun openChildren(entry: XEntry): List<XEntry> {
        val current = tree.value
        if (!entry.isContainer || entry.id !in current.expanded) return emptyList()
        val kids = current.children[entry.id] ?: return emptyList()
        val showHidden = (sortSpec.value ?: SortSpec()).showHidden
        return kids.filter { (showHidden || !it.hidden) && selectable(it) }
    }

    /**
     * Files and closed dirs toggle. An OPEN dir cycles three ways —
     * nothing → the dir itself → every entry inside it → nothing — so unticking a
     * ticked folder hands the selection down to its contents, and single items can then
     * be dropped from there (X-plore's inverse select). The last step clears instead of
     * re-ticking the folder on top of its own children.
     *
     * Whichever way it lands, a dir and anything under it are never selected together:
     * the ops would then process the same bytes twice (copy the folder, then copy its
     * files into the copy), so picking one level always drops the other.
     */
    fun toggleSelect(entry: XEntry) {
        if (!selectable(entry)) return
        val kids = openChildren(entry)
        if (kids.isEmpty()) {
            selection.update { sel ->
                if (entry.id in sel) {
                    sel - entry.id
                } else {
                    sel.filterTo(HashSet()) {
                        !isAncestorOf(it, entry.id) && !isAncestorOf(entry.id, it)
                    } + entry.id
                }
            }
            return
        }
        val kidIds = kids.mapTo(HashSet()) { it.id }
        selection.update { sel ->
            val outside = sel.filterTo(HashSet()) {
                it != entry.id &&
                    !isAncestorOf(it, entry.id) &&
                    !isAncestorOf(entry.id, it)
            }
            when {
                entry.id in sel -> outside + kidIds
                sel.containsAll(kidIds) -> outside
                else -> outside + entry.id
            }
        }
    }

    fun clearSelection() = selection.update { emptySet() }

    fun selectionEntries(): List<XEntry> {
        val sel = selection.value
        if (sel.isEmpty()) return emptyList()
        val found = LinkedHashMap<String, XEntry>()
        val current = tree.value
        current.roots.forEach { if (it.id in sel) found[it.id] = it }
        current.children.values.forEach { list ->
            list.forEach { if (it.id in sel) found[it.id] = it }
        }
        return found.values.toList()
    }

    // ---- lookups ----

    fun findEntry(id: String): XEntry? {
        val current = tree.value
        current.roots.firstOrNull { it.id == id }?.let { return it }
        current.children.values.forEach { list ->
            list.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    fun focusedDirEntry(): XEntry? =
        focusedDirId.value?.let { findEntry(it) } ?: tree.value.roots.firstOrNull()

    /** Cached siblings of [entry] with the given category (for viewer paging/playlists). */
    fun siblings(entry: XEntry, category: FileCategory): List<XEntry> {
        val parentId = XId.parent(entry.id) ?: return listOf(entry)
        val kids = tree.value.children[parentId] ?: return listOf(entry)
        val sorted = sortEntries(kids, sortSpec.value ?: SortSpec())
        return sorted.filter { !it.isDir && FileTypes.categoryOf(it.name, it.mime) == category }
            .ifEmpty { listOf(entry) }
    }

    // ---- tree flattening ----

    /** Filtered+sorted children of one dir, valid while its source list and the sort stand. */
    private class SortedListing(
        val source: List<XEntry>,
        val spec: SortSpec,
        val visible: List<XEntry>,
    )

    // sortedListings is only touched from flatten(), which runs serially inside the
    // `nodes` flow. Without the cache every tree state change (a loading flag flip, one
    // dir's listing landing) re-sorted EVERY expanded directory's children — with a few
    // large dirs open, that's most of the post-listing latency between tap and rows.
    private fun sortedVisible(dirId: String, kids: List<XEntry>, sort: SortSpec): List<XEntry> {
        sortedListings[dirId]?.let { cached ->
            if (cached.source === kids && cached.spec == sort) return cached.visible
        }
        val visible = sortEntries(kids.filter { sort.showHidden || !it.hidden }, sort)
        sortedListings[dirId] = SortedListing(kids, sort, visible)
        return visible
    }

    private fun flatten(
        roots: List<XEntry>,
        expanded: Set<String>,
        children: Map<String, List<XEntry>>,
        loading: Set<String>,
        errors: Map<String, String>,
        sort: SortSpec,
    ): List<TreeNode> {
        sortedListings.keys.retainAll(children.keys)
        val out = ArrayList<TreeNode>(256)

        fun visit(entries: List<XEntry>, depth: Int, guides: List<Boolean>, parentKey: String) {
            entries.forEachIndexed { index, e ->
                val isLast = index == entries.lastIndex
                val isExpanded = e.isContainer && e.id in expanded
                val nodeKey = "$parentKey|${e.id}"
                out += TreeNode(
                    entry = e,
                    key = nodeKey,
                    depth = depth,
                    expanded = isExpanded,
                    loading = e.id in loading,
                    guides = guides,
                    isLastChild = isLast,
                    error = errors[e.id],
                )
                if (isExpanded) {
                    children[e.id]?.let { kids ->
                        visit(sortedVisible(e.id, kids, sort), depth + 1, guides + !isLast, nodeKey)
                    }
                }
            }
        }
        visit(roots, 0, emptyList(), "")
        return out
    }

    private fun sortEntries(entries: List<XEntry>, sort: SortSpec): List<XEntry> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { e: XEntry -> e.name }
        var cmp: Comparator<XEntry> = when (sort.by) {
            SortBy.NAME -> byName
            SortBy.SIZE -> compareBy<XEntry> { it.size }.then(byName)
            SortBy.DATE -> compareBy<XEntry> { it.mtime }.then(byName)
            SortBy.TYPE -> compareBy<XEntry> { it.extension }.then(byName)
        }
        if (sort.descending) cmp = cmp.reversed()
        if (sort.dirsFirst) cmp = compareByDescending<XEntry> { it.isDir }.then(cmp)
        return entries.sortedWith(cmp)
    }
}
