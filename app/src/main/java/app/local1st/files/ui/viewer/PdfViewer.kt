package app.local1st.files.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.di.Graph
import app.local1st.files.ui.components.TooltipIconButton
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * Non-local entries are copied into the app cache because [PdfRenderer] requires a seekable file
 * descriptor; that private temporary copy is removed when this viewer leaves composition.
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
            val document = PdfDocument.open(context.applicationContext, entry) { opened = it }
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                .pointerInput(viewportWidth) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val pinching = event.changes.count { it.pressed } > 1 ||
                                zoomChange != 1f
                            val horizontalPan = scale > MIN_PDF_SCALE &&
                                abs(pan.x) > abs(pan.y)
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
                PdfPage(document, pageIndex, scale, renderScale, offsetX)
            }
        }
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
) {
    val renderError = stringResource(R.string.pdf_page_render_failed, pageIndex + 1)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
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
        val aspectRatio = when (val state = renderedState) {
            is PdfPageState.Ready -> state.image.width.toFloat() / state.image.height
            else -> DEFAULT_PAGE_ASPECT_RATIO
        }
        val baseHeight = maxWidth / aspectRatio

        Box(
            Modifier
                .fillMaxWidth()
                .height(baseHeight * scale),
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
                    .background(androidx.compose.ui.graphics.Color.White),
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
                        bitmap = state.image,
                        contentDescription = stringResource(
                            R.string.pdf_page_content_description,
                            pageIndex + 1,
                        ),
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
    data class Ready(val image: ImageBitmap) : PdfPageState
    data class Error(val message: String) : PdfPageState
}

private class PdfSourceException(cause: Throwable? = null) : IOException(cause)

private class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val temporaryFile: File?,
) {
    val pageCount: Int = renderer.pageCount

    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    /**
     * Renders one page at up to [requestedWidth] pixels wide. [PdfRenderer] allows a single open
     * page at a time, so renders queue behind each other; a caller that has already been cancelled
     * drops out at the lock without opening anything.
     *
     * A page big enough that its bitmap will not fit is retried at half the size rather than
     * failed: softer is better than missing, and a reader who zoomed in that far is looking at a
     * fraction of the page anyway.
     */
    suspend fun renderPage(pageIndex: Int, requestedWidth: Int): ImageBitmap = mutex.withLock {
        check(!closed.get()) { "PDF is closed" }
        withContext(Dispatchers.Default) {
            renderer.openPage(pageIndex).use { page ->
                if (page.width <= 0 || page.height <= 0) throw IOException("Invalid PDF page size")
                var factor = renderFactor(page.width, page.height, requestedWidth)
                repeat(RENDER_ATTEMPTS - 1) {
                    try {
                        return@withContext rasterize(page, factor)
                    } catch (_: OutOfMemoryError) {
                        // Not worth another attempt for a page nobody is waiting for any more.
                        currentCoroutineContext().ensureActive()
                        factor /= 2.0
                    }
                }
                // The last attempt's failure is the caller's to show.
                rasterize(page, factor)
            }
        }
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

    private fun rasterize(page: PdfRenderer.Page, factor: Double): ImageBitmap {
        val width = (page.width * factor).roundToInt().coerceAtLeast(1)
        val height = (page.height * factor).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Pages are drawn onto whatever is behind them, so an unpainted page must start white.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap.asImageBitmap()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Under the lock: a render in flight is inside the native renderer, which must not be
        // closed from under it.
        Graph.appScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching { renderer.close() }
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

            val descriptor = try {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (cancelled: CancellationException) {
                temporaryFile?.delete()
                throw cancelled
            } catch (error: Throwable) {
                temporaryFile?.delete()
                throw PdfSourceException(error)
            }

            var renderer: PdfRenderer? = null
            try {
                renderer = PdfRenderer(descriptor)
                if (renderer.pageCount <= 0) throw IOException("PDF contains no pages")
                PdfDocument(renderer, temporaryFile).also(onCreated)
            } catch (error: Throwable) {
                if (renderer == null) {
                    runCatching { descriptor.close() }
                } else {
                    runCatching { renderer.close() }
                }
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
                Graph.fsRegistry.forId(entry.id).openIn(entry).use { input ->
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
    }
}
