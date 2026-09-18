package app.local1st.files.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditBufferTest {

    @Test
    fun splitJoin_isLossless() {
        val samples = listOf(
            "",
            "a",
            "a\nb",
            "a\nb\n",
            "\n",
            "a\n\nb",
            "a\r\nb\r\n",
        )
        for (s in samples) {
            assertEquals(s, joinEditLines(splitEditLines(s)))
        }
    }

    @Test
    fun emptyText_isOneEmptyRow() {
        val buffer = EditBuffer("")
        assertEquals(1, buffer.size)
        assertEquals("", buffer[0])
        assertEquals(0, buffer.lineCount())
        assertEquals(0, buffer.byteCount)
    }

    @Test
    fun trailingNewline_isAnEmptyLastRow_butNotAnExtraLine() {
        val buffer = EditBuffer("a\nb\n")
        assertEquals(listOf("a", "b", ""), listOf(buffer[0], buffer[1], buffer[2]))
        assertEquals(2, buffer.lineCount())
        assertEquals("a\nb\n", buffer.toText())
    }

    @Test
    fun lineAtUtf8Offset_landsOnTheLineThatContainsThatByte() {
        val buffer = EditBuffer("hello\nworld")
        assertEquals(0, buffer.lineAtUtf8Offset(0))
        assertEquals(0, buffer.lineAtUtf8Offset(4))
        assertEquals(1, buffer.lineAtUtf8Offset(6))
        assertEquals(1, buffer.lineAtUtf8Offset(10))
        assertEquals(1, buffer.lineAtUtf8Offset(99))
    }

    @Test
    fun lineAtUtf8Offset_countsMultibyteCharacters() {
        val buffer = EditBuffer("中\n文")
        assertEquals(0, buffer.lineAtUtf8Offset(0))
        assertEquals(0, buffer.lineAtUtf8Offset(2))
        assertEquals(1, buffer.lineAtUtf8Offset(4))
    }

    @Test
    fun lineAtUtf8Offset_onAnEmptyBuffer_isZero() {
        assertEquals(0, EditBuffer("").lineAtUtf8Offset(0))
        assertEquals(0, EditBuffer("").lineAtUtf8Offset(10))
    }

    @Test
    fun caretAtUtf8Offset_landsOnTheLineAndColumn() {
        val buffer = EditBuffer("hello\nworld")
        assertEquals(EditCaret(0, 0), buffer.caretAtUtf8Offset(0))
        assertEquals(EditCaret(0, 4), buffer.caretAtUtf8Offset(4))
        assertEquals(EditCaret(0, 5), buffer.caretAtUtf8Offset(5))
        assertEquals(EditCaret(1, 0), buffer.caretAtUtf8Offset(6))
        assertEquals(EditCaret(1, 5), buffer.caretAtUtf8Offset(11))
        assertEquals(EditCaret(1, 5), buffer.caretAtUtf8Offset(99))
    }

    @Test
    fun caretAtUtf8Offset_countsMultibyteCharacters() {
        val buffer = EditBuffer("中\n文")
        assertEquals(EditCaret(0, 0), buffer.caretAtUtf8Offset(0))
        assertEquals(EditCaret(0, 0), buffer.caretAtUtf8Offset(1))
        assertEquals(EditCaret(0, 1), buffer.caretAtUtf8Offset(3))
        assertEquals(EditCaret(1, 0), buffer.caretAtUtf8Offset(4))
        assertEquals(EditCaret(1, 1), buffer.caretAtUtf8Offset(7))
    }

    @Test
    fun caretAtUtf8Offset_onALongLine_isTheCharacterAtThatByte() {
        val buffer = EditBuffer("x".repeat(10_000))
        assertEquals(EditCaret(0, 0), buffer.caretAtUtf8Offset(0))
        assertEquals(EditCaret(0, 5000), buffer.caretAtUtf8Offset(5000))
        assertEquals(EditCaret(0, 10_000), buffer.caretAtUtf8Offset(10_000))
    }

    @Test
    fun editOpen_mapsAMidFileOffsetOntoThatLineOfACappedWindow() {
        val lines = (0 until 80).map { "line-$it" }
        val text = lines.joinToString("\n", postfix = "\n")
        val centerLine = 40
        val center = lines.take(centerLine).sumOf { it.length + 1 }.toLong()
        val window = loadEditWindowAround(
            ArrayByteWindow(text.toByteArray(Charsets.UTF_8)),
            centerOffset = center,
            maxBytes = 200,
        )
        assertTrue(window.from > 0L)
        assertTrue(window.to < text.toByteArray(Charsets.UTF_8).size.toLong())
        val rel = (center - window.from).toInt()
        val buffer = EditBuffer(window.text)
        val caret = buffer.caretAtUtf8Offset(rel)
        assertEquals("line-$centerLine", buffer[caret.line])
        assertEquals(0, caret.column)
    }

    @Test
    fun replaceSameLine_keepsRowCount() {
        val buffer = EditBuffer("hello\nworld")
        val caret = buffer.replace(0, "hi", selectionStart = 2, maxBytes = 1024)

        assertEquals(EditCaret(0, 2), caret)
        assertEquals("hi\nworld", buffer.toText())
        assertEquals(utf8ByteCount("hi\nworld"), buffer.byteCount)
    }

    @Test
    fun enterInTheMiddle_splitsAndPutsTheCaretOnTheNewRow() {
        val buffer = EditBuffer("hello world")
        val caret = buffer.replace(0, "hello\nworld", selectionStart = 6, maxBytes = 1024)

        assertEquals(EditCaret(1, 0), caret)
        assertEquals("hello\nworld", buffer.toText())
        assertEquals(2, buffer.size)
    }

    @Test
    fun enterAtEnd_addsAnEmptyRow() {
        val buffer = EditBuffer("hello")
        val caret = buffer.replace(0, "hello\n", selectionStart = 6, maxBytes = 1024)

        assertEquals(EditCaret(1, 0), caret)
        assertEquals("hello\n", buffer.toText())
        assertEquals(1, buffer.lineCount())
    }

    @Test
    fun pasteSeveralLines_landsOnTheLastPastedRow() {
        val buffer = EditBuffer("x")
        val pasted = "a\nb\nc"
        val caret = buffer.replace(0, pasted, selectionStart = pasted.length, maxBytes = 1024)

        assertEquals(EditCaret(2, 1), caret)
        assertEquals("a\nb\nc", buffer.toText())
    }

    @Test
    fun overTheCap_isRejectedAndUnchanged() {
        val buffer = EditBuffer("ab")
        val before = buffer.toText()
        val bytes = buffer.byteCount
        assertNull(buffer.replace(0, "abcd", selectionStart = 4, maxBytes = 3))
        assertEquals(before, buffer.toText())
        assertEquals(bytes, buffer.byteCount)
    }

    @Test
    fun utf8Delta_countsMultibyteCharacters() {
        val buffer = EditBuffer("a")
        val caret = buffer.replace(0, "中", selectionStart = 1, maxBytes = 1024)

        assertEquals(EditCaret(0, 1), caret)
        assertEquals(3, buffer.byteCount)
        assertEquals("中", buffer.toText())
    }

    @Test
    fun mergeWithPrevious_joinsOnTheNewline() {
        val buffer = EditBuffer("hello\nworld")
        val caret = buffer.mergeWithPrevious(1)

        assertEquals(EditCaret(0, 5), caret)
        assertEquals("helloworld", buffer.toText())
        assertEquals(utf8ByteCount("helloworld"), buffer.byteCount)
        assertEquals(1, buffer.size)
    }

    @Test
    fun mergeFirstRow_isANoOp() {
        val buffer = EditBuffer("hello\nworld")
        assertNull(buffer.mergeWithPrevious(0))
        assertEquals("hello\nworld", buffer.toText())
    }

    @Test
    fun mergeWithNext_joinsOnTheNewline() {
        val buffer = EditBuffer("hello\nworld")
        val caret = buffer.mergeWithNext(0)

        assertEquals(EditCaret(0, 5), caret)
        assertEquals("helloworld", buffer.toText())
        assertEquals(1, buffer.size)
    }

    @Test
    fun mergeLastRow_isANoOp() {
        val buffer = EditBuffer("hello\nworld")
        assertNull(buffer.mergeWithNext(1))
        assertEquals("hello\nworld", buffer.toText())
    }

    @Test
    fun replaceEnterAtColumnZero_splitsAndLeavesTheOriginalRowEmpty() {
        val buffer = EditBuffer("hello")
        val caret = buffer.replace(0, "\nhello", selectionStart = 0, maxBytes = 1024)

        assertEquals(EditCaret(0, 0), caret)
        assertEquals(listOf("", "hello"), listOf(buffer[0], buffer[1]))
        assertEquals("\nhello", buffer.toText())
    }

    @Test
    fun textInRange_ofTheWholeBuffer_matchesToText() {
        for (s in listOf("", "a", "a\nb", "a\nb\n", "\n")) {
            val buffer = EditBuffer(s)
            val last = buffer.size - 1
            assertEquals(
                s,
                buffer.textInRange(EditCaret(0, 0), EditCaret(last, buffer[last].length)),
            )
        }
    }

    @Test
    fun textInRange_spansRowsAndIncludesTheNewline() {
        val buffer = EditBuffer("hello\nworld\n!")
        assertEquals("lo\nwo", buffer.textInRange(EditCaret(0, 3), EditCaret(1, 2)))
        assertEquals("lo\nwo", buffer.textInRange(EditCaret(1, 2), EditCaret(0, 3)))
        assertEquals("\n", buffer.textInRange(EditCaret(0, 5), EditCaret(1, 0)))
        assertEquals("ell", buffer.textInRange(EditCaret(0, 1), EditCaret(0, 4)))
        assertEquals("", buffer.textInRange(EditCaret(0, 2), EditCaret(0, 2)))
    }

    @Test
    fun replaceRange_deletesAcrossRows() {
        val buffer = EditBuffer("hello\nworld")
        val caret = buffer.replaceRange(EditCaret(0, 3), EditCaret(1, 2), "", maxBytes = 1024)
        assertEquals(EditCaret(0, 3), caret)
        assertEquals("helrld", buffer.toText())
        assertEquals(utf8ByteCount("helrld"), buffer.byteCount)
        assertEquals(1, buffer.size)
    }

    @Test
    fun replaceRange_insertsAndCanSplit() {
        val buffer = EditBuffer("hello\nworld")
        val caret = buffer.replaceRange(EditCaret(0, 5), EditCaret(1, 0), "X\nY", maxBytes = 1024)
        assertEquals(EditCaret(1, 1), caret)
        assertEquals("helloX\nYworld", buffer.toText())
        assertEquals(2, buffer.size)
        assertEquals(utf8ByteCount("helloX\nYworld"), buffer.byteCount)
    }

    @Test
    fun replaceRange_overManyRows_replacesInOneSplice() {
        val n = 20_000
        val buffer = EditBuffer((1..n).joinToString("\n") { "x" })
        val caret = buffer.replaceRange(
            EditCaret(0, 0),
            EditCaret(n - 1, 1),
            "y",
            maxBytes = 1 shl 20,
        )
        assertEquals(EditCaret(0, 1), caret)
        assertEquals("y", buffer.toText())
        assertEquals(1, buffer.size)
        assertEquals(utf8ByteCount("y"), buffer.byteCount)
    }

    @Test
    fun replaceRange_overTheCap_isRejectedAndUnchanged() {
        val buffer = EditBuffer("ab\ncd")
        val before = buffer.toText()
        val bytes = buffer.byteCount
        assertNull(
            buffer.replaceRange(EditCaret(0, 2), EditCaret(1, 0), "xxxx", maxBytes = bytes),
        )
        assertEquals(before, buffer.toText())
        assertEquals(bytes, buffer.byteCount)
    }

    @Test
    fun replace_onTheTrailingEmptyRow_changesLineCount() {
        val buffer = EditBuffer("a\n")
        assertEquals(1, buffer.lineCount())
        buffer.replace(1, "x", selectionStart = 1, maxBytes = 1024)
        assertEquals(2, buffer.lineCount())
        buffer.replace(1, "", selectionStart = 0, maxBytes = 1024)
        assertEquals(1, buffer.lineCount())
    }

    @Test
    fun crlf_staysOnTheLineAndSurvivesARoundTrip() {
        val buffer = EditBuffer("a\r\nb\r\n")
        assertEquals("a\r", buffer[0])
        assertEquals("b\r", buffer[1])
        assertEquals("a\r\nb\r\n", buffer.toText())
        assertTrue(buffer.replace(0, "aX\r", selectionStart = 2, maxBytes = 1024) != null)
        assertEquals("aX\r\nb\r\n", buffer.toText())
    }
}
