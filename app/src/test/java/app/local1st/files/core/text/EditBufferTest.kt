package app.local1st.files.core.text

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
            "a\r\nb\nc",
            "x".repeat(10_000),
            "x".repeat(10_000) + "\n" + "y".repeat(4096),
            "\uD83D\uDE00".repeat(4000),
        )
        for (s in samples) {
            assertEquals(s, joinEditRows(splitEditRows(s)))
            assertEquals(s, joinEditRows(splitEditRows(s, maxRowChars = 3)))
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
    fun caretAtCharOffset_landsOnTheLineThatContainsThatChar() {
        val buffer = EditBuffer("你好\n世界")
        assertEquals(EditCaret(0, 0), buffer.caretAtCharOffset(0))
        assertEquals(EditCaret(0, 1), buffer.caretAtCharOffset(1))
        assertEquals(EditCaret(0, 2), buffer.caretAtCharOffset(2))
        assertEquals(EditCaret(1, 0), buffer.caretAtCharOffset(3))
        assertEquals(EditCaret(1, 1), buffer.caretAtCharOffset(4))
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
        val buffer = EditBuffer("x".repeat(10_000), maxRowChars = 4096)
        assertEquals(EditCaret(0, 0), buffer.caretAtUtf8Offset(0))
        // The line is three rows of 4096 / 4096 / 1808; byte 5000 is 904 into the second.
        assertEquals(EditCaret(1, 904), buffer.caretAtUtf8Offset(5000))
        assertEquals(EditCaret(2, 1808), buffer.caretAtUtf8Offset(10_000))
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
        assertFalse(buffer.isDirty)
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
    fun gbkWindow_countsEncodedBytesNotUtf8() {
        assumeTrue(Charset.isSupported("GBK"))
        val charset = Charset.forName("GBK")
        val text = "中".repeat(10)
        val buffer = EditBuffer(text, charset)
        assertEquals(20, buffer.byteCount)
        assertEquals(30, utf8ByteCount(text))
        // Cap between GBK and UTF-8 sizes: UTF-8 counting would reject every replace.
        val maxBytes = 25
        assertNotNull(buffer.replace(0, text, selectionStart = 10, maxBytes = maxBytes))
        assertFalse(buffer.isDirty)
        assertNotNull(buffer.replace(0, text + "中", selectionStart = 11, maxBytes = maxBytes))
        assertEquals(22, buffer.byteCount)
        assertNull(buffer.replace(0, text + "中".repeat(3), selectionStart = 13, maxBytes = maxBytes))
        assertEquals(22, buffer.byteCount)
    }

    @Test
    fun gbkAtCap_sameLengthEditSucceeds() {
        assumeTrue(Charset.isSupported("GBK"))
        val charset = Charset.forName("GBK")
        val text = "中".repeat(5)
        val buffer = EditBuffer(text, charset)
        assertEquals(10, buffer.byteCount)
        assertNotNull(buffer.replace(0, "国".repeat(5), selectionStart = 5, maxBytes = 10))
        assertEquals("国".repeat(5), buffer.toText())
        assertEquals(10, buffer.byteCount)
        assertNull(buffer.replace(0, "国".repeat(6), selectionStart = 6, maxBytes = 10))
        assertEquals("国".repeat(5), buffer.toText())
    }

    @Test
    fun gbkReplaceRange_usesEncodedCap() {
        assumeTrue(Charset.isSupported("GBK"))
        val charset = Charset.forName("GBK")
        val buffer = EditBuffer("中中", charset)
        assertEquals(4, buffer.byteCount)
        assertNotNull(buffer.replaceRange(EditCaret(0, 0), EditCaret(0, 2), "国国", maxBytes = 4))
        assertEquals("国国", buffer.toText())
        assertNull(buffer.replaceRange(EditCaret(0, 0), EditCaret(0, 2), "中中中", maxBytes = 4))
        assertEquals("国国", buffer.toText())
    }

    @Test
    fun alreadyOverCap_sameLengthEditSucceedsButGrowthDoesNot() {
        val buffer = EditBuffer("abcd")
        assertEquals(4, buffer.byteCount)
        assertNotNull(buffer.replace(0, "abce", selectionStart = 4, maxBytes = 3))
        assertEquals("abce", buffer.toText())
        assertNull(buffer.replace(0, "abcde", selectionStart = 5, maxBytes = 3))
        assertEquals("abce", buffer.toText())
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
        assertFalse(buffer.isDirty)
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
    fun isDirty_staysFalseUntilAMutationChangesTheText() {
        val buffer = EditBuffer("hello\nworld")
        assertFalse(buffer.isDirty)
        assertNotNull(buffer.replace(0, "hello", selectionStart = 5, maxBytes = 1024))
        assertFalse(buffer.isDirty)
        assertNotNull(buffer.replace(0, "hallo", selectionStart = 5, maxBytes = 1024))
        assertTrue(buffer.isDirty)
    }

    @Test
    fun isDirty_isSetByJoinAndByARangeThatChangesText() {
        val joined = EditBuffer("hello\nworld")
        assertNotNull(joined.mergeWithPrevious(1))
        assertTrue(joined.isDirty)

        val same = EditBuffer("hello\nworld")
        assertNotNull(
            same.replaceRange(EditCaret(0, 0), EditCaret(0, 5), "hello", maxBytes = 1024),
        )
        assertFalse(same.isDirty)
        assertNotNull(
            same.replaceRange(EditCaret(0, 0), EditCaret(0, 5), "hi", maxBytes = 1024),
        )
        assertTrue(same.isDirty)
    }

    @Test
    fun crlf_isNotShownOnTheRowAndSurvivesARoundTrip() {
        val buffer = EditBuffer("a\r\nb\r\n")
        assertEquals(listOf("a", "b", ""), listOf(buffer[0], buffer[1], buffer[2]))
        assertEquals(2, buffer.lineCount())
        assertEquals("a\r\nb\r\n", buffer.toText())
        assertNotNull(buffer.replace(0, "aX", selectionStart = 2, maxBytes = 1024))
        assertEquals("aX\r\nb\r\n", buffer.toText())
    }

    @Test
    fun enterInACrlfWindow_writesCrlf() {
        val buffer = EditBuffer("one\r\ntwo\r\n")
        val caret = buffer.replace(0, "on\ne", selectionStart = 3, maxBytes = 1024)

        assertEquals(EditCaret(1, 0), caret)
        assertEquals("on\r\ne\r\ntwo\r\n", buffer.toText())
        assertEquals(utf8ByteCount("on\r\ne\r\ntwo\r\n"), buffer.byteCount)
    }

    @Test
    fun enterInAnLfWindow_staysLf_evenWithOneStrayCrlf() {
        val buffer = EditBuffer("one\ntwo\r\nthree\n")
        val caret = buffer.replace(0, "on\ne", selectionStart = 3, maxBytes = 1024)

        assertEquals(EditCaret(1, 0), caret)
        assertEquals("on\ne\ntwo\r\nthree\n", buffer.toText())
    }

    @Test
    fun crlfJoin_removesBothBytes() {
        val buffer = EditBuffer("a\r\nb")
        val caret = buffer.mergeWithPrevious(1)

        assertEquals(EditCaret(0, 1), caret)
        assertEquals("ab", buffer.toText())
        assertEquals(2, buffer.byteCount)
    }

    @Test
    fun aLineWithNoNewline_isBrokenIntoMeasurableRows() {
        val text = "x".repeat(10_000)
        val buffer = EditBuffer(text, maxRowChars = 4096)

        assertEquals(3, buffer.size)
        assertEquals(listOf(4096, 4096, 1808), (0 until buffer.size).map { buffer[it].length })
        // Broken up for layout only: still one line, and still the same bytes.
        assertEquals(1, buffer.lineCount())
        assertEquals(text, buffer.toText())
        assertEquals(text.length, buffer.byteCount)
    }

    @Test
    fun aBrokenRow_neverSplitsASurrogatePair() {
        val text = "\uD83D\uDE00".repeat(100)
        val buffer = EditBuffer(text, maxRowChars = 5)

        for (i in 0 until buffer.size) {
            assertEquals(0, buffer[i].length % 2)
            assertFalse(Character.isHighSurrogate(buffer[i].last()))
        }
        assertEquals(text, buffer.toText())
    }

    @Test
    fun typingOnABrokenRow_addsNoNewline() {
        val buffer = EditBuffer("x".repeat(10), maxRowChars = 4)
        assertEquals(3, buffer.size)

        val caret = buffer.replace(1, "xxxY", selectionStart = 4, maxBytes = 1024)
        assertEquals(EditCaret(1, 4), caret)
        assertEquals("xxxxxxxYxx", buffer.toText())
        assertEquals(1, buffer.lineCount())
    }

    @Test
    fun backspaceAtColumnZeroOfABrokenRow_deletesACharacterNotANewline() {
        val buffer = EditBuffer("abcdefgh", maxRowChars = 4)
        assertEquals(listOf("abcd", "efgh"), listOf(buffer[0], buffer[1]))

        val caret = buffer.mergeWithPrevious(1)
        assertEquals(EditCaret(0, 3), caret)
        assertEquals("abcefgh", buffer.toText())
        assertEquals(7, buffer.byteCount)
        assertEquals(1, buffer.lineCount())
    }

    @Test
    fun forwardDeleteAtTheEndOfABrokenRow_takesTheNextCharacter() {
        val buffer = EditBuffer("abcdefgh", maxRowChars = 4)
        val caret = buffer.mergeWithNext(0)

        assertEquals(EditCaret(0, 4), caret)
        assertEquals("abcdfgh", buffer.toText())
        assertEquals(1, buffer.lineCount())
    }

    @Test
    fun emptyingABrokenRow_dropsItRatherThanLeavingABlankLine() {
        val buffer = EditBuffer("abcdefgh", maxRowChars = 4)
        assertNotNull(buffer.replace(0, "", selectionStart = 0, maxBytes = 1024))

        assertEquals(1, buffer.size)
        assertEquals("efgh", buffer[0])
        assertEquals("efgh", buffer.toText())
        assertEquals(1, buffer.lineCount())
        // And Backspace out of the row that is now first is still a no-op.
        assertNull(buffer.mergeWithPrevious(0))
    }

    @Test
    fun aHalfMegabyteLineOpensAsRowsATextLayoutCanMeasure() {
        val text = "a".repeat(512 * 1024)
        val buffer = EditBuffer(text)

        assertEquals(128, buffer.size)
        for (i in 0 until buffer.size) assertTrue(buffer[i].length <= MAX_EDIT_ROW_CHARS)
        assertEquals(text, buffer.toText())
    }

    @Test
    fun everyChangeIsReportedAsASpliceThatReverses() {
        val buffer = EditBuffer("one\ntwo\nthree\n")
        val splices = ArrayList<EditSplice>()
        buffer.onSplice = { splices.add(it) }

        assertNotNull(buffer.replace(1, "two\n2", 5, maxBytes = 1024))
        assertNotNull(buffer.mergeWithPrevious(3))
        assertNotNull(buffer.replaceRange(EditCaret(0, 1), EditCaret(1, 1), "", maxBytes = 1024))

        assertEquals(3, splices.size)
        val after = buffer.toText()
        for (splice in splices.asReversed()) buffer.apply(splice, reverse = true)
        assertEquals("one\ntwo\nthree\n", buffer.toText())
        assertEquals(utf8ByteCount("one\ntwo\nthree\n"), buffer.byteCount)
        for (splice in splices) buffer.apply(splice, reverse = false)
        assertEquals(after, buffer.toText())
        assertEquals(utf8ByteCount(after), buffer.byteCount)
    }

    @Test
    fun aChangeThatChangesNothing_isNotReported() {
        val buffer = EditBuffer("abc\ndef\n")
        var reported = 0
        buffer.onSplice = { reported++ }

        assertNotNull(buffer.replace(0, "abc", 3, maxBytes = 1024))
        assertNotNull(buffer.replaceRange(EditCaret(0, 1), EditCaret(0, 2), "b", maxBytes = 1024))

        assertEquals(0, reported)
    }

    @Test
    fun theSpliceCaretSitsWhereTheChangeBegan() {
        val buffer = EditBuffer("hello world")
        var splice: EditSplice? = null
        buffer.onSplice = { splice = it }

        buffer.replace(0, "hello, world", 6, maxBytes = 1024)

        assertEquals(EditCaret(0, 5), splice!!.caretBefore)
        assertEquals(EditCaret(0, 6), splice!!.caretAfter)
        assertTrue(splice!!.isRowRewrite)
    }

    @Test
    fun absorb_dropsTheEmptyRowAfterAFinalNewline_andReportsTheShift() {
        val left = EditBuffer("a\nb\n")
        val right = EditBuffer("c\nd")

        val offset = left.absorb(right)

        assertEquals(2, offset)
        assertEquals("a\nb\nc\nd", left.toText())
        assertEquals(listOf("a", "b", "c", "d"), (0 until left.size).map { left[it] })
        assertEquals(utf8ByteCount("a\nb\nc\nd"), left.byteCount)
    }

    @Test
    fun absorb_keepsAPieceOfALineAsAPieceBesideItsContinuation() {
        val left = EditBuffer("abc")
        val right = EditBuffer("def\n")

        val offset = left.absorb(right)

        assertEquals(1, offset)
        assertEquals("abcdef\n", left.toText())
        assertEquals(listOf("abc", "def", ""), (0 until left.size).map { left[it] })
        // Between the pieces there is no character: forward delete takes the continuation's first.
        assertEquals(EditCaret(0, 3), left.mergeWithNext(0))
        assertEquals("abcef\n", left.toText())
    }

    @Test
    fun absorb_intoAnEmptyBuffer_isTheOtherBuffer() {
        val left = EditBuffer("")
        val right = EditBuffer("x\ny")

        assertEquals(0, left.absorb(right))
        assertEquals("x\ny", left.toText())
        assertEquals(2, left.size)
    }

    @Test
    fun endsWithNewline_andNewlineCount_describeTheTerminators() {
        assertTrue(EditBuffer("a\nb\n").endsWithNewline)
        assertFalse(EditBuffer("a\nb").endsWithNewline)
        assertFalse(EditBuffer("").endsWithNewline)
        assertTrue(EditBuffer("\n").endsWithNewline)
        assertEquals(2, EditBuffer("a\nb\n").newlineCount())
        assertEquals(1, EditBuffer("a\r\nb").newlineCount())
        assertEquals(0, EditBuffer("x".repeat(10_000), maxRowChars = 4096).newlineCount())
    }
}
