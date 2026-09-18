package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import app.local1st.files.R
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.text.ArrayByteWindow
import app.local1st.files.core.text.ByteWindow
import app.local1st.files.core.text.EditBuffer
import app.local1st.files.core.text.EditCaret
import app.local1st.files.core.text.EditWindow
import app.local1st.files.core.text.FileByteWindow
import app.local1st.files.core.text.TextRowIndex
import app.local1st.files.core.text.loadEditWindowAround
import app.local1st.files.core.util.AxmlDecoder
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import app.local1st.files.ui.components.TooltipIconButton
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Schemes that can only be streamed get this much of their head; a real file gets all of it. */
private const val STREAM_LIMIT_BYTES = 8 * 1024 * 1024

/**
 * Editing holds a slice, not the file: Compose still cannot page a single field, so the window
 * is this wide and Save splices it back. The rows themselves are a list, like the viewer, so
 * scrolling the slice does not lay 512 KB out as one field. Scroll to another place and edit
 * again to change a different part.
 */
private const val EDIT_LIMIT_BYTES = 512L * 1024

/** One delayed IME deleteSurroundingText after a line join; must outlive recomposition. */
private const val JOIN_SWALLOW_MS = 400L

private const val EDITOR_FOCUS_RETRY_MS = 16L
private const val EDITOR_FOCUS_GIVE_UP_MS = 500L

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
 * Plain-text viewer with optional in-place editing for writable local files.
 *
 * A real file is never read whole: [TextRowIndex] walks it once to learn where its rows start and
 * the list decodes only the rows on screen, so a multi-gigabyte log opens at once, scrolls at a
 * constant few megabytes of memory, and reports its line count as it goes. Entries that can only be
 * streamed (archive members, su paths) still show their leading 8 MiB. Editing a large file uses
 * the same bound as a small one: one [EDIT_LIMIT_BYTES] slice as a list of rows, spliced back on
 * save.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextViewer(entry: XEntry, startEditing: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val cannotRead = stringResource(R.string.cannot_read, entry.name)
    val saveFailed = stringResource(R.string.save_failed)
    val saved = stringResource(R.string.saved, entry.name)
    var reloads by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var editBuffer by remember { mutableStateOf<EditBuffer?>(null) }
    var editGen by remember { mutableIntStateOf(0) }
    var editFocusLine by remember { mutableIntStateOf(0) }
    var editFocusColumn by remember { mutableIntStateOf(0) }
    var editFrom by remember { mutableLongStateOf(0L) }
    var editTo by remember { mutableLongStateOf(0L) }
    var editFileSize by remember { mutableLongStateOf(0L) }
    var firstVisibleRow by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var preparingEdit by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var initialEditPending by remember(entry.id, startEditing) { mutableStateOf(startEditing) }
    val scope = rememberCoroutineScope()
    val editorFocus = remember { FocusRequester() }
    val wrap by Graph.settings.textWrap.collectAsState(initial = false)

    val file = remember(entry.id, startEditing) { pageableFile(entry, allowEmpty = startEditing) }
    val document = remember(entry.id, reloads) { TextDocument(context, entry, file) }
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
        opened && !document.axml.value

    fun openEditWindow(window: EditWindow, centerOffset: Long) {
        val buffer = EditBuffer(window.text)
        val rel = (centerOffset - window.from).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val caret = buffer.caretAtUtf8Offset(rel)
        editBuffer = buffer
        editFocusLine = caret.line.coerceIn(0, (buffer.size - 1).coerceAtLeast(0))
        editFocusColumn = caret.column
        editGen++
        editFrom = window.from
        editTo = window.to
        editFileSize = window.fileSize
    }

    fun reloadEditorFromDisk(centerOffset: Long) {
        val target = file ?: return
        if (preparingEdit) return
        preparingEdit = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    FileByteWindow(target).use { window ->
                        val center = centerOffset.takeIf { it in 0L until window.size } ?: 0L
                        loadEditWindowAround(window, center, EDIT_LIMIT_BYTES)
                    }
                }
            }
            preparingEdit = false
            loaded.fold(
                onSuccess = { openEditWindow(it, centerOffset) },
                onFailure = {
                    editing = false
                    editBuffer = null
                    feedback = it.message ?: cannotRead
                },
            )
        }
    }

    fun save() {
        if (saving) return
        val pending = editBuffer?.toText() ?: return
        saving = true
        // Snapshotted here, not read on the IO thread when the write finally starts: what gets
        // written is what was on screen when Save was pressed. The field is read-only meanwhile, so
        // nothing can be typed into the gap and then thrown away by the re-index below.
        val from = editFrom
        val to = editTo
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // LocalFileSystem preserves this editor's atomic File replacement where
                    // permitted and owns the narrow API 26-29 secondary-volume SAF fallback.
                    val fs = Graph.fsRegistry.forId(entry.id) as LocalFileSystem
                    fs.replaceRange(entry, from, to, pending.toByteArray(Charsets.UTF_8))
                }
            }
            saving = false
            result.fold(
                onSuccess = { wroteReplacement ->
                    if (wroteReplacement) {
                        editing = false
                        editBuffer = null
                        feedback = saved
                        reloads++
                    } else {
                        // Leftover restored instead of this buffer — keep editing and reload
                        // the recovered file so the next Save uses matching offsets.
                        feedback = saveFailed
                        val center = editFrom
                        reloadEditorFromDisk(center)
                        reloads++
                    }
                },
                onFailure = { feedback = it.message ?: saveFailed },
            )
        }
    }

    fun toggleEditing() {
        if (editing) {
            editing = false
            editBuffer = null
            return
        }
        // Guarded: without it a second tap starts a second read whose result lands on top of
        // whatever has been typed in the meantime.
        if (preparingEdit) return
        val target = file ?: return
        val fromRow = firstVisibleRow
        preparingEdit = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    FileByteWindow(target).use { window ->
                        val center = document.rowOffset(fromRow)
                            .takeIf { it in 0L until window.size } ?: 0L
                        center to loadEditWindowAround(window, center, EDIT_LIMIT_BYTES)
                    }
                }
            }
            preparingEdit = false
            loaded.fold(
                onSuccess = { (center, window) ->
                    openEditWindow(window, center)
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

    LaunchedEffect(initialEditPending, canEdit, opened) {
        if (initialEditPending && canEdit && opened) {
            initialEditPending = false
            toggleEditing()
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
                                if (editFrom > 0L || editTo < editFileSize) {
                                    stringResource(
                                        R.string.editing_portion,
                                        Format.bytes(editTo - editFrom),
                                        Format.bytes(editFileSize),
                                    )
                                } else {
                                    stringResource(
                                        R.string.lines,
                                        editGen.let { editBuffer?.lineCount() ?: 0 },
                                    )
                                }
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
                editing -> {
                    val buffer = editBuffer
                    if (buffer != null) {
                        key(buffer) {
                            EditRows(
                                buffer = buffer,
                                generation = editGen,
                                initialLine = editFocusLine,
                                initialColumn = editFocusColumn,
                                chrome = chrome,
                                readOnly = saving,
                                focusRequester = editorFocus,
                                onChanged = { editGen++ },
                            )
                        }
                    }
                }
                // An error with nothing indexed is the whole story; one that arrives later is a
                // banner over the rows that did make it.
                loadError != null && rowCount == 0 -> ViewerNotice(chrome) {
                    Text(
                        loadError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                !opened || !textRowsRestoreReady(rowCount, firstVisibleRow, complete) ->
                    ViewerNotice(chrome) { LoadingIndicator() }
                rowCount == 0 -> ViewerNotice(chrome) {
                    Text(stringResource(R.string.empty_file), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> TextRows(entry, document, rowCount, chrome, wrap, firstVisibleRow) {
                    firstVisibleRow = it
                }
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
 * One row per list item of the edit window. Only the focused row is a field; the rest are
 * the same [Text] the viewer uses, so scrolling a 512 KB slice does not lay the slice out whole.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EditRows(
    buffer: EditBuffer,
    generation: Int,
    initialLine: Int,
    initialColumn: Int,
    chrome: PaddingValues,
    readOnly: Boolean,
    focusRequester: FocusRequester,
    onChanged: () -> Unit,
) {
    val startLine = initialLine.coerceIn(0, (buffer.size - 1).coerceAtLeast(0))
    val startColumn = initialColumn.coerceIn(0, buffer[startLine].length)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startLine)
    var focused by remember { mutableIntStateOf(startLine) }
    var field by remember {
        mutableStateOf(TextFieldValue(buffer[startLine], TextRange(startColumn)))
    }
    var anchorLine by remember { mutableIntStateOf(startLine) }
    var anchorCol by remember { mutableIntStateOf(startColumn) }
    var desiredColumn by remember { mutableIntStateOf(startColumn) }
    var fieldLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val textStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val cursor = SolidColor(MaterialTheme.colorScheme.primary)
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val minLine = 20.dp
    val rowCount = generation.let { buffer.size }
    val joinSwallow = remember {
        ImeJoinSwallow(JOIN_SWALLOW_MS) { SystemClock.uptimeMillis() }
    }
    val imeHasRange = remember { AtomicBoolean(false) }
    val imeSelectedText = remember { AtomicReference("") }
    val extendHeld = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun caretNow() = EditCaret(focused, field.selection.end)
    fun anchorNow() = EditCaret(anchorLine, anchorCol)
    fun crossLine() = anchorLine != focused
    fun documentRangeCollapsed() =
        editDocumentRangeCollapsed(focused, anchorLine, field.selection.collapsed)

    fun fieldRange(text: String, caretCol: Int): TextRange =
        editFieldRange(text, focused, caretCol, anchorLine, anchorCol)

    fun runIme(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        mainHandler.post { block() }
        return true
    }

    fun moveTo(line: Int, column: Int, keepDesired: Boolean = false, extend: Boolean = false) {
        val target = line.coerceIn(0, (buffer.size - 1).coerceAtLeast(0))
        val text = buffer[target]
        val col = column.coerceIn(0, text.length)
        focused = target
        if (!extend) {
            anchorLine = target
            anchorCol = col
            field = TextFieldValue(text, TextRange(col))
        } else {
            field = TextFieldValue(text, fieldRange(text, col))
        }
        if (!keepDesired) desiredColumn = col
        fieldLayout = null
    }

    fun markJoin(previous: Boolean) = joinSwallow.mark(previous)

    fun applyJoin(caret: EditCaret?, previous: Boolean): Boolean {
        if (caret == null) return false
        markJoin(previous)
        onChanged()
        moveTo(caret.line, caret.column)
        return true
    }

    fun tryJoin(beforeLength: Int, afterLength: Int): Boolean {
        if (readOnly || !editTryJoinAtEdge(
                field.selection.collapsed,
                crossLine(),
                field.selection.start,
                field.text.length,
                beforeLength,
                afterLength,
            )
        ) {
            return false
        }
        return if (beforeLength > 0 && field.selection.start == 0) {
            applyJoin(buffer.mergeWithPrevious(focused), previous = true)
        } else {
            applyJoin(buffer.mergeWithNext(focused), previous = false)
        }
    }

    fun selectedText(): String = buffer.textInRange(anchorNow(), caretNow())

    fun copySelection(): Boolean {
        val text = selectedText()
        if (text.isEmpty()) return false
        clipboard.setText(AnnotatedString(text))
        return true
    }

    fun deleteSelection(): Boolean {
        if (readOnly) return false
        val caret = buffer.replaceRange(anchorNow(), caretNow(), "", EDIT_LIMIT_BYTES.toInt())
            ?: return false
        onChanged()
        moveTo(caret.line, caret.column)
        return true
    }

    fun cutSelection(): Boolean {
        if (readOnly) return false
        if (!copySelection()) return false
        return deleteSelection()
    }

    fun selectAll(): Boolean {
        if (buffer.size == 0) return false
        anchorLine = 0
        anchorCol = 0
        val last = buffer.size - 1
        moveTo(last, buffer[last].length, extend = true)
        return true
    }

    fun pasteClipboard(): Boolean {
        if (readOnly) return false
        val text = clipboard.getText()?.text ?: return false
        val caret = buffer.replaceRange(anchorNow(), caretNow(), text, EDIT_LIMIT_BYTES.toInt())
            ?: return false
        onChanged()
        moveTo(caret.line, caret.column)
        return true
    }

    val performMenu = rememberUpdatedState<(Int) -> Boolean> { id ->
        when (id) {
            android.R.id.copy -> copySelection()
            android.R.id.cut -> cutSelection()
            android.R.id.paste -> pasteClipboard()
            android.R.id.selectAll -> selectAll()
            else -> false
        }
    }
    fun deleteSelectionOrJoin(before: Int, after: Int): Boolean {
        if (!documentRangeCollapsed()) {
            val ok = deleteSelection()
            if (ok) markJoin(previous = before > 0)
            return ok
        }
        return tryJoin(before, after)
    }
    val handleImeDelete = rememberUpdatedState<(Int, Int) -> Boolean> { before, after ->
        if (readOnly) return@rememberUpdatedState false
        if (editSwallowImeJoinDuplicate(before, after, joinSwallow)) {
            return@rememberUpdatedState true
        }
        deleteSelectionOrJoin(before, after)
    }
    val handleKeyDelete = rememberUpdatedState<(Int, Int) -> Boolean> { before, after ->
        if (readOnly) return@rememberUpdatedState false
        deleteSelectionOrJoin(before, after)
    }
    val systemToolbar = LocalTextToolbar.current
    val copyLatest = rememberUpdatedState { copySelection() }
    val cutLatest = rememberUpdatedState { cutSelection() }
    val pasteLatest = rememberUpdatedState { pasteClipboard() }
    val selectAllLatest = rememberUpdatedState { selectAll() }
    val editToolbar = remember(systemToolbar) {
        object : TextToolbar {
            override val status get() = systemToolbar.status
            override fun hide() = systemToolbar.hide()
            override fun showMenu(
                rect: androidx.compose.ui.geometry.Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) {
                systemToolbar.showMenu(
                    rect,
                    onCopyRequested = onCopyRequested?.let { { copyLatest.value() } },
                    onPasteRequested = onPasteRequested?.let { { pasteLatest.value() } },
                    onCutRequested = onCutRequested?.let { { cutLatest.value() } },
                    onSelectAllRequested = { selectAllLatest.value() },
                )
            }
        }
    }
    SideEffect {
        val range = !documentRangeCollapsed()
        imeHasRange.set(range)
        imeSelectedText.set(if (range) selectedText() else "")
    }
    val imeInterceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val base = request.createInputConnection(outAttributes)
                        return object : InputConnectionWrapper(base, true) {
                            override fun deleteSurroundingText(
                                beforeLength: Int,
                                afterLength: Int,
                            ): Boolean = runIme {
                                if (handleImeDelete.value(beforeLength, afterLength)) {
                                    true
                                } else {
                                    base.deleteSurroundingText(beforeLength, afterLength)
                                }
                            }

                            override fun deleteSurroundingTextInCodePoints(
                                beforeLength: Int,
                                afterLength: Int,
                            ): Boolean = runIme {
                                if (handleImeDelete.value(beforeLength, afterLength)) {
                                    true
                                } else {
                                    base.deleteSurroundingTextInCodePoints(
                                        beforeLength,
                                        afterLength,
                                    )
                                }
                            }

                            override fun performContextMenuAction(id: Int): Boolean = runIme {
                                if (performMenu.value(id)) {
                                    true
                                } else {
                                    base.performContextMenuAction(id)
                                }
                            }

                            override fun getSelectedText(flags: Int): CharSequence? {
                                if (imeHasRange.get()) {
                                    val text = imeSelectedText.get()
                                    if (text.isNotEmpty()) return text
                                }
                                return base.getSelectedText(flags)
                            }
                        }
                    }
                },
            )
        }
    }

    val documentHasRange = !documentRangeCollapsed()
    val crossCollapsed = crossLine() && field.selection.collapsed
    LaunchedEffect(
        documentHasRange,
        crossCollapsed,
        fieldCoords,
        fieldLayout,
        field.selection.end,
        readOnly,
    ) {
        if (!documentHasRange) {
            editToolbar.hide()
            return@LaunchedEffect
        }
        if (!crossCollapsed) return@LaunchedEffect
        val layout = fieldLayout
        val coords = fieldCoords
        if (layout == null || coords == null || !coords.isAttached) return@LaunchedEffect
        val off = field.selection.end.coerceIn(0, layout.layoutInput.text.length)
        val local = layout.getCursorRect(off)
        val rect = Rect(coords.localToRoot(local.topLeft), coords.localToRoot(local.bottomRight))
        if (rect.isEmpty) return@LaunchedEffect
        editToolbar.showMenu(
            rect,
            onCopyRequested = { copyLatest.value() },
            onPasteRequested = if (readOnly) null else ({ pasteLatest.value() }),
            onCutRequested = if (readOnly) null else ({ cutLatest.value() }),
            onSelectAllRequested = { selectAllLatest.value() },
        )
    }
    DisposableEffect(editToolbar) {
        onDispose { editToolbar.hide() }
    }
    LaunchedEffect(focused, listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val layout = fieldLayout
            val caret = field.selection.end
            val cursor = layout?.let { tl ->
                if (tl.layoutInput.text.text != field.text) {
                    null
                } else {
                    val off = caret.coerceIn(0, tl.layoutInput.text.length)
                    tl.getCursorRect(off)
                }
            }
            viewport to cursor
        }.collect { (viewport, cursor) ->
            if (viewport <= 0) return@collect
            val vis = listState.layoutInfo.visibleItemsInfo
            if (vis.none { it.index == focused }) {
                val first = vis.minByOrNull { it.index }
                val last = vis.maxByOrNull { it.index }
                val px = if (first != null && last != null) {
                    editOffscreenRevealPx(
                        focused,
                        first.index,
                        first.size,
                        last.index,
                        last.size,
                    )
                } else {
                    null
                }
                if (px != null) listState.scrollBy(px.toFloat())
                else listState.scrollToItem(focused)
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == focused }) {
                    listState.scrollToItem(focused)
                }
            }
            val item = snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focused && it.size > 0 }
            }.first { it != null } ?: return@collect
            val layout = listState.layoutInfo
            val usableTop = layout.viewportStartOffset + layout.beforeContentPadding
            val usableBottom = layout.viewportEndOffset - layout.afterContentPadding
            val usable = (usableBottom - usableTop).coerceAtLeast(1)
            val top: Int
            val bottom: Int
            if (item.size < usable) {
                top = item.offset
                bottom = item.offset + item.size
            } else if (cursor != null) {
                top = item.offset + cursor.top.roundToInt()
                bottom = item.offset + cursor.bottom.roundToInt()
            } else {
                return@collect
            }
            val delta = editKeepInViewDelta(top, bottom, usableTop, usableBottom)
            if (abs(delta) > 1) listState.scrollBy(delta.toFloat())
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = chrome.calculateTopPadding() + 8.dp,
                bottom = chrome.calculateBottomPadding() + 8.dp,
            ),
        ) {
            items(rowCount) { i ->
                val rowMod = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minLine)
                    .padding(horizontal = 12.dp)
                if (i == focused) {
                    CompositionLocalProvider(LocalTextToolbar provides editToolbar) {
                    InterceptPlatformTextInput(imeInterceptor) {
                        BasicTextField(
                            value = field,
                            onValueChange = { next ->
                                if (i != focused) return@BasicTextField
                                if (next.text == field.text) {
                                    if (editCollapseCrossLineOnCaretMove(
                                            next.selection.collapsed,
                                            crossLine(),
                                            extendHeld.get(),
                                        )
                                    ) {
                                        moveTo(focused, next.selection.end)
                                        return@BasicTextField
                                    }
                                    field = next
                                    desiredColumn = next.selection.end
                                    if (!crossLine()) {
                                        anchorCol = next.selection.start
                                    }
                                    return@BasicTextField
                                }
                                if (readOnly) return@BasicTextField
                                if (crossLine()) {
                                    val insert = textFieldReplacement(
                                        field.text,
                                        field.selection,
                                        next.text,
                                    ) ?: return@BasicTextField
                                    val caret = buffer.replaceRange(
                                        anchorNow(),
                                        caretNow(),
                                        insert,
                                        EDIT_LIMIT_BYTES.toInt(),
                                    ) ?: return@BasicTextField
                                    onChanged()
                                    moveTo(caret.line, caret.column)
                                    return@BasicTextField
                                }
                                val linesBefore = buffer.lineCount()
                                val caret = buffer.replace(
                                    i,
                                    next.text,
                                    next.selection.start,
                                    EDIT_LIMIT_BYTES.toInt(),
                                ) ?: return@BasicTextField
                                if (caret.line != i || next.text.contains('\n')) {
                                    onChanged()
                                    moveTo(caret.line, caret.column)
                                } else {
                                    field = next
                                    anchorLine = i
                                    anchorCol = next.selection.start
                                    desiredColumn = next.selection.start
                                    if (buffer.lineCount() != linesBefore) onChanged()
                                }
                            },
                            readOnly = readOnly,
                            textStyle = textStyle,
                            cursorBrush = cursor,
                            onTextLayout = { fieldLayout = it },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.None,
                            ),
                            modifier = rowMod
                                .focusRequester(focusRequester)
                                .onGloballyPositioned { fieldCoords = it }
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            extendHeld.set(event.keyboardModifiers.isPointerShiftPressed)
                                        }
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.key == Key.ShiftLeft || event.key == Key.ShiftRight) {
                                        extendHeld.set(event.type != KeyEventType.KeyUp)
                                        return@onPreviewKeyEvent false
                                    }
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    extendHeld.set(event.isShiftPressed)
                                    val shortcut = event.isCtrlPressed || event.isMetaPressed
                                    if (shortcut) {
                                        return@onPreviewKeyEvent when (event.key) {
                                            Key.C -> copySelection()
                                            Key.X -> cutSelection()
                                            Key.V -> pasteClipboard()
                                            Key.A -> selectAll()
                                            else -> false
                                        }
                                    }
                                    if (readOnly) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.Backspace -> handleKeyDelete.value(1, 0)
                                        Key.Delete -> handleKeyDelete.value(0, 1)
                                        Key.DirectionLeft -> {
                                            val extend = event.isShiftPressed
                                            if (!extend && !field.selection.collapsed && !crossLine()) {
                                                false
                                            } else if (field.selection.end == 0) {
                                                if (focused == 0) {
                                                    true
                                                } else {
                                                    moveTo(
                                                        focused - 1,
                                                        buffer[focused - 1].length,
                                                        extend = extend,
                                                    )
                                                    true
                                                }
                                            } else {
                                                false
                                            }
                                        }
                                        Key.DirectionRight -> {
                                            val extend = event.isShiftPressed
                                            if (!extend && !field.selection.collapsed && !crossLine()) {
                                                false
                                            } else if (field.selection.end == field.text.length) {
                                                if (focused >= buffer.size - 1) {
                                                    true
                                                } else {
                                                    moveTo(focused + 1, 0, extend = extend)
                                                    true
                                                }
                                            } else {
                                                false
                                            }
                                        }
                                        Key.DirectionUp -> {
                                            val extend = event.isShiftPressed
                                            if (!extend && !field.selection.collapsed && !crossLine()) {
                                                false
                                            } else {
                                                val layout = fieldLayout
                                                val onFirstVisual = layout != null &&
                                                    layout.getLineForOffset(
                                                        field.selection.end.coerceIn(
                                                            0,
                                                            layout.layoutInput.text.length,
                                                        ),
                                                    ) == 0
                                                if (!editMoveToAdjacentLine(
                                                        layout?.layoutInput?.text?.text,
                                                        field.text,
                                                        onFirstVisual,
                                                    )
                                                ) {
                                                    false
                                                } else if (focused == 0) {
                                                    if (extend) {
                                                        moveTo(0, 0, keepDesired = true, extend = true)
                                                    }
                                                    true
                                                } else {
                                                    moveTo(
                                                        focused - 1,
                                                        desiredColumn,
                                                        keepDesired = true,
                                                        extend = extend,
                                                    )
                                                    true
                                                }
                                            }
                                        }
                                        Key.DirectionDown -> {
                                            val extend = event.isShiftPressed
                                            if (!extend && !field.selection.collapsed && !crossLine()) {
                                                false
                                            } else {
                                                val layout = fieldLayout
                                                val onLastVisual = layout != null &&
                                                    layout.getLineForOffset(
                                                        field.selection.end.coerceIn(
                                                            0,
                                                            layout.layoutInput.text.length,
                                                        ),
                                                    ) == layout.lineCount - 1
                                                if (!editMoveToAdjacentLine(
                                                        layout?.layoutInput?.text?.text,
                                                        field.text,
                                                        onLastVisual,
                                                    )
                                                ) {
                                                    false
                                                } else if (focused >= buffer.size - 1) {
                                                    if (extend) {
                                                        moveTo(
                                                            focused,
                                                            buffer[focused].length,
                                                            keepDesired = true,
                                                            extend = true,
                                                        )
                                                    }
                                                    true
                                                } else {
                                                    moveTo(
                                                        focused + 1,
                                                        desiredColumn,
                                                        keepDesired = true,
                                                        extend = extend,
                                                    )
                                                    true
                                                }
                                            }
                                        }
                                        else -> false
                                    }
                                },
                        )
                        LaunchedEffect(Unit) {
                            val started = SystemClock.uptimeMillis()
                            while (SystemClock.uptimeMillis() - started < EDITOR_FOCUS_GIVE_UP_MS) {
                                val focusedNow = runCatching {
                                    focusRequester.requestFocus()
                                }.getOrDefault(false)
                                if (focusedNow) {
                                    keyboard?.show()
                                    break
                                }
                                delay(EDITOR_FOCUS_RETRY_MS)
                            }
                        }
                    }
                    }
                } else {
                    var layout by remember(i) { mutableStateOf<TextLayoutResult?>(null) }
                    Text(
                        editSelectionOnLine(
                            buffer[i],
                            i,
                            anchorNow(),
                            caretNow(),
                            selectionColor,
                        ),
                        style = textStyle,
                        modifier = rowMod.pointerInput(i, readOnly) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val slop = viewConfiguration.touchSlop
                                val start = down.position
                                val shift = currentEvent.keyboardModifiers.isPointerShiftPressed
                                var pastSlop = false
                                val up: PointerInputChange? = withTimeoutOrNull(
                                    viewConfiguration.longPressTimeoutMillis,
                                ) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue
                                        if ((change.position - start).getDistance() > slop) {
                                            pastSlop = true
                                            return@withTimeoutOrNull null
                                        }
                                        if (change.changedToUp()) return@withTimeoutOrNull change
                                    }
                                    @Suppress("UNREACHABLE_CODE")
                                    null
                                }
                                if (pastSlop || readOnly) return@awaitEachGesture
                                if (up != null) {
                                    up.consume()
                                    val col = layout?.getOffsetForPosition(up.position) ?: 0
                                    moveTo(i, col, extend = shift)
                                } else if (currentEvent.changes.any { it.pressed }) {
                                    currentEvent.changes.forEach { it.consume() }
                                    val col = layout?.getOffsetForPosition(down.position) ?: 0
                                    moveTo(i, col, extend = true)
                                    waitForUpOrCancellation()
                                }
                            }
                        },
                        onTextLayout = { layout = it },
                    )
                }
            }
        }
        ViewerScrollbar(
            listState,
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = chrome.calculateTopPadding(), bottom = chrome.calculateBottomPadding()),
        )
    }
}

/** List index for [TextRows]: headers stay on screen when opening at row 0. */
internal fun textRowsInitialListIndex(initialRow: Int, headerCount: Int): Int =
    if (initialRow <= 0) 0 else initialRow + headerCount.coerceAtLeast(0)

/** Wait to create list state until the restored document row exists, or the scan has finished. */
internal fun textRowsRestoreReady(rowCount: Int, initialRow: Int, complete: Boolean): Boolean =
    complete || rowCount > initialRow.coerceAtLeast(0)

/** Pixels to scrollBy when the caret row is just off-screen; null means jump to the item. */
internal fun editOffscreenRevealPx(
    focused: Int,
    firstIndex: Int,
    firstSize: Int,
    lastIndex: Int,
    lastSize: Int,
): Int? = when (focused) {
    lastIndex + 1 -> lastSize.coerceAtLeast(1)
    firstIndex - 1 -> -firstSize.coerceAtLeast(1)
    else -> null
}

/** Scroll delta that brings [top, bottom) into [usableTop, usableBottom), or 0 if already in view. */
internal fun editKeepInViewDelta(
    top: Int,
    bottom: Int,
    usableTop: Int,
    usableBottom: Int,
): Int {
    val usable = (usableBottom - usableTop).coerceAtLeast(1)
    return when {
        bottom - top >= usable -> top - usableTop
        top < usableTop -> top - usableTop
        bottom > usableBottom -> bottom - usableBottom
        else -> 0
    }
}

/**
 * Insert implied by a TextField replacing [selection] in [old] with [next].
 * Null when [next] is not prefix + insert + suffix, so the caller can ignore
 * the edit instead of deleting the document range.
 */
internal fun textFieldReplacement(old: String, selection: TextRange, next: String): String? {
    val min = selection.min.coerceIn(0, old.length)
    val max = selection.max.coerceIn(0, old.length)
    val prefix = old.substring(0, min)
    val suffix = old.substring(max)
    return if (next.startsWith(prefix) && next.endsWith(suffix) &&
        next.length >= prefix.length + suffix.length
    ) {
        next.substring(prefix.length, next.length - suffix.length)
    } else {
        null
    }
}

/**
 * Selection the focused row reports to the IME. A document range that ends at
 * column 0 (or starts at the end of an earlier row) is collapsed here — the
 * newline lives between rows — even though [editDocumentRangeCollapsed] is false.
 */
internal fun editFieldRange(
    text: String,
    focusedLine: Int,
    caretCol: Int,
    anchorLine: Int,
    anchorCol: Int,
): TextRange {
    val col = caretCol.coerceIn(0, text.length)
    if (anchorLine == focusedLine) {
        return TextRange(anchorCol.coerceIn(0, text.length), col)
    }
    val other = if (focusedLine > anchorLine) 0 else text.length
    return TextRange(other, col)
}

internal fun editDocumentRangeCollapsed(
    focusedLine: Int,
    anchorLine: Int,
    fieldSelectionCollapsed: Boolean,
): Boolean = anchorLine == focusedLine && fieldSelectionCollapsed

/** A tap or non-shift caret move in the focused row must not keep a sticky multi-line range. */
internal fun editCollapseCrossLineOnCaretMove(
    fieldSelectionCollapsed: Boolean,
    crossLine: Boolean,
    extend: Boolean,
): Boolean = fieldSelectionCollapsed && crossLine && !extend

/**
 * Up/Down leave this field only when layout matches the current text and the caret
 * is already on the first/last visual line. Null/stale layout is not an edge.
 */
internal fun editMoveToAdjacentLine(
    layoutText: String?,
    fieldText: String,
    onVisualEdge: Boolean,
): Boolean = layoutText != null && layoutText == fieldText && onVisualEdge

/** Join only at the start (Backspace) or end (Delete) of a collapsed single-line caret. */
internal fun editTryJoinAtEdge(
    selectionCollapsed: Boolean,
    crossLine: Boolean,
    column: Int,
    lineLength: Int,
    beforeLength: Int,
    afterLength: Int,
): Boolean {
    if (!selectionCollapsed || crossLine) return false
    if (beforeLength > 0 && column == 0) return true
    if (afterLength > 0 && column == lineLength) return true
    return false
}

/** IME-only join duplicate. Hardware keys must not consult this. */
internal fun editSwallowImeJoinDuplicate(
    beforeLength: Int,
    afterLength: Int,
    swallow: ImeJoinSwallow,
): Boolean = (beforeLength > 0 && swallow.swallow(previous = true)) ||
    (afterLength > 0 && swallow.swallow(previous = false))

/** One-shot join duplicate. Survives composition; expires after [windowMs]. */
internal class ImeJoinSwallow(
    private val windowMs: Long,
    private val now: () -> Long,
) {
    private val backspace = AtomicBoolean(false)
    private val delete = AtomicBoolean(false)
    private val lastBackspaceAt = AtomicLong(0)
    private val lastDeleteAt = AtomicLong(0)

    fun mark(previous: Boolean) {
        if (previous) {
            backspace.set(true)
            lastBackspaceAt.set(now())
        } else {
            delete.set(true)
            lastDeleteAt.set(now())
        }
    }

    fun swallow(previous: Boolean): Boolean {
        val flag = if (previous) backspace else delete
        val stamp = if (previous) lastBackspaceAt else lastDeleteAt
        if (!flag.compareAndSet(true, false)) return false
        return now() - stamp.get() < windowMs
    }
}

internal data class EditLineHighlight(val start: Int, val end: Int, val extraSpace: Boolean)

/**
 * Highlight on [line] for a possibly multi-line range, as `[start, end)` in a
 * string that is the row plus a trailing space when the newline after it is
 * selected. Null if this row has nothing to paint.
 */
internal fun editLineHighlight(
    textLength: Int,
    line: Int,
    anchor: EditCaret,
    caret: EditCaret,
): EditLineHighlight? {
    val from: EditCaret
    val to: EditCaret
    if (anchor.line < caret.line || (anchor.line == caret.line && anchor.column <= caret.column)) {
        from = anchor
        to = caret
    } else {
        from = caret
        to = anchor
    }
    if (from.line == to.line || line !in from.line..to.line) return null
    val start = (if (line == from.line) from.column else 0).coerceIn(0, textLength)
    val end = (if (line == to.line) to.column else textLength).coerceIn(0, textLength)
    val extraSpace = line < to.line
    val paintEnd = if (extraSpace) textLength + 1 else end
    val paintStart = start.coerceAtMost(paintEnd)
    if (paintStart >= paintEnd) return null
    return EditLineHighlight(paintStart, paintEnd, extraSpace)
}

private fun editSelectionOnLine(
    text: String,
    line: Int,
    anchor: EditCaret,
    caret: EditCaret,
    color: Color,
): AnnotatedString {
    val highlight = editLineHighlight(text.length, line, anchor, caret) ?: return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        if (highlight.extraSpace) append(' ')
        addStyle(SpanStyle(background = color), highlight.start, highlight.end)
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
    initialRow: Int,
    onFirstVisibleRow: (Int) -> Unit,
) {
    val headerCount =
        (if (document.error.value != null) 1 else 0) +
            (if (document.axml.value) 1 else 0) +
            (if (document.truncated.value) 1 else 0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = textRowsInitialListIndex(initialRow, headerCount),
    )
    val horizontal = rememberScrollState()
    LaunchedEffect(listState) {
        snapshotFlow {
            var headers = 0
            if (document.error.value != null) headers++
            if (document.axml.value) headers++
            if (document.truncated.value) headers++
            (listState.firstVisibleItemIndex - headers).coerceAtLeast(0)
        }.collect { onFirstVisibleRow(it) }
    }
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
 * A length of zero also normally goes the streaming way: /proc and /sys nodes report no length but
 * hand over plenty of bytes when read, and paging trusts the length. [allowEmpty] is reserved for
 * a newly created local file that is opening straight into the editor.
 */
private fun pageableFile(entry: XEntry, allowEmpty: Boolean = false): File? {
    val path = entry.localPath ?: entry.path.takeIf { entry.scheme == XId.SCHEME_FILE } ?: return null
    return File(path).takeIf {
        it.isFile && it.canRead() && (allowEmpty || it.length() > 0L)
    }
}

/**
 * One open text document: the index over its bytes, how far indexing has come, and a bounded cache
 * of decoded rows. Composition only ever reads the cache; a miss schedules the page and shows a
 * blank row until it lands.
 */
private class TextDocument(
    private val context: Context,
    private val entry: XEntry,
    private val file: File?,
) {
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

    /**
     * Byte offset of [row], or -1 if the index is gone or has not reached it.
     * Blocking IO: call from an IO dispatcher.
     */
    fun rowOffset(row: Int): Long {
        val rowIndex = synchronized(this) { if (closed) null else index } ?: return -1L
        return rowIndex.rowStart(row)
    }

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
        val bytes = openSource(context, entry)
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

/** ACTION_VIEW hands us a content URI, not an XFiles filesystem id. */
private fun openSource(context: Context, entry: XEntry): InputStream {
    if (entry.scheme == "content") {
        return context.contentResolver.openInputStream(entry.id.toUri())
            ?: throw IOException("Cannot read ${entry.name}")
    }
    return Graph.fsRegistry.forId(entry.id).openIn(entry)
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
