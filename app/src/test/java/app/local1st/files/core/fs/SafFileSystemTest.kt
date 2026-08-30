package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SafLocation
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFileSystemTest {

    private val location = SafLocation(
        id = "loc",
        treeUri = "content://provider/tree/root",
        displayName = "NAS",
        writable = true,
    )

    @Test
    fun listMkdirCreateRoundTrip() {
        val backend = MemoryDocumentsBackend()
        val fs = SafFileSystem(backend) { listOf(location) }
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        assertEquals(EntryKind.LOCATION, root.kind)
        assertEquals("NAS", root.name)
        assertTrue(root.canWrite)

        val dir = fs.mkdir(root, "docs")
        assertTrue(dir.isDir)
        assertEquals(1, XId.safDocumentIds(dir.id).size)
        assertEquals("docs", dir.name)

        val file = fs.createFile(dir, "note.txt")
        assertFalse(file.isDir)
        fs.openOut(dir, "note.txt").use { it.write("hi".toByteArray()) }
        val text = fs.openIn(file).use { it.readBytes().decodeToString() }
        assertEquals("hi", text)

        val listed = fs.list(dir)
        assertEquals(listOf("note.txt"), listed.map { it.name })
    }

    @Test
    fun exclusiveCreateDeletesProviderAutoRename() {
        val backend = MemoryDocumentsBackend(autoRename = true)
        val fs = SafFileSystem(backend) { listOf(location) }
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        assertThrows(FileAlreadyExistsException::class.java) {
            fs.createFile(root, "note.txt")
        }
        assertTrue(fs.list(root).isEmpty())
    }

    @Test
    fun deadProviderLeavesRootStatNull() {
        val backend = MemoryDocumentsBackend(dead = true)
        val fs = SafFileSystem(backend) { listOf(location) }
        assertNull(fs.stat(XId.saf("loc")))
        assertThrows(IOException::class.java) { fs.list(XEntry(id = XId.saf("loc"), name = "NAS", isDir = true)) }
        val painted = safLocationRoot(location, stat = null)
        assertEquals("Not available", painted.badge)
        assertFalse(painted.canWrite)
        assertEquals(EntryKind.LOCATION, painted.kind)
    }

    @Test
    fun refusesDeletingTheLocationRoot() {
        val fs = SafFileSystem(MemoryDocumentsBackend()) { listOf(location) }
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        assertThrows(IOException::class.java) { fs.delete(root) }
    }

    @Test
    fun mkdirIsIdempotent() {
        val fs = SafFileSystem(MemoryDocumentsBackend()) { listOf(location) }
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        val first = fs.mkdir(root, "a")
        val second = fs.mkdir(root, "a")
        assertEquals(first.id, second.id)
    }
}
