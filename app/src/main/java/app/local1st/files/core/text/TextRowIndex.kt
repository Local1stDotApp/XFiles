package app.local1st.files.core.text

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Row content longer than this is broken up, so one absurd line can still be laid out.
 *
 * A hard ceiling, not a preference: unwrapped, a row is laid out at its full width, and Compose
 * cannot represent a layout dimension over 262143 px — one past that throws rather than clipping.
 * At the viewer's monospace body size that is around 9900 characters on a normal screen and half
 * that at the largest font scale, so 4096 is the widest round number that survives any of them.
 * A line longer than this arrives as several rows; [lineCount] still counts it once.
 */
const val MAX_ROW_BYTES = 4096

private const val SCAN_BUFFER = 1 shl 18
private const val READ_BUFFER = 1 shl 16
private const val CHECK_EVERY_ROWS = 4096
private const val PUBLISH_NANOS = 100_000_000L
private const val NEWLINE = '\n'.code.toByte()
private const val CARRIAGE_RETURN = '\r'.code.toByte()

/** Bytes behind a text document, readable at any offset from any thread. */
interface ByteWindow : Closeable {
    /** Total bytes, fixed when the window is opened. */
    val size: Long

    /**
     * Reads up to [count] bytes at [offset] into [dest] starting at [destOffset];
     * returns how many were read, 0 at end of input.
     */
    @Throws(IOException::class)
    fun read(offset: Long, dest: ByteArray, destOffset: Int, count: Int): Int
}

/** A local file, paged through a single shared handle. */
class FileByteWindow(file: File) : ByteWindow {
    private val handle = RandomAccessFile(file, "r")

    // Fixed at open: a row already on screen must keep meaning what it meant even if the file grows.
    override val size: Long = handle.length()

    override fun read(offset: Long, dest: ByteArray, destOffset: Int, count: Int): Int {
        if (offset >= size) return 0
        val want = minOf(count.toLong(), size - offset).toInt()
        synchronized(handle) {
            handle.seek(offset)
            var read = 0
            while (read < want) {
                val n = handle.read(dest, destOffset + read, want - read)
                if (n < 0) break
                read += n
            }
            return read
        }
    }

    // Holds the same monitor as [read]: cancelling a coroutine cannot interrupt a blocking read, so
    // closing without it would pull the descriptor out from under a reader still inside one.
    override fun close() = synchronized(handle) { handle.close() }
}

/**
 * Bytes already in memory: a stream's leading window, or decoded binary XML. Only the first
 * [length] bytes are shown, so a reader that over-read to detect truncation need not copy.
 */
class ArrayByteWindow(private val bytes: ByteArray, length: Int = bytes.size) : ByteWindow {
    override val size: Long = length.coerceIn(0, bytes.size).toLong()

    override fun read(offset: Long, dest: ByteArray, destOffset: Int, count: Int): Int {
        if (offset >= size) return 0
        val n = minOf(count.toLong(), size - offset).toInt()
        System.arraycopy(bytes, offset.toInt(), dest, destOffset, n)
        return n
    }

    override fun close() = Unit
}

/**
 * Maps the rows a text viewer shows onto a [ByteWindow] without ever holding the text: a 10 GB log
 * opens as fast as a small one and costs the same memory.
 *
 * A row ends at a newline or after [maxRowBytes] bytes, whichever comes first, so a file with no
 * newline at all is still a list of rows a text layout can measure. Only every stride-th row start
 * is remembered, and the stride doubles whenever the table fills up — the table therefore stays
 * under [maxCheckpoints] entries for any file size, at the price of a short forward scan to reach a
 * row between two checkpoints.
 *
 * [scan] runs once on an IO dispatcher and is cancellable; [rows] may be called concurrently from
 * others while it runs and sees every row [rowCount] has already reached.
 */
class TextRowIndex(
    private val source: ByteWindow,
    private val maxRowBytes: Int = MAX_ROW_BYTES,
    initialCheckpoints: Int = 1 shl 12,
    private val maxCheckpoints: Int = 1 shl 16,
) {
    private val lock = Any()
    private var starts = LongArray(initialCheckpoints.coerceAtLeast(2))
    private var checkpointCount = 0
    private var stride = 1

    @Volatile private var rows = 0

    @Volatile private var lines = 0

    @Volatile private var scanned = 0L

    @Volatile private var done = false

    /** Rows found so far; grows while [scan] runs. */
    val rowCount: Int get() = rows

    /** Newline-delimited lines found so far — the count a row split into pieces must not inflate. */
    val lineCount: Int get() = lines

    val indexedBytes: Long get() = scanned
    val isComplete: Boolean get() = done
    val size: Long get() = source.size

    /**
     * Walks the whole source once, publishing progress through [onProgress] a few times a second.
     * Runs on the caller's (IO) thread and stops promptly when its coroutine is cancelled.
     */
    @Throws(IOException::class)
    suspend fun scan(onProgress: () -> Unit) {
        val splitter = RowSplitter(source, skipByteOrderMark(), maxRowBytes, SCAN_BUFFER)
        var untilCheck = CHECK_EVERY_ROWS
        var lastPublish = 0L
        var unterminated = false
        while (rows < Int.MAX_VALUE) {
            if (!splitter.advance()) break
            noteRowStart(splitter.rowStart)
            if (splitter.newlineTerminated) lines++
            unterminated = !splitter.newlineTerminated
            if (--untilCheck == 0) {
                untilCheck = CHECK_EVERY_ROWS
                currentCoroutineContext().ensureActive()
                scanned = splitter.rowEnd
                val now = System.nanoTime()
                if (now - lastPublish >= PUBLISH_NANOS) {
                    lastPublish = now
                    onProgress()
                }
            }
        }
        // A file not ending in a newline still ends in a line.
        if (unterminated) lines++
        scanned = source.size
        done = true
        onProgress()
    }

    /**
     * Decodes up to [count] rows starting at row [first], clamped to what has been indexed.
     * Blocking IO: call from an IO dispatcher.
     */
    @Throws(IOException::class)
    fun rows(first: Int, count: Int): List<String> {
        val available = rows
        if (first < 0 || first >= available || count <= 0) return emptyList()
        val take = minOf(count, available - first)
        val anchor = synchronized(lock) {
            if (checkpointCount == 0) return emptyList()
            val checkpoint = minOf(first / stride, checkpointCount - 1)
            Anchor(checkpoint * stride, starts[checkpoint])
        }
        val splitter = RowSplitter(source, anchor.offset, maxRowBytes, READ_BUFFER)
        val out = ArrayList<String>(take)
        var row = anchor.row
        while (out.size < take) {
            if (!splitter.advance()) break
            // Rows before [first] are the price of a sparse table: they are walked, not decoded.
            if (row >= first) out.add(splitter.text())
            row++
        }
        return out
    }

    /**
     * Records the start of the row about to be counted. Only [scan] calls this, so the table is
     * read here without the lock and only written under it — where [rows] reads it.
     */
    private fun noteRowStart(offset: Long) {
        if (rows % stride == 0) {
            synchronized(lock) {
                if (checkpointCount == starts.size) makeRoom()
                // makeRoom may have doubled the stride, which can drop this row from the table.
                if (rows % stride == 0) starts[checkpointCount++] = offset
            }
        }
        rows++
    }

    /** Doubles the table until it hits its ceiling, then halves its resolution instead. */
    private fun makeRoom() {
        if (starts.size < maxCheckpoints) {
            starts = starts.copyOf(minOf(starts.size * 2, maxCheckpoints))
            return
        }
        var write = 0
        var read = 0
        while (read < checkpointCount) {
            starts[write++] = starts[read]
            read += 2
        }
        checkpointCount = write
        stride *= 2
    }

    /** A UTF-8 BOM belongs to the encoding, not to the first line. */
    private fun skipByteOrderMark(): Long {
        if (source.size < 3) return 0L
        val head = ByteArray(3)
        if (source.read(0, head, 0, 3) < 3) return 0L
        val isBom = head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        return if (isBom) 3L else 0L
    }

    private class Anchor(val row: Int, val offset: Long)
}

/**
 * Walks a [ByteWindow] forward one row at a time. The row just produced sits in [buffer] between
 * [from] and [to] with its line terminator stripped, which lets an index scan skip decoding
 * altogether and lets a read decode straight out of the buffer.
 *
 * Boundaries depend only on the bytes and [maxRowBytes], never on where the walk started — so
 * resuming from a checkpoint reproduces exactly the rows the full scan found.
 */
private class RowSplitter(
    private val source: ByteWindow,
    startOffset: Long,
    private val maxRowBytes: Int,
    bufferSize: Int,
) {
    // Two rows' worth at minimum: a whole row must fit after a top-up, or it could never complete.
    private val buffer = ByteArray(maxOf(bufferSize, maxRowBytes * 2))
    private var bufferStart = startOffset
    private var fill = 0
    private var pos = 0

    private var from = 0
    private var to = 0

    /** Absolute offset of the row's first byte. */
    var rowStart = 0L
        private set

    /** Absolute offset just past the row and its terminator — where the next row starts. */
    var rowEnd = 0L
        private set

    /** False for the file's last row when it has no newline, and for a row broken up by length. */
    var newlineTerminated = false
        private set

    fun advance(): Boolean {
        if (fill - pos <= maxRowBytes) topUp()
        if (pos == fill) return false
        val start = pos
        val limit = minOf(fill, start + maxRowBytes)
        var i = start
        while (i < limit && buffer[i] != NEWLINE) i++
        rowStart = bufferStart + start
        from = start
        if (i < limit) {
            // A newline inside the row's byte budget: content stops before it, the row eats it.
            newlineTerminated = true
            to = if (i > start && buffer[i - 1] == CARRIAGE_RETURN) i - 1 else i
            pos = i + 1
        } else if (i < fill) {
            // Content filled its budget with bytes still to come. If all that follows is this
            // line's own terminator then the line simply ended here, and treating it as broken
            // would invent a blank row after it; otherwise break it, but never mid-character.
            val crlf = buffer[i] == CARRIAGE_RETURN && i + 1 < fill && buffer[i + 1] == NEWLINE
            newlineTerminated = crlf || buffer[i] == NEWLINE
            to = if (newlineTerminated) i else characterBoundary(start, i)
            pos = when {
                crlf -> i + 2
                newlineTerminated -> i + 1
                else -> to
            }
        } else {
            // Input ended on the budget. The break is the end of the file, so there is no next
            // character to keep whole — and no byte at [i] to look at, which reading one anyway
            // would turn into a boundary that depends on the buffer size rather than the bytes.
            newlineTerminated = false
            to = i
            pos = i
        }
        rowEnd = bufferStart + pos
        return true
    }

    fun text(): String = String(buffer, from, to - from, Charsets.UTF_8)

    /**
     * Pulls a forced break back to the start of the UTF-8 character it lands inside, so splitting a
     * long line does not leave a replacement glyph on either side of the seam.
     */
    private fun characterBoundary(start: Int, breakAt: Int): Int {
        var i = breakAt
        var back = 0
        while (i > start && back < 4 && (buffer[i].toInt() and 0xC0) == 0x80) {
            i--
            back++
        }
        return if (i > start && back in 1..3) i else breakAt
    }

    /** Slides the unread bytes to the front and refills, so the next row is contiguous. */
    private fun topUp() {
        if (pos > 0) {
            System.arraycopy(buffer, pos, buffer, 0, fill - pos)
            bufferStart += pos
            fill -= pos
            pos = 0
        }
        while (fill < buffer.size) {
            val n = source.read(bufferStart + fill, buffer, fill, buffer.size - fill)
            if (n <= 0) break
            fill += n
        }
    }
}
