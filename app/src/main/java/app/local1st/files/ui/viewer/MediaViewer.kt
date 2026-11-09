package app.local1st.files.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forward5
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay5
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.local1st.files.R
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.ui.components.TooltipIconButton
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * media3 playback: PlayerView for video, an Expressive card UI for audio.
 * Local files use Media3's normal file source; root:// files use a binder-opened seekable fd.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewer(
    entry: XEntry,
    playlist: List<XEntry>,
    onClose: () -> Unit,
    onOpenWith: (XEntry) -> Unit,
) {
    val privilegedFdAvailable = PrivilegedAccess.canOpenFd()
    var prepared by remember(entry.id, playlist) { mutableStateOf<List<Pair<XEntry, Uri>>?>(null) }
    LaunchedEffect(entry.id, playlist, privilegedFdAvailable) {
        val rows = playlist.ifEmpty { listOf(entry) }
        prepared = withContext(Dispatchers.IO) {
            rows.mapNotNull { item ->
                val uri = mediaUri(item)
                    ?: if (item.scheme == XId.SCHEME_ROOT && privilegedFdAvailable) {
                        Uri.Builder().scheme(XId.SCHEME_ROOT).path(item.path).build()
                    } else {
                        null
                    }
                uri?.let { item to it }
            }
        }
    }
    val playable = prepared
    if (playable == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val startIndex = mediaStartIndex(entry.id, playable.map { it.first.id })
    if (playable.isEmpty() || startIndex == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.cannot_play, entry.name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.only_local_files_playable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.close))
            }
        }
        return
    }

    val context = LocalContext.current
    // Recreate when the queue changes. Shizuku/root arriving later adds rows the
    // first player never received, and next/previous would disagree with the UI.
    val player = remember(playable) {
        val dataSourceFactory = DefaultDataSource.Factory(context, PrivilegedDataSource.Factory())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            // Headphones unplugged mid-track: pause rather than carry on out loud.
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItems(
                    playable.map { (_, uri) -> MediaItem.fromUri(uri) },
                    startIndex,
                    C.TIME_UNSET,
                )
                prepare()
                playWhenReady = true
            }
    }

    var currentIndex by remember(playable) { mutableIntStateOf(startIndex) }
    var playing by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentIndex = p.currentMediaItemIndex
                // isPlaying drops to false for the whole exact-seek decode, which made
                // the pause control flip to play and looked like scrubbing paused the video.
                playing = playbackIntended(p)
                metadata = p.mediaMetadata
                hasPrevious = p.hasPreviousMediaItem()
                hasNext = p.hasNextMediaItem()
                speed = p.playbackParameters.speed
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val currentEntry = playable[currentIndex.coerceIn(0, playable.lastIndex)].first
    val isVideo = FileTypes.categoryOf(currentEntry.name, currentEntry.mime) == FileCategory.VIDEO
    // The playing item, which next/previous may have moved off the tapped one. Paused first: the
    // other app is about to play it too.
    val openWith = {
        player.pause()
        onOpenWith(currentEntry)
    }

    if (isVideo) {
        VideoPlayerScreen(
            player = player,
            entry = currentEntry,
            playing = playing,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            speed = speed,
            onClose = onClose,
            onOpenWith = openWith,
        )
    } else {
        AudioPlayerScreen(
            player = player,
            entry = currentEntry,
            metadata = metadata,
            playing = playing,
            trackNumber = currentIndex + 1,
            trackCount = playable.size,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            speed = speed,
            onClose = onClose,
            onOpenWith = openWith,
        )
    }
}

private fun mediaUri(entry: XEntry) = when {
    entry.localPath != null -> File(entry.localPath).toUri()
    entry.scheme == "content" -> entry.id.toUri()
    entry.scheme == XId.SCHEME_FILE -> grantMediaUri(entry)
    else -> null
}

/** Content URI for a file the grant can open and [File] cannot. A bin symlink stays unplayable. */
private fun grantMediaUri(entry: XEntry): Uri? {
    val path = entry.path
    if (path.isEmpty()) return null
    val file = File(path)
    if (java.nio.file.Files.isSymbolicLink(file.toPath())) return null
    return runCatching { app.local1st.files.di.Graph.legacySaf?.persistedDocument(file)?.uri }.getOrNull()
}

/** Null when the tapped item is not playable. Do not start a different sibling. */
internal fun mediaStartIndex(tappedId: String, playableIds: List<String>): Int? {
    val index = playableIds.indexOf(tappedId)
    return if (index < 0) null else index
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudioPlayerScreen(
    player: Player,
    entry: XEntry,
    metadata: MediaMetadata,
    playing: Boolean,
    trackNumber: Int,
    trackCount: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    speed: Float,
    onClose: () -> Unit,
    onOpenWith: () -> Unit,
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // Where the thumb is while a finger holds it; the poll below must not drag it back.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(200L)
        }
    }
    fun seekTo(ms: Long) {
        val target = if (durationMs > 0) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)
        player.seekTo(target)
        // Shown now rather than at the next poll, so the thumb does not hop back first.
        positionMs = target
    }

    // Nothing scrolls here, so the bar stays put; only the background reaches into the system bars.
    ViewerChrome(
        collapsible = false,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music)) },
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
        },
    ) { chrome ->
        Box(Modifier.fillMaxSize().padding(chrome), contentAlignment = Alignment.Center) {
            ElevatedCard(Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp),
                    )
                    Text(
                        metadata.title?.toString()?.takeIf { it.isNotBlank() }
                            ?: entry.name.substringBeforeLast('.'),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_artist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${entry.name}  ·  $trackNumber/$trackCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Slider(
                        value = dragFraction
                            ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { if (durationMs > 0) dragFraction = it },
                        onValueChangeFinished = {
                            dragFraction?.let { seekTo((it * durationMs).toLong()) }
                            dragFraction = null
                        },
                        enabled = durationMs > 0,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: positionMs
                        Text(formatPlayTime(shownMs), style = MaterialTheme.typography.labelMedium)
                        Text(formatPlayTime(durationMs), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        if (trackCount > 1) {
                            TooltipIconButton(
                                stringResource(R.string.previous),
                                Icons.Outlined.SkipPrevious,
                                enabled = hasPrevious,
                                onClick = { player.seekToPreviousMediaItem() },
                            )
                        }
                        TooltipIconButton(stringResource(R.string.back_5_seconds), Icons.Outlined.Replay5) {
                            seekTo(player.currentPosition - SEEK_STEP_MS)
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(if (playing) R.string.pause else R.string.play)) } },
                            state = rememberTooltipState(),
                        ) {
                            FilledIconButton(
                                onClick = { togglePlayback(player, playing) },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                                )
                            }
                        }
                        TooltipIconButton(stringResource(R.string.forward_5_seconds), Icons.Outlined.Forward5) {
                            seekTo(player.currentPosition + SEEK_STEP_MS)
                        }
                        if (trackCount > 1) {
                            TooltipIconButton(
                                stringResource(R.string.next),
                                Icons.Outlined.SkipNext,
                                enabled = hasNext,
                                onClick = { player.seekToNextMediaItem() },
                            )
                        }
                    }
                    PlaybackSpeedButton(
                        speed = speed,
                        onSpeed = { player.setPlaybackSpeed(it) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Play or pause. play() alone does nothing once playback has ended or failed: the item has to be
 * rewound or prepared again first, which is what the stock controller does too.
 */
internal fun togglePlayback(player: Player, playing: Boolean) {
    when {
        playing -> player.pause()
        player.playbackState == Player.STATE_ENDED -> {
            player.seekToDefaultPosition()
            player.play()
        }
        else -> {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }
}

/** Speeds offered; beyond 3× speech stops being speech, and a high-resolution video drops frames. */
internal val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

/** "1.5×", in the reader's digits. */
internal fun formatSpeed(speed: Float, locale: Locale = Locale.getDefault()): String {
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
    return number.format(speed) + "×"
}

/**
 * The current speed, which opens the list of [PLAYBACK_SPEEDS]. Pitch is left alone: Media3
 * time-stretches, so a voice at 1.5× is still the same voice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSpeedButton(
    speed: Float,
    onSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    fun setOpen(value: Boolean) {
        open = value
        onExpandedChange(value)
    }
    val label = stringResource(R.string.playback_speed)
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            TextButton(
                onClick = { setOpen(true) },
                colors = if (contentColor.isSpecified) {
                    ButtonDefaults.textButtonColors(contentColor = contentColor)
                } else {
                    ButtonDefaults.textButtonColors()
                },
                modifier = Modifier.semantics { contentDescription = "$label ${formatSpeed(speed)}" },
            ) {
                Text(formatSpeed(speed), style = MaterialTheme.typography.labelLarge)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            for (choice in PLAYBACK_SPEEDS) {
                DropdownMenuItem(
                    text = { Text(formatSpeed(choice)) },
                    trailingIcon = if (choice == speed) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        setOpen(false)
                        onSpeed(choice)
                    },
                )
            }
        }
    }
}

private const val SEEK_STEP_MS = 5_000L

/** Play/pause chrome follows the user's intent, not decoder liveness. */
internal fun playbackIntended(player: Player): Boolean =
    player.playWhenReady &&
        player.playbackState != Player.STATE_ENDED &&
        player.playbackState != Player.STATE_IDLE

internal fun formatPlayTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
