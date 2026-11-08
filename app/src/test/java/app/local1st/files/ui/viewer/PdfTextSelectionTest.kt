package app.local1st.files.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfTextSelectionTest {

    private val line1 = Rect(344f, 73f, 383f, 85f)
    private val line2 = Rect(72f, 95f, 410f, 107f)

    @Test
    fun copiedLinesEndWithLineFeeds() {
        val selection = pdfTextSelection(
            pageIndex = 0,
            texts = listOf("foxtrot golf hotel\r\nLine 02", "\rend"),
            lines = listOf(line1, line2),
        )
        assertEquals("foxtrot golf hotel\nLine 02\nend", selection?.text)
    }

    @Test
    fun nothingButWhiteSpaceIsNoSelection() {
        assertNull(pdfTextSelection(0, listOf(" \r\n "), listOf(line1)))
        assertNull(pdfTextSelection(0, listOf("text"), emptyList()))
    }

    @Test
    fun handlesHangFromTheFirstAndLastLine() {
        val selection = checkNotNull(pdfTextSelection(3, listOf("a\r\nb"), listOf(line1, line2)))
        assertEquals(3, selection.pageIndex)
        assertEquals(Offset(344f, 85f), selection.startCorner)
        assertEquals(Offset(410f, 107f), selection.stopCorner)
    }
}
