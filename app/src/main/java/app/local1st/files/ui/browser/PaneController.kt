package app.local1st.files.ui.browser

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.SortBy
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.R
import app.local1st.files.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SortSpec(
    val by: SortBy = SortBy.NAME,
    val descending: Boolean = false,
    val dirsFirst: Boolean = true,
    val showHidden: Boolean = false,
)

/**
 * State machine of one browser pane: an X-plore style tree where containers
 * expand in place. Not an AAC ViewModel — two of these live inside MainViewModel.
 */
class PaneController(
    val paneId: Int,
    private val scope: CoroutineScope,
) {
    private val registry get() = Graph.fsRegistry

    private val roots = MutableStateFlow<List<XEntry>>(emptyList())
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    private val children = MutableStateFlow<Map<String, List<XEntry>>>(emptyMap())
    private val loading = MutableStateFlow<Set<String>>(emptySet())
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val focusedDirId = MutableStateFlow<String?>(null)
    private val loadingRoots = MutableStateFlow(true)

    // Declared BEFORE `nodes`: its eager stateIn starts flatten() on another thread
    // during construction, so everything flatten touches must already be initialized.
    private val sortedListings = HashMap<String, SortedListing>()

    /** Entry id the pane list should scroll to (consumed by the UI). */
    val scrollTo = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val sortSpec: StateFlow<SortSpec> = combine(
        Graph.settings.sortBy,
        Graph.settings.sortDescending,
        Graph.settings.dirsFirst,
        Graph.settings.showHidden,
    ) { by, desc, dirsFirst, hidden -> SortSpec(by, desc, dirsFirst, hidden) }
        .stateIn(scope, SharingStarted.Eagerly, SortSpec())

    // Flattening (filter + per-dir sort) depends only on tree state, not on selection/focus,
    // so it lives in its own flow computed off the main thread. Selection toggles then only
    // re-run the cheap outer combine instead of re-sorting the whole visible tree.
    private val nodes: StateFlow<List<TreeNode>> = combine(
        combine(roots, expanded, children) { r, e, c -> Triple(r, e, c) },
        combine(loading, errors) { l, err -> l to err },
        sortSpec,
    ) { (r, e, c), (l, err), sort -> flatten(r, e, c, l, err, sort) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<PaneUiState> = combine(
        nodes,
        selection,
        focusedDirId,
        loadingRoots,
    ) { n, sel, focus, lr ->
        PaneUiState(nodes = n, selection = sel, focusedDirId = focus, loadingRoots = lr)
    }.stateIn(scope, SharingStarted.Eagerly, PaneUiState())

    /** (expanded ids, focused dir id) as persisted for session restore. */
    val sessionState = combine(expanded, focusedDirId) { e, f -> e to f }

    /** Synchronous [sessionState] snapshot, for the final flush when the ViewModel is cleared. */
    fun sessionSnapshot(): Pair<Set<String>, String?> = expanded.value to focusedDirId.value

    // No initial load here: MainViewModel always drives startup through [restore],
    // which falls back to the plain first-root expansion when nothing was saved.

    // ---- loading ----

    fun reloadRoots(expandFirst: Boolean = false) {
        scope.launch {
            loadingRoots.value = true
            val list = withContext(Dispatchers.IO) {
                runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
            }
            roots.value = list
            loadingRoots.value = false
            if (expandFirst) expandFirstRoot()
        }
    }

    private fun expandFirstRoot() {
        roots.value.firstOrNull()?.let { first ->
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
    suspend fun restore(savedExpanded: Set<String>, savedFocused: String?) {
        loadingRoots.value = true
        val list = withContext(Dispatchers.IO) {
            runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
        }
        roots.value = list
        loadingRoots.value = false

        if (savedFocused == null && savedExpanded.isEmpty()) {
            expandFirstRoot()
            return
        }

        // Adopt the whole saved set (sub-expansions under a collapsed parent stay
        // remembered), then list only dirs actually reachable from a current root.
        var restoredAny = false
        if (savedExpanded.isNotEmpty()) {
            expanded.value = savedExpanded
            val queue = ArrayDeque(list.filter { it.isContainer && it.id in savedExpanded })
            val visited = HashSet<String>()
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                if (!visited.add(dir.id)) continue
                restoredAny = true
                loadNow(dir).forEach { kid ->
                    if (kid.isContainer && kid.id in savedExpanded) queue.add(kid)
                }
            }
        }

        val target = savedFocused?.let { nearestExisting(it) }?.takeIf { reachable(it) }
        when {
            target != null -> revealPath(target)
            !restoredAny -> expandFirstRoot()
            // else: tree restored but focus lost; focusedDirEntry() falls back to the first root.
        }
    }

    /**
     * Re-runs [restore] from the current in-memory state after storage access was granted
     * mid-session. Pre-grant listings all failed (and were cached as errors), so drop the
     * caches and replay the expansion/focus the user still has — unlike a blind [reset],
     * this keeps the restored session instead of wiping it (and letting the auto-save
     * persist the wipe).
     */
    suspend fun restoreAfterGrant() {
        errors.value = emptyMap()
        children.value = emptyMap()
        restore(expanded.value, focusedDirId.value)
    }

    /** Walks [id] up its parent chain to the first entry that still exists, or null. */
    private suspend fun nearestExisting(id: String): String? = withContext(Dispatchers.IO) {
        var cur: String? = id
        while (cur != null) {
            val candidate = cur
            if (runCatching { registry.forId(candidate).stat(candidate) }.getOrNull() != null) {
                return@withContext candidate
            }
            cur = XId.parent(candidate)
        }
        null
    }

    /** True when [id]'s ancestor chain passes through one of the current pane roots. */
    private fun reachable(id: String): Boolean {
        val rootIds = roots.value.mapTo(HashSet()) { it.id }
        var cur: String? = id
        while (cur != null) {
            if (cur in rootIds) return true
            cur = XId.parent(cur)
        }
        return false
    }

    /**
     * Drops cached listings/errors for every id under [scheme] and re-lists the dirs still
     * expanded. Call when a filesystem-wide gate flips (root browsing toggled): without this,
     * listings cached while the gate was open stay browsable after it closes, and gate-error
     * rows cached while it was closed outlive re-opening it.
     */
    fun invalidateScheme(scheme: String) {
        val prefix = "$scheme://"
        val ids = children.value.keys.filter { it.startsWith(prefix) }.toSet()
        if (ids.isEmpty()) return
        children.update { it - ids }
        errors.update { it - ids }
        ids.filter { it in expanded.value }.forEach { id -> findEntry(id)?.let { load(it) } }
    }

    /** True when [id] is one of the pane's current top-level roots. */
    fun isTopLevelRoot(id: String): Boolean = roots.value.any { it.id == id }

    /** Ids whose reload was requested while a load was already in flight; re-run on completion. */
    private val reloadRequested = HashSet<String>()

    private fun load(entry: XEntry) {
        if (entry.id in loading.value) {
            // Don't drop the request: a refresh during an in-flight load must re-list.
            reloadRequested += entry.id
            return
        }
        loading.update { it + entry.id }
        errors.update { it - entry.id }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { registry.forEntry(entry).list(entry) }
            }
            result.fold(
                onSuccess = { kids ->
                    children.update { it + (entry.id to kids) }
                    // Cascade into remembered-but-unloaded sub-expansions. A restored deep
                    // expansion sits in `expanded` with no cached children (restore only lists
                    // dirs reachable through an already-expanded ancestor), so when its collapsed
                    // parent is finally opened it would render as an open folder with no rows
                    // until the user toggled it. Load such children now, as their parent's listing
                    // lands; each recurses through its own onSuccess down the remembered chain.
                    kids.forEach { kid ->
                        if (kid.isContainer && kid.id in expanded.value &&
                            children.value[kid.id] == null && kid.id !in loading.value
                        ) {
                            load(kid)
                        }
                    }
                },
                onFailure = { err ->
                    errors.update { it + (entry.id to (err.message ?: Graph.appContext.getString(R.string.cannot_read_folder))) }
                    children.update { it + (entry.id to emptyList()) }
                },
            )
            loading.update { it - entry.id }
            if (reloadRequested.remove(entry.id)) load(entry)
        }
    }

    // ---- navigation / expansion ----

    fun toggleExpand(entry: XEntry) {
        if (!entry.isContainer) return
        if (entry.id in expanded.value) collapse(entry) else expand(entry)
    }

    fun expand(entry: XEntry) {
        if (!entry.isContainer) return
        expanded.update { it + entry.id }
        focusedDirId.value = entry.id
        // Reload when uncached or when the last attempt failed (e.g. before permission grant).
        if (children.value[entry.id] == null || entry.id in errors.value) load(entry)
    }

    fun collapse(entry: XEntry) {
        expanded.update { it - entry.id }
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
        focusedDirId.value = if (entry.isContainer) entry.id else XId.parent(entry.id)
    }

    /** Expand the ancestor chain of [id], then scroll to it. */
    fun revealPath(id: String) {
        scope.launch {
            val chain = generateSequence(XId.parent(id)) { XId.parent(it) }.toList().reversed()
            for (ancestorId in chain) {
                val entry = findEntry(ancestorId)
                    ?: withContext(Dispatchers.IO) { runCatching { registry.forId(ancestorId).stat(ancestorId) }.getOrNull() }
                    ?: continue
                if (!entry.isContainer) continue
                expanded.update { it + entry.id }
                if (children.value[entry.id] == null) {
                    val kids = withContext(Dispatchers.IO) {
                        runCatching { registry.forEntry(entry).list(entry) }.getOrDefault(emptyList())
                    }
                    children.update { it + (entry.id to kids) }
                }
            }
            focusedDirId.value = findEntry(id)?.takeIf { it.isContainer }?.id ?: XId.parent(id)
            scrollTo.tryEmit(id)
        }
    }

    /**
     * Expand [app] and then its base APK child so the APK's zip contents show inline
     * (the explicit "Open as zip" action). No-op if the app exposes no browsable APK.
     */
    fun revealAppApk(app: XEntry) {
        scope.launch {
            expanded.update { it + app.id }
            focusedDirId.value = app.id
            val kids = loadNow(app)
            val apk = kids.firstOrNull {
                it.kind == EntryKind.ARCHIVE && it.name == "base.apk"
            } ?: kids.firstOrNull { it.kind == EntryKind.ARCHIVE }
                ?: return@launch
            expanded.update { it + apk.id }
            loadNow(apk)
            focusedDirId.value = apk.id
            scrollTo.tryEmit(apk.id)
        }
    }

    /** Lists [entry]'s children synchronously (awaiting IO) and caches them; returns the list. */
    private suspend fun loadNow(entry: XEntry): List<XEntry> {
        children.value[entry.id]?.let { if (entry.id !in errors.value) return it }
        loading.update { it + entry.id }
        val result = withContext(Dispatchers.IO) {
            runCatching { registry.forEntry(entry).list(entry) }
        }
        val kids = result.getOrDefault(emptyList())
        children.update { it + (entry.id to kids) }
        result.exceptionOrNull()
            ?.let { e -> errors.update { it + (entry.id to (e.message ?: Graph.appContext.getString(R.string.cannot_read, entry.name))) } }
            ?: errors.update { it - entry.id }
        loading.update { it - entry.id }
        return kids
    }

    // ---- refresh ----

    fun refresh(dirId: String) {
        val entry = findEntry(dirId) ?: return
        if (children.value.containsKey(dirId)) load(entry)
    }

    fun refreshDirty(ids: Set<String>) {
        ids.forEach { refresh(it) }
        // A delete/move may have removed the focused dir. Once the triggered reloads settle,
        // if the focused id no longer exists, fall back to the nearest surviving ancestor.
        scope.launch {
            loading.first { it.isEmpty() }
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
        reloadRoots()
        children.value.keys.filter { it in expanded.value }.forEach { id ->
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
        if (!entry.isContainer || entry.id !in expanded.value) return emptyList()
        val kids = children.value[entry.id] ?: return emptyList()
        val showHidden = sortSpec.value.showHidden
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
        roots.value.forEach { if (it.id in sel) found[it.id] = it }
        children.value.values.forEach { list ->
            list.forEach { if (it.id in sel) found[it.id] = it }
        }
        return found.values.toList()
    }

    // ---- lookups ----

    fun findEntry(id: String): XEntry? {
        roots.value.firstOrNull { it.id == id }?.let { return it }
        children.value.values.forEach { list ->
            list.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    fun focusedDirEntry(): XEntry? =
        focusedDirId.value?.let { findEntry(it) } ?: roots.value.firstOrNull()

    /** Cached siblings of [entry] with the given category (for viewer paging/playlists). */
    fun siblings(entry: XEntry, category: FileCategory): List<XEntry> {
        val parentId = XId.parent(entry.id) ?: return listOf(entry)
        val kids = children.value[parentId] ?: return listOf(entry)
        val sorted = sortEntries(kids, sortSpec.value)
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
