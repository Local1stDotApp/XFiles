package app.local1st.files.ui.viewer

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import app.local1st.files.R
import kotlin.math.roundToInt

/**
 * Implemented by the activity. Android 12 and later shrink into picture-in-picture by themselves
 * when the user leaves ([PictureInPictureParams.Builder.setAutoEnterEnabled]); earlier versions
 * only hear about it in `onUserLeaveHint`, so the player leaves the params to enter with here —
 * or null, to let the activity simply stop.
 */
interface PictureInPictureHost {
    var pipOnLeave: PictureInPictureParams?
}

internal const val ACTION_PIP_CONTROL = "app.local1st.files.action.PIP_CONTROL"
internal const val EXTRA_PIP_CONTROL = "control"
internal const val PIP_PLAY_PAUSE = 1
internal const val PIP_REWIND = 2
internal const val PIP_FORWARD = 3

/** The system clamps the window to this range of aspect ratios (2.39:1 either way) and throws past it. */
private const val PIP_MAX_RATIO = 2.38f

/** Whether the device offers picture-in-picture at all; Android Go devices may not. */
internal fun supportsPictureInPicture(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

internal fun Context.findActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

/** True while the activity is showing in a picture-in-picture window. */
@Composable
internal fun rememberInPictureInPicture(): Boolean {
    val activity = LocalContext.current.findActivity() ?: return false
    var inPip by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * Width over height of a video of [width]×[height] pixels shown at [pixelRatio], or null before
 * the size is known.
 */
internal fun videoAspect(width: Int, height: Int, pixelRatio: Float): Float? =
    if (width <= 0 || height <= 0 || pixelRatio <= 0f) null else width * pixelRatio / height

/** [aspect] pulled into the range of window shapes the system accepts. */
internal fun clampPipAspect(aspect: Float): Float = aspect.coerceIn(1f / PIP_MAX_RATIO, PIP_MAX_RATIO)

private fun pipAspectRatio(aspect: Float): Rational =
    Rational((clampPipAspect(aspect) * 10_000).roundToInt(), 10_000)

/**
 * `[left, top, right, bottom]` of the picture fitted at [aspect] inside that box: the rect the
 * window grows out of. Null when the window will not have that shape anyway (an aspect the system
 * clamps), since a hint of another shape animates worse than none.
 */
internal fun fittedVideoBounds(left: Int, top: Int, right: Int, bottom: Int, aspect: Float): IntArray? {
    if (aspect != clampPipAspect(aspect)) return null
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null
    return if (width.toFloat() / height > aspect) {
        val fitted = (height * aspect).roundToInt()
        val start = left + (width - fitted) / 2
        intArrayOf(start, top, start + fitted, bottom)
    } else {
        val fitted = (width / aspect).roundToInt()
        val start = top + (height - fitted) / 2
        intArrayOf(left, start, right, start + fitted)
    }
}

internal fun fittedVideoRect(container: Rect, aspect: Float): Rect? =
    fittedVideoBounds(container.left, container.top, container.right, container.bottom, aspect)
        ?.let { Rect(it[0], it[1], it[2], it[3]) }

/**
 * The window's buttons: back, play or pause, forward — or only play/pause where the system allows
 * fewer than three. They come back to the player as [ACTION_PIP_CONTROL] broadcasts.
 */
internal fun pipActions(context: Context, playing: Boolean, maxActions: Int): List<RemoteAction> {
    val playPause = if (playing) {
        pipAction(context, PIP_PLAY_PAUSE, R.drawable.ic_pip_pause, context.getString(R.string.pause))
    } else {
        pipAction(context, PIP_PLAY_PAUSE, R.drawable.ic_pip_play, context.getString(R.string.play))
    }
    if (maxActions < 3) return listOf(playPause)
    return listOf(
        pipAction(context, PIP_REWIND, R.drawable.ic_pip_rewind, context.getString(R.string.back_5_seconds)),
        playPause,
        pipAction(context, PIP_FORWARD, R.drawable.ic_pip_forward, context.getString(R.string.forward_5_seconds)),
    )
}

private fun pipAction(context: Context, control: Int, icon: Int, title: String): RemoteAction {
    val intent = Intent(ACTION_PIP_CONTROL)
        .setPackage(context.packageName)
        .putExtra(EXTRA_PIP_CONTROL, control)
    val pending = PendingIntent.getBroadcast(
        context,
        control,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return RemoteAction(Icon.createWithResource(context, icon), title, title, pending)
}

/**
 * Params for the window: its shape, where it grows from, its buttons, and — on Android 12 and
 * later — whether leaving the app should shrink into it ([autoEnter]).
 */
internal fun pipParams(
    aspect: Float?,
    sourceRect: Rect?,
    actions: List<RemoteAction>,
    autoEnter: Boolean,
): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder().setActions(actions)
    aspect?.let { builder.setAspectRatio(pipAspectRatio(it)) }
    sourceRect?.let { builder.setSourceRectHint(it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        // Video cross-fades better than it stretches while the window is resized.
        builder.setSeamlessResizeEnabled(false)
    }
    return builder.build()
}
