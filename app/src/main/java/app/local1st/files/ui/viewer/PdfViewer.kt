package app.local1st.files.ui.viewer

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.di.Graph
import app.local1st.files.ui.components.TooltipIconButton
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MIN_PDF_SCALE = 1f
private const val MAX_PDF_SCALE = 4f
private const val DEFAULT_PAGE_ASPECT_RATIO = 0.707f
private const val MAX_BITMAP_DIMENSION = 4096
private const val MAX_BITMAP_PIXELS = 12 * 1024 * 1024
private const val RENDER_ATTEMPTS = 4

/**
 * Displays a PDF as lazily rendered pages, with pinch zoom and access to the system reader chooser.
 *
 * Non-local entries are copied into the app cache because the platform renderer requires a
 * seekable file descriptor; that private temporary copy is removed when this viewer leaves
 * composition.
 *
 * Where the platform reads PDF text ([PdfEngine]), a long press selects a word and the system
 * toolbar copies it; elsewhere pages are pictures only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PdfViewer(entry: XEntry, onClose: () -> Unit, onOpenWith: () -> Unit) {
    val context = LocalContext.current
    val passwordError = stringResource(R.string.pdf_password_protected, entry.name)
    val invalidError = stringResource(R.string.pdf_corrupt_or_incomplete, entry.name)
    val unreadableError = stringResource(R.string.pdf_unreadable, entry.name)
    val documentState by produceState<PdfDocumentState>(
        initialValue = PdfDocumentState.Loading,
        entry.id,
    ) {
        var opened: PdfDocument? = null
        try {
            val document = PdfDocument.open(context, entry) { opened = it }
            opened = document
            value = PdfDocumentState.Ready(document)
            awaitDispose { document.close() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            value = PdfDocumentState.Error(
                when (error) {
                    is PdfSourceException -> unreadableError
                    is SecurityException -> passwordError
                    is IOException, is IllegalArgumentException -> invalidError
                    else -> unreadableError
                },
            )
        } finally {
            opened?.close()
        }
    }

    val listState = rememberLazyListState()
    val pageCount = (documentState as? PdfDocumentState.Ready)?.document?.pageCount ?: 0
    // Read inside the top bar's own scope so turning a page repaints the indicator, not the pages.
    val currentPage = remember(listState, pageCount) {
        derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceIn(1, maxOf(pageCount, 1)) }
    }

    ViewerChrome(
        topBar = {
            PdfTopBar(
                entry.name,
                pageCount.takeIf { it > 0 }?.let {
                    stringResource(R.string.pdf_page_indicator, currentPage.value, it)
                },
                onClose,
                onOpenWith,
            )
        },
    ) { chrome ->
        when (val state = documentState) {
            PdfDocumentState.Loading -> ViewerNotice(chrome) { LoadingIndicator() }
            is PdfDocumentState.Error -> ViewerNotice(chrome) {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            is PdfDocumentState.Ready -> PdfPages(state.document, listState, chrome)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfTopBar(
    name: String,
    pageIndicator: String?,
    onClose: () -> Unit,
    onOpenWith: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                pageIndicator?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            TooltipIconButton(stringResource(R.string.close), Icons.Outlined.Close, onClick = onClose)
        },
        actions = {
            TooltipIconButton(
                stringResource(R.string.open_with),
                Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = onOpenWith,
            )
        },
    )
}

@Composable
private fun PdfPages(
    document: PdfDocument,
    listState: LazyListState,
    chrome: PaddingValues,
) {
    var scale by remember(document) { mutableFloatStateOf(MIN_PDF_SCALE) }
    var renderScale by remember(document) { mutableFloatStateOf(MIN_PDF_SCALE) }
    var offsetX by remember(document) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // Null where the platform cannot read a page's text: the pages are pictures only.
    val selector = remember(document) { PdfSelector(document, scope).takeIf { document.readsText } }
    val selection = selector?.selection
    var viewport by remember { mutableStateOf<Rect?>(null) }

    if (selector != null) {
        BackHandler(enabled = selection != null) { selector.clear() }
        PdfSelectionToolbar(selector, listState) { viewport }
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(selection?.pageIndex, selection?.text, selection?.lines) {
            if (selector.handleHeld && selection != null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { viewport = it.boundsInRoot() },
    ) {
        val viewportWidth = constraints.maxWidth.toFloat()

        fun applyTransform(newScaleRaw: Float, centroid: Offset, panX: Float) {
            val newScale = newScaleRaw.coerceIn(MIN_PDF_SCALE, MAX_PDF_SCALE)
            val centerX = viewportWidth / 2f
            val distanceFromCenter = centroid.x - centerX
            val ratio = newScale / scale
            val unclamped = distanceFromCenter + panX - (distanceFromCenter - offsetX) * ratio
            val maxOffset = viewportWidth * (newScale - 1f) / 2f
            scale = newScale
            offsetX = unclamped.coerceIn(-maxOffset, maxOffset)
        }

        LaunchedEffect(viewportWidth) {
            val maxOffset = viewportWidth * (scale - 1f) / 2f
            offsetX = offsetX.coerceIn(-maxOffset, maxOffset)
        }

        LazyColumn(
            state = listState,
            // Pages scroll through both system bars; the chrome's insets only decide where they
            // come to rest, so the first and last page are never trapped under the bars.
            contentPadding = PaddingValues(
                start = 12.dp,
                top = chrome.calculateTopPadding() + 12.dp,
                end = 12.dp,
                bottom = chrome.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewportWidth, selector) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val pinching = event.changes.count { it.pressed } > 1 ||
                                zoomChange != 1f
                            // A selection handle takes its drag for itself.
                            val horizontalPan = scale > MIN_PDF_SCALE &&
                                abs(pan.x) > abs(pan.y) &&
                                selector?.handleHeld != true
                            if (centroid.isSpecified && (pinching || horizontalPan)) {
                                applyTransform(scale * zoomChange, centroid, pan.x)
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        // Keep gestures fluid by scaling the existing bitmap, then render once
                        // at the settled resolution instead of starting work for every event.
                        renderScale = scale
                    }
                },
        ) {
            items(document.pageCount, key = { it }) { pageIndex ->
                PdfPage(
                    document = document,
                    pageIndex = pageIndex,
                    scale = scale,
                    renderScale = renderScale,
                    offsetX = offsetX,
                    selector = selector,
                    selection = selection?.takeIf { it.pageIndex == pageIndex },
                )
            }
        }
    }
}

/**
 * The system's floating Copy / Select all bar over the selection. It waits while the pages scroll
 * or a handle is dragged, and while the selection is off screen.
 */
@Composable
private fun PdfSelectionToolbar(
    selector: PdfSelector,
    listState: LazyListState,
    viewport: () -> Rect?,
) {
    val toolbar = LocalTextToolbar.current
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val latestViewport by rememberUpdatedState(viewport)
    LaunchedEffect(selector, toolbar) {
        snapshotFlow {
            val selection = selector.selection
            val bounds = selector.bounds?.takeIf { it.pageIndex == selection?.pageIndex }?.rect
            val visible = latestViewport()
            when {
                selection == null || bounds == null || visible == null -> null
                selector.handleHeld || listState.isScrollInProgress -> null
                !bounds.overlaps(visible) -> null
                else -> bounds.intersect(visible)
            }
        }.collect { rect ->
            if (rect == null) {
                toolbar.hide()
            } else {
                toolbar.showMenu(
                    rect,
                    onCopyRequested = {
                        val text = selector.selection?.text
                        if (text != null) {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText("plain text", text).toClipEntry(),
                                )
                            }
                            // Android 13 and later confirm a copy themselves.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                        selector.clear()
                    },
                    onSelectAllRequested = {
                        selector.selection?.let { selector.selectPage(it.pageIndex) }
                    },
                )
            }
        }
    }
    DisposableEffect(toolbar) {
        onDispose { toolbar.hide() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PdfPage(
    document: PdfDocument,
    pageIndex: Int,
    scale: Float,
    renderScale: Float,
    offsetX: Float,
    selector: PdfSelector?,
    selection: PdfTextSelection?,
) {
    val renderError = stringResource(R.string.pdf_page_render_failed, pageIndex + 1)
    // The handles of a selection near the bottom hang below the page: draw them over the next one.
    BoxWithConstraints(Modifier.fillMaxWidth().zIndex(if (selection != null) 1f else 0f)) {
        val targetWidth = (constraints.maxWidth * renderScale).roundToInt().coerceAtLeast(1)
        // The state outlives each render: a zoom or a rotation changes [targetWidth], and until the
        // sharper bitmap arrives the previous one keeps the page on screen — scaled, but there —
        // instead of blanking a page the reader was looking at. That is also why nothing here
        // recycles a bitmap: it can still be inside a display list, and drawing a recycled bitmap
        // takes the whole app down. Bitmap memory is native and the collector reclaims it.
        val renderedState by produceState<PdfPageState>(
            initialValue = PdfPageState.Loading,
            document,
            pageIndex,
            targetWidth,
        ) {
            value = try {
                PdfPageState.Ready(document.renderPage(pageIndex, targetWidth))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                PdfPageState.Error(renderError)
            }
        }
        val rendered = (renderedState as? PdfPageState.Ready)?.page
        val aspectRatio = rendered?.let { it.image.width.toFloat() / it.image.height }
            ?: DEFAULT_PAGE_ASPECT_RATIO
        val baseHeight = maxWidth / aspectRatio
        val pageWidthPx = constraints.maxWidth.toFloat()
        val pageHeightPx = with(LocalDensity.current) { baseHeight.toPx() }
        // Unscaled page pixels per page point, on each axis. Null until the page has rendered.
        val pxPerPoint = rendered?.let {
            Offset(pageWidthPx / it.widthPoints, pageHeightPx / it.heightPoints)
        }
        val geometry = pxPerPoint?.let { PdfPageGeometry(it, pageWidthPx, scale, offsetX) }

        // The toolbar sits over the selection, so it needs the selection's place on screen.
        val box = remember { LayoutHolder() }
        val latestSelection by rememberUpdatedState(selection)
        val latestGeometry by rememberUpdatedState(geometry)
        val reportBounds = remember(selector) {
            {
                val current = latestSelection
                val pageGeometry = latestGeometry
                val coordinates = box.coordinates?.takeIf { it.isAttached }
                if (selector != null && current != null && pageGeometry != null && coordinates != null) {
                    selector.bounds = SelectionBounds(
                        current.pageIndex,
                        pageGeometry.boundsInRoot(current, coordinates),
                    )
                }
            }
        }
        if (selector != null && selection != null) {
            LaunchedEffect(selection, geometry) { reportBounds() }
            DisposableEffect(selector, pageIndex) {
                onDispose { selector.forgetBounds(pageIndex) }
            }
        }
        val haptics = LocalHapticFeedback.current
        val latestPxPerPoint by rememberUpdatedState(pxPerPoint)

        Box(
            Modifier
                .fillMaxWidth()
                .height(baseHeight * scale)
                .then(
                    if (selection != null) {
                        Modifier.onGloballyPositioned {
                            box.coordinates = it
                            reportBounds()
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(baseHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .background(androidx.compose.ui.graphics.Color.White)
                    .then(
                        // After the layer, so a touch arrives in unscaled page pixels. A long
                        // press selects the word under the finger; a tap drops the selection.
                        if (selector != null && pxPerPoint != null) {
                            Modifier.pointerInput(selector, pageIndex) {
                                detectTapGestures(
                                    onLongPress = { at ->
                                        val perPoint = latestPxPerPoint ?: return@detectTapGestures
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selector.selectWordAt(
                                            pageIndex,
                                            Offset(at.x / perPoint.x, at.y / perPoint.y),
                                        )
                                    },
                                    onTap = { selector.clear() },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = renderedState) {
                    PdfPageState.Loading -> LoadingIndicator(Modifier.size(32.dp))
                    is PdfPageState.Error -> Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                    is PdfPageState.Ready -> Image(
                        bitmap = state.page.image,
                        contentDescription = stringResource(
                            R.string.pdf_page_content_description,
                            pageIndex + 1,
                        ),
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (selection != null && pxPerPoint != null) {
                    val highlight = LocalTextSelectionColors.current.backgroundColor
                    Canvas(Modifier.matchParentSize()) {
                        selection.lines.forEach { line ->
                            drawRect(
                                highlight,
                                topLeft = Offset(line.left * pxPerPoint.x, line.top * pxPerPoint.y),
                                size = Size(line.width * pxPerPoint.x, line.height * pxPerPoint.y),
                            )
                        }
                    }
                }
            }
            if (selector != null && selection != null && geometry != null) {
                SelectionHandle(selector, selection, geometry, start = true)
                SelectionHandle(selector, selection, geometry, start = false)
            }
        }
    }
}

/**
 * Where a page and its points sit in the page's outer box, which is not zoomed: the page box is
 * scaled by [scale] about its top centre and moved by [offsetX].
 */
private data class PdfPageGeometry(
    /** Unscaled page pixels per page point, on each axis. */
    val pxPerPoint: Offset,
    val pageWidthPx: Float,
    val scale: Float,
    val offsetX: Float,
) {
    /** A point on the page, in page points, to the outer box's pixels. */
    fun toBox(point: Offset): Offset {
        val pivot = pageWidthPx / 2f
        return Offset(
            pivot + (point.x * pxPerPoint.x - pivot) * scale + offsetX,
            point.y * pxPerPoint.y * scale,
        )
    }

    /** A move of the finger, in the outer box's pixels, to page points. */
    fun toPoints(drag: Offset): Offset =
        Offset(drag.x / scale / pxPerPoint.x, drag.y / scale / pxPerPoint.y)

    /** Everything [selection] covers, in root coordinates, given the outer box's [coordinates]. */
    fun boundsInRoot(selection: PdfTextSelection, coordinates: LayoutCoordinates): Rect {
        val lines = selection.lines
        val topLeft = toBox(Offset(lines.minOf { it.left }, lines.minOf { it.top }))
        val bottomRight = toBox(Offset(lines.maxOf { it.right }, lines.maxOf { it.bottom }))
        return Rect(coordinates.localToRoot(topLeft), coordinates.localToRoot(bottomRight))
    }
}

/** Layout coordinates kept for later reads, without making a state of every move. */
private class LayoutHolder {
    var coordinates: LayoutCoordinates? = null
}

/**
 * A drag handle at one end of the selection, hanging below the line it ends on: under its start
 * for [start], under its stop otherwise. Dragging it moves that end; the other stays put. Both
 * ends go back to the platform as points, as AndroidX PDF does: the platform reports the ends
 * of a selection as points only.
 */
@Composable
private fun BoxScope.SelectionHandle(
    selector: PdfSelector,
    selection: PdfTextSelection,
    geometry: PdfPageGeometry,
    start: Boolean,
) {
    val color = LocalTextSelectionColors.current.handleColor
    val density = LocalDensity.current
    val latestSelection by rememberUpdatedState(selection)
    val latestGeometry by rememberUpdatedState(geometry)
    // Page points of the corner the handle hangs from, while the finger moves it.
    var dragged by remember { mutableStateOf<Offset?>(null) }
    val corner = dragged ?: if (start) selection.startCorner else selection.stopCorner
    val anchor = geometry.toBox(corner)
    val sizePx = with(density) { HandleTouchSize.toPx() }
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset {
                // The start handle's top right corner meets the text, the stop handle's top left.
                IntOffset(
                    (if (start) anchor.x - sizePx else anchor.x).roundToInt(),
                    anchor.y.roundToInt(),
                )
            }
            .size(HandleTouchSize)
            .pointerInput(selector, start) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // Held from the first touch, not from the touch slop: until then a zoomed page
                    // would pan sideways under the finger and take the drag away.
                    selector.handleHeld = true
                    val current = latestSelection
                    val fixed = if (start) current.stopCorner else current.startCorner
                    var moved = if (start) current.startCorner else current.stopCorner
                    fun moveBy(amount: Offset) {
                        moved += latestGeometry.toPoints(amount)
                        dragged = moved
                        val pageIndex = latestSelection.pageIndex
                        if (start) {
                            selector.extend(pageIndex, TextEdge.Near(moved), TextEdge.Near(fixed))
                        } else {
                            selector.extend(pageIndex, TextEdge.Near(fixed), TextEdge.Near(moved))
                        }
                    }
                    try {
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                            change.consume()
                            moveBy(overSlop)
                        }
                        if (slop != null) {
                            drag(slop.id) { change ->
                                // Read before consuming: a consumed change reports no movement.
                                val amount = change.positionChange()
                                change.consume()
                                moveBy(amount)
                            }
                        }
                    } finally {
                        dragged = null
                        selector.handleHeld = false
                    }
                }
            },
    ) {
        Canvas(Modifier.size(HandleSize).align(if (start) Alignment.TopEnd else Alignment.TopStart)) {
            val radius = size.minDimension / 2f
            drawCircle(color, radius, center = Offset(radius, radius))
            // The square quarter points at the text.
            drawRect(
                color,
                topLeft = Offset(if (start) radius else 0f, 0f),
                size = Size(radius, radius),
            )
        }
    }
}

private val HandleSize = 22.dp
private val HandleTouchSize = 36.dp

/** The selection's bounds on screen, in root coordinates, for the page that holds it. */
private class SelectionBounds(val pageIndex: Int, val rect: Rect)

/**
 * The text the reader has selected, on one page at a time. Lookups queue behind page renders for
 * the one open page, and a newer one cancels an older one still waiting.
 */
@Stable
private class PdfSelector(private val document: PdfDocument, private val scope: CoroutineScope) {
    var selection by mutableStateOf<PdfTextSelection?>(null)
        private set

    /** True while a finger is on a handle: the toolbar waits, and a zoomed page does not pan. */
    var handleHeld by mutableStateOf(false)

    var bounds by mutableStateOf<SelectionBounds?>(null)

    private var lookup: Job? = null

    /** The word under [point], in page points; nothing when there is no text there. */
    fun selectWordAt(pageIndex: Int, point: Offset) = find(keepOnMiss = false) {
        document.selectText(pageIndex, TextEdge.Near(point), TextEdge.Near(point))
    }

    fun selectPage(pageIndex: Int) = find(keepOnMiss = true) { document.selectAllText(pageIndex) }

    /** Moves an end while a handle is dragged. A miss keeps what is already selected. */
    fun extend(pageIndex: Int, start: TextEdge, stop: TextEdge) = find(keepOnMiss = true) {
        document.selectText(pageIndex, start, stop)
    }

    fun clear() {
        lookup?.cancel()
        selection = null
        bounds = null
    }

    fun forgetBounds(pageIndex: Int) {
        if (bounds?.pageIndex == pageIndex) bounds = null
    }

    private fun find(keepOnMiss: Boolean, lookUp: suspend () -> PdfTextSelection?) {
        lookup?.cancel()
        lookup = scope.launch {
            val found = try {
                lookUp()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A page the renderer cannot read text from, or a document closing underneath.
                null
            }
            if (found != null || !keepOnMiss) selection = found
        }
    }
}

private sealed interface PdfDocumentState {
    data object Loading : PdfDocumentState
    data class Ready(val document: PdfDocument) : PdfDocumentState
    data class Error(val message: String) : PdfDocumentState
}

private sealed interface PdfPageState {
    data object Loading : PdfPageState
    data class Ready(val page: RenderedPdfPage) : PdfPageState
    data class Error(val message: String) : PdfPageState
}

/** A page's picture, and the page's own size in points (1/72 inch) to map touches onto its text. */
private class RenderedPdfPage(val image: ImageBitmap, val widthPoints: Int, val heightPoints: Int)

private class PdfSourceException(cause: Throwable? = null) : IOException(cause)

private class PdfDocument private constructor(
    private val engine: PdfEngine,
    private val temporaryFile: File?,
) {
    val pageCount: Int = engine.pageCount

    /** Whether pages can be searched for the text under a touch. */
    val readsText: Boolean = engine.readsText

    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    /**
     * Renders one page at up to [requestedWidth] pixels wide. The renderer allows a single open
     * page at a time, so renders queue behind each other; a caller that has already been cancelled
     * drops out at the lock without opening anything.
     *
     * A page big enough that its bitmap will not fit is retried at half the size rather than
     * failed: softer is better than missing, and a reader who zoomed in that far is looking at a
     * fraction of the page anyway.
     */
    suspend fun renderPage(pageIndex: Int, requestedWidth: Int): RenderedPdfPage {
        val caller = currentCoroutineContext().job
        return onPage(pageIndex) { page ->
            if (page.width <= 0 || page.height <= 0) throw IOException("Invalid PDF page size")
            var factor = renderFactor(page.width, page.height, requestedWidth)
            repeat(RENDER_ATTEMPTS - 1) {
                try {
                    return@onPage RenderedPdfPage(rasterize(page, factor), page.width, page.height)
                } catch (_: OutOfMemoryError) {
                    // Not worth another attempt for a page nobody is waiting for any more.
                    caller.ensureActive()
                    factor /= 2.0
                }
            }
            // The last attempt's failure is the caller's to show.
            RenderedPdfPage(rasterize(page, factor), page.width, page.height)
        }
    }

    /** The text on page [pageIndex] from [start] to [stop]; null when there is none. */
    suspend fun selectText(pageIndex: Int, start: TextEdge, stop: TextEdge): PdfTextSelection? =
        onPage(pageIndex) { it.select(start, stop) }

    /** All the text on page [pageIndex]; null when there is none. */
    suspend fun selectAllText(pageIndex: Int): PdfTextSelection? = onPage(pageIndex) { it.selectAll() }

    /** Runs [block] on the open page; the page is closed again before the lock is let go. */
    private suspend fun <T> onPage(pageIndex: Int, block: (PdfEnginePage) -> T): T = mutex.withLock {
        check(!closed.get()) { "PDF is closed" }
        withContext(Dispatchers.Default) { engine.withPage(pageIndex, block) }
    }

    /** How much to scale the page by: what was asked for, capped by what a bitmap can hold. */
    private fun renderFactor(pageWidth: Int, pageHeight: Int, requestedWidth: Int): Double {
        val longestSide = maxOf(pageWidth, pageHeight)
        val sourcePixels = pageWidth.toDouble() * pageHeight
        return min(
            requestedWidth.toDouble() / pageWidth,
            min(
                MAX_BITMAP_DIMENSION.toDouble() / longestSide,
                sqrt(MAX_BITMAP_PIXELS / sourcePixels),
            ),
        ).coerceAtLeast(1.0 / longestSide)
    }

    private fun rasterize(page: PdfEnginePage, factor: Double): ImageBitmap {
        val width = (page.width * factor).roundToInt().coerceAtLeast(1)
        val height = (page.height * factor).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Pages are drawn onto whatever is behind them, so an unpainted page must start white.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap)
        return bitmap.asImageBitmap()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Under the lock: a render in flight is inside the native renderer, which must not be
        // closed from under it.
        Graph.appScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching { engine.close() }
                temporaryFile?.delete()
            }
        }
    }

    companion object {
        suspend fun open(
            context: Context,
            entry: XEntry,
            onCreated: (PdfDocument) -> Unit,
        ): PdfDocument = withContext(Dispatchers.IO) {
            val temporaryFile = if (entry.localPath == null) {
                copyToCache(context, entry)
            } else {
                null
            }
            val source = entry.localPath?.let(::File) ?: temporaryFile
                ?: throw PdfSourceException()
            if (!source.isFile || !source.canRead()) {
                temporaryFile?.delete()
                throw PdfSourceException()
            }

            var engine: PdfEngine? = null
            try {
                engine = PdfEngine.open {
                    try {
                        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                    } catch (error: Throwable) {
                        throw PdfSourceException(error)
                    }
                }
                if (engine.pageCount <= 0) throw IOException("PDF contains no pages")
                PdfDocument(engine, temporaryFile).also(onCreated)
            } catch (error: Throwable) {
                engine?.let { runCatching { it.close() } }
                temporaryFile?.delete()
                throw error
            }
        }

        private suspend fun copyToCache(context: Context, entry: XEntry): File {
            val outputFile = try {
                File.createTempFile("xfiles-pdf-", ".pdf", context.cacheDir)
            } catch (error: Throwable) {
                throw PdfSourceException(error)
            }
            try {
                openSource(context, entry).use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return outputFile
            } catch (cancelled: CancellationException) {
                outputFile.delete()
                throw cancelled
            } catch (error: Throwable) {
                outputFile.delete()
                throw PdfSourceException(error)
            }
        }

        /** ACTION_VIEW hands us a content URI, not an XFiles filesystem id. */
        private fun openSource(context: Context, entry: XEntry): InputStream {
            if (entry.scheme == "content") {
                return context.contentResolver.openInputStream(entry.id.toUri())
                    ?: throw PdfSourceException()
            }
            return Graph.fsRegistry.forId(entry.id).openIn(entry)
        }
    }
}
