package com.xfiles.ui.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.core.fs.XEntry
import com.xfiles.core.fs.XId
import com.xfiles.core.util.Format
import com.xfiles.di.Graph
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HEX_PAGE_SIZE = 4096
private const val HEX_ROW_BYTES = 16
private const val HEX_STREAM_LIMIT = 8 * 1024 * 1024
private const val MAX_CACHED_PAGES = 1024
private const val HEX_CHARS = "0123456789ABCDEF"

/**
 * Classic hex dump: "OFFSET  HH HH ...  ASCII" rows. Local files are paged on
 * demand through a RandomAccessFile; other schemes are read up to 8 MiB.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HexViewer(entry: XEntry, onClose: () -> Unit) {
    val localPath = remember(entry.id) {
        entry.localPath ?: entry.path.takeIf { entry.scheme == XId.SCHEME_FILE }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        if (localPath != null) {
            val file = remember(localPath) { File(localPath) }
            val fileSize = remember(file) { if (entry.size >= 0) entry.size else file.length() }
            HexTopBar(entry.name, fileSize, onClose)
            LocalFileHexRows(file, fileSize, Modifier.weight(1f))
        } else {
            val loaded by produceState<Result<ByteArray>?>(initialValue = null, entry.id) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        Graph.fsRegistry.forId(entry.id).openIn(entry)
                            .use { it.readUpTo(HEX_STREAM_LIMIT + 1) }
                    }
                }
            }
            val loadedSize = loaded?.getOrNull()?.size?.toLong() ?: -1L
            HexTopBar(entry.name, if (entry.size >= 0) entry.size else loadedSize, onClose)
            when (val result = loaded) {
                null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                else -> result.fold(
                    onSuccess = { data ->
                        if (data.size > HEX_STREAM_LIMIT) {
                            HexBanner("Showing the first 8 MB of ${entry.name}")
                        }
                        StreamHexRows(data, Modifier.weight(1f))
                    },
                    onFailure = { e ->
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                e.message ?: "Cannot read ${entry.name}",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HexTopBar(name: String, sizeBytes: Long, onClose: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sizeBytes >= 0) {
                    Text(
                        Format.bytes(sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close")
            }
        },
    )
}

@Composable
private fun HexBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LocalFileHexRows(file: File, fileSize: Long, modifier: Modifier) {
    val cache = remember(file) { HexPageCache(file) }
    DisposableEffect(cache) {
        onDispose { cache.close() }
    }
    val error by cache.error
    val offsetDigits = if (fileSize > 0xFFFF_FFFFL) 12 else 8
    val rowCount = ((fileSize + HEX_ROW_BYTES - 1) / HEX_ROW_BYTES)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    Column(modifier) {
        error?.let { HexBanner(it) }
        HexRowList(rowCount, Modifier.weight(1f)) { row ->
            val offset = row.toLong() * HEX_ROW_BYTES
            val pageIndex = (offset / HEX_PAGE_SIZE).toInt()
            val page = cache.pages[pageIndex]
            // Re-requests after eviction too: SideEffect runs on every recomposition
            // and request() is a no-op for cached or in-flight pages.
            SideEffect { cache.request(pageIndex) }
            if (page == null) {
                placeholderRow(offset, offsetDigits)
            } else {
                val start = (offset % HEX_PAGE_SIZE).toInt()
                val count = minOf(HEX_ROW_BYTES, page.size - start).coerceAtLeast(0)
                formatHexRow(offset, page, start, count, offsetDigits)
            }
        }
    }
}

@Composable
private fun StreamHexRows(data: ByteArray, modifier: Modifier) {
    val visibleBytes = minOf(data.size, HEX_STREAM_LIMIT)
    val rowCount = (visibleBytes + HEX_ROW_BYTES - 1) / HEX_ROW_BYTES
    HexRowList(rowCount, modifier) { row ->
        val offset = row * HEX_ROW_BYTES
        formatHexRow(offset.toLong(), data, offset, minOf(HEX_ROW_BYTES, visibleBytes - offset), 8)
    }
}

@Composable
private fun HexRowList(rowCount: Int, modifier: Modifier, rowText: @Composable (Int) -> String) {
    if (rowCount == 0) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Empty file", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val hScroll = rememberScrollState()
    Box(modifier.fillMaxWidth().horizontalScroll(hScroll)) {
        LazyColumn(Modifier.fillMaxHeight()) {
            items(rowCount) { row ->
                Text(
                    text = rowText(row),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

/** On-demand 4 KiB page cache over a RandomAccessFile, bounded to ~4 MiB. */
private class HexPageCache(private val file: File) {
    val pages = mutableStateMapOf<Int, ByteArray>()
    val error = mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val rafLazy = lazy { RandomAccessFile(file, "r") }

    fun request(index: Int) {
        if (pages.containsKey(index) || !inFlight.add(index)) return
        scope.launch {
            val data = try {
                val raf = rafLazy.value
                synchronized(raf) {
                    val start = index.toLong() * HEX_PAGE_SIZE
                    val length = (raf.length() - start).coerceIn(0L, HEX_PAGE_SIZE.toLong()).toInt()
                    ByteArray(length).also {
                        raf.seek(start)
                        raf.readFully(it)
                    }
                }
            } catch (e: IOException) {
                error.value = e.message ?: "Read error in ${file.name}"
                ByteArray(0)
            }
            trimIfNeeded()
            pages[index] = data
            inFlight.remove(index)
        }
    }

    private fun trimIfNeeded() {
        if (pages.size < MAX_CACHED_PAGES) return
        // Arbitrary eviction; evicted rows on screen simply re-request their page.
        pages.keys.toList().take(MAX_CACHED_PAGES / 4).forEach { pages.remove(it) }
    }

    fun close() {
        scope.cancel()
        if (rafLazy.isInitialized()) {
            runCatching { rafLazy.value.close() }
        }
    }
}

private fun formatHexRow(offset: Long, data: ByteArray, start: Int, count: Int, offsetDigits: Int): String {
    val sb = StringBuilder(offsetDigits + 2 + HEX_ROW_BYTES * 3 + 2 + HEX_ROW_BYTES)
    appendOffset(sb, offset, offsetDigits)
    sb.append("  ")
    for (i in 0 until HEX_ROW_BYTES) {
        if (i == 8) sb.append(' ')
        if (i < count) {
            val b = data[start + i].toInt() and 0xFF
            sb.append(HEX_CHARS[b ushr 4]).append(HEX_CHARS[b and 0xF]).append(' ')
        } else {
            sb.append("   ")
        }
    }
    sb.append(' ')
    for (i in 0 until count) {
        val b = data[start + i].toInt() and 0xFF
        sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
    }
    return sb.toString()
}

private fun placeholderRow(offset: Long, offsetDigits: Int): String {
    val sb = StringBuilder(offsetDigits + 2 + HEX_ROW_BYTES * 3)
    appendOffset(sb, offset, offsetDigits)
    sb.append("  ")
    for (i in 0 until HEX_ROW_BYTES) {
        if (i == 8) sb.append(' ')
        sb.append("·· ")
    }
    return sb.toString()
}

private fun appendOffset(sb: StringBuilder, offset: Long, digits: Int) {
    for (shift in (digits - 1) * 4 downTo 0 step 4) {
        sb.append(HEX_CHARS[((offset ushr shift) and 0xFL).toInt()])
    }
}

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 1 shl 16))
    val buffer = ByteArray(1 shl 16)
    var remaining = limit
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}
