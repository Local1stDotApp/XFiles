package com.xfiles.ui.viewer

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xfiles.core.fs.XEntry
import com.xfiles.core.util.FileCategory
import com.xfiles.core.util.FileTypes
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * media3 playback: PlayerView for video, an Expressive card UI for audio.
 * Only entries with a real local file are playable; others are skipped.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewer(entry: XEntry, playlist: List<XEntry>, onClose: () -> Unit) {
    val playable = remember(entry.id, playlist) {
        playlist.ifEmpty { listOf(entry) }.filter { it.localPath != null }
    }
    if (playable.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Cannot play ${entry.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only files with a local copy can be played.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) {
                Text("Close")
            }
        }
        return
    }

    val context = LocalContext.current
    val startIndex = remember(playable, entry.id) {
        playable.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .apply {
                setMediaItems(
                    playable.map { MediaItem.fromUri(File(checkNotNull(it.localPath)).toUri()) },
                    startIndex,
                    C.TIME_UNSET,
                )
                prepare()
                playWhenReady = true
            }
    }

    var currentIndex by remember { mutableIntStateOf(startIndex) }
    var playing by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentIndex = p.currentMediaItemIndex
                playing = p.isPlaying
                metadata = p.mediaMetadata
                hasPrevious = p.hasPreviousMediaItem()
                hasNext = p.hasNextMediaItem()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val currentEntry = playable[currentIndex.coerceIn(0, playable.lastIndex)]
    val isVideo = FileTypes.categoryOf(currentEntry.name, currentEntry.mime) == FileCategory.VIDEO

    val view = LocalView.current
    DisposableEffect(view, isVideo, playing) {
        view.keepScreenOn = isVideo && playing
        onDispose { view.keepScreenOn = false }
    }

    if (isVideo) {
        VideoSurface(player, onClose)
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
            onClose = onClose,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: Player, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setUseController(true)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
        }
    }
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
    onClose: () -> Unit,
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(200L)
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopAppBar(
            title = { Text("Music") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ElevatedCard(Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
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
                        metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown artist",
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
                    LinearWavyProgressIndicator(
                        progress = {
                            if (durationMs > 0) {
                                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatPlayTime(positionMs), style = MaterialTheme.typography.labelMedium)
                        Text(formatPlayTime(durationMs), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        IconButton(onClick = { player.seekToPreviousMediaItem() }, enabled = hasPrevious) {
                            Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous")
                        }
                        FilledIconButton(
                            onClick = { if (playing) player.pause() else player.play() },
                            modifier = Modifier.size(64.dp),
                        ) {
                            Icon(
                                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                            )
                        }
                        IconButton(onClick = { player.seekToNextMediaItem() }, enabled = hasNext) {
                            Icon(Icons.Outlined.SkipNext, contentDescription = "Next")
                        }
                    }
                }
            }
        }
    }
}

private fun formatPlayTime(ms: Long): String {
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
