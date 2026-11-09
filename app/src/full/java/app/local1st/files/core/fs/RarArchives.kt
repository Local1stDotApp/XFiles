package app.local1st.files.core.fs

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.rarfile.FileHeader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The full flavor's RAR hook for [ArchiveFileSystem]: the RAR archives libarchive can't read,
 * through junrar.
 *
 * libarchive reads every other RAR in both flavors, from RAR 2.0 to RAR 5, but it can't decode
 * members packed by RAR 1.5x, nor the RAR 1.4 format ([RarVolumes.isBeyondLibarchive]). junrar
 * can. junrar is under the UnRAR licence, which F-Droid treats as non-free, so the fdroid
 * flavor's copy of this object has no fallback.
 *
 * Two of junrar's habits are worked around here. It reads a multi-volume set one volume at a
 * time, so [JoinedRarVolumes] hands it the set as one archive. And its getInputStream drops
 * extraction errors, so [ExtractedStream] streams members instead.
 */
internal object RarArchives {

    /** Whether [archiveFile] is one libarchive can't read, so this object reads it. */
    fun readsItself(archiveFile: File): Boolean = RarVolumes.isBeyondLibarchive(archiveFile)

    fun list(
        archiveFile: File,
        add: (rawName: String, isDir: Boolean, size: Long, mtime: Long, index: Int) -> Unit,
    ) {
        open(archiveFile).use { rar ->
            for ((index, header) in rar.archive.fileHeaders.withIndex()) {
                add(
                    header.fileName,
                    header.isDirectory,
                    if (header.isDirectory) -1L else header.fullUnpackSize,
                    header.mTime?.time ?: 0L,
                    index,
                )
            }
        }
    }

    /** Streams member [index] (named [rawName]). Closing the stream also closes the archive. */
    fun openEntry(archiveFile: File, rawName: String, index: Int): InputStream {
        val rar = open(archiveFile)
        try {
            val headers = rar.archive.fileHeaders
            val header = headers.getOrNull(index)?.takeIf { !it.isDirectory && it.fileName == rawName }
                ?: throw IOException("Entry not found in ${archiveFile.name}: $rawName")
            check(rar, header, archiveFile)
            // A solid member continues where the members before it left off, so those are
            // decoded (and dropped) first.
            val before = if (header.isSolid) headers.subList(0, index).filter { !it.isDirectory } else emptyList()
            return ExtractedStream(rawName, sizeOf(header), onDone = rar::closeQuietly) { sink ->
                for (previous in before) rar.archive.extractFile(previous, DISCARD)
                rar.archive.extractFile(header, sink)
            }
        } catch (e: Throwable) {
            rar.closeQuietly()
            throw e
        }
    }

    /** Streams every member in archive order, decoding the solid stream once. */
    fun forEachFile(archiveFile: File, visit: (rawName: String, index: Int, input: InputStream) -> Unit) {
        open(archiveFile).use { rar ->
            for ((index, header) in rar.archive.fileHeaders.withIndex()) {
                if (header.isDirectory) continue
                check(rar, header, archiveFile)
                val stream = ExtractedStream(header.fileName, sizeOf(header), onDone = {}) { sink ->
                    rar.archive.extractFile(header, sink)
                }
                try {
                    visit(header.fileName, index, stream)
                    // The next member's state depends on all of this one being decoded.
                    stream.copyTo(DISCARD)
                } finally {
                    stream.closeAndWait()
                }
            }
        }
    }

    private fun check(rar: OpenRar, header: FileHeader, archiveFile: File) {
        // There is no password prompt, and without a password junrar fails the member.
        if (header.isEncrypted) throw IOException("${header.fileName} is password-protected")
        if (rar.joined?.isIncomplete(header.positionInFile) == true) {
            throw IOException("${header.fileName} continues in a volume of ${archiveFile.name} that is missing")
        }
    }

    private fun sizeOf(header: FileHeader): Long = if (header.isUnpSizeUnknown) -1L else header.fullUnpackSize

    private val DISCARD = object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private class OpenRar(val archive: Archive, val joined: JoinedRarVolumes?) : Closeable {
        override fun close() = archive.close()

        fun closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // Best-effort cleanup on a failure path.
            }
        }
    }

    private fun open(archiveFile: File): OpenRar {
        val joined = JoinedRarVolumes.join(RarVolumes.volumesOf(archiveFile))
        val archive = if (joined == null) {
            Archive(archiveFile)
        } else {
            Archive(joined, ArchiveOptions.builder().build())
        }
        return OpenRar(archive, joined)
    }

    /**
     * One member's bytes. junrar extracts them on a thread of its own, as its getInputStream
     * does. But getInputStream drops extraction errors, so a CRC error or a missing volume read
     * as a short file. Here an error fails the read, and so does a member that ends short of its
     * size. Closing early stops the extraction; [onDone] then runs on the extraction thread.
     */
    private class ExtractedStream(
        private val name: String,
        private val expectedSize: Long,
        onDone: () -> Unit,
        extract: (OutputStream) -> Unit,
    ) : InputStream() {
        private val queue = ArrayBlockingQueue<Any>(QUEUED_CHUNKS)

        @Volatile
        private var closed = false
        private var chunk = EMPTY
        private var offset = 0
        private var received = 0L
        private var ended = false
        private var failure: IOException? = null

        private val worker = thread(name = "rar-extract", isDaemon = true) {
            var outcome: Any = END
            try {
                Chunker().use { extract(it) }
            } catch (t: Throwable) {
                outcome = t
            } finally {
                onDone()
            }
            hand(outcome)
        }

        /** Closes and waits for the extraction thread, before the archive is used again. */
        fun closeAndWait() {
            close()
            worker.join()
        }

        override fun read(): Int = if (fill()) chunk[offset++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, chunk.size - offset)
            System.arraycopy(chunk, offset, b, off, n)
            offset += n
            return n
        }

        override fun available(): Int = if (closed) 0 else chunk.size - offset

        override fun close() {
            if (closed) return
            closed = true
            // Wakes a read waiting on the queue. The extraction thread sees the close at its
            // next chunk and stops.
            queue.clear()
            queue.offer(CLOSED)
        }

        /** Makes [chunk] hold unread bytes; false at the member's end. */
        private fun fill(): Boolean {
            if (closed) throw IOException("Stream closed")
            failure?.let { throw it }
            if (offset < chunk.size) return true
            if (ended) return false
            val item = try {
                queue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
            return when {
                item is ByteArray -> {
                    chunk = item
                    offset = 0
                    received += item.size
                    true
                }
                item === END -> {
                    ended = true
                    if (expectedSize >= 0 && received != expectedSize) {
                        throw fail(IOException("Cannot extract $name: it ended after $received of $expectedSize bytes"))
                    }
                    false
                }
                item is Throwable -> throw fail(IOException("Cannot extract $name", item))
                else -> throw IOException("Stream closed")
            }
        }

        private fun fail(e: IOException): IOException = e.also { failure = it }

        /** Queues [item] for the reader; false once the stream is closed. */
        private fun hand(item: Any): Boolean {
            while (!closed) {
                try {
                    if (queue.offer(item, HAND_WAIT_MS, TimeUnit.MILLISECONDS)) return true
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return false
        }

        /** Collects what junrar writes into chunks for the reader. */
        private inner class Chunker : OutputStream() {
            private var buffer = ByteArray(CHUNK_SIZE)
            private var size = 0

            override fun write(b: Int) {
                if (size == buffer.size) push()
                buffer[size++] = b.toByte()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var from = off
                var left = len
                while (left > 0) {
                    if (size == buffer.size) push()
                    val n = minOf(left, buffer.size - size)
                    System.arraycopy(b, from, buffer, size, n)
                    size += n
                    from += n
                    left -= n
                }
            }

            override fun close() {
                if (size > 0) push()
            }

            private fun push() {
                val full = size == buffer.size
                if (!hand(if (full) buffer else buffer.copyOf(size))) throw IOException("Stream closed")
                if (full) buffer = ByteArray(CHUNK_SIZE)
                size = 0
            }
        }

        private companion object {
            const val CHUNK_SIZE = 64 * 1024
            const val QUEUED_CHUNKS = 4
            const val HAND_WAIT_MS = 100L
            val EMPTY = ByteArray(0)
            val END = Any()
            val CLOSED = Any()
        }
    }
}
