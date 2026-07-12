package app.local1st.files.ui.browser

import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.SortBy
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
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

    init {
        reloadRoots(expandFirst = true)
    }

    // ---- loading ----

    fun reloadRoots(expandFirst: Boolean = false) {
        scope.launch {
            loadingRoots.value = true
            val list = withContext(Dispatchers.IO) {
                runCatching { Graph.roots.paneRoots() }.getOrDefault(emptyList())
            }
            roots.value = list
            loadingRoots.value = false
            if (expandFirst) list.firstOrNull()?.let { first ->
                if (focusedDirId.value == null) {
                    focusedDirId.value = first.id
                    expand(first)
                }
            }
        }
    }

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
                onSuccess = { kids -> children.update { it + (entry.id to kids) } },
                onFailure = { err ->
                    errors.update { it + (entry.id to (err.message ?: "Cannot read folder")) }
                    children.update { it + (entry.id to emptyList()) }
                },
            )
            loading.update { it - entry.id }
            if (reloadRequested.remove(entry.id)) load(entry)
        }
    }

    /**
     * Drops all cached listings/errors and re-expands the first root. Call after storage
     * permission is granted so a pre-grant "Cannot read" failure is retried instead of
     * sticking around as a cached empty listing.
     */
    fun reset() {
        errors.value = emptyMap()
        children.value = emptyMap()
        expanded.value = emptySet()
        focusedDirId.value = null
        reloadRoots(expandFirst = true)
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

    fun toggleSelect(entry: XEntry) {
        if (entry.kind == app.local1st.files.core.fs.EntryKind.VOLUME_INTERNAL ||
            entry.kind == app.local1st.files.core.fs.EntryKind.VOLUME_SD ||
            entry.kind == app.local1st.files.core.fs.EntryKind.VOLUME_USB ||
            entry.kind == app.local1st.files.core.fs.EntryKind.APPS_ROOT ||
            entry.kind == app.local1st.files.core.fs.EntryKind.ROOT
        ) return
        selection.update { if (entry.id in it) it - entry.id else it + entry.id }
    }

    fun clearSelection() = selection.update { emptySet() }

    fun selectAllIn(dirId: String) {
        val kids = children.value[dirId] ?: return
        selection.update { it + kids.map { k -> k.id } }
    }

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

    private fun flatten(
        roots: List<XEntry>,
        expanded: Set<String>,
        children: Map<String, List<XEntry>>,
        loading: Set<String>,
        errors: Map<String, String>,
        sort: SortSpec,
    ): List<TreeNode> {
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
                        val visible = kids.filter { sort.showHidden || !it.hidden }
                        visit(sortEntries(visible, sort), depth + 1, guides + !isLast, nodeKey)
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
