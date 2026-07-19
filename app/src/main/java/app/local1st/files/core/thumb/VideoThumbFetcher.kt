package app.local1st.files.core.thumb

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.priv.PrivilegedAccess
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Coil model for a video file's poster frame. [mtime]/[size] are part of the cache key,
 * so an overwritten video naturally invalidates its stale thumbnail.
 */
data class VideoThumb(
    val path: String,
    val mtime: Long,
    val size: Long,
    val privileged: Boolean = false,
)

/**
 * Extracts a small poster frame from a local video and keeps it in an on-disk thumbnail
 * cache. Frame extraction spins up a hardware codec and can take seconds for big videos,
 * so results must survive process death — Coil's own disk cache only stores source data
 * (which here would be the whole video), never decoded frames.
 *
 * Entries for videos that were deleted or renamed are not cleaned up eagerly; they sit
 * in the app-private, OS-clearable cacheDir until pruning reclaims them.
 */
class VideoThumbFetcher(
    private val context: Context,
    private val data: VideoThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cached = cacheFile(context, data)
        when (val read = readCached(cached)) {
            is CacheRead.Hit -> return read.result
            CacheRead.Failed -> return null
            CacheRead.Miss -> {}
        }
        // Hardware codec instances are a scarce global resource; a folder of fresh
        // videos must not race a dozen extractions (losers fall back to software
        // decoders or fail outright).
        return extractSemaphore.withPermit {
            // The wait may have been exactly this video, extracted for the other pane.
            when (val read = readCached(cached)) {
                is CacheRead.Hit -> return@withPermit read.result
                CacheRead.Failed -> return@withPermit null
                CacheRead.Miss -> {}
            }
            val bitmap = extractFrame(data)
            writeCache(cached, bitmap)
            bitmap?.let {
                ImageFetchResult(image = it.asImage(), isSampled = true, dataSource = DataSource.DISK)
            }
        }
    }

    private sealed interface CacheRead {
        class Hit(val result: FetchResult) : CacheRead
        object Failed : CacheRead
        object Miss : CacheRead
    }

    private fun readCached(cached: File): CacheRead {
        // length() before isFile: a file deleted in between reads as 0 bytes, which must
        // fall through to Miss, not be mistaken for the zero-byte failure marker.
        val len = cached.length()
        if (len == 0L) {
            if (!cached.isFile) return CacheRead.Miss
            // Failure markers expire: extraction also fails for transient reasons
            // (hardware codecs exhausted, I/O hiccup) that shouldn't cost the video
            // its thumbnail forever — one retry per TTL is cheap.
            if (System.currentTimeMillis() - cached.lastModified() < NEGATIVE_TTL_MS) {
                return CacheRead.Failed
            }
            cached.delete()
            return CacheRead.Miss
        }
        // A crash or full disk can leave a truncated file; serving it would pin a broken
        // thumbnail with no repair path. SOI/EOI markers catch that without decoding.
        if (!isValidJpeg(cached, len)) {
            cached.delete()
            return CacheRead.Miss
        }
        return CacheRead.Hit(
            SourceFetchResult(
                source = ImageSource(cached.toOkioPath(), FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            ),
        )
    }

    private fun isValidJpeg(file: File, len: Long): Boolean = runCatching {
        if (len < 4) return false
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(2)
            raf.readFully(head)
            raf.seek(len - 2)
            val tail = ByteArray(2)
            raf.readFully(tail)
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
                tail[0] == 0xFF.toByte() && tail[1] == 0xD9.toByte()
        }
    }.getOrDefault(false)

    private fun extractFrame(data: VideoThumb): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var descriptor: ParcelFileDescriptor? = null
        // A malformed stream can wedge the native call indefinitely while it holds one of
        // the two extraction permits; release() from another thread aborts it with an
        // exception. The lock keeps watchdog and normal teardown from double-releasing.
        val releaseLock = Any()
        fun release() {
            synchronized(releaseLock) { runCatching { retriever.release() } }
        }
        val watchdog = watchdogExecutor.schedule({ release() }, EXTRACT_TIMEOUT_S, TimeUnit.SECONDS)
        return try {
            if (data.privileged) {
                val transport = PrivilegedAccess.active
                    ?.takeIf { PrivilegedAccess.enabled && it.supportsFileDescriptors }
                    ?: return null
                descriptor = transport.openFd(data.path, write = false) ?: return null
                retriever.setDataSource(descriptor.fileDescriptor)
            } else {
                retriever.setDataSource(data.path)
            }
            // timeUs -1 = the format's representative frame. Scaled decode caps the
            // output at thumb size instead of a full video-resolution bitmap.
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    -1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_SIZE, THUMB_SIZE,
                )
            } else {
                retriever.getFrameAtTime(-1)?.let(::scaleDown)
            }
        } catch (_: Exception) {
            null
        } finally {
            watchdog.cancel(false)
            release()
            // MediaMetadataRetriever does not own the caller's descriptor.
            runCatching { descriptor?.close() }
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= THUMB_SIZE) return src
        val scale = THUMB_SIZE.toFloat() / maxDim
        val out = Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (out !== src) src.recycle()
        return out
    }

    /** Atomically publish the frame (or a zero-byte failure marker) under [target]. */
    private fun writeCache(target: File, bitmap: Bitmap?) {
        runCatching {
            val dir = target.parentFile ?: return
            pruneMaybe(dir)
            if (bitmap == null) {
                target.createNewFile()
                return
            }
            val tmp = File.createTempFile("thumb", ".tmp", dir)
            val ok = tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            // compress reports stream errors (disk full) as `false`, not an exception — a
            // truncated frame must never be published. No failure marker either: freeing
            // up space should be enough to get thumbnails back.
            if (!ok || !tmp.renameTo(target)) tmp.delete()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoThumbFetcher(context, data)
    }

    /** Without an explicit keyer a custom model has no memory-cache key at all. */
    class Key : Keyer<VideoThumb> {
        override fun key(data: VideoThumb, options: Options): String =
            "video-thumb:${data.path}:${data.mtime}:${data.size}"
    }

    companion object {
        private const val THUMB_SIZE = 256
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val NEGATIVE_TTL_MS = 60L * 60 * 1000
        private const val EXTRACT_TIMEOUT_S = 20L
        private const val PRUNE_EVERY_WRITES = 512
        private const val PRUNE_SKIP_RECENT_MS = 5L * 60 * 1000

        private val extractSemaphore = Semaphore(2)
        private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "video-thumb-watchdog").apply { isDaemon = true }
        }

        // Prune on the first write of each process, then re-check every N writes so a
        // single long session cannot overshoot the budget without bound.
        private var writesUntilPrune = 1

        private fun cacheFile(context: Context, data: VideoThumb): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${data.path}|${data.mtime}|${data.size}".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            val dir = File(context.cacheDir, "video_thumbs")
            dir.mkdirs()
            return File(dir, "$digest.jpg")
        }

        /**
         * Drop oldest entries when the cache outgrows its budget. Entries keyed by a
         * changed mtime/size or a deleted source video are orphaned, so growth is real.
         */
        @Synchronized
        private fun pruneMaybe(dir: File) {
            if (--writesUntilPrune > 0) return
            writesUntilPrune = PRUNE_EVERY_WRITES
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            val now = System.currentTimeMillis()
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE_BYTES / 2) break
                // Fresh files may back an in-flight SourceFetchResult that has not been
                // decoded yet; deleting them would error those rows.
                if (now - f.lastModified() < PRUNE_SKIP_RECENT_MS) continue
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }
}
