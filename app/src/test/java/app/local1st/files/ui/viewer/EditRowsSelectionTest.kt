package app.local1st.files.ui.viewer

import androidx.compose.ui.text.TextRange
import app.local1st.files.core.text.EditCaret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditRowsSelectionTest {

    @Test
    fun fieldRange_atColumnZeroOnALaterRow_isCollapsedInTheField() {
        val range = editFieldRange(
            text = "world",
            focusedLine = 1,
            caretCol = 0,
            anchorLine = 0,
            anchorCol = 3,
        )
        assertTrue(range.collapsed)
        assertEquals(0, range.start)
        assertEquals(0, range.end)
        assertFalse(
            editDocumentRangeCollapsed(
                focusedLine = 1,
                anchorLine = 0,
                fieldSelectionCollapsed = range.collapsed,
            ),
        )
    }

    @Test
    fun fieldRange_atEndOfAnEarlierRow_isCollapsedInTheField() {
        val range = editFieldRange(
            text = "hello",
            focusedLine = 0,
            caretCol = 5,
            anchorLine = 1,
            anchorCol = 0,
        )
        assertTrue(range.collapsed)
        assertEquals(5, range.start)
        assertFalse(
            editDocumentRangeCollapsed(
                focusedLine = 0,
                anchorLine = 1,
                fieldSelectionCollapsed = range.collapsed,
            ),
        )
    }

    @Test
    fun fieldRange_onTheSameRow_usesTheAnchor() {
        val range = editFieldRange(
            text = "hello",
            focusedLine = 0,
            caretCol = 4,
            anchorLine = 0,
            anchorCol = 1,
        )
        assertEquals(TextRange(1, 4), range)
        assertTrue(
            editDocumentRangeCollapsed(
                focusedLine = 0,
                anchorLine = 0,
                fieldSelectionCollapsed = true,
            ),
        )
    }

    @Test
    fun fieldRange_onALaterRow_selectsFromTheStart() {
        val range = editFieldRange(
            text = "world",
            focusedLine = 1,
            caretCol = 3,
            anchorLine = 0,
            anchorCol = 2,
        )
        assertEquals(TextRange(0, 3), range)
        assertFalse(range.collapsed)
    }

    @Test
    fun highlight_forANewlineOnlyRange_paintsTheEndOfTheStartRow() {
        val highlight = editLineHighlight(
            textLength = 5,
            line = 0,
            anchor = EditCaret(0, 5),
            caret = EditCaret(1, 0),
        )
        assertNotNull(highlight)
        assertEquals(5, highlight!!.start)
        assertEquals(6, highlight.end)
        assertTrue(highlight.extraSpace)
        assertNull(
            editLineHighlight(
                textLength = 5,
                line = 1,
                anchor = EditCaret(0, 5),
                caret = EditCaret(1, 0),
            ),
        )
    }

    @Test
    fun highlight_onAMiddleEmptyRow_paintsTheNewline() {
        val highlight = editLineHighlight(
            textLength = 0,
            line = 1,
            anchor = EditCaret(0, 0),
            caret = EditCaret(2, 0),
        )
        assertNotNull(highlight)
        assertEquals(0, highlight!!.start)
        assertEquals(1, highlight.end)
        assertTrue(highlight.extraSpace)
    }

    @Test
    fun highlight_onTheLastRowAtColumnZero_paintsNothing() {
        assertNull(
            editLineHighlight(
                textLength = 5,
                line = 1,
                anchor = EditCaret(0, 2),
                caret = EditCaret(1, 0),
            ),
        )
    }

    @Test
    fun highlight_onTheStartRow_coversTheRestAndTheNewline() {
        val highlight = editLineHighlight(
            textLength = 5,
            line = 0,
            anchor = EditCaret(0, 2),
            caret = EditCaret(1, 3),
        )
        assertEquals(EditLineHighlight(2, 6, extraSpace = true), highlight)
    }

    @Test
    fun caretMove_inTheFocusedRow_collapsesACrossLineRangeUnlessExtending() {
        assertTrue(
            editCollapseCrossLineOnCaretMove(
                fieldSelectionCollapsed = true,
                crossLine = true,
                extend = false,
            ),
        )
        assertFalse(
            editCollapseCrossLineOnCaretMove(
                fieldSelectionCollapsed = true,
                crossLine = true,
                extend = true,
            ),
        )
        assertFalse(
            editCollapseCrossLineOnCaretMove(
                fieldSelectionCollapsed = false,
                crossLine = true,
                extend = false,
            ),
        )
        assertFalse(
            editCollapseCrossLineOnCaretMove(
                fieldSelectionCollapsed = true,
                crossLine = false,
                extend = false,
            ),
        )
    }

    @Test
    fun listIndex_atRowZero_keepsHeadersOnScreen() {
        assertEquals(0, textRowsInitialListIndex(initialRow = 0, headerCount = 2))
        assertEquals(0, textRowsInitialListIndex(initialRow = 0, headerCount = 0))
    }

    @Test
    fun listIndex_restoringADocumentRow_skipsHeaders() {
        assertEquals(7, textRowsInitialListIndex(initialRow = 5, headerCount = 2))
        assertEquals(5, textRowsInitialListIndex(initialRow = 5, headerCount = 0))
    }

    @Test
    fun restoreReady_waitsUntilTheRowExistsUnlessTheScanFinished() {
        assertFalse(textRowsRestoreReady(rowCount = 4096, initialRow = 50_000, complete = false))
        assertTrue(textRowsRestoreReady(rowCount = 50_001, initialRow = 50_000, complete = false))
        assertTrue(textRowsRestoreReady(rowCount = 100, initialRow = 50_000, complete = true))
        assertTrue(textRowsRestoreReady(rowCount = 10, initialRow = 0, complete = false))
        assertTrue(textRowsRestoreReady(rowCount = 0, initialRow = 0, complete = true))
    }

    @Test
    fun offscreenReveal_scrollsOneRowWhenTheCaretIsAdjacent() {
        assertEquals(
            20,
            editOffscreenRevealPx(
                focused = 11,
                firstIndex = 0,
                firstSize = 20,
                lastIndex = 10,
                lastSize = 20,
            ),
        )
        assertEquals(
            -20,
            editOffscreenRevealPx(
                focused = 4,
                firstIndex = 5,
                firstSize = 20,
                lastIndex = 15,
                lastSize = 20,
            ),
        )
    }

    @Test
    fun offscreenReveal_jumpsWhenTheCaretIsFar() {
        assertNull(
            editOffscreenRevealPx(
                focused = 50,
                firstIndex = 0,
                firstSize = 20,
                lastIndex = 10,
                lastSize = 20,
            ),
        )
    }

    @Test
    fun keepInView_doesNothingWhenAlreadyVisible() {
        assertEquals(0, editKeepInViewDelta(top = 100, bottom = 120, usableTop = 0, usableBottom = 500))
    }

    @Test
    fun keepInView_scrollsTheMinimumToRevealAShortRow() {
        assertEquals(-40, editKeepInViewDelta(top = 10, bottom = 30, usableTop = 50, usableBottom = 550))
        assertEquals(20, editKeepInViewDelta(top = 540, bottom = 570, usableTop = 50, usableBottom = 550))
    }

    @Test
    fun keepInView_pinsTheTopWhenTheRangeIsTallerThanTheViewport() {
        assertEquals(20, editKeepInViewDelta(top = 70, bottom = 900, usableTop = 50, usableBottom = 550))
    }

    @Test
    fun replacement_collapsedAtEnd_takesTheInsertedTail() {
        assertEquals("\n", textFieldReplacement("hello", TextRange(5, 5), "hello\n"))
        assertEquals("X", textFieldReplacement("hello", TextRange(5, 5), "helloX"))
    }

    @Test
    fun replacement_wholeLine_isTheNewText() {
        assertEquals("XYZ", textFieldReplacement("world", TextRange(0, 5), "XYZ"))
        assertEquals("", textFieldReplacement("world", TextRange(0, 5), ""))
    }

    @Test
    fun replacement_withoutSharedSuffix_isIgnored() {
        assertNull(textFieldReplacement("world", TextRange(0, 2), "XYZ"))
        assertNull(textFieldReplacement("world", TextRange(2, 5), "XYZ"))
        assertEquals("X", textFieldReplacement("world", TextRange(2, 3), "woXld"))
    }

    @Test
    fun adjacentLine_requiresMatchingLayoutOnTheVisualEdge() {
        assertFalse(editMoveToAdjacentLine(layoutText = null, fieldText = "hello", onVisualEdge = true))
        assertFalse(editMoveToAdjacentLine(layoutText = "old", fieldText = "hello", onVisualEdge = true))
        assertFalse(editMoveToAdjacentLine(layoutText = "hello", fieldText = "hello", onVisualEdge = false))
        assertTrue(editMoveToAdjacentLine(layoutText = "hello", fieldText = "hello", onVisualEdge = true))
    }

    @Test
    fun tryJoin_onlyAtTheLineEdge() {
        assertTrue(editTryJoinAtEdge(true, false, 0, 5, 1, 0))
        assertTrue(editTryJoinAtEdge(true, false, 5, 5, 0, 1))
        assertFalse(editTryJoinAtEdge(true, false, 3, 5, 1, 0))
        assertFalse(editTryJoinAtEdge(true, false, 3, 5, 0, 1))
        assertFalse(editTryJoinAtEdge(false, false, 0, 5, 1, 0))
        assertFalse(editTryJoinAtEdge(true, true, 0, 5, 1, 0))
    }

    @Test
    fun imeJoinDuplicate_swallowsOnceOnTheImePath() {
        var now = 0L
        val swallow = ImeJoinSwallow(windowMs = 400L) { now }
        swallow.mark(previous = true)
        now = 10L
        assertFalse(editSwallowImeJoinDuplicate(0, 1, swallow))
        assertTrue(editSwallowImeJoinDuplicate(1, 0, swallow))
        assertFalse(editSwallowImeJoinDuplicate(1, 0, swallow))
    }

    @Test
    fun joinSwallow_takesOneMatchingDeleteInsideTheWindow() {
        var now = 0L
        val swallow = ImeJoinSwallow(windowMs = 400L) { now }
        swallow.mark(previous = true)
        now = 200L
        assertTrue(swallow.swallow(previous = true))
        assertFalse(swallow.swallow(previous = true))
    }

    @Test
    fun joinSwallow_ignoresTheOppositeDeleteAndExpires() {
        var now = 0L
        val swallow = ImeJoinSwallow(windowMs = 400L) { now }
        swallow.mark(previous = true)
        now = 10L
        assertFalse(swallow.swallow(previous = false))
        now = 400L
        assertFalse(swallow.swallow(previous = true))
        swallow.mark(previous = false)
        now = 500L
        assertTrue(swallow.swallow(previous = false))
    }
}
