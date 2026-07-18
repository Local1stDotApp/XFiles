package app.local1st.files.ui.viewer

import android.app.Activity
import android.content.ContextWrapper
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forward5
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay5
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import app.local1st.files.core.fs.XEntry
import app.local1st.files.ui.components.TooltipIconButton
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Video chrome replacing PlayerView's stock controller: instead of a full-screen scrim,
 * a compact bottom card that can be dragged vertically off whatever region is being
 * watched. Tapping the time display switches it to a frame counter, and in frame mode
 * every seek control steps by exactly one frame. Horizontal swipes on the video itself
 * seek (by time, or by frame in frame mode) with all chrome hidden.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoPlayerScreen(
    player: ExoPlayer,
    entry: XEntry,
    playing: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onClose: () -> Unit,
) {
    // All of these are UNKEYED remembers on purpose: long-lived gesture closures
    // (pointerInput below) capture the state objects once, and only an unkeyed
    // remember keeps the same object alive across playlist item transitions.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableFloatStateOf(0f) }
    var frameMode by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubLabel by remember { mutableStateOf<String?>(null) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var sliderPos by remember { mutableStateOf<Float?>(null) }
    var sliderWasPlaying by remember { mutableStateOf(false) }
    var cardDragging by remember { mutableStateOf(false) }

    // Fullscreen: hide the system bars while the video is up. Besides the obvious, this
    // is what keeps horizontal scrubbing usable — with bars hidden, an edge swipe first
    // reveals transient bars instead of dispatching the system back gesture.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // Keep the panel lit while playing, and also while inspecting frames: frame work is
    // long stretches of staring at a paused picture.
    DisposableEffect(view, playing, frameMode) {
        view.keepScreenOn = playing || frameMode
        onDispose { view.keepScreenOn = false }
    }

    // Seeks are always sample-exact (see below), and an exact seek decodes forward from
    // the previous keyframe — seconds of frames in a long-GOP screen recording. Issuing
    // a seek per drag event would keep cancelling that decode and nothing would render
    // until the finger lifts, so the gate paces issuance to actual render completion:
    // the picture then updates continuously at whatever rate the decoder sustains.
    val seekGate = remember(player) { SeekGate(player) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = seekGate.onFrameRendered()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
                seekGate.reset()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, entry.id) {
        while (isActive) {
            // Prefer the gate's queued target so the readout tracks the finger even
            // while a seek is still decoding.
            positionMs = (seekGate.targetMs.takeIf { it >= 0 } ?: player.currentPosition)
                .coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            if (fps <= 0f) player.videoFormat?.frameRate?.takeIf { it > 0f }?.let { fps = it }
            delay(100L)
        }
    }
    LaunchedEffect(entry.id) {
        // New item: forget the previous stream's rate (the poll above refills it), then
        // give the declared format a moment to surface before paying for a probe.
        fps = 0f
        delay(1000L)
        if (fps > 0f) return@LaunchedEffect
        val probed = withContext(Dispatchers.IO) { probeFrameRate(entry.localPath) }
        if (fps <= 0f && probed != null) fps = probed
    }

    // Always sample-exact: sync-frame shortcuts snap to keyframes, which screen
    // recordings space seconds apart — scrubbing would show almost nothing.
    LaunchedEffect(player) { player.setSeekParameters(SeekParameters.EXACT) }

    // Helper *functions*, not vals: gesture closures outlive many recompositions, and a
    // function reading the state vars sees live values where a captured val would not.
    fun effFpsNow(): Float = if (fps > 0f) fps else FALLBACK_FPS
    fun totalFramesNow(): Long =
        if (durationMs > 0) floor(durationMs.toDouble() * effFpsNow() / 1000.0).toLong() else 0L
    fun clampMs(ms: Long) = if (durationMs > 0) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)
    fun frameOf(ms: Long): Long = floor(ms.toDouble() * effFpsNow() / 1000.0).toLong()
    // Rapid steps must chain off the queued target, not currentPosition, or taps that
    // arrive while a seek is still decoding would all compute the same base.
    fun anchorMs(): Long = seekGate.targetMs.takeIf { it >= 0 } ?: player.currentPosition
    fun seekToFrame(frame: Long): Long {
        val target = frame.coerceIn(0L, (totalFramesNow() - 1).coerceAtLeast(0L))
        // Aim mid-frame: a boundary timestamp rounded down to ms would resolve to the
        // previous frame.
        seekGate.request(clampMs(((target + 0.5) * 1000.0 / effFpsNow()).toLong()))
        return target
    }
    fun stepFrame(delta: Int) {
        player.pause()
        seekToFrame(frameOf(anchorMs()) + delta)
        interactionTick++
    }
    fun stepSeconds(delta: Int) {
        seekGate.request(clampMs(anchorMs() + delta * 1000L))
        interactionTick++
    }

    // Pausing is when the controls matter, so surface them — except mid-swipe, where the
    // pause is our own bookkeeping and chrome would defeat the gesture's purpose.
    LaunchedEffect(playing) {
        if (!playing && !scrubbing && sliderPos == null) controlsVisible = true
    }
    // Auto-hide only while playing hands-free: a finger on the slider or the card must
    // never have the control it's holding vanish beneath it.
    val interacting = scrubbing || cardDragging || sliderPos != null
    LaunchedEffect(controlsVisible, playing, interacting, interactionTick) {
        if (controlsVisible && playing && !interacting) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // IgnoringVisibility: this screen itself hides the bars, and chrome anchored to the
    // live insets would collapse into the top/bottom edges (and jump when transient
    // bars appear). The close row keeps one status-bar height of clearance regardless.
    val statusBarsIns = WindowInsets.statusBarsIgnoringVisibility
    val navBarsIns = WindowInsets.navigationBarsIgnoringVisibility
    val cutout = WindowInsets.displayCutout
    var parentHeightPx by remember { mutableIntStateOf(0) }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    var cardOffsetY by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { parentHeightPx = it.height },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setUseController(false)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap toggles the chrome; horizontal swipes seek directly on the picture.
        // pointerInput(Unit), never keyed on frameMode: the handlers read frameMode and
        // friends live through snapshot state, and a key change mid-gesture would kill
        // the drag coroutine without running onDragCancel (stuck label, stuck pause).
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                },
        ) {
            // The scrub layer stays clear of the system back-gesture edge zones
            // (with a floor for devices that report none), so an edge swipe is never
            // both a seek and a back navigation. Taps still work full-bleed via the
            // parent layer above.
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.systemGestures
                            .only(WindowInsetsSides.Horizontal)
                            .union(WindowInsets(left = EDGE_GUARD_DP.dp, right = EDGE_GUARD_DP.dp)),
                    )
                    .pointerInput(Unit) {
                        var wasPlaying = false
                        var accumPx = 0f
                        var baseMs = 0L
                        var baseFrame = 0L
                        val endScrub: () -> Unit = {
                            scrubbing = false
                            scrubLabel = null
                            // Frame mode exists to inspect a chosen frame — stay on it
                            // instead of resuming playback over it.
                            if (wasPlaying && !frameMode) player.play()
                            interactionTick++
                        }
                        detectHorizontalDragGestures(
                            onDragStart = {
                                scrubbing = true
                                wasPlaying = player.isPlaying
                                player.pause()
                                accumPx = 0f
                                baseMs = anchorMs()
                                baseFrame = frameOf(baseMs)
                            },
                            onDragEnd = endScrub,
                            onDragCancel = endScrub,
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                accumPx += dragAmount
                                if (frameMode) {
                                    val deltaFrames = (accumPx / FRAME_SWIPE_DP.dp.toPx()).toLong()
                                    val landed = seekToFrame(baseFrame + deltaFrames)
                                    scrubLabel = String.format(
                                        Locale.US, "%d F  (%+d)", landed + 1, deltaFrames,
                                    )
                                } else {
                                    val deltaMs =
                                        (accumPx / 1.dp.toPx() * TIME_SWIPE_MS_PER_DP).toLong()
                                    val target = clampMs(baseMs + deltaMs)
                                    seekGate.request(target)
                                    scrubLabel = String.format(
                                        Locale.US,
                                        "%s  (%+.1fs)",
                                        formatPlayTime(target),
                                        (target - baseMs) / 1000f,
                                    )
                                }
                            },
                        )
                    },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                        ),
                    )
                    // Status-bar height (visible or not) ∪ cutout: the close row keeps
                    // its safe-area clearance in fullscreen and clears a landscape notch.
                    .windowInsetsPadding(
                        statusBarsIns.union(
                            cutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        ),
                    )
                    .padding(start = 4.dp, end = 16.dp, bottom = 8.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        scrubLabel?.let { label ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(statusBarsIns.union(cutout.only(WindowInsetsSides.Top)))
                    .padding(top = 48.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    navBarsIns.union(
                        cutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
                )
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp)
                // Clamp at placement, not only at drag: rotation or an inset change can
                // shrink the room, and this lambda re-runs on those size changes.
                .offset {
                    val travel = (
                        parentHeightPx - cardHeightPx - statusBarsIns.getTop(this) -
                            navBarsIns.getBottom(this) - 10.dp.roundToPx()
                        ).coerceAtLeast(0)
                    IntOffset(0, cardOffsetY.roundToInt().coerceIn(-travel, 0))
                },
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .onSizeChanged { cardHeightPx = it.height }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { cardDragging = true },
                            onDragEnd = {
                                cardDragging = false
                                interactionTick++
                            },
                            onDragCancel = {
                                cardDragging = false
                                interactionTick++
                            },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                val travel = (
                                    parentHeightPx - cardHeightPx - statusBarsIns.getTop(this) -
                                        navBarsIns.getBottom(this) - 10.dp.toPx()
                                    ).coerceAtLeast(0f)
                                cardOffsetY = (cardOffsetY + dy).coerceIn(-travel, 0f)
                            },
                        )
                    },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                CircleShape,
                            ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // "≈" flags an untrustworthy rate: missing entirely, or a
                        // non-standard average — MP4 rates are often synthesized as
                        // frameCount/duration, which is meaningless for VFR screen
                        // recordings that only encode a frame when the screen changes.
                        val approx = if (fps > 0f && isStandardFps(fps)) "" else "≈"
                        val total = totalFramesNow()
                        val modeColor = if (frameMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                        ModeToggleText(
                            text = if (frameMode) {
                                "$approx${(frameOf(positionMs).coerceIn(0L, (total - 1).coerceAtLeast(0L))) + 1}"
                            } else {
                                formatPlayTime(positionMs)
                            },
                            frameMode = frameMode,
                            color = modeColor,
                            onToggle = {
                                frameMode = !frameMode
                                interactionTick++
                            },
                        )
                        Slider(
                            value = sliderPos
                                ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                            onValueChange = { v ->
                                if (durationMs > 0) {
                                    if (sliderPos == null) {
                                        // Pause while scrubbing: playback racing the
                                        // thumb fights the user, and a video ending
                                        // mid-drag would auto-advance under the finger.
                                        sliderWasPlaying = player.isPlaying
                                        player.pause()
                                    }
                                    sliderPos = v
                                    seekGate.request((v * durationMs).toLong())
                                }
                            },
                            onValueChangeFinished = {
                                sliderPos?.let { seekGate.request((it * durationMs).toLong()) }
                                sliderPos = null
                                if (sliderWasPlaying && !frameMode) player.play()
                                sliderWasPlaying = false
                                interactionTick++
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        ModeToggleText(
                            text = if (frameMode) {
                                "$approx$total · ${String.format(Locale.US, "%.1f", effFpsNow())}fps"
                            } else {
                                formatPlayTime(durationMs)
                            },
                            frameMode = frameMode,
                            color = modeColor,
                            onToggle = {
                                frameMode = !frameMode
                                interactionTick++
                            },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (hasPrevious || hasNext) {
                            TooltipIconButton(
                                "Previous video",
                                Icons.Outlined.SkipPrevious,
                                enabled = hasPrevious,
                            ) {
                                player.seekToPreviousMediaItem()
                                interactionTick++
                            }
                        }
                        if (frameMode) {
                            TooltipIconButton("Previous frame", Icons.Outlined.ChevronLeft) {
                                stepFrame(-1)
                            }
                        } else {
                            TooltipIconButton("Back 5 seconds", Icons.Outlined.Replay5) {
                                stepSeconds(-STEP_SECONDS)
                            }
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(if (playing) "Pause" else "Play") } },
                            state = rememberTooltipState(),
                        ) {
                            FilledIconButton(
                                onClick = {
                                    when {
                                        playing -> player.pause()
                                        // play() alone is a no-op at ENDED/IDLE — the
                                        // stock controller restarts/re-prepares, so do we.
                                        player.playbackState == Player.STATE_ENDED -> {
                                            player.seekToDefaultPosition()
                                            player.play()
                                        }
                                        else -> {
                                            if (player.playbackState == Player.STATE_IDLE) {
                                                player.prepare()
                                            }
                                            player.play()
                                        }
                                    }
                                    interactionTick++
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                )
                            }
                        }
                        if (frameMode) {
                            TooltipIconButton("Next frame", Icons.Outlined.ChevronRight) {
                                stepFrame(1)
                            }
                        } else {
                            TooltipIconButton("Forward 5 seconds", Icons.Outlined.Forward5) {
                                stepSeconds(STEP_SECONDS)
                            }
                        }
                        if (hasPrevious || hasNext) {
                            TooltipIconButton(
                                "Next video",
                                Icons.Outlined.SkipNext,
                                enabled = hasNext,
                            ) {
                                player.seekToNextMediaItem()
                                interactionTick++
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Time/frame readout that toggles the display mode. Tooltip + padded hit target: a bare
 * clickable label would be an undiscoverable, sub-minimum touch target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggleText(
    text: String,
    frameMode: Boolean,
    color: Color,
    onToggle: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip { Text(if (frameMode) "Switch to time" else "Switch to frame counter") }
        },
        state = rememberTooltipState(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 2.dp, vertical = 12.dp),
        )
    }
}

/**
 * Serializes seeks so at most one is decoding at a time; the newest request replaces any
 * queued one and is issued when the in-flight seek's frame actually renders. Without
 * this, drag-speed seek streams keep aborting the keyframe-forward decode and no frame
 * appears until the gesture ends. All calls are main-thread (gestures + Player.Listener).
 */
private class SeekGate(private val player: ExoPlayer) {
    /** In-flight or queued seek target; -1 when idle. Anchors chained steps. */
    var targetMs = -1L
        private set
    private var queuedMs = -1L
    private var busySince = 0L

    fun request(ms: Long) {
        val now = SystemClock.uptimeMillis()
        // The staleness escape covers a missed render callback (e.g. seek resolved to
        // the already-shown frame on some codecs) so the gate can never wedge shut.
        if (targetMs >= 0 && now - busySince < STALE_SEEK_MS) {
            queuedMs = ms
            targetMs = ms
            return
        }
        targetMs = ms
        queuedMs = -1L
        busySince = now
        player.seekTo(ms)
    }

    fun onFrameRendered() {
        if (queuedMs >= 0) {
            val t = queuedMs
            queuedMs = -1L
            busySince = SystemClock.uptimeMillis()
            player.seekTo(t)
        } else {
            targetMs = -1L
        }
    }

    /** A queued seek targets the old item's timeline; drop it on item transitions. */
    fun reset() {
        targetMs = -1L
        queuedMs = -1L
    }
}

/**
 * Fallback frame rate for containers that don't declare one, derived from the stream's
 * total frame count. Null when that metadata is missing too.
 */
private fun probeFrameRate(path: String?): Float? {
    if (path == null || Build.VERSION.SDK_INT < 28) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val frames = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            ?.toLongOrNull()
        val durMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        if (frames != null && frames > 0 && durMs != null && durMs > 0) {
            frames * 1000f / durMs
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** Within 1% of a standard capture/broadcast rate; anything else smells of a VFR average. */
private fun isStandardFps(f: Float): Boolean = STANDARD_FPS.any { abs(f - it) / it < 0.01f }

private val STANDARD_FPS =
    floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 90f, 120f)

private const val FALLBACK_FPS = 30f
private const val STALE_SEEK_MS = 800L
private const val FRAME_SWIPE_DP = 8f // one frame per this much horizontal travel
private const val EDGE_GUARD_DP = 24 // scrub dead zone at screen edges (back gesture)
private const val TIME_SWIPE_MS_PER_DP = 100L // a full-width swipe covers roughly 40 s
private const val AUTO_HIDE_MS = 4000L
private const val STEP_SECONDS = 5
