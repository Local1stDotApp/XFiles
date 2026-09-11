package app.local1st.files.core.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditWindowTest {

    @Test
    fun smallFile_isLoadedWhole() {
        val window = load("hello\nworld\n", start = 0, maxBytes = 1024)

        assertEquals("hello\nworld\n", window.text)
        assertEquals(0L, window.from)
        assertEquals(12L, window.to)
        assertFalse(window.isPartial)
    }

    @Test
    fun capBeforeEof_endsOnTheLastNewline() {
        val window = load("aa\nbb\ncc\n", start = 0, maxBytes = 7)

        assertEquals("aa\nbb\n", window.text)
        assertEquals(6L, window.to)
        assertTrue(window.isPartial)
    }

    @Test
    fun capWithNoNewline_keepsWholeCharacters() {
        // "中" is three bytes; a 5-byte cap would otherwise split the second one.
        val window = load("中中中", start = 0, maxBytes = 5)

        assertEquals("中", window.text)
        assertEquals(3L, window.to)
        assertTrue(window.isPartial)
    }

    @Test
    fun windowStartingMidFile_doesNotRewriteThePrefix() {
        val window = load("HEAD\nBODY\nTAIL\n", start = 5, maxBytes = 5)

        assertEquals("BODY\n", window.text)
        assertEquals(5L, window.from)
        assertEquals(10L, window.to)
        assertTrue(window.isPartial)
    }

    @Test
    fun tailShorterThanTheCap_takesTheRestOfTheFile() {
        val window = load("HEAD\nTAIL", start = 5, maxBytes = 1024)

        assertEquals("TAIL", window.text)
        assertEquals(5L, window.from)
        assertEquals(9L, window.to)
        assertTrue(window.isPartial)
        assertEquals(9L, window.fileSize)
    }

    @Test
    fun emptySource_isAnEmptyWindow() {
        val window = load("", start = 0, maxBytes = 1024)

        assertEquals("", window.text)
        assertEquals(0L, window.from)
        assertEquals(0L, window.to)
        assertFalse(window.isPartial)
    }

    @Test
    fun startAtEof_isEmptyAndPartial() {
        val window = load("abc", start = 3, maxBytes = 1024)

        assertEquals("", window.text)
        assertEquals(3L, window.from)
        assertEquals(3L, window.to)
        assertTrue(window.isPartial)
    }

    @Test
    fun aroundTheMiddle_includesBytesOnBothSides() {
        val text = "0123456789abcdefghij0123456789"
        val window = loadAround(text, center = 15, maxBytes = 10)

        assertEquals("abcdefghij", window.text)
        assertEquals(10L, window.from)
        assertEquals(20L, window.to)
    }

    @Test
    fun aroundTheEnd_loadsTheTail() {
        val text = "HEAD\n" + "x".repeat(40) + "\nTAIL"
        val window = loadAround(text, center = text.length - 1L, maxBytes = 20)

        assertTrue(window.text.contains("TAIL"))
        assertEquals(text.length.toLong(), window.to)
        assertTrue(window.from > 0L)
    }

    @Test
    fun aroundTheStart_loadsTheHead() {
        val text = "HEAD\n" + "x".repeat(40)
        val window = loadAround(text, center = 0, maxBytes = 20)

        assertEquals(0L, window.from)
        assertTrue(window.text.startsWith("HEAD"))
    }

    @Test
    fun aroundALine_snapsStartToThePreviousNewline() {
        val text = "AAAA\nBBBB\nCCCC\nDDDD\n"
        // Center in "CCCC\n" (offset 10). Raw start is mid-"BBBB"; snap back to that line.
        val window = loadAround(text, center = 10, maxBytes = 10)

        assertEquals(5L, window.from)
        assertEquals("BBBB\nCCCC\n", window.text)
    }

    @Test
    fun aroundTheEnd_keepsTheTailInsteadOfSnappingItOff() {
        val text = "AAAA\nBBBB\nCCCC"
        val window = loadAround(text, center = 12, maxBytes = 8)

        assertTrue(window.text.endsWith("CCCC"))
        assertEquals(text.length.toLong(), window.to)
        assertTrue(window.from > 0L)
    }

    @Test
    fun windowStartingMidCharacter_snapsBackToTheLeadByte() {
        val bytes = "中中中".toByteArray(Charsets.UTF_8)
        val window = loadEditWindow(ArrayByteWindow(bytes), startOffset = 1, maxBytes = 1024)

        assertEquals(0L, window.from)
        assertEquals(bytes.size.toLong(), window.to)
        assertEquals("中中中", window.text)
        assertArrayEquals(bytes, window.text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun windowStartingOnACharactersLastByte_snapsBackToTheLeadByte() {
        val bytes = "中中中".toByteArray(Charsets.UTF_8)
        val window = loadEditWindow(ArrayByteWindow(bytes), startOffset = 2, maxBytes = 1024)

        assertEquals(0L, window.from)
        assertEquals("中中中", window.text)
    }

    @Test
    fun spliceRoundTrip_preservesBytesWhenTheWindowStartedMidCharacter() {
        val bytes = ("x".repeat(10) + "中" + "y".repeat(10)).toByteArray(Charsets.UTF_8)
        val window = loadEditWindow(ArrayByteWindow(bytes), startOffset = 11, maxBytes = 1024)

        assertEquals(10L, window.from)
        assertEquals(bytes.size.toLong(), window.to)
        assertEquals("中" + "y".repeat(10), window.text)
        assertArrayEquals(bytes, splice(bytes, window))
    }

    @Test
    fun aroundALongLine_stillIncludesTheRowOnScreen() {
        val text = "HEAD\n" + "x".repeat(1_000_000)
        val center = 100_000L
        val window = loadAround(text, center = center, maxBytes = 512L * 1024)

        assertTrue(window.from <= center)
        assertTrue(center < window.to)
        assertTrue(window.text.all { it == 'x' })
        assertArrayEquals(
            text.toByteArray(Charsets.UTF_8),
            splice(text.toByteArray(Charsets.UTF_8), window),
        )
    }

    @Test
    fun tailWindow_reachesEofWhenStartLandsOnAContinuationByte() {
        val text = "中".repeat(20) + "\nTAIL"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val window = loadEditWindowAround(
            ArrayByteWindow(bytes),
            centerOffset = bytes.size.toLong(),
            maxBytes = bytes.size - 1L,
        )

        assertTrue(window.text.endsWith("TAIL"))
        assertEquals(bytes.size.toLong(), window.to)
        assertArrayEquals(bytes, splice(bytes, window))
    }

    @Test
    fun aroundTheMiddleOfALongUnicodeLine_doesNotSplitACharacter() {
        val bytes = "中".repeat(400).toByteArray(Charsets.UTF_8)
        val window = loadEditWindowAround(ArrayByteWindow(bytes), centerOffset = 600, maxBytes = 50)

        assertFalse(window.text.contains('\uFFFD'))
        assertTrue(window.from > 0L)
        assertTrue(window.to < bytes.size)
        assertArrayEquals(
            bytes.copyOfRange(window.from.toInt(), window.to.toInt()),
            window.text.toByteArray(Charsets.UTF_8),
        )
        assertArrayEquals(bytes, splice(bytes, window))
    }

    @Test
    fun orphanContinuationBytes_areLeftInThePrefix() {
        val bytes = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 'A'.code.toByte(),
        )
        val window = loadEditWindow(ArrayByteWindow(bytes), startOffset = 3, maxBytes = 1024)

        assertEquals(4L, window.from)
        assertEquals("A", window.text)
        assertArrayEquals(bytes, splice(bytes, window))
    }

    private fun load(text: String, start: Long, maxBytes: Long): EditWindow {
        val source = ArrayByteWindow(text.toByteArray(Charsets.UTF_8))
        return loadEditWindow(source, start, maxBytes)
    }

    private fun loadAround(text: String, center: Long, maxBytes: Long): EditWindow {
        val source = ArrayByteWindow(text.toByteArray(Charsets.UTF_8))
        return loadEditWindowAround(source, center, maxBytes)
    }

    private fun splice(original: ByteArray, window: EditWindow): ByteArray {
        val encoded = window.text.toByteArray(Charsets.UTF_8)
        val prefix = window.from.toInt()
        val suffixFrom = window.to.toInt()
        return original.copyOfRange(0, prefix) + encoded + original.copyOfRange(suffixFrom, original.size)
    }
}
