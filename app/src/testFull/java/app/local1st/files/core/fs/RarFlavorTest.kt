package app.local1st.files.core.fs

import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The full flavor hands junrar the RAR archives libarchive can't read. */
class RarFlavorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })

    @Test
    fun fullFlavor_keepsJunrarForWhatLibarchiveCantRead() {
        Class.forName("com.github.junrar.Archive")

        assertTrue(RarArchives.readsItself(fixture("rar2-unix-owner.rar")))
        // Solid RAR 4 is read natively now (SolidRar4Test).
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar4_solid_encrypted.rar")))
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar5_compressed.rar")))
        assertFalse(RarArchives.readsItself(fixture("test_read_format_rar_multivolume.part0001.rar")))
    }

    @Test
    fun rar2Archive_opensThroughJunrar() {
        val entries = fileSystem.list(root(fixture("rar2-unix-owner.rar")))

        assertEquals(listOf("file.txt"), entries.map(XEntry::name))
        val bytes = fileSystem.openIn(entries.single()).use { it.readBytes() }
        assertEquals(4, bytes.size)
        assertEquals(0x7E3265A8L, CRC32().apply { update(bytes) }.value) // the CRC its header holds
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
