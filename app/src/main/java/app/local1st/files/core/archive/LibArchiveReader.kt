package app.local1st.files.core.archive

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reads a 7z, RAR, tar, cpio, ar or ISO 9660 archive, or a single compressed stream, front to
 * back through libxfarchive's libarchive. Every decompression filter is enabled, so a tar
 * named .tgz that is really xz-compressed still opens.
 *
 * One thread at a time: walk with [next] and read the current entry through [entryStream].
 * Moving on skips whatever is left of the entry.
 */
internal class LibArchiveReader private constructor(private var handle: Long) : Closeable {

    class Entry(
        /** Position among all the archive's entries, counting from 0. */
        val ordinal: Int,
        /** The stored path, empty when the format allows none (a lone 7z stream). */
        val path: String,
        val type: Int,
        /** -1 when the format doesn't say. */
        val size: Long,
        /** Epoch millis, or [Long.MIN_VALUE] when unknown. */
        val mtime: Long,
        val isHardLink: Boolean,
        val isEncrypted: Boolean,
    ) {
        val isDirectory: Boolean get() = type == NativeArchive.TYPE_DIR

        /**
         * Regular files, which carry their own bytes. Links and devices are left out, as before:
         * a hard link has no data of its own and would open empty.
         */
        val isFile: Boolean get() = (type == NativeArchive.TYPE_FILE || type == 0) && !isHardLink
    }

    private val info = LongArray(4)
    private var ordinal = 0
    private var current: Entry? = null

    /** The decompression filter of the outermost layer; 0 when uncompressed. */
    val filter: Int
        @Synchronized get() = NativeArchive.filter(requireOpen())

    /** Moves to the next entry; null at the end. */
    @Synchronized
    fun next(): Entry? {
        val path = NativeArchive.next(requireOpen(), info) ?: return null.also { current = null }
        return Entry(
            ordinal = ordinal++,
            path = ArchiveNames.decode(path),
            type = info[0].toInt(),
            size = info[1],
            mtime = info[2],
            isHardLink = info[3] and 1L != 0L,
            isEncrypted = info[3] and 2L != 0L,
        ).also { current = it }
    }

    @Synchronized
    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        val entry = current ?: return -1
        if (entry.isEncrypted) throw IOException("${entry.path} is password-protected")
        return NativeArchive.read(requireOpen(), b, off, len)
    }

    /** The current entry's bytes. Closing the stream leaves the reader open. */
    fun entryStream(): InputStream = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = this@LibArchiveReader.read(b, off, len)
    }

    /**
     * The current entry's bytes; closing the stream closes the reader. For handing a single
     * entry out of an archive opened just for it.
     */
    fun detachEntryStream(): InputStream = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = this@LibArchiveReader.read(b, off, len)
        override fun close() = this@LibArchiveReader.close()
    }

    @Synchronized
    override fun close() {
        val toClose = handle
        if (toClose == 0L) return
        handle = 0L
        NativeArchive.close(toClose)
    }

    private fun requireOpen(): Long = handle.takeIf { it != 0L } ?: throw IOException("Archive closed")

    companion object {
        /**
         * Opens [volumes] (several only for a multi-volume RAR) with the FORMAT_* mask [formats].
         * [forData] is for reading entries rather than listing them: in a solid RAR 4 archive,
         * the members skipped on the way are then decoded, as the ones after them need.
         */
        fun open(volumes: List<File>, formats: Int, forData: Boolean): LibArchiveReader {
            NativeArchive.ensureLoaded()
            val paths = Array(volumes.size) { volumes[it].path.toByteArray() }
            return LibArchiveReader(NativeArchive.open(paths, formats, forData))
        }
    }
}
