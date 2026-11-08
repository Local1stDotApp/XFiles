package app.local1st.files.core.ops

import app.local1st.files.core.archive.ArchivePaths
import app.local1st.files.core.archive.NativeZip
import app.local1st.files.core.archive.ZipWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * Maximum-throughput zip create/extract that saturates all CPU cores.
 *
 *  - **Create** deflates entries on worker threads, a few entries ahead of the writer, which
 *    appends them in order. Already-compressed payloads (jpg/mp4/…) are STORED to skip pointless
 *    deflate work: a worker only checksums them, and the writer copies them straight from the
 *    source, so they are never staged anywhere. Deflated output waits in memory, or in [cacheDir]
 *    past [SPOOL_MEMORY] per entry.
 *  - **Extract** reads the zip with libxfarchive's native reader: one parse of the central
 *    directory, then one stream per worker, each reading its own entries at their own offsets,
 *    so decompression and write IO run fully in parallel.
 */
object ZipTurbo {

    /** Worker threads: bounded so we don't oversubscribe tiny or huge core counts. */
    private val WORKERS = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    /** Entries prepared ahead of the writer; bounds memory and temp space. */
    private val LOOKAHEAD = WORKERS + 2

    /** Deflated bytes one entry keeps in memory before spilling to a temp file. */
    private const val SPOOL_MEMORY = 1 shl 20

    private const val EXTRACT_BUFFER = 256 * 1024

    /** Payload types where deflate rarely helps; STORE them to save CPU. */
    private val INCOMPRESSIBLE = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp",
        "mp3", "aac", "ogg", "oga", "opus", "flac", "m4a",
        "zip", "jar", "apk", "aab", "apks", "apkm", "xapk", "7z", "gz", "tgz", "xz", "bz2", "rar", "zst",
    )

    class ZipSource(
        val name: String,
        val isDir: Boolean,
        val mtime: Long,
        /** Opens a fresh stream of the entry's raw bytes. Called on a worker thread, maybe twice. */
        val open: () -> InputStream,
        /** Uncompressed size if known, else -1; only decides whether a streamed entry needs Zip64. */
        val size: Long = -1L,
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
        val done = AtomicLong(0)
        val progress = { n: Int -> onBytes(done.addAndGet(n.toLong())) }
        val spools = Collections.synchronizedList(ArrayList<Spool>())
        val pool = Executors.newFixedThreadPool(WORKERS)
        val pending = ArrayDeque<Future<Prepared>>()
        try {
            val zip = ZipWriter(out)
            var next = 0
            for (i in sources.indices) {
                while (next < sources.size && pending.size < LOOKAHEAD) {
                    val src = sources[next++]
                    pending.addLast(
                        pool.submit(Callable { prepare(src, level, cacheDir, spools, progress, isCancelled) }),
                    )
                }
                if (isCancelled()) throw InterruptedException("cancelled")
                val prepared = try {
                    pending.removeFirst().get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                try {
                    write(zip, prepared, progress, isCancelled)
                } finally {
                    prepared.spool?.delete()
                }
            }
            zip.finish()
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            // Entries prepared but never written (cancel, failure) leave spools behind.
            synchronized(spools) { spools.forEach { it.delete() } }
        }
    }

    /**
     * Single-threaded streaming zip that needs no temp space. Falls back here when the parallel
     * path could need more of [createZip]'s cacheDir than is free.
     */
    @Throws(IOException::class)
    fun createZipSequential(
        sources: List<ZipSource>,
        out: OutputStream,
        level: Int = Deflater.DEFAULT_COMPRESSION,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val done = AtomicLong(0)
        val progress = { n: Int -> onBytes(done.addAndGet(n.toLong())) }
        val zip = ZipWriter(out)
        for (src in sources) {
            if (isCancelled()) throw InterruptedException("cancelled")
            when {
                src.isDir -> zip.addDirectory(src.name, src.mtime)
                methodFor(src.name) == ZipEntry.STORED -> {
                    val (crc, size) = checksum(src, isCancelled)
                    writeStored(zip, src, crc, size, progress, isCancelled)
                }
                else -> src.open().use { input ->
                    zip.addDeflatedStream(src.name, src.mtime, src.size, input, level, progress, isCancelled)
                }
            }
        }
        zip.finish()
    }

    /** One entry, ready for the writer. */
    private class Prepared(
        val source: ZipSource,
        val method: Int,
        val crc: Long,
        val size: Long,
        /** Deflated data for DEFLATED entries; null for directories and STORED entries. */
        val spool: Spool?,
    )

    private fun prepare(
        src: ZipSource,
        level: Int,
        cacheDir: File,
        spools: MutableList<Spool>,
        progress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): Prepared {
        if (src.isDir) return Prepared(src, ZipEntry.STORED, 0, 0, null)
        if (methodFor(src.name) == ZipEntry.STORED) {
            // Checksummed now, copied from the source by the writer: nothing to stage.
            val (crc, size) = checksum(src, isCancelled)
            return Prepared(src, ZipEntry.STORED, crc, size, null)
        }
        val spool = Spool(cacheDir).also { spools += it }
        val crc = CRC32()
        var size = 0L
        src.open().use { input ->
            spool.use { output ->
                ZipWriter.deflate(input, output, level, crc, onRead = {
                    size += it
                    progress(it)
                }, isCancelled)
            }
        }
        return Prepared(src, ZipEntry.DEFLATED, crc.value, size, spool)
    }

    private fun write(zip: ZipWriter, p: Prepared, progress: (Int) -> Unit, isCancelled: () -> Boolean) {
        val src = p.source
        when {
            src.isDir -> zip.addDirectory(src.name, src.mtime)
            p.method == ZipEntry.STORED -> writeStored(zip, src, p.crc, p.size, progress, isCancelled)
            else -> {
                val spool = p.spool!!
                zip.addEntry(src.name, src.mtime, ZipEntry.DEFLATED, p.crc, p.size, spool.size) { spool.writeTo(it) }
            }
        }
    }

    private fun writeStored(
        zip: ZipWriter,
        src: ZipSource,
        crc: Long,
        size: Long,
        progress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        zip.addEntry(src.name, src.mtime, ZipEntry.STORED, crc, size, size) { out ->
            val check = CRC32()
            val buffer = ByteArray(EXTRACT_BUFFER)
            src.open().use { input ->
                while (true) {
                    if (isCancelled()) throw InterruptedException("cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    check.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    progress(n)
                }
            }
            if (check.value != crc) throw IOException("${src.name} changed while it was being zipped")
        }
    }

    /** CRC-32 and length of a source's bytes. */
    private fun checksum(src: ZipSource, isCancelled: () -> Boolean): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        val buffer = ByteArray(EXTRACT_BUFFER)
        src.open().use { input ->
            while (true) {
                if (isCancelled()) throw InterruptedException("cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return crc.value to size
    }

    /** Holds one entry's deflated bytes: in memory up to [SPOOL_MEMORY], then in a temp file. */
    private class Spool(private val cacheDir: File) : OutputStream() {
        private val memory = ByteArrayOutputStream()
        private var file: File? = null
        private var fileOut: OutputStream? = null
        var size = 0L
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (fileOut == null && memory.size() + len > SPOOL_MEMORY) {
                val f = File.createTempFile("xf-zip-", ".tmp", cacheDir)
                file = f
                fileOut = FileOutputStream(f).buffered(EXTRACT_BUFFER)
            }
            val target = fileOut
            if (target != null) target.write(b, off, len) else memory.write(b, off, len)
            size += len
        }

        override fun close() {
            fileOut?.close()
        }

        fun writeTo(out: OutputStream) {
            memory.writeTo(out)
            file?.inputStream()?.use { it.copyTo(out, EXTRACT_BUFFER) }
        }

        fun delete() {
            runCatching { fileOut?.close() }
            file?.delete()
        }
    }

    /**
     * Extracts a zip/jar/apk into [destDir] with [WORKERS] native entry streams.
     * [onBytes] receives cumulative extracted bytes; [isCancelled] aborts all workers.
     * Entry paths are normalized like the archive browser shows them, so nothing lands outside
     * [destDir] (Zip-Slip); a destination that still resolves outside, through a link, fails.
     */
    @Throws(IOException::class)
    fun extractZip(
        archiveFile: File,
        destDir: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val destRoot = destDir.canonicalPath
        NativeZip.open(archiveFile).use { zip ->
            val layout = ArchivePaths.layout(zip.entries.map { ArchivePaths.Item(it.name, it.isDirectory) })
            // Folders first, parents before children (the layout's order), each checked before
            // it is made. Then every file's folder exists when the file is checked: resolving a
            // path is quick when its parent exists, and costs a lookup per missing level when it
            // doesn't, each a round trip to MediaProvider on shared storage.
            for (dir in layout.directories) {
                if (isCancelled()) throw InterruptedException("Extract cancelled")
                inside(destDir, destRoot, dir, dir).mkdir()
            }
            val files = ArrayList<Pair<NativeZip.Entry, File>>()
            for ((index, entry) in zip.entries.withIndex()) {
                val path = layout.fileAt(index) ?: continue
                if (isCancelled()) throw InterruptedException("Extract cancelled")
                files += entry to inside(destDir, destRoot, path, entry.name)
            }

            val queue = ConcurrentLinkedQueue(files)
            val total = AtomicLong(0)
            val pool = Executors.newFixedThreadPool(WORKERS)
            var firstError: Throwable? = null
            val errorLock = Any()
            try {
                val futures = (0 until WORKERS).map {
                    pool.submit {
                        val buffer = ByteArray(EXTRACT_BUFFER)
                        while (!isCancelled()) {
                            val (entry, target) = queue.poll() ?: break
                            try {
                                zip.openEntry(entry.index).use { input ->
                                    FileOutputStream(target).use { fos ->
                                        while (true) {
                                            if (isCancelled()) break
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            fos.write(buffer, 0, n)
                                            onBytes(total.addAndGet(n.toLong()))
                                        }
                                    }
                                }
                                if (isCancelled()) {
                                    runCatching { target.delete() } // drop the partial file
                                    return@submit
                                }
                                if (entry.time > 0) target.setLastModified(entry.time)
                            } catch (t: Throwable) {
                                runCatching { target.delete() }
                                synchronized(errorLock) { if (firstError == null) firstError = t }
                                return@submit
                            }
                        }
                    }
                }
                futures.forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }
            // Surface cancellation to the caller so the op is reported CANCELLED, not DONE.
            if (isCancelled()) throw InterruptedException("Extract cancelled")
            firstError?.let { throw IOException("Extract failed: ${it.message}", it) }
        }
    }

    /**
     * [path] under [destDir], refusing one whose real path leaves [destRoot], through a link
     * already in the destination say. [shownAs] names it in the error.
     */
    private fun inside(destDir: File, destRoot: String, path: String, shownAs: String): File {
        val target = File(destDir, path)
        if (!target.canonicalPath.startsWith(destRoot + File.separator)) {
            throw IOException("Blocked path traversal in archive: $shownAs")
        }
        return target
    }

    /** Sum of uncompressed sizes across all file entries (for progress totals). */
    fun totalUncompressed(archiveFile: File): Long =
        NativeZip.open(archiveFile).use { zip ->
            zip.entries.sumOf { if (!it.isDirectory && it.size > 0) it.size else 0L }
        }
}
