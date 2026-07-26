package app.local1st.files.ui.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditOff
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.local1st.files.R
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.text.ArrayByteWindow
import app.local1st.files.core.text.ByteWindow
import app.local1st.files.core.text.FileByteWindow
import app.local1st.files.core.text.TextRowIndex
import app.local1st.files.core.util.AxmlDecoder
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import app.local1st.files.ui.components.TooltipIconButton
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Schemes that can only be streamed get this much of their head; a real file gets all of it. */
private const val STREAM_LIMIT_BYTES = 8 * 1024 * 1024

/**
 * Editing holds the whole text in memory in one text field, and Compose lays that field out whole —
 * so what it costs tracks the file, not the screen, and it is paid on the main thread before the
 * editor appears. Measured on a OnePlus 7 Pro, release build: 200 KB opens in about a second, 512 KB
 * in ten, 1 MB in half a minute, 2 MB never finishes, and 20 MB exhausts the heap. Half a megabyte
 * is the last size that still ends in an editor rather than in a wait, so that is where this sits.
 * Raising it means not laying the whole document out at once — a different editor, not a bigger cap.
 */
private const val EDIT_LIMIT_BYTES = 512L * 1024

private const val AXML_PROBE_BYTES = 64
private const val AXML_LIMIT_BYTES = 8L * 1024 * 1024
private const val ROWS_PER_PAGE = 128

/**
 * Pages held at once. Deliberately modest: a page is 128 rows, and a file of maximum-length rows
 * makes each of those half a megabyte of text, so a generous cache would cost tens of megabytes on
 * exactly the files this viewer exists for. Four screenfuls' worth is plenty to scroll on.
 */
private const val MAX_CACHED_ROW_PAGES = 32

/**
 * Plain-text viewer with optional in-place editing for small writable local files.
 *
 * A real file is never read whole: [TextRowIndex] walks it once to learn where its rows start and
 * the list decodes only the rows on screen, so a multi-gigabyte log opens at once, scrolls at a
 * constant few megabytes of memory, and reports its line count as it goes. Entries that can only be
 * streamed (archive members, su paths) still show their leading 8 MiB.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextViewer(entry: XEntry, onClose: () -> Unit) {
    val cannotRead = stringResource(R.string.cannot_read, entry.name)
    val saveFailed = stringResource(R.string.save_failed)
    val saved = stringResource(R.string.saved, entry.name)
    var reloads by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var preparingEdit by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val wrap by Graph.settings.textWrap.collectAsState(initial = false)

    val file = remember(entry.id) { pageableFile(entry) }
    val document = remember(entry.id, reloads) { TextDocument(entry, file) }
    DisposableEffect(document) {
        document.start()
        onDispose { document.close() }
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(3000)
            feedback = null
        }
    }

    val rowCount by document.rowCount
    val loadError by document.error
    val opened by document.opened
    val complete by document.complete
    // A pageable file is never the truncated kind, so only the decoded-XML case has to be ruled out.
    val canEdit = file != null && entry.scheme == XId.SCHEME_FILE && entry.canWrite &&
        !document.axml.value && document.sizeBytes.value in 0..EDIT_LIMIT_BYTES

    fun save() {
        if (saving) return
        saving = true
        // Snapshotted here, not read on the IO thread when the write finally starts: what gets
        // written is what was on screen when Save was pressed. The field is read-only meanwhile, so
        // nothing can be typed into the gap and then thrown away by the re-index below.
        val pending = editText
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // LocalFileSystem preserves this editor's atomic File replacement where
                    // permitted and owns the narrow API 26-29 secondary-volume SAF fallback.
                    val fs = Graph.fsRegistry.forId(entry.id) as LocalFileSystem
                    fs.replaceContents(entry, pending.toByteArray(Charsets.UTF_8))
                }
            }
            saving = false
            result.fold(
                onSuccess = {
                    editing = false
                    feedback = saved
                    // The bytes on disk moved: index them again rather than trust the old rows.
                    reloads++
                },
                onFailure = { feedback = it.message ?: saveFailed },
            )
        }
    }

    fun toggleEditing() {
        if (editing) {
            editing = false
            return
        }
        // Guarded: without it a second tap starts a second read whose result lands on top of
        // whatever has been typed in the meantime.
        if (preparingEdit) return
        val target = file ?: return
        preparingEdit = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    // Re-checked against the file as it is now: the button was enabled from a length
                    // read when the viewer opened, and a live log can have grown past editing since.
                    if (target.length() > EDIT_LIMIT_BYTES) throw IOException(cannotRead)
                    target.readText()
                }
            }
            preparingEdit = false
            loaded.fold(
                onSuccess = {
                    editText = it
                    editing = true
                },
                onFailure = {
                    feedback = it.message ?: cannotRead
                    // Whatever the length says now is what the header and the button should reflect.
                    reloads++
                },
            )
        }
    }

    ViewerChrome(
        modifier = Modifier.imePadding(),
        // Pinned while editing: the editor scrolls itself to follow the cursor, and Save must not
        // ride away with the bar.
        collapsible = !editing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (editing) {
                                val lines = remember(editText) { countLines(editText) }
                                stringResource(R.string.lines, lines)
                            } else {
                                documentSubtitle(document)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    TooltipIconButton(stringResource(R.string.close), Icons.Outlined.Close, onClick = onClose)
                },
                actions = {
                    // Not while editing: the field wraps whatever this says, so offering the choice
                    // there would be offering one that does not exist.
                    if (!editing) {
                        TooltipIconButton(
                            stringResource(if (wrap) R.string.stop_wrapping else R.string.wrap_lines),
                            Icons.AutoMirrored.Outlined.WrapText,
                            selected = wrap,
                            // The app scope, not the viewer's: closing the viewer on the same tap
                            // would otherwise cancel the write and lose the choice.
                            onClick = { Graph.appScope.launch { Graph.settings.setTextWrap(!wrap) } },
                        )
                    }
                    if (canEdit) {
                        if (editing) {
                            TooltipIconButton(
                                stringResource(R.string.save),
                                Icons.Outlined.Save,
                                enabled = !saving,
                                onClick = { save() },
                            )
                        }
                        TooltipIconButton(
                            stringResource(if (editing) R.string.stop_editing else R.string.edit),
                            if (editing) Icons.Outlined.EditOff else Icons.Outlined.Edit,
                            enabled = !preparingEdit && !saving,
                            onClick = { toggleEditing() },
                        )
                    }
                },
            )
        },
    ) { chrome ->
        Box(Modifier.fillMaxSize()) {
            when {
                editing -> BasicTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    // Frozen while the write is in flight: a keystroke that lands after the bytes
                    // were snapshotted would be discarded by the re-index, silently.
                    readOnly = saving,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // Inside the scroll, so the text travels under both system bars while its
                        // first and last lines still settle clear of them.
                        .padding(
                            start = 12.dp,
                            top = chrome.calculateTopPadding() + 12.dp,
                            end = 12.dp,
                            bottom = chrome.calculateBottomPadding() + 12.dp,
                        ),
                )
                // An error with nothing indexed is the whole story; one that arrives later is a
                // banner over the rows that did make it.
                loadError != null && rowCount == 0 -> ViewerNotice(chrome) {
                    Text(
                        loadError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                !opened || (rowCount == 0 && !complete) -> ViewerNotice(chrome) { LoadingIndicator() }
                rowCount == 0 -> ViewerNotice(chrome) {
                    Text(stringResource(R.string.empty_file), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> TextRows(entry, document, rowCount, chrome, wrap)
            }

            feedback?.let { message ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = chrome.calculateBottomPadding())
                        .padding(16.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * One row per list item, decoded from the document's page cache as it scrolls past.
 *
 * Unwrapped by default: a line runs off the right edge and the list scrolls sideways as a whole, so
 * indentation and column alignment survive — which is the only way XML, JSON or source reads as it
 * was written. What that costs is knowing how wide to be: finding the longest line means laying out
 * every row, and not doing that is why this viewer opens a gigabyte at once. So the width is the
 * widest row laid out *so far*, and it only ever grows. Growing costs one re-layout when a longer
 * line first comes into view; shrinking would slide the text sideways under a reader who scrolled
 * into a narrow stretch of the file, so it is never allowed. The floor is the window's own width,
 * or the list would only take scroll gestures over the strip its text happens to cover.
 *
 * With [wrap] on, rows break to the window instead and there is nothing to scroll sideways.
 *
 * Notices ride along as the first items so they scroll away with the text instead of pinning it.
 *
 * Selection is per row, which is what a list of rows can offer: copying spans of rows joins them
 * with newlines, so a line long enough to have been broken up comes back with a break in it, and
 * "select all" reaches as far as the rows that are composed. The alternative — one text holding the
 * document — is the thing this viewer exists to avoid.
 */
@Composable
private fun TextRows(
    entry: XEntry,
    document: TextDocument,
    rowCount: Int,
    chrome: PaddingValues,
    wrap: Boolean,
) {
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    var widest by remember { mutableIntStateOf(0) }
    val baseStyle = MaterialTheme.typography.bodySmall
    val rowStyle = remember(baseStyle, wrap) {
        // Hanging indent, and only where it means something: what a line wraps onto stays
        // distinguishable from the next line, which is most of what unwrapped text gives for free.
        if (wrap) baseStyle.copy(textIndent = TextIndent(restLine = 16.sp)) else baseStyle
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = with(LocalDensity.current) { maxOf(widest, constraints.maxWidth).toDp() }
        val rowModifier = if (wrap) {
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        } else {
            Modifier.padding(horizontal = 12.dp)
        }
        SelectionContainer {
            Box(
                if (wrap) Modifier.fillMaxSize()
                else Modifier.fillMaxSize().horizontalScroll(horizontal),
            ) {
                LazyColumn(
                    if (wrap) {
                        Modifier.fillMaxSize()
                    } else {
                        // Measured against an unbounded width, so the list comes out as wide as its
                        // widest composed row — with the running maximum as a floor, which is what
                        // keeps the sideways offset still when that row scrolls out of sight.
                        Modifier
                            .fillMaxHeight()
                            .widthIn(min = contentWidth)
                            .onSizeChanged { if (it.width > widest) widest = it.width }
                    },
                    state = listState,
                    contentPadding = PaddingValues(
                        top = chrome.calculateTopPadding() + 8.dp,
                        bottom = chrome.calculateBottomPadding() + 8.dp,
                    ),
                ) {
                    document.error.value?.let { message ->
                        item {
                            TextBanner(
                                message,
                                MaterialTheme.colorScheme.errorContainer,
                                contentWidth,
                                wrap,
                            )
                        }
                    }
                    if (document.axml.value) {
                        item {
                            TextBanner(
                                stringResource(R.string.decoded_binary_xml),
                                MaterialTheme.colorScheme.secondaryContainer,
                                contentWidth,
                                wrap,
                            )
                        }
                    }
                    if (document.truncated.value) {
                        item {
                            TextBanner(
                                stringResource(R.string.showing_first_8_mb, entry.name),
                                MaterialTheme.colorScheme.tertiaryContainer,
                                contentWidth,
                                wrap,
                            )
                        }
                    }
                    items(rowCount) { row ->
                        // Re-requests after eviction too: SideEffect runs on every recomposition and
                        // request() is a no-op for a cached or in-flight page.
                        SideEffect { document.request(row) }
                        Text(
                            document.row(row) ?: "",
                            fontFamily = FontFamily.Monospace,
                            style = rowStyle,
                            softWrap = wrap,
                            maxLines = if (wrap) Int.MAX_VALUE else 1,
                            modifier = rowModifier,
                        )
                    }
                }
            }
        }
        // Outside the horizontal scroll: the thumb belongs to the window, not to the text's width.
        ViewerScrollbar(
            listState,
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = chrome.calculateTopPadding(), bottom = chrome.calculateBottomPadding()),
        )
    }
}

/** Size, lines found so far, and — while a big file is still being walked — how far that got. */
@Composable
private fun documentSubtitle(document: TextDocument): String {
    val lines = stringResource(R.string.lines, document.lineCount.value)
    val size = document.sizeBytes.value
    val head = if (size >= 0) Format.bytes(size) + " · " else ""
    val tail = if (document.complete.value) "" else " · ${(document.progress.value * 100).roundToInt()}%"
    return head + lines + tail
}

/**
 * Full-width notice above the text: a read error, truncation, or "this was compiled binary XML".
 * Unwrapped, the width to fill is the list's rather than the window's — filling a width that was
 * never bounded would leave the notice hugging its own text.
 */
@Composable
private fun TextBanner(message: String, color: Color, width: Dp, wrap: Boolean) {
    Surface(color = color, modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.width(width)) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * The file behind [entry] when there is one that can be paged, rather than streamed. Everything
 * else — archive members, su paths, an unreadable path on a legacy secondary volume — has no
 * seekable handle, so it is read as a leading window instead.
 *
 * A length of zero also goes the streaming way: /proc and /sys nodes report no length but hand over
 * plenty of bytes when read, and paging trusts the length. A file that really is empty reads as
 * empty either way.
 */
private fun pageableFile(entry: XEntry): File? {
    val path = entry.localPath ?: entry.path.takeIf { entry.scheme == XId.SCHEME_FILE } ?: return null
    return File(path).takeIf { it.isFile && it.canRead() && it.length() > 0L }
}

/**
 * One open text document: the index over its bytes, how far indexing has come, and a bounded cache
 * of decoded rows. Composition only ever reads the cache; a miss schedules the page and shows a
 * blank row until it lands.
 */
private class TextDocument(private val entry: XEntry, private val file: File?) {
    /** True once the bytes are open and rows can start arriving. */
    val opened = mutableStateOf(false)
    val rowCount = mutableStateOf(0)
    val lineCount = mutableStateOf(0)
    val sizeBytes = mutableStateOf(-1L)
    val progress = mutableStateOf(0f)
    val complete = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    /** Set when the entry could only be streamed and the stream was cut short. */
    val truncated = mutableStateOf(false)

    /** Set when the bytes turned out to be compiled Android binary XML, decoded for display. */
    val axml = mutableStateOf(false)

    private val pages = mutableStateMapOf<Int, List<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private var window: ByteWindow? = null
    private var index: TextRowIndex? = null
    private var closed = false

    fun start() {
        scope.launch {
            // Throwable, not Exception: reading a whole stream into memory can exhaust the heap, and
            // that has to reach the reader as "cannot read" rather than as a dead process. Nothing
            // here suspends, so this cannot swallow cancellation.
            val source = try {
                openWindow()
            } catch (e: Throwable) {
                error.value = e.message ?: message(R.string.cannot_read)
                return@launch
            }
            val rowIndex = TextRowIndex(source)
            synchronized(this@TextDocument) {
                if (closed) {
                    source.close()
                    return@launch
                }
                window = source
                index = rowIndex
            }
            // A file is measured here and now, so the header follows an edit; a stream's window is
            // only its head, so there the entry's size is the honest one.
            sizeBytes.value = when {
                file != null -> file.length()
                entry.size >= 0 -> entry.size
                else -> source.size
            }
            opened.value = true
            try {
                rowIndex.scan { publish(rowIndex) }
            } catch (e: IOException) {
                error.value = e.message ?: message(R.string.read_error)
            }
            publish(rowIndex)
        }
    }

    fun row(row: Int): String? = pages[row / ROWS_PER_PAGE]?.getOrNull(row % ROWS_PER_PAGE)

    fun request(row: Int) {
        val page = row / ROWS_PER_PAGE
        if (pages.containsKey(page) || !inFlight.add(page)) return
        scope.launch {
            try {
                val rowIndex = synchronized(this@TextDocument) { index } ?: return@launch
                // Read before the rows, never after: a scan that finishes in between would make a
                // page that was cut short at the time look like a legitimately short last page.
                val indexed = rowIndex.isComplete
                val rows = rowIndex.rows(page * ROWS_PER_PAGE, ROWS_PER_PAGE)
                // A page cut short because the scan has not reached its end yet is not cached: the
                // next progress update recomposes those rows and they ask again.
                if (rows.size == ROWS_PER_PAGE || indexed) {
                    trimIfNeeded()
                    pages[page] = rows
                }
            } catch (e: Exception) {
                // Nothing is cached for a page that failed, so the rows retry rather than staying
                // blank for as long as the viewer is open.
                error.value = e.message ?: message(R.string.read_error)
            } finally {
                inFlight.remove(page)
            }
        }
    }

    fun close() {
        val open = synchronized(this) {
            closed = true
            window
        }
        scope.cancel()
        open?.let { runCatching { it.close() } }
    }

    /**
     * Opens the bytes to show: the file itself when it can be paged, its decoded form when it is
     * compiled binary XML, else as much of the stream as is allowed in memory.
     */
    private fun openWindow(): ByteWindow {
        if (file != null) {
            val paged = FileByteWindow(file)
            // Closed on any failure on the way out: an open handle nobody holds a reference to is a
            // descriptor leaked for the life of the process, once per failed open and again per save.
            val decoded = try {
                val head = ByteArray(minOf(paged.size, AXML_PROBE_BYTES.toLong()).toInt())
                paged.read(0, head, 0, head.size)
                // Compiled Android binary XML (a compiled resource XML on disk) is decoded whole —
                // they are small, and the size cap keeps a stray magic number from pulling in a
                // gigabyte.
                if (!AxmlDecoder.isAxml(head) || paged.size > AXML_LIMIT_BYTES) return paged
                AxmlDecoder.decode(file.readBytes())
            } catch (e: Throwable) {
                paged.close()
                throw e
            }
            paged.close()
            axml.value = true
            return ArrayByteWindow(decoded.toByteArray(Charsets.UTF_8))
        }
        // One byte over the limit tells truncation from a file that ends exactly on it. The window
        // is told to stop at the limit rather than copied down to it.
        val bytes = Graph.fsRegistry.forId(entry.id).openIn(entry)
            .use { it.readUpTo(STREAM_LIMIT_BYTES + 1) }
        val cut = bytes.size > STREAM_LIMIT_BYTES
        if (AxmlDecoder.isAxml(bytes)) {
            val visible = if (cut) bytes.copyOf(STREAM_LIMIT_BYTES) else bytes
            axml.value = true
            return ArrayByteWindow(AxmlDecoder.decode(visible).toByteArray(Charsets.UTF_8))
        }
        truncated.value = cut
        return ArrayByteWindow(bytes, minOf(bytes.size, STREAM_LIMIT_BYTES))
    }

    private fun publish(index: TextRowIndex) {
        rowCount.value = index.rowCount
        lineCount.value = index.lineCount
        complete.value = index.isComplete
        progress.value =
            if (index.size <= 0) 1f else (index.indexedBytes.toFloat() / index.size).coerceIn(0f, 1f)
    }

    private fun trimIfNeeded() {
        if (pages.size < MAX_CACHED_ROW_PAGES) return
        // Arbitrary eviction; evicted rows on screen simply re-request their page.
        pages.keys.toList().take(MAX_CACHED_ROW_PAGES / 4).forEach { pages.remove(it) }
    }

    private fun message(resId: Int): String = Graph.appContext.getString(resId, entry.name)
}

/** Counts lines the way [TextRowIndex] does, so the header does not jump when editing starts. */
private fun countLines(s: String): Int {
    if (s.isEmpty()) return 0
    var lines = 0
    for (c in s) if (c == '\n') lines++
    // A file ending in a newline does not end in an empty line; one that does not still ends in a line.
    return if (s.endsWith('\n')) lines else lines + 1
}

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 1 shl 16))
    val buffer = ByteArray(1 shl 16)
    var remaining = limit
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}
