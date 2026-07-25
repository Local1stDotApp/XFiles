package app.local1st.files.ui.viewer

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

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
 * Hides the system bars while [hidden] and brings them back when it flips or the viewer leaves, so a
 * viewer that hides its own chrome can go properly full-screen instead of keeping a status bar the
 * content is not allowed to use.
 *
 * Transient-bars behaviour is what keeps a hidden bar recoverable: an edge swipe reveals the bars
 * instead of dispatching the system back gesture.
 */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            insets?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
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
