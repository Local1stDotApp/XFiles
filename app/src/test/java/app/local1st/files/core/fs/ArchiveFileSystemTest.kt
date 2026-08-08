package app.local1st.files.core.fs

import java.nio.charset.StandardCharsets
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveFileSystemTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun duplicateEntryNamesDoNotRejectTheWholeArchive() {
        val archive = temporaryFolder.root.resolve("duplicate entries.zip")
        ZipArchiveOutputStream(archive).use { output ->
            output.putArchiveEntry(ZipArchiveEntry("duplicate"))
            output.write("first".toByteArray(StandardCharsets.UTF_8))
            output.closeArchiveEntry()

            output.putArchiveEntry(ZipArchiveEntry("duplicate"))
            output.write("later".toByteArray(StandardCharsets.UTF_8))
            output.closeArchiveEntry()
        }
        val archiveEntry = XEntry(
            id = XId.file(archive.absolutePath),
            name = archive.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = archive.absolutePath,
        )
        val fileSystem = ArchiveFileSystem()

        val entries = fileSystem.list(archiveEntry)

        assertEquals(listOf("duplicate"), entries.map(XEntry::name))
        val contents = fileSystem.openIn(entries.single()).bufferedReader().use { it.readText() }
        assertEquals("first", contents)
    }
}
