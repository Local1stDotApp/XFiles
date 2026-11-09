package app.local1st.files.core.fs

import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The full flavor hands junrar the RAR archives libarchive can't read, those made by RAR 1.x. */
class RarFlavorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })

    @Test
    fun fullFlavor_keepsJunrarForWhatLibarchiveCantRead() {
        Class.forName("com.github.junrar.Archive")

        assertTrue(RarArchives.readsItself(fixture("rar15.rar")))
        assertTrue(RarArchives.readsItself(fixture("rar15-comment.rar")))
        // RAR 2.x and solid RAR 4 are read natively now (Rar2Test, SolidRar4Test).
        assertFalse(RarArchives.readsItself(fixture("rar2-unix-owner.rar")))
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar4_solid_encrypted.rar")))
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar5_compressed.rar")))
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar_multivolume.part0001.rar")))
    }

    @Test
    fun rar15Archive_opensThroughJunrar() {
        // rar15-comment.rar without its file comments, which junrar can't read past.
        val entries = fileSystem.list(root(fixture("rar15.rar")))

        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), entries.map(XEntry::name))
        // The CRCs their headers hold. FILE1.TXT is packed with the RAR 1.5 algorithm, FILE2.TXT stored.
        val crcs = entries.map { entry -> CRC32().apply { update(fileSystem.openIn(entry).use { it.readBytes() }) }.value }
        assertEquals(listOf(0xE27F07A9L, 0x3C4306F7L), crcs)
    }

    private fun root(archive: File) = XEntry(
        id = XId.file(archive.absolutePath),
        name = archive.name,
        isDir = false,
        kind = EntryKind.ARCHIVE,
        localPath = archive.absolutePath,
    )

    private fun fixture(name: String): File {
        val target = File(temporaryFolder.root, name)
        javaClass.getResourceAsStream("/rar/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
