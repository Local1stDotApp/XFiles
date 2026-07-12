package com.xfiles.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.core.search.SearchHit
import com.xfiles.core.util.Format
import com.xfiles.di.Graph
import com.xfiles.ui.browser.EntryIcons
import com.xfiles.ui.main.MainViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn

private const val DEBOUNCE_MS = 400L
private const val MIN_QUERY_LENGTH = 2

private enum class SearchPhase { IDLE, SEARCHING, DONE }

/** Full-screen recursive filename search below [MainViewModel.searchRoot]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)
@Composable
fun SearchOverlay(vm: MainViewModel) {
    val root by vm.searchRoot.collectAsState()
    val r = root ?: return
    val close = { vm.searchRoot.value = null }

    BackHandler(onBack = close)

    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<SearchHit>() }
    var phase by remember { mutableStateOf(SearchPhase.IDLE) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(r.id) {
        snapshotFlow { query.trim() }
            .debounce(DEBOUNCE_MS)
            .collectLatest { q ->
                results.clear()
                error = null
                if (q.length < MIN_QUERY_LENGTH) {
                    phase = SearchPhase.IDLE
                    return@collectLatest
                }
                phase = SearchPhase.SEARCHING
                try {
                    Graph.searchEngine.search(r, q)
                        .flowOn(Dispatchers.IO)
                        .collect { results.add(it) }
                    phase = SearchPhase.DONE
                } catch (e: IOException) {
                    error = e.message ?: "Search failed"
                    phase = SearchPhase.DONE
                }
            }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("Search files (* and ? wildcards)") },
                leadingIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search")
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, "Clear query")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )

            Text(
                "Searching in ${r.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            when (phase) {
                SearchPhase.SEARCHING -> Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (results.isEmpty()) "Searching…" else "${results.size} found so far…",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                SearchPhase.DONE -> Text(
                    when {
                        error != null -> error.orEmpty()
                        results.size == 1 -> "1 result"
                        else -> "${results.size} results"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SearchPhase.IDLE -> {}
            }

            if (results.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (phase) {
                        SearchPhase.IDLE -> Text(
                            "Type at least $MIN_QUERY_LENGTH characters to search",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SearchPhase.SEARCHING -> LoadingIndicator()
                        SearchPhase.DONE -> if (error == null) {
                            Text(
                                "No results for \"${query.trim()}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(results, key = { it.entry.id }) { hit ->
                        SearchHitRow(hit, onClick = { vm.revealSearchHit(hit.entry.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    val entry = hit.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = EntryIcons.forEntry(entry),
            contentDescription = null,
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
                hit.parentId.substringAfter("://"),
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
