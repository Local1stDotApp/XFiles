package app.local1st.files.ui.motion

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.navigationevent.NavigationEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class WindowPose(
    val scale: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
    val alpha: Float = 1f,
) {
    val isIdentity: Boolean
        get() = scale == 1f && tx == 0f && ty == 0f && alpha == 1f
}

fun AppScreenSwitcher.frontPose(
    width: Float,
    height: Float,
    marginPx: Float,
    enteringOffsetPx: Float,
    towardEnd: Int,
): WindowPose = when (mode) {
    AppScreenSwitcher.Mode.Idle -> WindowPose()
    AppScreenSwitcher.Mode.Forward -> WindowPose(
        tx = (1f - playhead) * towardEnd * width,
    )
    AppScreenSwitcher.Mode.BackPreview,
    AppScreenSwitcher.Mode.BackCommit,
    -> closingPose(
        width = width,
        height = height,
        preview = playhead,
        commit = commit,
        swipeEdge = swipeEdge,
        touchYDelta = touchYDelta,
        marginPx = marginPx,
        enteringOffsetPx = enteringOffsetPx,
    )
}

fun AppScreenSwitcher.rearPose(
    height: Float,
    marginPx: Float,
    enteringOffsetPx: Float,
    towardEnd: Int,
): WindowPose = when (mode) {
    AppScreenSwitcher.Mode.Idle -> WindowPose()
    AppScreenSwitcher.Mode.Forward -> WindowPose(
        scale = lerp(1f, MaxScale, playhead),
        tx = -towardEnd * playhead * enteringOffsetPx,
    )
    AppScreenSwitcher.Mode.BackPreview,
    AppScreenSwitcher.Mode.BackCommit,
    -> enteringPose(
        height = height,
        preview = playhead,
        commit = commit,
        touchYDelta = touchYDelta,
        marginPx = marginPx,
        enteringOffsetPx = enteringOffsetPx,
    )
}

fun AppScreenSwitcher.scrimAlpha(isDark: Boolean): Float {
    if (mode != AppScreenSwitcher.Mode.BackPreview &&
        mode != AppScreenSwitcher.Mode.BackCommit
    ) {
        return 0f
    }
    val maxScrim = if (isDark) ScrimAlphaDark else ScrimAlphaLight
    return maxScrim * (1f - commit)
}

@Composable
fun AppScreenSurface(
    pose: WindowPose,
    displayCornerRadius: Dp,
    content: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val round = !pose.isIdentity
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                transformOrigin = TransformOrigin.Center
                scaleX = pose.scale
                scaleY = pose.scale
                translationX = pose.tx
                translationY = pose.ty
                alpha = pose.alpha
                clip = round
                shape = if (round) RoundedCornerShape(displayCornerRadius) else RoundedCornerShape(0)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .background(background),
    ) {
        content()
    }
}

@Composable
fun rememberDisplayCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val fallback = 28.dp
    return remember(view, density) {
        val insets = ViewCompat.getRootWindowInsets(view)
        val radiusPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val tl = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            val tr = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
            val bl = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            val br = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
            maxOf(tl, tr, bl, br)
        } else {
            0
        }
        if (radiusPx > 0) with(density) { radiusPx.toDp() } else fallback
    }
}

internal const val PostCommitMs = 450
internal val ActivityEmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
internal const val MaxScale = 0.9f
internal val EnteringStartOffset = 96.dp
internal val DisplayBoundsMargin = 8.dp

private const val ScrimAlphaLight = 0.2f
private const val ScrimAlphaDark = 0.8f

internal fun closingPose(
    width: Float,
    height: Float,
    preview: Float,
    commit: Float,
    swipeEdge: Int,
    touchYDelta: Float,
    marginPx: Float,
    enteringOffsetPx: Float,
): WindowPose {
    val scale = lerp(1f, MaxScale, preview)
    val tx = if (swipeEdge == NavigationEvent.EDGE_RIGHT) {
        0f
    } else {
        preview * (width * (1f - MaxScale) / 2f - marginPx).coerceAtLeast(0f)
    }
    val ty = dampedY(touchYDelta, height, scale, marginPx)
    if (commit <= 0f) return WindowPose(scale, tx, ty, 1f)

    val emphasized = ActivityEmphasizedEasing.transform(commit)
    val currentLeft = width * (1f - scale) / 2f + tx
    return WindowPose(
        scale = lerp(scale, 1f, emphasized),
        tx = lerp(tx, currentLeft + enteringOffsetPx, emphasized),
        ty = lerp(ty, 0f, emphasized),
        alpha = max(1f - commit * 5f, 0f),
    )
}

internal fun enteringPose(
    height: Float,
    preview: Float,
    commit: Float,
    touchYDelta: Float,
    marginPx: Float,
    enteringOffsetPx: Float,
): WindowPose {
    val scale = lerp(1f, MaxScale, preview)
    val tx = -enteringOffsetPx
    val ty = dampedY(touchYDelta, height, scale, marginPx)
    if (commit <= 0f) return WindowPose(scale, tx, ty, 1f)

    val emphasized = ActivityEmphasizedEasing.transform(commit)
    return WindowPose(
        scale = lerp(scale, 1f, emphasized),
        tx = lerp(tx, 0f, emphasized),
        ty = lerp(ty, 0f, emphasized),
        alpha = 1f,
    )
}

internal fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun dampedY(
    rawYDelta: Float,
    height: Float,
    scale: Float,
    marginPx: Float,
): Float {
    if (height <= 0f) return 0f
    val maxDelta = max(0f, (height - height * scale) / 2f - marginPx)
    val ratio = min(1f, abs(rawYDelta) / (height / 2f))
    val decelerated = 1f - (1f - ratio) * (1f - ratio)
    val direction = if (rawYDelta < 0f) -1f else 1f
    return maxDelta * decelerated * direction
}

fun towardEndSign(layoutDirection: LayoutDirection): Int {
    return if (layoutDirection == LayoutDirection.Rtl) -1 else 1
}
