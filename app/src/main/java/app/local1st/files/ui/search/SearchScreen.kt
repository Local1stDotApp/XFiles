package app.local1st.files.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.search.Resume
import app.local1st.files.core.search.SearchEvent
import app.local1st.files.core.search.SearchHit
import app.local1st.files.core.search.SearchOutcome
import app.local1st.files.core.search.SearchScope
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.EntryIcon
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.dialogs.searchHitLocation
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.navigationBarsStable
import app.local1st.files.ui.statusBarsStable
import java.io.IOException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private const val DEBOUNCE_MS = 400L
private const val MIN_QUERY_LENGTH = 2

private enum class SearchPhase { IDLE, SEARCHING, PAUSED, DONE }

/** Full-screen recursive filename search destination. */
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
    FlowPreview::class,
)
@Composable
fun SearchScreen(
    vm: MainViewModel,
    root: XEntry,
    onBack: () -> Unit,
) {
    val r = root
    val close = onBack
    val searchFailed = stringResource(R.string.search_failed)

    // Navigation 3 retains saveable entry state while another destination is on top.
    var query by rememberSaveable { mutableStateOf("") }
    // Every volume instead of the folder search was opened from.
    var allStorage by rememberSaveable { mutableStateOf(false) }
    // Chosen at a pause. Later queries on this screen then run to the end as well.
    var toTheEnd by rememberSaveable { mutableStateOf(false) }
    val results = remember { mutableStateListOf<SearchHit>() }
    var phase by remember { mutableStateOf(SearchPhase.IDLE) }
    var examined by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf<SearchEvent.Paused?>(null) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Cancels the running search. Null while none runs.
    var stop by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(r.id) {
        combine(
            // Typing waits for a pause; a scope change searches again at once.
            snapshotFlow { query.trim() }.debounce(DEBOUNCE_MS),
            snapshotFlow { allStorage },
        ) { q, all -> q to all }
            .collectLatest { (q, all) ->
                results.clear()
                examined = 0
                paused = null
                outcome = null
                stopped = false
                error = null
                if (q.length < MIN_QUERY_LENGTH) {
                    phase = SearchPhase.IDLE
                    return@collectLatest
                }
                phase = SearchPhase.SEARCHING
                coroutineScope {
                    val job = launch {
                        try {
                            val scope = if (all) SearchScope.AllStorage else SearchScope.Subtree(r)
                            Graph.searchEngine.search(scope, q, toTheEnd)
                                .collect { event ->
                                    when (event) {
                                        is SearchEvent.Hits -> results.addAll(event.hits)
                                        is SearchEvent.Progress -> examined = event.examined
                                        is SearchEvent.Paused -> {
                                            examined = event.examined
                                            paused = event
                                            phase = SearchPhase.PAUSED
                                        }
                                        is SearchEvent.Finished -> {
                                            examined = event.examined
                                            outcome = event.outcome
                                            phase = SearchPhase.DONE
                                        }
                                    }
                                }
                        } catch (e: IOException) {
                            error = e.message ?: searchFailed
                            phase = SearchPhase.DONE
                        }
                    }
                    stop = {
                        job.cancel()
                        paused = null
                        stopped = true
                        phase = SearchPhase.DONE
                    }
                    try {
                        job.join()
                    } finally {
                        stop = null
                    }
                }
            }
    }
    val resume = { choice: Resume ->
        if (choice == Resume.TO_THE_END) toTheEnd = true
        paused?.resume(choice)
        paused = null
        phase = SearchPhase.SEARCHING
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current
    val binName = stringResource(R.string.recycle_bin)
    val volumeRoots = Graph.roots.mountedVolumes.collectAsState().value.map { it.path }

    val bottomSafe = WindowInsets.ime.union(WindowInsets.navigationBarsStable)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBarsStable)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
                    // singleLine covers only the query; a long hint would still wrap and grow the field.
                    placeholder = {
                        Text(
                            stringResource(R.string.search_files_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        TooltipIconButton(
                            stringResource(R.string.close_search),
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            onClick = close,
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            TooltipIconButton(
                                stringResource(R.string.clear_query),
                                Icons.Outlined.Close,
                                onClick = { query = "" },
                            )
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )

                ScopeChips(
                    folderName = r.name,
                    allStorage = allStorage,
                    onAllStorageChange = { allStorage = it },
                )
                if (allStorage) {
                    Text(
                        stringResource(R.string.search_all_storage_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when (phase) {
                    SearchPhase.SEARCHING -> Row(
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                examined > 0 -> stringResource(
                                    R.string.search_progress,
                                    Format.count(examined),
                                    Format.count(results.size),
                                )
                                results.isEmpty() -> stringResource(R.string.searching)
                                else -> stringResource(R.string.found_so_far, results.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { stop?.invoke() }) {
                            Text(stringResource(R.string.search_stop))
                        }
                    }
                    SearchPhase.PAUSED -> Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.search_paused,
                                Format.count(examined),
                                Format.count(results.size),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        // Wraps where a translation makes the pair wider than the screen.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(onClick = { resume(Resume.NEXT_ROUND) }) {
                                Text(stringResource(R.string.search_keep_going))
                            }
                            OutlinedButton(onClick = { resume(Resume.TO_THE_END) }) {
                                Text(stringResource(R.string.search_to_the_end))
                            }
                        }
                    }
                    SearchPhase.DONE -> Text(
                        when {
                            error != null -> error.orEmpty()
                            stopped -> stringResource(
                                R.string.search_stopped,
                                Format.count(results.size),
                            )
                            outcome == SearchOutcome.TOO_MANY_HITS -> stringResource(
                                R.string.search_too_many,
                                Format.count(results.size),
                            )
                            results.size == 1 -> stringResource(R.string.one_result)
                            else -> stringResource(R.string.results, results.size)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    SearchPhase.IDLE -> {}
                }
            }

            if (results.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(bottomSafe),
                    contentAlignment = Alignment.Center,
                ) {
                    when (phase) {
                        SearchPhase.IDLE -> Text(
                            stringResource(R.string.search_minimum_length, MIN_QUERY_LENGTH),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SearchPhase.SEARCHING -> LoadingIndicator()
                        // The bar above says how far it got and offers the way on.
                        SearchPhase.PAUSED -> {}
                        // A stopped search did not look everywhere, so "no results" is not known.
                        SearchPhase.DONE -> if (error == null && !stopped) {
                            Text(
                                stringResource(R.string.no_results_for, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        bottom = bottomSafe.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    items(results, key = { it.entry.id }) { hit ->
                        SearchHitRow(
                            hit,
                            volumeRoots,
                            binName,
                            onClick = { vm.revealSearchHit(hit.entry.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Where to search: the folder search was opened from, or every volume. */
@Composable
private fun ScopeChips(
    folderName: String,
    allStorage: Boolean,
    onAllStorageChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = !allStorage,
            onClick = { onAllStorageChange(false) },
            label = { Text(folderName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            // A long folder name shrinks this chip, never the other one.
            modifier = Modifier.weight(1f, fill = false),
        )
        FilterChip(
            selected = allStorage,
            onClick = { onAllStorageChange(true) },
            label = { Text(stringResource(R.string.search_all_storage), maxLines = 1) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    volumeRoots: List<String>,
    binName: String,
    onClick: () -> Unit,
) {
    val entry = hit.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(
            entry,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                searchHitLocation(hit.parentId, volumeRoots, binName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            val size = Format.bytes(entry.size)
            if (size.isNotEmpty()) {
                Text(size, style = MaterialTheme.typography.labelSmall)
            }
            val date = Format.dateTime(entry.mtime)
            if (date.isNotEmpty()) {
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
