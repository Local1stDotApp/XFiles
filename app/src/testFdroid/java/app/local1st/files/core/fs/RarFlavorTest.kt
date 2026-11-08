package app.local1st.files.core.fs

import java.io.File
import java.io.IOException
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
    }

    @Test
    fun rar2Archive_failsWithAnErrorInsteadOfReadingGarbage() {
        val archive = fixture("rar2-unix-owner.rar")
        val fileSystem = ArchiveFileSystem(mimeOf = { null })
        val root = XEntry(
            id = XId.file(archive.absolutePath),
            name = archive.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = archive.absolutePath,
        )

        // libarchive trips over the RAR 2 block after the member, or failing that its data.
        assertThrows(IOException::class.java) {
            fileSystem.list(root).forEach { entry -> fileSystem.openIn(entry).use { it.readBytes() } }
        }
    }

    private fun fixture(name: String): File {
        val target = File(temporaryFolder.root, name)
        javaClass.getResourceAsStream("/rar/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
