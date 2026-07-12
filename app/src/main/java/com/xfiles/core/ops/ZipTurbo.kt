package com.xfiles.core.ops

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UncheckedIOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ParallelScatterZipCreator
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.parallel.FileBasedScatterGatherBackingStore
import org.apache.commons.compress.parallel.InputStreamSupplier
import org.apache.commons.compress.parallel.ScatterGatherBackingStoreSupplier

/**
 * Maximum-throughput zip create/extract that saturates all CPU cores.
 *
 *  - **Create** uses commons-compress [ParallelScatterZipCreator]: every entry deflates on
 *    its own worker thread into a temp scatter store, then the results are gathered into the
 *    final zip sequentially. Already-compressed payloads (jpg/mp4/…) are STORED to skip
 *    pointless deflate work.
 *  - **Extract** gives each worker its own [ZipFile] handle (independent random-access reads,
 *    no lock contention) and pulls entries off a shared queue, so decompression + write IO run
 *    fully in parallel.
 */
object ZipTurbo {

    /** Worker threads: bounded so we don't oversubscribe tiny or huge core counts. */
    private val WORKERS = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    private const val EXTRACT_BUFFER = 256 * 1024

    /** Payload types where deflate rarely helps; STORE them to save CPU. */
    private val INCOMPRESSIBLE = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp",
        "mp3", "aac", "ogg", "oga", "opus", "flac", "m4a",
        "zip", "jar", "apk", "apks", "7z", "gz", "tgz", "xz", "bz2", "rar", "zst",
    )

    class ZipSource(
        val name: String,
        val isDir: Boolean,
        val mtime: Long,
        /** Opens a fresh stream of the entry's raw bytes. Called on a worker thread. */
        val open: () -> InputStream,
    )

    fun methodFor(name: String): Int =
        if (name.substringAfterLast('.', "").lowercase() in INCOMPRESSIBLE) {
            ZipEntry.STORED
        } else {
            ZipEntry.DEFLATED
        }

    /**
     * Builds a zip into [out] from [sources], deflating entries across [WORKERS] threads.
     * [onBytes] receives the cumulative count of uncompressed bytes read (for progress);
     * [isCancelled] is polled and aborts the whole operation when it returns true.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun createZip(
        sources: List<ZipSource>,
        out: OutputStream,
        cacheDir: File,
        level: Int = Deflater.DEFAULT_COMPRESSION,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val executor = Executors.newFixedThreadPool(WORKERS)
        val tempCounter = AtomicInteger()
        val backing = ScatterGatherBackingStoreSupplier {
            FileBasedScatterGatherBackingStore(
                File(cacheDir, "xf-scatter-${tempCounter.incrementAndGet()}-${System.nanoTime()}.tmp"),
            )
        }
        val creator = ParallelScatterZipCreator(executor, backing, level)
        val readTotal = AtomicLong(0)

        try {
            for (src in sources) {
                if (isCancelled()) throw InterruptedException("cancelled")
                val entry = ZipArchiveEntry(if (src.isDir) src.name.trimEnd('/') + "/" else src.name)
                if (src.mtime > 0) entry.time = src.mtime
                if (src.isDir) {
                    entry.method = ZipEntry.STORED
                    entry.size = 0
                    creator.addArchiveEntry(entry, InputStreamSupplier { EMPTY_STREAM })
                } else {
                    entry.method = methodFor(src.name)
                    creator.addArchiveEntry(entry, supplierFor(src, readTotal, onBytes, isCancelled))
                }
            }
            val zos = ZipArchiveOutputStream(out)
            creator.writeTo(zos)
            zos.close()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun supplierFor(
        src: ZipSource,
        readTotal: AtomicLong,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) = InputStreamSupplier {
        try {
            CountingCancellableStream(src.open(), readTotal, onBytes, isCancelled)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    /**
     * Extracts a zip/jar/apk into [destDir] using [WORKERS] independent [ZipFile] handles.
     * [onBytes] receives cumulative extracted bytes; [isCancelled] aborts all workers.
     * Guards against Zip-Slip (entries escaping [destDir]).
     */
    @Throws(IOException::class)
    fun extractZip(
        archiveFile: File,
        destDir: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val destRoot = destDir.canonicalPath
        val fileEntries = ArrayList<ZipEntry>()

        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = File(destDir, entry.name)
                val canonical = target.canonicalPath
                if (canonical != destRoot && !canonical.startsWith(destRoot + File.separator)) {
                    throw IOException("Blocked path traversal in archive: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    fileEntries += entry
                }
            }
        }

        val queue = ConcurrentLinkedQueue(fileEntries)
        val total = AtomicLong(0)
        val handles = ArrayList<ZipFile>(WORKERS)
        val pool = Executors.newFixedThreadPool(WORKERS)
        var firstError: Throwable? = null
        val errorLock = Any()

        try {
            val futures = (0 until WORKERS).map {
                val handle = ZipFile(archiveFile)
                handles += handle
                pool.submit {
                    val buffer = ByteArray(EXTRACT_BUFFER)
                    while (!isCancelled()) {
                        val entry = queue.poll() ?: break
                        val target = File(destDir, entry.name)
                        try {
                            handle.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { fos ->
                                    while (true) {
                                        if (isCancelled()) return@submit
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        fos.write(buffer, 0, n)
                                        onBytes(total.addAndGet(n.toLong()))
                                    }
                                }
                            }
                            if (entry.time >= 0) target.setLastModified(entry.time)
                        } catch (t: Throwable) {
                            synchronized(errorLock) { if (firstError == null) firstError = t }
                            return@submit
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
            handles.forEach { runCatching { it.close() } }
        }
        firstError?.let { throw IOException("Extract failed: ${it.message}", it) }
    }

    /** Sum of uncompressed sizes across all file entries (for progress totals). */
    fun totalUncompressed(archiveFile: File): Long {
        var total = 0L
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.size > 0) total += entry.size
            }
        }
        return total
    }

    private val EMPTY_STREAM: InputStream get() = object : InputStream() {
        override fun read(): Int = -1
    }

    /** Wraps a source stream to count bytes read and to abort promptly on cancellation. */
    private class CountingCancellableStream(
        stream: InputStream,
        private val readTotal: AtomicLong,
        private val onBytes: (Long) -> Unit,
        private val isCancelled: () -> Boolean,
    ) : FilterInputStream(stream) {
        override fun read(): Int {
            if (isCancelled()) throw IOException("cancelled")
            val b = super.read()
            if (b >= 0) onBytes(readTotal.incrementAndGet())
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isCancelled()) throw IOException("cancelled")
            val n = super.read(b, off, len)
            if (n > 0) onBytes(readTotal.addAndGet(n.toLong()))
            return n
        }
    }
}
