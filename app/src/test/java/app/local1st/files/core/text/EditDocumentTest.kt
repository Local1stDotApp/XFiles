package app.local1st.files.core.text

import app.local1st.files.core.fs.ByteRangeEdit
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditDocumentTest {

    @Test
    fun aSmallFileLoadsWhole_andReadsLikeTheEditor() {
        val doc = document("alpha\nbeta\ngamma\n")

        assertEquals(3, doc.size)
        assertNull(doc.rowText(1))
        assertEquals(1, doc.fileRow(1))

        val loaded = doc.load(1)

        // Row 1 stays row 1; the file's trailing newline is a place to type, as at the end of a buffer.
        assertEquals(1, loaded.row)
        assertEquals(0, loaded.column)
        assertEquals(4, doc.size)
        assertEquals(listOf("alpha", "beta", "gamma", ""), doc.rows())
        assertTrue(doc.isLoaded(3))
        assertEquals(3, doc.lineCount())
        assertFalse(doc.isDirty)
        assertEquals(emptyList<ByteRangeEdit>(), doc.edits())
    }

    @Test
    fun editingALoadedRow_splicesOnlyThatStretchOnSave() {
        val text = lines(200)
        val doc = document(text, chunkBytes = 300)

        val loaded = doc.load(100)
        assertEquals(100, loaded.row)
        assertEquals("line-100", doc.rowText(100))
        assertNull(doc.rowText(0))
        assertNull(doc.rowText(199))
        assertEquals(200, doc.size)

        assertNotNull(doc.replace(100, "line-100 edited", 15))
        assertTrue(doc.isDirty)
        val edits = doc.edits()
        assertEquals(1, edits.size)
        assertTrue(edits[0].from > 0L)
        assertTrue(edits[0].to < text.toByteArray().size)
        assertEquals(text.replace("line-100\n", "line-100 edited\n"), doc.saved(text))
        assertEquals(200, doc.lineCount())
    }

    @Test
    fun enterAndJoin_moveTheRowsBelowAndKeepTheCountRight() {
        val text = lines(50)
        val doc = document(text, chunkBytes = 120)
        doc.load(20)

        val caret = doc.replace(20, "line-\n20", 6)
        assertEquals(EditCaret(21, 0), caret)
        assertEquals(51, doc.size)
        assertEquals(51, doc.lineCount())
        assertEquals("20", doc.rowText(21))
        // Rows below the stretch are the file's, one further down.
        assertEquals(40, doc.fileRow(41))

        assertEquals(EditCaret(20, 5), doc.mergeWithPrevious(21))
        assertEquals(50, doc.size)
        assertEquals(50, doc.lineCount())
        assertEquals(text, doc.saved(text))
    }

    @Test
    fun twoStretchesApart_areTwoEditsInFileOrder() {
        val text = lines(400)
        val doc = document(text, chunkBytes = 200)
        doc.load(350)
        doc.load(20)

        assertNotNull(doc.replace(20, "A", 1))
        assertNotNull(doc.replace(350, "B", 1))

        val edits = doc.edits()
        assertEquals(2, edits.size)
        assertTrue(edits[0].to <= edits[1].from)
        assertEquals(text.replace("line-20\n", "A\n").replace("line-350\n", "B\n"), doc.saved(text))
    }

    @Test
    fun loadingTheRowAfterAStretch_joinsThemWithoutABlankSeam() {
        val text = lines(100)
        val doc = document(text, chunkBytes = 100)
        doc.load(30)
        val range = doc.loadedRange(30)!!
        val after = range.last + 1
        assertNull(doc.rowText(after))

        val loaded = doc.load(after)

        assertEquals(after, loaded.row)
        assertEquals(100, doc.size)
        val joined = doc.loadedRange(30)!!
        assertTrue(joined.first == range.first && joined.last > after)
        for (i in joined) assertEquals("line-$i", doc.rowText(i))
        assertNotNull(doc.replace(after, "joined", 6))
        assertEquals(1, doc.edits().size)
        assertEquals(text.replace("line-$after\n", "joined\n"), doc.saved(text))
    }

    @Test
    fun loadingTheRowBeforeAStretch_keepsTheCaretRowAndItsHistory() {
        val text = lines(100)
        val doc = document(text, chunkBytes = 100)
        val first = doc.load(50)
        val range = doc.loadedRange(first.row)!!
        assertNotNull(doc.replace(first.row, "fifty", 5))
        val before = range.first - 1

        val loaded = doc.load(before, keepRow = first.row)

        assertEquals(before, loaded.row)
        assertEquals(first.row, loaded.keptRow)
        assertEquals("fifty", doc.rowText(first.row))
        // The edit made before the join is still the one undo reverses.
        assertEquals(EditCaret(first.row, 0), doc.undo())
        assertEquals("line-50", doc.rowText(first.row))
        assertFalse(doc.isDirty)
    }

    @Test
    fun aStretchEndingInANewline_doesNotShowTheEmptyRowAfterIt_untilTheNewlineGoes() {
        val text = lines(60)
        val doc = document(text, chunkBytes = 100)
        doc.load(30)
        val range = doc.loadedRange(30)!!
        assertEquals(60, doc.size)
        val last = range.last
        assertEquals("line-$last", doc.rowText(last))
        assertNull(doc.rowText(last + 1))

        // Delete at the end of the stretch's last row eats the newline; the line now runs on
        // into the row still on disk, which the document shows as a second piece of it.
        assertEquals(EditCaret(last, "line-$last".length), doc.mergeWithNext(last))
        assertEquals(60, doc.size)
        assertEquals(59, doc.lineCount())
        assertEquals(text.replace("line-$last\n", "line-$last"), doc.saved(text))
    }

    @Test
    fun deleteAtTheEndOfTheLastLoadedRow_isRefusedWhenTheLineContinuesOnDisk() {
        // Rows broken by length: a stretch can end mid-line, where the next character is on disk.
        val text = "x".repeat(1000)
        val doc = document(text, maxRowBytes = 100, chunkBytes = 300)
        val loaded = doc.load(4)
        val range = doc.loadedRange(loaded.row)!!
        // Three 100-byte pieces of the file became one editor row; the rows around are still pieces.
        assertEquals(8, doc.size)
        assertEquals(300, doc.rowText(loaded.row)!!.length)

        assertNull(doc.mergeWithNext(range.last))
        assertNull(doc.mergeWithPrevious(range.first))
        assertFalse(doc.isDirty)
        assertEquals(1, doc.lineCount())
        assertEquals(text, doc.saved(text))
    }

    @Test
    fun aRowPartWayThroughALongLine_landsPartWayAlongTheEditorRowItJoins() {
        // Rows broken by length, in a three-byte script: each 300-byte file row is 100 characters,
        // and the editor's longer rows put several of them together, so the requested row's
        // first character is not at column 0 of the row it is on.
        val text = String(CharArray(1000) { '\u4E00' + it % 97 })
        val doc = document(text, maxRowBytes = 300, chunkBytes = 900)

        val loaded = doc.load(4)

        assertEquals(3, loaded.row)
        assertEquals(100, loaded.column)
        val row = doc.rowText(loaded.row)!!
        assertEquals(300, row.length)
        assertEquals(text.substring(400, 500), row.substring(loaded.column, loaded.column + 100))
    }

    @Test
    fun typingCoalescesIntoOneUndo_andUndoRedoRoundTrip() {
        var now = 0L
        val doc = document("hello\nworld\n", clock = { now })
        doc.load(0)

        assertNotNull(doc.replace(0, "hello!", 6))
        now += 100
        assertNotNull(doc.replace(0, "hello!!", 7))
        now += 5000
        assertNotNull(doc.replace(1, "world?", 6))

        assertTrue(doc.canUndo)
        assertFalse(doc.canRedo)
        assertEquals(EditCaret(1, 5), doc.undo())
        assertEquals("world", doc.rowText(1))
        assertEquals(EditCaret(0, 5), doc.undo())
        assertEquals("hello", doc.rowText(0))
        assertFalse(doc.isDirty)
        assertFalse(doc.canUndo)
        assertNull(doc.undo())

        assertEquals(EditCaret(0, 7), doc.redo())
        assertEquals("hello!!", doc.rowText(0))
        assertTrue(doc.isDirty)
        assertEquals("hello!!\nworld\n", doc.saved("hello\nworld\n"))

        // A new edit clears what could have been redone.
        assertNotNull(doc.replace(1, "w", 1))
        assertFalse(doc.canRedo)
    }

    @Test
    fun typingThenDeletingTheSameCharacters_isNotDirty() {
        var now = 0L
        val doc = document("hello\nworld\n", clock = { now })
        doc.load(0)

        assertNotNull(doc.replace(0, "helloX", 6))
        assertTrue(doc.isDirty)
        now += 100
        assertNotNull(doc.replace(0, "hello", 5))

        assertFalse(doc.isDirty)
        assertFalse(doc.canUndo)
        assertEquals(emptyList<ByteRangeEdit>(), doc.edits())
        assertEquals("hello\nworld\n", doc.saved("hello\nworld\n"))
    }

    @Test
    fun aCoalescedNoOp_doesNotDropAnEarlierEdit() {
        var now = 0L
        val doc = document("hello\nworld\n", clock = { now })
        doc.load(0)

        assertNotNull(doc.replace(0, "hello!", 6))
        now += 5000
        assertNotNull(doc.replace(0, "hello!X", 7))
        now += 100
        assertNotNull(doc.replace(0, "hello!", 6))

        assertTrue(doc.isDirty)
        assertEquals("hello!", doc.rowText(0))
        assertEquals(EditCaret(0, 5), doc.undo())
        assertEquals("hello", doc.rowText(0))
        assertFalse(doc.isDirty)
    }

    @Test
    fun undoRunsAcrossStretchesInTheOrderTheEditsWereMade() {
        val text = lines(400)
        val doc = document(text, chunkBytes = 200)
        doc.load(20)
        doc.load(350)
        assertNotNull(doc.replace(20, "first", 5))
        assertNotNull(doc.replace(350, "second", 6))

        assertEquals(EditCaret(350, 0), doc.undo())
        assertEquals("line-350", doc.rowText(350))
        assertEquals("first", doc.rowText(20))
        assertEquals(EditCaret(20, 0), doc.undo())
        assertFalse(doc.isDirty)
        assertEquals(text, doc.saved(text))
    }

    @Test
    fun untouchedStretchesAreDroppedToMakeRoom_editedOnesAreNot() {
        val text = lines(1000)
        val doc = document(text, chunkBytes = 300, maxLoadedBytes = 700)
        doc.load(10)
        doc.load(500)
        assertTrue(doc.isLoaded(10))
        assertTrue(doc.isLoaded(500))

        doc.load(900)

        // The stretch farthest from the new one went; the nearer one stayed.
        assertFalse(doc.isLoaded(10))
        assertTrue(doc.isLoaded(500))
        assertTrue(doc.isLoaded(900))
        assertEquals(1000, doc.size)

        assertNotNull(doc.replace(500, "kept", 4))
        assertNotNull(doc.replace(900, "kept", 4))
        val pending = doc.prepareLoad(doc.fileRow(10))!!
        assertNull(doc.install(pending))
        assertEquals(2, doc.edits().size)
    }

    @Test
    fun theSizeCapAlsoBoundsTyping() {
        val doc = document("ab\n", maxLoadedBytes = 8)
        doc.load(0)

        assertNotNull(doc.replace(0, "abcde", 5))
        assertNull(doc.replace(0, "abcdefghij", 10))
        assertEquals("abcde", doc.rowText(0))
    }

    @Test
    fun anEmptyFile_getsOneRowToTypeOn_andSaveAppends() {
        val doc = document("")
        assertEquals(0, doc.size)

        val loaded = doc.load(0)

        assertEquals(0, loaded.row)
        assertEquals(1, doc.size)
        assertEquals("", doc.rowText(0))
        assertEquals(0, doc.lineCount())
        assertEquals(EditCaret(1, 0), doc.replace(0, "new\n", 4))
        assertEquals(1, doc.lineCount())
        val edits = doc.edits()
        assertEquals(1, edits.size)
        assertEquals(0L, edits[0].from)
        assertEquals(0L, edits[0].to)
        assertEquals("new\n", doc.saved(""))
    }

    @Test
    fun aByteOrderMarkStaysOutsideEveryStretch() {
        val text = "\uFEFFalpha\nbeta\n"
        val doc = document(text)
        doc.load(0)

        assertEquals("alpha", doc.rowText(0))
        assertNotNull(doc.replace(0, "ALPHA", 5))
        val edit = doc.edits().single()
        assertEquals(3L, edit.from)
        assertEquals("\uFEFFALPHA\nbeta\n", doc.saved(text))
    }

    @Test
    fun gbkRows_editAndSaveInGbk() {
        val charset = Charset.forName("GBK")
        val text = "你好\n世界\n再见\n"
        val doc = document(text, charset = charset, chunkBytes = 6)

        val loaded = doc.load(1)
        assertEquals(1, loaded.row)
        assertEquals("世界", doc.rowText(1))
        assertNotNull(doc.replace(1, "世界！", 3))

        val edit = doc.edits().single()
        assertArrayEquals("世界！\n".toByteArray(charset), edit.bytes)
        assertEquals(text.replace("世界", "世界！"), doc.saved(text, charset))
    }

    @Test
    fun selectionAcrossRowsOfOneStretch_copiesAndReplaces() {
        val doc = document("one\ntwo\nthree\n")
        doc.load(0)

        assertEquals("ne\ntw", doc.textInRange(EditCaret(0, 1), EditCaret(1, 2)))
        assertEquals(EditCaret(0, 2), doc.replaceRange(EditCaret(0, 1), EditCaret(1, 2), "X"))
        assertEquals(listOf("oXo", "three", ""), doc.rows())
        assertEquals("oXo\nthree\n", doc.saved("one\ntwo\nthree\n"))
    }

    @Test
    fun aRangeThatReachesARowStillOnDisk_isNotOneTheDocumentCanServe() {
        val text = lines(100)
        val doc = document(text, chunkBytes = 100)
        doc.load(50)
        val range = doc.loadedRange(50)!!

        assertNull(doc.textInRange(EditCaret(range.first, 0), EditCaret(range.last + 1, 0)))
        assertNull(doc.replaceRange(EditCaret(range.first, 0), EditCaret(range.last + 1, 0), ""))
    }

    @Test
    fun nearestFileRow_followsTheEditorBackToTheViewer() {
        val text = lines(100)
        val doc = document(text, chunkBytes = 100)
        doc.load(50)
        assertEquals(10, doc.nearestFileRow(10))
        assertEquals(50, doc.nearestFileRow(50))
        assertNotNull(doc.replace(50, "a\nb\nc", 5))
        // Rows below the stretch shifted by two; the viewer's row for them did not.
        assertEquals(90, doc.nearestFileRow(92))
    }

    @Test
    fun rowsAreNotLoadedTwice() {
        val doc = document(lines(10))
        doc.load(3)

        assertNull(doc.prepareLoad(3))
    }

    // -- helpers --------------------------------------------------------------------------------

    private fun document(
        text: String,
        charset: Charset = Charsets.UTF_8,
        maxRowBytes: Int = MAX_ROW_BYTES,
        chunkBytes: Int = EditDocument.DEFAULT_CHUNK_BYTES,
        maxLoadedBytes: Int = EditDocument.DEFAULT_MAX_LOADED_BYTES,
        clock: () -> Long = { 0L },
    ): EditDocument {
        val bytes = text.toByteArray(charset)
        val window = ArrayByteWindow(bytes)
        val index = TextRowIndex(window, maxRowBytes, charset = charset)
        runBlocking { index.scan {} }
        return EditDocument(window, index, charset, chunkBytes, maxLoadedBytes, clock = clock)
    }

    private fun EditDocument.load(fileRow: Int, keepRow: Int = -1): EditDocument.Loaded {
        val pending = checkNotNull(prepareLoad(fileRow)) { "row $fileRow is not loadable" }
        return checkNotNull(install(pending, keepRow)) { "row $fileRow could not be installed" }
    }

    private fun EditDocument.rows(): List<String> = (0 until size).map { rowText(it) ?: "<disk:${fileRow(it)}>" }

    private fun EditDocument.saved(original: String, charset: Charset = Charsets.UTF_8): String =
        String(applyEdits(original.toByteArray(charset), edits()), charset)

    private fun applyEdits(original: ByteArray, edits: List<ByteRangeEdit>): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        for (edit in edits.sortedBy { it.from }) {
            out.write(original, at, edit.from.toInt() - at)
            out.write(edit.bytes)
            at = edit.to.toInt()
        }
        out.write(original, at, original.size - at)
        return out.toByteArray()
    }

    private fun lines(n: Int): String = (0 until n).joinToString("") { "line-$it\n" }
}
