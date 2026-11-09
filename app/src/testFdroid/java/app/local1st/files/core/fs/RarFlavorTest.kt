package app.local1st.files.core.fs

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RarFlavorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fdroidFlavor_leavesJunrarOut() {
        // F-Droid's scanner rejects junrar (UnRAR licence), so it must not reach this classpath.
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.github.junrar.Archive")
        }
    }

    @Test
    fun fdroidFlavor_readsEveryRarThroughLibarchive() {
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar4_solid_encrypted.rar")))
        assertFalse(RarArchives.readsItself(fixture("rar2-unix-owner.rar")))
        assertFalse(RarArchives.readsItself(fixture("rar15-comment.rar")))
    }

    @Test
    fun rar15Member_failsWithAnErrorInsteadOfReadingGarbage() {
        val archive = fixture("rar15-comment.rar")
        val fileSystem = ArchiveFileSystem(mimeOf = { null })
        val root = XEntry(
            id = XId.file(archive.absolutePath),
            name = archive.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = archive.absolutePath,
        )

        val entries = fileSystem.list(root)
        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), entries.map(XEntry::name))
        // FILE2.TXT is stored, so it reads; FILE1.TXT is packed with the RAR 1.5 algorithm.
        val stored = fileSystem.openIn(entries[1]).use { it.readBytes() }
        assertEquals(0x3C4306F7L, CRC32().apply { update(stored) }.value) // the CRC its header holds
        val e = assertThrows(IOException::class.java) { fileSystem.openIn(entries[0]).use { it.readBytes() } }
        assertEquals("RAR 1.5 compression is not supported", e.message)
    }

    private fun fixture(name: String): File {
        val target = File(temporaryFolder.root, name)
        javaClass.getResourceAsStream("/rar/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
