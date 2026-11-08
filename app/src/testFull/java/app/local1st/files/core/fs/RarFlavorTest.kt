package app.local1st.files.core.fs

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The full flavor hands junrar the RAR archives libarchive can't read. */
class RarFlavorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fullFlavor_keepsJunrarForSolidRar4() {
        Class.forName("com.github.junrar.Archive")

        assertTrue(RarArchives.readsItself(fixture("test_read_format_rar4_solid_encrypted.rar")))
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar5_compressed.rar")))
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar_multivolume.part0001.rar")))
    }

    @Test
    fun solidRar4_listsAndOpensThroughJunrar() {
        val archive = fixture("test_read_format_rar4_solid_encrypted.rar")
        val fileSystem = ArchiveFileSystem(mimeOf = { null })
        val root = XEntry(
            id = XId.file(archive.absolutePath),
            name = archive.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = archive.absolutePath,
        )

        val entries = fileSystem.list(root)

        assertEquals(listOf("a.txt", "b.txt", "c.txt", "d.txt"), entries.map(XEntry::name))
        val e = assertThrows(IOException::class.java) { fileSystem.openIn(entries.first()).use { it.read() } }
        assertEquals("a.txt is password-protected", e.message)
    }

    private fun fixture(name: String): File {
        val target = File(temporaryFolder.root, name)
        javaClass.getResourceAsStream("/rar/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
