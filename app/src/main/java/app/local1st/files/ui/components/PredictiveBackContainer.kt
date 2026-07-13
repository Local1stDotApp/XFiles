package app.local1st.files.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wraps a full-screen overlay so the system predictive-back gesture visibly scales and fades
 * it out while the user drags, committing [onBack] when the gesture completes and springing
 * back to rest when it is cancelled. Relies on `android:enableOnBackInvokedCallback="true"`.
 */
@Composable
fun PredictiveBackContainer(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var swipeEdgeLeft by remember { mutableFloatStateOf(1f) }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                progress = event.progress
                swipeEdgeLeft = if (event.swipeEdge == 0) 1f else -1f // 0 == BackEventCompat.EDGE_LEFT
            }
            progress = 0f
            onBack()
        } catch (_: CancellationException) {
            // Gesture cancelled: spring back to the resting state.
            progress = 0f
        }
    }

    // Ease so the shrink is gentle early and firmer as the gesture commits (M3 predictive-back feel).
    val eased = progress * (2f - progress)
    val scale = 1f - 0.10f * eased
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = 1f - 0.20f * eased
                // Pivot toward the swiped edge so it slides off in the gesture's direction.
                transformOrigin = TransformOrigin(if (swipeEdgeLeft > 0) 0.15f else 0.85f, 0.5f)
                translationX = swipeEdgeLeft * eased * size.width * 0.05f
            },
    ) { content() }
}
