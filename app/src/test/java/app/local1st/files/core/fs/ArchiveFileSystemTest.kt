package app.local1st.files.core.fs

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun grantOnlyZipCanBeListed() {
        val bytes = java.io.ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { output ->
            output.putArchiveEntry(ZipArchiveEntry("inside"))
            output.write("hello".toByteArray(StandardCharsets.UTF_8))
            output.closeArchiveEntry()
        }
        val missing = File(temporaryFolder.root, "notes.zip")
        val fileSystem = ArchiveFileSystem(openGrant = { file ->
            if (file.absolutePath == missing.absolutePath) bytes.toByteArray().inputStream() else null
        })
        val entry = XEntry(
            id = XId.file(missing.absolutePath),
            name = missing.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = null,
        )

        val names = fileSystem.list(entry).map(XEntry::name)

        assertEquals(listOf("inside"), names)
        assertEquals("hello", fileSystem.openIn(fileSystem.list(entry).single()).bufferedReader().use { it.readText() })
    }

    @Test
    fun grantOnlyTarGzCanBeListed() {
        val bytes = ByteArrayOutputStream()
        GzipCompressorOutputStream(bytes).use { gzip ->
            TarArchiveOutputStream(gzip).use { output ->
                val payload = "hello".toByteArray(StandardCharsets.UTF_8)
                val tarEntry = TarArchiveEntry("inside")
                tarEntry.size = payload.size.toLong()
                output.putArchiveEntry(tarEntry)
                output.write(payload)
                output.closeArchiveEntry()
            }
        }
        val missing = File(temporaryFolder.root, "photos.tar.gz")
        val fileSystem = ArchiveFileSystem(openGrant = { file ->
            if (file.absolutePath == missing.absolutePath) bytes.toByteArray().inputStream() else null
        })
        val entry = XEntry(
            id = XId.file(missing.absolutePath),
            name = missing.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = null,
        )

        val names = fileSystem.list(entry).map(XEntry::name)

        assertEquals(listOf("inside"), names)
        assertEquals(
            "hello",
            fileSystem.openIn(fileSystem.list(entry).single()).bufferedReader().use { it.readText() },
        )
    }

    @Test
    fun replacedGrantOnlyArchiveIsListedAgain() {
        val missing = File(temporaryFolder.root, "notes.zip")
        var stamp = 1L to 1L
        var payload = "old"
        val fileSystem = ArchiveFileSystem(
            grantStamp = { file ->
                if (file.absolutePath == missing.absolutePath) stamp else null
            },
            openGrant = { file ->
                if (file.absolutePath == missing.absolutePath) zipBytes(payload).inputStream() else null
            },
        )
        val entry = XEntry(
            id = XId.file(missing.absolutePath),
            name = missing.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = null,
        )

        assertEquals(payload, readOnlyChild(fileSystem, entry))
        stamp = 2L to 1L
        payload = "new"
        assertEquals(payload, readOnlyChild(fileSystem, entry))
    }

    @Test
    fun binSymlinkIsNotOpenedAsAnArchive() {
        val volume = temporaryFolder.newFolder("vol")
        val live = temporaryFolder.newFile("live.aab").apply { writeText("zip-bytes") }
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val link = File(bucket, "photos.aab")
        Files.createSymbolicLink(link.toPath(), live.toPath())
        val fileSystem = ArchiveFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val entry = XEntry(
            id = XId.file(link.absolutePath),
            name = link.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = link.absolutePath,
        )

        assertThrows(IOException::class.java) { fileSystem.list(entry) }
        assertThrows(IOException::class.java) { fileSystem.openIn(entry) }
        assertEquals("zip-bytes", live.readText())
    }

    @Test
    fun symlinkThatResolvesIntoTheBinIsNotOpenedAsAnArchive() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val live = File(bucket, "photos.aab").apply { writeText("zip-bytes") }
        val link = File(volume, "photos.aab")
        Files.createSymbolicLink(link.toPath(), live.toPath())
        val fileSystem = ArchiveFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val entry = XEntry(
            id = XId.file(link.absolutePath),
            name = link.name,
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = link.absolutePath,
        )

        assertThrows(IOException::class.java) { fileSystem.list(entry) }
        assertEquals("zip-bytes", live.readText())
    }

    private fun zipBytes(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { output ->
            output.putArchiveEntry(ZipArchiveEntry("inside"))
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }

    private fun readOnlyChild(fileSystem: ArchiveFileSystem, entry: XEntry): String =
        fileSystem.openIn(fileSystem.list(entry).single()).bufferedReader().use { it.readText() }
}
