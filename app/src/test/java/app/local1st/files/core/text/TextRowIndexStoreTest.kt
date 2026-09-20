package app.local1st.files.core.text

import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import app.local1st.files.core.fs.FileStamp

class TextRowIndexStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aSavedTableComesBackAndRestoresWithoutAScan() {
        val file = textFile((0 until 500).joinToString("\n") { "row $it" })
        val store = store()
        val scanned = scan(file)
        store.save(file, FileStamp.of(file), scanned.snapshot()!!)

        val snapshot = store.load(file, Charsets.UTF_8)
        assertNotNull(snapshot)

        FileByteWindow(file).use { window ->
            val restored = TextRowIndex(window, initialCheckpoints = 2, maxCheckpoints = 8)
            assertTrue(restored.restore(snapshot!!))
            assertTrue(restored.isComplete)
            assertEquals(scanned.rowCount, restored.rowCount)
            assertEquals(scanned.lineCount, restored.lineCount)
            assertEquals(scanned.rows(0, 500), restored.rows(0, 500))
            assertEquals(scanned.rowStart(377), restored.rowStart(377))
        }
    }

    @Test
    fun aTableIsOnlyForTheBytesItWasScannedFrom() {
        val file = textFile("a\nb\nc\n")
        val store = store()
        val stamp = FileStamp.of(file)
        store.save(file, stamp, scan(file).snapshot()!!)
        assertNotNull(store.load(file, Charsets.UTF_8))

        file.appendText("d\n")
        file.setLastModified(stamp.mtime + 10_000)

        assertNull(store.load(file, Charsets.UTF_8))
    }

    @Test
    fun aTableForAnotherEncodingOrRowBudgetIsNotUsed() {
        val file = textFile("a\nb\nc\n")
        val store = store()
        store.save(file, FileStamp.of(file), scan(file).snapshot()!!)

        assertNull(store.load(file, Charset.forName("GBK")))
        assertNull(store.load(file, Charsets.UTF_8, maxRowBytes = 64))
        assertNotNull(store.load(file, Charsets.UTF_8))
    }

    @Test
    fun smallFilesAreNotStored() {
        val file = textFile("a\nb\nc\n")
        val store = TextRowIndexStore(temporaryFolder.newFolder("idx"), minBytes = 1 shl 20)

        store.save(file, FileStamp.of(file), scan(file).snapshot()!!)

        assertNull(store.load(file, Charsets.UTF_8))
        assertEquals(0, store.dirSize())
    }

    @Test
    fun aDamagedEntryIsDroppedRatherThanTrusted() {
        val file = textFile("a\nb\nc\n")
        val dir = temporaryFolder.newFolder("idx")
        val store = TextRowIndexStore(dir, minBytes = 0)
        store.save(file, FileStamp.of(file), scan(file).snapshot()!!)
        val entry = dir.listFiles()!!.single()
        entry.writeBytes(entry.readBytes().copyOf(entry.length().toInt() - 3))

        assertNull(store.load(file, Charsets.UTF_8))
        assertFalse(entry.exists())
    }

    @Test
    fun theStoreKeepsOnlyTheMostRecentEntries() {
        val dir = temporaryFolder.newFolder("idx")
        val store = TextRowIndexStore(dir, minBytes = 0, maxEntries = 2)
        val files = (0 until 3).map { textFile("f$it\n", name = "f$it.txt") }
        val seen = HashSet<String>()
        files.forEachIndexed { i, file ->
            store.save(file, FileStamp.of(file), scan(file).snapshot()!!)
            // Age the entries in save order, whatever the clock's resolution.
            dir.listFiles()!!.filter { seen.add(it.name) }.forEach { it.setLastModified(1_000_000L * (i + 1)) }
        }

        assertEquals(2, dir.listFiles()!!.size)
        assertNull(store.load(files[0], Charsets.UTF_8))
        assertNotNull(store.load(files[2], Charsets.UTF_8))
    }

    @Test
    fun aRestoredIndexRefusesTheWrongSnapshot() {
        val file = textFile("a\nb\nc\n")
        val snapshot = scan(file).snapshot()!!
        FileByteWindow(textFile("a\nb\nc\nd\n", name = "other.txt")).use { window ->
            assertFalse(TextRowIndex(window).restore(snapshot))
        }
        FileByteWindow(file).use { window ->
            assertFalse(TextRowIndex(window, maxRowBytes = 64).restore(snapshot))
            assertTrue(TextRowIndex(window).restore(snapshot))
        }
    }

    @Test
    fun snapshotIsNullUntilTheScanFinishes() {
        val window = ArrayByteWindow("a\nb\n".toByteArray())
        val index = TextRowIndex(window)
        assertNull(index.snapshot())
        runBlocking { index.scan {} }
        assertNotNull(index.snapshot())
    }

    private fun store() = TextRowIndexStore(temporaryFolder.newFolder("idx"), minBytes = 0)

    private fun TextRowIndexStore.dirSize(): Int =
        temporaryFolder.root.listFiles()!!.first { it.isDirectory }.listFiles()!!.size

    private fun textFile(text: String, name: String = "text.txt"): File {
        val file = File(temporaryFolder.root, name)
        file.writeText(text)
        return file
    }

    private fun scan(file: File): TextRowIndex {
        // Small tables so the snapshot carries a compacted stride, not just the trivial one.
        val index = TextRowIndex(FileByteWindow(file), initialCheckpoints = 2, maxCheckpoints = 8)
        runBlocking { index.scan {} }
        return index
    }
}
