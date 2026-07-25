package app.local1st.files.core.text

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRowIndexTest {

    @Test
    fun trailingNewline_doesNotAddAPhantomRow() {
        val index = index("alpha\nbeta\n")

        assertEquals(listOf("alpha", "beta"), index.allRows())
        assertEquals(2, index.lineCount)
    }

    @Test
    fun lastLineWithoutNewline_isStillARow() {
        val index = index("alpha\nbeta")

        assertEquals(listOf("alpha", "beta"), index.allRows())
        assertEquals(2, index.lineCount)
    }

    @Test
    fun blankLines_andCrlf_surviveAsEmptyRows() {
        val index = index("a\r\n\r\nb\r\n")

        assertEquals(listOf("a", "", "b"), index.allRows())
        assertEquals(3, index.lineCount)
    }

    @Test
    fun emptyInput_hasNoRows() {
        val index = index("")

        assertEquals(0, index.rowCount)
        assertEquals(0, index.lineCount)
        assertEquals(emptyList<String>(), index.rows(0, 10))
    }

    @Test
    fun byteOrderMark_isNotPartOfTheFirstLine() {
        val index = index("\uFEFFalpha\nbeta\n")

        assertEquals(listOf("alpha", "beta"), index.allRows())
    }

    @Test
    fun inputWithoutNewlines_isBrokenIntoRowsThatRejoin() {
        val text = "x".repeat(1000)

        val index = index(text, maxRowBytes = 64)

        assertEquals(1000 / 64 + 1, index.rowCount)
        // Broken-up rows are one line, however many rows they take on screen.
        assertEquals(1, index.lineCount)
        assertEquals(text, index.allRows().joinToString(""))
    }

    @Test
    fun lineEndingExactlyOnTheRowBudget_doesNotAddABlankRow() {
        val long = "x".repeat(64)

        val index = index("$long\nnext\n", maxRowBytes = 64)

        assertEquals(listOf(long, "next"), index.allRows())
        assertEquals(2, index.lineCount)
    }

    @Test
    fun crlfLineEndingExactlyOnTheRowBudget_keepsNoCarriageReturn() {
        val long = "x".repeat(64)

        val index = index("$long\r\nnext\r\n", maxRowBytes = 64)

        assertEquals(listOf(long, "next"), index.allRows())
        assertEquals(2, index.lineCount)
    }

    @Test
    fun inputEndingExactlyOnTheRowBudget_isOneRow() {
        // 21 three-byte characters and a one-byte one: exactly the budget, ending the input.
        val text = "中".repeat(21) + "x"

        val index = index(text, maxRowBytes = 64)

        assertEquals(listOf(text), index.allRows())
        assertEquals(1, index.lineCount)
    }

    @Test
    fun forcedBreak_neverSplitsAMultiByteCharacter() {
        // The 21st three-byte character straddles a 64-byte break.
        val text = "中".repeat(60)

        val index = index(text, maxRowBytes = 64)

        assertEquals(text, index.allRows().joinToString(""))
        assertTrue(index.allRows().none { it.contains('�') })
    }

    @Test
    fun rowsAreExactEvenWhenTheCheckpointTableKeepsHalvingItsResolution() {
        val lines = (0 until 200).map { "line $it padded with a little text" }
        // A two-entry ceiling forces a compaction every couple of rows, so nearly every lookup
        // lands between checkpoints and has to walk forward to its row.
        val index = index(lines.joinToString("\n"), initialCheckpoints = 2, maxCheckpoints = 2)

        assertEquals(lines.size, index.rowCount)
        lines.forEachIndexed { row, expected ->
            assertEquals("row $row", listOf(expected), index.rows(row, 1))
        }
        assertEquals(lines.subList(97, 105), index.rows(97, 8))
    }

    @Test
    fun readsBeyondTheIndexedRowsAreClamped() {
        val index = index("a\nb\nc\n")

        assertEquals(listOf("b", "c"), index.rows(1, 99))
        assertEquals(emptyList<String>(), index.rows(3, 1))
        assertEquals(emptyList<String>(), index.rows(-1, 1))
    }

    @Test
    fun rowsPastTheFourGigabyteMark_areFoundExactly() {
        // 4.5 GiB of numbered lines, generated as they are read: past the 2^32 byte mark every
        // offset needs 64-bit arithmetic, from the scan through to the row that comes back.
        val source = NumberedLines(lineCount = 2_415_000, lineBytes = 2000)
        val index = TextRowIndex(source)
        runBlocking { index.scan {} }

        assertTrue(source.size > 0xFFFF_FFFFL)
        assertEquals(source.lineCount, index.rowCount)
        assertEquals(source.lineCount, index.lineCount)
        assertEquals(listOf(source.line(0)), index.rows(0, 1))
        assertEquals(listOf(source.line(1_700_000)), index.rows(1_700_000, 1))
        val last = source.lineCount - 1
        assertEquals(listOf(source.line(last)), index.rows(last, 1))
    }

    /**
     * [lineCount] lines of [lineBytes] bytes each — "line 41" then dot padding — materialised only
     * where they are read, so a window far larger than memory costs nothing to walk.
     */
    private class NumberedLines(val lineCount: Int, private val lineBytes: Int) : ByteWindow {
        override val size: Long = lineCount.toLong() * lineBytes

        // Padding and terminator never change; only the head is rewritten per line.
        private val row = ByteArray(lineBytes) { '.'.code.toByte() }
        private var rowIndex = -1
        private var headLength = 0

        init {
            row[lineBytes - 1] = '\n'.code.toByte()
        }

        fun line(index: Int): String = "line $index".padEnd(lineBytes - 1, '.')

        private fun bytesFor(index: Int): ByteArray {
            if (index != rowIndex) {
                val head = "line $index".toByteArray(Charsets.UTF_8)
                row.fill('.'.code.toByte(), head.size, maxOf(headLength, head.size))
                System.arraycopy(head, 0, row, 0, head.size)
                headLength = head.size
                rowIndex = index
            }
            return row
        }

        override fun read(offset: Long, dest: ByteArray, destOffset: Int, count: Int): Int {
            var written = 0
            var at = offset
            while (written < count && at < size) {
                val bytes = bytesFor((at / lineBytes).toInt())
                val within = (at % lineBytes).toInt()
                val n = minOf(bytes.size - within, count - written)
                System.arraycopy(bytes, within, dest, destOffset + written, n)
                written += n
                at += n
            }
            return written
        }

        override fun close() = Unit
    }

    private fun index(
        text: String,
        maxRowBytes: Int = MAX_ROW_BYTES,
        initialCheckpoints: Int = 1 shl 12,
        maxCheckpoints: Int = 1 shl 16,
    ): TextRowIndex {
        val window = ArrayByteWindow(text.toByteArray(Charsets.UTF_8))
        val index = TextRowIndex(window, maxRowBytes, initialCheckpoints, maxCheckpoints)
        runBlocking { index.scan {} }
        assertTrue(index.isComplete)
        return index
    }

    private fun TextRowIndex.allRows(): List<String> = rows(0, rowCount)
}
