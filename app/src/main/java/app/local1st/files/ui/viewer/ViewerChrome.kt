package app.local1st.files.ui.viewer

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Below this many rows a list is short enough to swipe through, and the thumb is just clutter. */
private const val SCROLLBAR_MIN_ITEMS = 40
private const val SCROLLBAR_LINGER_MILLIS = 1200L
private val SCROLLBAR_TOUCH_WIDTH = 28.dp
private val SCROLLBAR_THUMB_WIDTH = 6.dp
private val SCROLLBAR_MIN_THUMB = 40.dp

/**
 * Chrome shared by the full-screen viewers.
 *
 * [content] fills the window and draws behind both system bars; it is handed the padding its own
 * scrollable must apply so that nothing comes to *rest* under the chrome while everything is still
 * free to scroll through it.
 *
 * [topBar] keeps its own status-bar inset and its full height — it is deliberately not given a
 * Material scroll behaviour, which would collapse its height and squeeze the title. Instead the
 * whole bar, inset included, slides off the top edge as the content scrolls and slides back the
 * moment the content scrolls the other way. A gradient waits underneath to keep the status-bar icons
 * legible over the content that passes there once the bar is gone.
 *
 * Pass `collapsible = false` for a viewer with nothing to scroll, or one whose bar holds an action
 * the user must be able to reach at all times.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerChrome(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barHeight = statusBar + TopAppBarDefaults.TopAppBarExpandedHeight
    val barHeightPx = with(LocalDensity.current) { barHeight.toPx() }

    // Only the nested-scroll half of the behaviour is used: it turns scrolling into an offset and
    // settles it after a fling. The travel is the bar's full height because the bar leaves the
    // screen entirely rather than shrinking in place.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    SideEffect {
        scrollBehavior.state.heightOffsetLimit = if (collapsible) -barHeightPx else 0f
        if (!collapsible) scrollBehavior.state.heightOffset = 0f
    }

    Box(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        content(PaddingValues(top = barHeight, bottom = navigationBar))
        // Opaque and drawn under the bar, so it only ever shows once the bar has slid past it.
        StatusBarScrim(statusBar)
        Box(Modifier.offset { IntOffset(0, scrollBehavior.state.heightOffset.roundToInt()) }) {
            topBar()
        }
    }
}

/**
 * Hides the system bars while [hidden] so a viewer that hides its own chrome can go properly
 * full-screen. Touches the window **only** while [hidden] is true; on exit the pre-hide state is
 * restored (not a forced "normal").
 *
 * **API 28–29 (pre-R):** immersive [hide] is skipped. Restoring `systemUiVisibility` after
 * immersive correctly fixes the platform flags, but Compose's
 * [WindowInsets.navigationBarsIgnoringVisibility] stays at 0 afterwards (platform `stable`
 * insets remain 144px — only Compose's IgnoringVisibility cache is wrong). That is what drops
 * MainScreen's bottom safe area after returning from the image/video viewer even though cold
 * start is fine. App chrome still toggles; system bars stay put on pre-R.
 *
 * **API 30+:** hide/show via [WindowInsetsControllerCompat] with snapshot/restore of visibility
 * and [systemBarsBehavior].
 */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    // Pre-R: do not call hide/show — see KDoc. Keeps IgnoringVisibility alive for MainScreen.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val view = LocalView.current
    DisposableEffect(view, hidden) {
        if (!hidden) {
            onDispose { }
        } else {
            val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }

            val previousBehavior = controller?.systemBarsBehavior
                ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            val wasVisible = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.systemBars()) != false
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                if (controller == null) return@onDispose
                if (wasVisible) {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
                controller.systemBarsBehavior = previousBehavior
                view.post { ViewCompat.requestApplyInsets(view) }
            }
        }
    }
}

/**
 * Drag-to-seek scrollbar over a viewer's row list. Millions of rows can never be crossed by
 * swiping, so for a very large file the thumb is the only practical way through it. It shows up once
 * the list moves, stays while it is held, and fades out shortly after both stop — an invisible strip
 * would otherwise swallow swipes along the edge.
 */
@Composable
fun ViewerScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val itemCount = state.layoutInfo.totalItemsCount
    val onScreen = state.layoutInfo.visibleItemsInfo.size
    if (itemCount < SCROLLBAR_MIN_ITEMS || onScreen == 0) return

    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            shown = true
        } else {
            delay(SCROLLBAR_LINGER_MILLIS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f)
    if (alpha == 0f) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Scrolling ends when the last row reaches the bottom, not when it reaches the top.
    val lastTop = (itemCount - onScreen).coerceAtLeast(1)
    val progress = (state.firstVisibleItemIndex.toFloat() / lastTop).coerceIn(0f, 1f)

    BoxWithConstraints(modifier.fillMaxHeight().width(SCROLLBAR_TOUCH_WIDTH)) {
        val thumbHeight =
            maxOf(SCROLLBAR_MIN_THUMB, maxHeight * (onScreen.toFloat() / itemCount))
                .coerceAtMost(maxHeight)
        val travel = maxHeight - thumbHeight
        val travelPx = with(density) { travel.toPx() }.coerceAtLeast(1f)
        var held by remember { mutableFloatStateOf(0f) }
        var sought by remember { mutableStateOf(-1) }
        // Kept current: a DraggableState remembers its callback, and this one closes over a track
        // height and row count that change as the list grows.
        val seek by rememberUpdatedState<(Float) -> Unit> { offset ->
            held = offset.coerceIn(0f, travelPx)
            // Clamped, not just rounded: past a few million rows a Float cannot land on every index
            // and may round past the last one.
            val row = ((held / travelPx) * lastTop).roundToInt().coerceIn(0, lastTop)
            if (row != sought) {
                sought = row
                scope.launch { state.scrollToItem(row) }
            }
        }
        // Only the thumb seeks. A drag surface spanning the whole strip would, for the second or so
        // the bar lingers after every scroll, turn a swipe along the screen edge into a jump of
        // thousands of rows — and the same swipe a moment later would scroll normally.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = if (dragging) with(density) { held.toDp() } else travel * progress)
                .height(thumbHeight)
                .width(SCROLLBAR_TOUCH_WIDTH)
                .draggable(
                    state = rememberDraggableState { delta -> seek(held + delta) },
                    orientation = Orientation.Vertical,
                    // Relative to where the thumb already is, so the list never jumps on touch-down.
                    onDragStarted = {
                        held = travelPx * progress
                        sought = -1
                        dragging = true
                    },
                    onDragStopped = { dragging = false },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(SCROLLBAR_THUMB_WIDTH)
                    .alpha(alpha)
                    .background(
                        if (dragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        CircleShape,
                    ),
            )
        }
    }
}

/** Centres [content] in the space the chrome leaves free — for spinners and failure messages. */
@Composable
fun ViewerNotice(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { content() }
}

/**
 * Stands in for the app bar's status-bar backdrop once the bar has slid away: the surface colour
 * fading out across the inset.
 */
@Composable
private fun StatusBarScrim(height: Dp) {
    if (height <= 0.dp) return
    val surface = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0f to surface,
                    0.7f to surface.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                ),
            ),
    )
}
