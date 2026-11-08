package app.local1st.files.core.archive

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * Writes a zip archive front to back into any [OutputStream], so it works for streams that
 * can't seek (document providers).
 *
 * Entries are stored or deflated. Names are UTF-8 with the language encoding flag set, times are
 * DOS times plus an extended timestamp (UTC seconds), and Zip64 records are added only where a
 * size, offset or count needs them. [finish] writes the central directory.
 */
internal class ZipWriter(out: OutputStream) : Closeable {

    private val out = CountingStream(BufferedOutputStream(out, BUFFER_SIZE))
    private val central = ByteArrayOutputStream()
    private var count = 0L
    private var finished = false
    private val calendar = Calendar.getInstance()

    fun addDirectory(name: String, mtime: Long) {
        val dirName = name.trimEnd('/') + "/"
        writeKnown(dirName, mtime, ZipEntry.STORED, crc = 0, size = 0, compressedSize = 0, isDir = true) {}
    }

    /**
     * An entry whose CRC and sizes are known up front. [writeData] must write exactly
     * [compressedSize] bytes: the raw bytes for STORED, raw deflate data for DEFLATED.
     */
    fun addEntry(
        name: String,
        mtime: Long,
        method: Int,
        crc: Long,
        size: Long,
        compressedSize: Long,
        writeData: (OutputStream) -> Unit,
    ) = writeKnown(name, mtime, method, crc, size, compressedSize, isDir = false, writeData)

    /**
     * A deflated entry whose sizes are only known at its end, so they follow the data in a data
     * descriptor. [sizeHint] (-1 if unknown) decides whether the entry needs Zip64 sizes.
     */
    fun addDeflatedStream(
        name: String,
        mtime: Long,
        sizeHint: Long,
        input: InputStream,
        level: Int,
        onRead: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val offset = out.count
        val zip64 = sizeHint < 0 || sizeHint >= ZIP64_SIZE_THRESHOLD
        val localExtra = ByteArrayOutputStream().apply {
            if (zip64) writeZip64Sizes(this, 0, 0)
            writeTimestamp(this, mtime)
        }.toByteArray()
        val flags = FLAG_UTF8 or FLAG_DATA_DESCRIPTOR
        val dos = javaToDos(mtime)
        writeLocalHeader(
            version = if (zip64) VERSION_ZIP64 else VERSION_DEFLATE,
            flags = flags,
            method = ZipEntry.DEFLATED,
            dos = dos,
            crc = 0,
            compressedSize = if (zip64) MAX_32 else 0,
            size = if (zip64) MAX_32 else 0,
            name = nameBytes,
            extra = localExtra,
        )
        val crc = CRC32()
        var size = 0L
        val before = out.count
        deflate(input, out, level, crc, onRead = {
            size += it
            onRead(it)
        }, isCancelled)
        val compressedSize = out.count - before
        if (!zip64 && (size >= MAX_32 || compressedSize >= MAX_32)) {
            throw IOException("$name grew past 4 GB while it was being zipped")
        }
        writeInt(out, SIG_DATA_DESCRIPTOR)
        writeInt(out, crc.value)
        if (zip64) {
            writeLong(out, compressedSize)
            writeLong(out, size)
        } else {
            writeInt(out, compressedSize)
            writeInt(out, size)
        }
        addCentral(
            nameBytes, mtime, dos, ZipEntry.DEFLATED, flags, crc.value, size, compressedSize, offset,
            isDir = false,
        )
    }

    /** Writes the central directory. The underlying stream stays open until [close]. */
    fun finish() {
        if (finished) return
        finished = true
        val cdOffset = out.count
        central.writeTo(out)
        val cdSize = out.count - cdOffset
        if (count >= 0xFFFF || cdSize >= MAX_32 || cdOffset >= MAX_32) {
            val zip64End = out.count
            writeInt(out, SIG_ZIP64_END)
            writeLong(out, 44)
            writeShort(out, VERSION_ZIP64)
            writeShort(out, VERSION_ZIP64)
            writeInt(out, 0)
            writeInt(out, 0)
            writeLong(out, count)
            writeLong(out, count)
            writeLong(out, cdSize)
            writeLong(out, cdOffset)
            writeInt(out, SIG_ZIP64_LOCATOR)
            writeInt(out, 0)
            writeLong(out, zip64End)
            writeInt(out, 1)
        }
        writeInt(out, SIG_END)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, minOf(count, 0xFFFF).toInt())
        writeShort(out, minOf(count, 0xFFFF).toInt())
        writeInt(out, minOf(cdSize, MAX_32))
        writeInt(out, minOf(cdOffset, MAX_32))
        writeShort(out, 0)
        out.flush()
    }

    /** Finishes the archive and closes the underlying stream. */
    override fun close() {
        try {
            finish()
        } finally {
            out.close()
        }
    }

    private fun writeKnown(
        name: String,
        mtime: Long,
        method: Int,
        crc: Long,
        size: Long,
        compressedSize: Long,
        isDir: Boolean,
        writeData: (OutputStream) -> Unit,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val offset = out.count
        val zip64 = size >= MAX_32 || compressedSize >= MAX_32
        val localExtra = ByteArrayOutputStream().apply {
            if (zip64) writeZip64Sizes(this, size, compressedSize)
            writeTimestamp(this, mtime)
        }.toByteArray()
        val dos = javaToDos(mtime)
        writeLocalHeader(
            version = when {
                zip64 -> VERSION_ZIP64
                method == ZipEntry.DEFLATED || isDir -> VERSION_DEFLATE
                else -> VERSION_STORED
            },
            flags = FLAG_UTF8,
            method = method,
            dos = dos,
            crc = crc,
            compressedSize = if (zip64) MAX_32 else compressedSize,
            size = if (zip64) MAX_32 else size,
            name = nameBytes,
            extra = localExtra,
        )
        val before = out.count
        writeData(out)
        if (out.count - before != compressedSize) {
            throw IOException("$name changed while it was being zipped")
        }
        addCentral(nameBytes, mtime, dos, method, FLAG_UTF8, crc, size, compressedSize, offset, isDir)
    }

    private fun writeLocalHeader(
        version: Int,
        flags: Int,
        method: Int,
        dos: Long,
        crc: Long,
        compressedSize: Long,
        size: Long,
        name: ByteArray,
        extra: ByteArray,
    ) {
        writeInt(out, SIG_LOCAL)
        writeShort(out, version)
        writeShort(out, flags)
        writeShort(out, method)
        writeInt(out, dos)
        writeInt(out, crc)
        writeInt(out, compressedSize)
        writeInt(out, size)
        writeShort(out, name.size)
        writeShort(out, extra.size)
        out.write(name)
        out.write(extra)
    }

    private fun addCentral(
        name: ByteArray,
        mtime: Long,
        dos: Long,
        method: Int,
        flags: Int,
        crc: Long,
        size: Long,
        compressedSize: Long,
        offset: Long,
        isDir: Boolean,
    ) {
        val zip64Size = size >= MAX_32
        val zip64Compressed = compressedSize >= MAX_32
        val zip64Offset = offset >= MAX_32
        val zip64 = zip64Size || zip64Compressed || zip64Offset
        val extra = ByteArrayOutputStream().apply {
            if (zip64) {
                writeShort(this, EXTRA_ZIP64)
                writeShort(this, 8 * (zip64Size.toInt() + zip64Compressed.toInt() + zip64Offset.toInt()))
                if (zip64Size) writeLong(this, size)
                if (zip64Compressed) writeLong(this, compressedSize)
                if (zip64Offset) writeLong(this, offset)
            }
            writeTimestamp(this, mtime)
        }.toByteArray()
        val version = when {
            zip64 -> VERSION_ZIP64
            method == ZipEntry.DEFLATED || isDir -> VERSION_DEFLATE
            else -> VERSION_STORED
        }
        val c = central
        writeInt(c, SIG_CENTRAL)
        writeShort(c, version) // made by: MS-DOS, as Commons Compress wrote it
        writeShort(c, version)
        writeShort(c, flags)
        writeShort(c, method)
        writeInt(c, dos)
        writeInt(c, crc)
        writeInt(c, if (zip64Compressed) MAX_32 else compressedSize)
        writeInt(c, if (zip64Size) MAX_32 else size)
        writeShort(c, name.size)
        writeShort(c, extra.size)
        writeShort(c, 0) // comment
        writeShort(c, 0) // disk
        writeShort(c, 0) // internal attributes
        writeInt(c, if (isDir) MSDOS_DIRECTORY else 0L)
        writeInt(c, if (zip64Offset) MAX_32 else offset)
        c.write(name)
        c.write(extra)
        count++
    }

    private fun writeZip64Sizes(to: OutputStream, size: Long, compressedSize: Long) {
        writeShort(to, EXTRA_ZIP64)
        writeShort(to, 16)
        writeLong(to, size)
        writeLong(to, compressedSize)
    }

    /** Info-ZIP's extended timestamp: the modification time in UTC seconds. */
    private fun writeTimestamp(to: OutputStream, mtime: Long) {
        val seconds = Math.floorDiv(mtime, 1000L)
        if (mtime <= 0 || seconds > Int.MAX_VALUE) return
        writeShort(to, EXTRA_TIMESTAMP)
        writeShort(to, 5)
        to.write(1)
        writeInt(to, seconds)
    }

    /** The DOS date and time of [time] in the device's time zone, clamped to 1980–2107. */
    private fun javaToDos(time: Long): Long {
        calendar.timeInMillis = time
        val year = calendar.get(Calendar.YEAR)
        if (time <= 0 || year < 1980) return DOS_1980
        if (year > 2107) return DOS_2107
        return ((year - 1980).toLong() shl 25) or
            ((calendar.get(Calendar.MONTH) + 1).toLong() shl 21) or
            (calendar.get(Calendar.DAY_OF_MONTH).toLong() shl 16) or
            (calendar.get(Calendar.HOUR_OF_DAY).toLong() shl 11) or
            (calendar.get(Calendar.MINUTE).toLong() shl 5) or
            (calendar.get(Calendar.SECOND).toLong() shr 1)
    }

    /** Counts what reaches the archive, for entry offsets. */
    private class CountingStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    companion object {
        const val BUFFER_SIZE = 256 * 1024

        /**
         * Deflates [input] into [output] as raw deflate data, feeding [crc] with what was read.
         * [onRead] gets each chunk's length; [isCancelled] is polled between chunks.
         */
        fun deflate(
            input: InputStream,
            output: OutputStream,
            level: Int,
            crc: CRC32,
            onRead: (Int) -> Unit,
            isCancelled: () -> Boolean,
        ) {
            val deflater = Deflater(level, true)
            val inBuf = ByteArray(64 * 1024)
            val outBuf = ByteArray(64 * 1024)
            try {
                while (true) {
                    if (isCancelled()) throw IOException("cancelled")
                    val n = input.read(inBuf)
                    if (n < 0) break
                    if (n == 0) continue
                    crc.update(inBuf, 0, n)
                    onRead(n)
                    deflater.setInput(inBuf, 0, n)
                    while (!deflater.needsInput()) {
                        val m = deflater.deflate(outBuf)
                        if (m > 0) output.write(outBuf, 0, m)
                    }
                }
                deflater.finish()
                while (!deflater.finished()) {
                    val m = deflater.deflate(outBuf)
                    if (m > 0) output.write(outBuf, 0, m)
                }
            } finally {
                deflater.end()
            }
        }

        private const val SIG_LOCAL = 0x04034b50L
        private const val SIG_CENTRAL = 0x02014b50L
        private const val SIG_DATA_DESCRIPTOR = 0x08074b50L
        private const val SIG_END = 0x06054b50L
        private const val SIG_ZIP64_END = 0x06064b50L
        private const val SIG_ZIP64_LOCATOR = 0x07064b50L
        private const val EXTRA_ZIP64 = 0x0001
        private const val EXTRA_TIMESTAMP = 0x5455
        private const val FLAG_DATA_DESCRIPTOR = 0x0008
        private const val FLAG_UTF8 = 0x0800
        private const val VERSION_STORED = 10
        private const val VERSION_DEFLATE = 20
        private const val VERSION_ZIP64 = 45
        private const val MSDOS_DIRECTORY = 0x10L
        private const val MAX_32 = 0xFFFFFFFFL

        /** Streamed entries this large might pass 4 GB once deflate adds its framing. */
        private const val ZIP64_SIZE_THRESHOLD = 0xFF000000L
        private const val DOS_1980 = (1L shl 21) or (1L shl 16)
        private const val DOS_2107 = (127L shl 25) or (12L shl 21) or (31L shl 16) or
            (23L shl 11) or (59L shl 5) or 29L

        private fun Boolean.toInt() = if (this) 1 else 0

        private fun writeShort(to: OutputStream, v: Int) {
            to.write(v and 0xFF)
            to.write((v ushr 8) and 0xFF)
        }

        private fun writeInt(to: OutputStream, v: Long) {
            writeShort(to, (v and 0xFFFF).toInt())
            writeShort(to, ((v ushr 16) and 0xFFFF).toInt())
        }

        private fun writeLong(to: OutputStream, v: Long) {
            writeInt(to, v and MAX_32)
            writeInt(to, v ushr 32)
        }
    }
}
