package com.xfiles.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xfiles.core.fs.XEntry
import com.xfiles.di.Graph
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/** Upper bound for buffering an archive-embedded image fully into memory. */
private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

/**
 * X-plore style full-screen image browser: swipe between sibling images,
 * pinch/double-tap to zoom, tap to toggle the overlay bar.
 */
@Composable
fun ImageViewer(items: List<XEntry>, startIndex: Int, onClose: () -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Nothing to show", color = Color.White)
        }
        return
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.size - 1),
    ) { items.size }
    var barsVisible by remember { mutableStateOf(true) }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val currentZoomed = zoomedPages[pagerState.currentPage] == true

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !currentZoomed,
            beyondViewportPageCount = 1,
            key = { items[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImagePage(
                entry = items[page],
                isSettled = pagerState.settledPage == page,
                onToggleBars = { barsVisible = !barsVisible },
                onZoomChanged = { zoomedPages[page] = it },
            )
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    items[pagerState.currentPage.coerceIn(0, items.size - 1)].name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${pagerState.currentPage + 1}/${items.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoomableImagePage(
    entry: XEntry,
    isSettled: Boolean,
    onToggleBars: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isSettled) {
        if (!isSettled && scale != MIN_SCALE) {
            scale = MIN_SCALE
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }

    fun applyTransform(newScaleRaw: Float, centroid: Offset, pan: Offset, containerSize: IntSize) {
        val newScale = newScaleRaw.coerceIn(MIN_SCALE, MAX_SCALE)
        // graphicsLayer scales around the center; keep the content point under the
        // gesture centroid fixed while scaling, then clamp panning to the content edges.
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val d = centroid - center
        val ratio = newScale / scale
        val unclamped = d + pan - (d - offset) * ratio
        val maxX = containerSize.width * (newScale - 1f) / 2f
        val maxY = containerSize.height * (newScale - 1f) / 2f
        scale = newScale
        offset = Offset(unclamped.x.coerceIn(-maxX, maxX), unclamped.y.coerceIn(-maxY, maxY))
        onZoomChanged(newScale > MIN_SCALE)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleBars() },
                    onDoubleTap = { tap ->
                        if (scale > MIN_SCALE) {
                            scale = MIN_SCALE
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            applyTransform(DOUBLE_TAP_SCALE, tap, Offset.Zero, size)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // detectTransformGestures would consume one-finger pans and kill pager
                // swipes at scale 1, so transforms are detected manually and consumed
                // only while pinching or already zoomed.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f || scale > MIN_SCALE) {
                            val centroid = event.calculateCentroid()
                            if (centroid.isSpecified) {
                                applyTransform(scale * zoomChange, centroid, event.calculatePan(), size)
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val imageModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        val localPath = entry.localPath
        if (localPath != null) {
            AsyncImage(
                model = File(localPath),
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )
        } else {
            val loaded by produceState<Result<ByteArray>?>(initialValue = null, entry.id) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        // Cap in-memory decode: an archive member has no localPath, so it is
                        // buffered whole. Refuse absurd sizes instead of OOM-crashing.
                        if (entry.size > MAX_IMAGE_BYTES) throw IOException("Image too large to preview")
                        Graph.fsRegistry.forId(entry.id).openIn(entry).use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(chunk)
                                if (n < 0) break
                                total += n
                                if (total > MAX_IMAGE_BYTES) throw IOException("Image too large to preview")
                                out.write(chunk, 0, n)
                            }
                            out.toByteArray()
                        }
                    }
                }
            }
            when (val result = loaded) {
                null -> LoadingIndicator(color = Color.White)
                else -> result.fold(
                    onSuccess = { bytes ->
                        AsyncImage(
                            model = bytes,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Fit,
                            modifier = imageModifier,
                        )
                    },
                    onFailure = { e ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.BrokenImage,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                            )
                            Text(
                                e.message ?: "Cannot load ${entry.name}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}
