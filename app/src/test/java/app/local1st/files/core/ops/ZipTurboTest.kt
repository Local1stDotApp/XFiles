package app.local1st.files.core.ops

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipTurboTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mtime = 1_700_000_000_000L // a whole second, which DOS time can hold to 2 s

    private val files = linkedMapOf(
        "notes/readme.txt" to "hello zip ".repeat(5000).toByteArray(),
        "notes/empty.txt" to ByteArray(0),
        "photos/照片.jpg" to ByteArray(300_000) { (it * 31 + it / 7).toByte() },
        "big.bin" to ByteArray(3_000_000) { (it % 251).toByte() }, // past the 1 MB spool
    )

    private fun sources(): List<ZipTurbo.ZipSource> =
        listOf(ZipTurbo.ZipSource("notes", isDir = true, mtime, { ByteArray(0).inputStream() })) +
            files.map { (name, bytes) ->
                ZipTurbo.ZipSource(name, isDir = false, mtime, { bytes.inputStream() }, bytes.size.toLong())
            }

    @Test
    fun parallelCreate_writesAZipOthersRead() {
        val zip = temporaryFolder.root.resolve("parallel.zip")
        var reported = 0L
        zip.outputStream().use { out ->
            ZipTurbo.createZip(sources(), out, temporaryFolder.newFolder("cache"), onBytes = { reported = it }, isCancelled = { false })
        }

        assertReadable(zip)
        assertEquals(files.values.sumOf { it.size.toLong() }, reported)
        assertTrue(temporaryFolder.root.resolve("cache").listFiles()!!.isEmpty())
    }

    @Test
    fun sequentialCreate_streamsAZipOthersRead() {
        val zip = temporaryFolder.root.resolve("sequential.zip")
        zip.outputStream().use { out ->
            ZipTurbo.createZipSequential(sources(), out, onBytes = {}, isCancelled = { false })
        }

        assertReadable(zip)
        // The stream reader can't take a stored entry with a data descriptor; none are written.
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory) assertArrayEquals(entry.name, files[entry.name], input.readBytes())
            }
        }
    }

    @Test
    fun create_storesWhatDoesNotCompress() {
        val zip = temporaryFolder.root.resolve("methods.zip")
        zip.outputStream().use { out ->
            ZipTurbo.createZip(sources(), out, temporaryFolder.newFolder("cache"), onBytes = {}, isCancelled = { false })
        }

        ZipFile(zip).use { read ->
            assertEquals(ZipEntry.STORED, read.getEntry("photos/照片.jpg").method)
            assertEquals(ZipEntry.DEFLATED, read.getEntry("notes/readme.txt").method)
        }
    }

    @Test
    fun create_cancelsAndLeavesNoTempFiles() {
        val cache = temporaryFolder.newFolder("cache")
        var calls = 0
        assertThrows(Exception::class.java) {
            ByteArrayOutputStream().use { out ->
                ZipTurbo.createZip(sources(), out, cache, onBytes = {}, isCancelled = { ++calls > 3 })
            }
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun extract_writesEveryFileWithItsTime() {
        val zip = temporaryFolder.root.resolve("in.zip")
        zip.outputStream().use { out ->
            ZipTurbo.createZip(sources(), out, temporaryFolder.newFolder("cache"), onBytes = {}, isCancelled = { false })
        }
        val dest = temporaryFolder.newFolder("out")
        var reported = 0L

        ZipTurbo.extractZip(zip, dest, onBytes = { reported = it }, isCancelled = { false })

        for ((name, bytes) in files) {
            val file = dest.resolve(name)
            assertArrayEquals(name, bytes, file.readBytes())
            assertEquals(name, mtime, file.lastModified())
        }
        assertEquals(files.values.sumOf { it.size.toLong() }, reported)
        assertEquals(files.values.sumOf { it.size.toLong() }, ZipTurbo.totalUncompressed(zip))
    }

    @Test
    fun extract_keepsEntriesInsideTheDestination() {
        val zip = temporaryFolder.root.resolve("slip.zip")
        ZipArchiveOutputStream(zip).use { out ->
            for (name in listOf("../../escape.txt", "a\\b.txt", "./dup.txt", "dup.txt")) {
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(name.toByteArray())
                out.closeArchiveEntry()
            }
        }
        val dest = temporaryFolder.newFolder("out")

        ZipTurbo.extractZip(zip, dest, onBytes = {}, isCancelled = { false })

        assertEquals("../../escape.txt", dest.resolve("escape.txt").readText())
        assertEquals("a\\b.txt", dest.resolve("a/b.txt").readText())
        // Like the archive browser: the later of two entries with the same path wins.
        assertEquals("dup.txt", dest.resolve("dup.txt").readText())
        assertFalse(temporaryFolder.root.resolve("escape.txt").exists())
    }

    @Test
    fun extract_refusesLinksThatLeadOutOfTheDestination() {
        val outside = temporaryFolder.newFolder("outside")
        val target = outside.resolve("target.txt").apply { writeText("untouched") }
        // A link where a folder goes is refused as that folder, before anything is made in it.
        for ((linkName, linkTo, entryName) in listOf(
            Triple("folder", outside, "folder/evil.txt"),
            Triple("victim.txt", target, "victim.txt"),
        )) {
            val dest = temporaryFolder.newFolder("dest-$linkName")
            Files.createSymbolicLink(dest.resolve(linkName).toPath(), linkTo.toPath())
            val zip = temporaryFolder.root.resolve("link-$linkName.zip")
            ZipArchiveOutputStream(zip).use { out ->
                out.putArchiveEntry(ZipArchiveEntry(entryName))
                out.write("evil".toByteArray())
                out.closeArchiveEntry()
            }

            val e = assertThrows(IOException::class.java) {
                ZipTurbo.extractZip(zip, dest, onBytes = {}, isCancelled = { false })
            }
            assertEquals("Blocked path traversal in archive: $linkName", e.message)
        }
        assertEquals(listOf("target.txt"), outside.list()!!.toList())
        assertEquals("untouched", target.readText())
    }

    @Test
    fun extract_cancelledBeforeItStarts_writesNothing() {
        val zip = temporaryFolder.root.resolve("in.zip")
        zip.outputStream().use { out ->
            ZipTurbo.createZip(sources(), out, temporaryFolder.newFolder("cache"), onBytes = {}, isCancelled = { false })
        }
        val dest = temporaryFolder.newFolder("out")

        assertThrows(InterruptedException::class.java) {
            ZipTurbo.extractZip(zip, dest, onBytes = {}, isCancelled = { true })
        }
        assertEquals(0, dest.list()!!.size)
    }

    @Test
    fun extract_failsOnCorruptData() {
        val zip = temporaryFolder.root.resolve("corrupt.zip")
        ZipArchiveOutputStream(zip).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("data.txt"))
            out.write("some text that deflates ".repeat(100).toByteArray())
            out.closeArchiveEntry()
        }
        val bytes = zip.readBytes()
        bytes[60] = (bytes[60].toInt() xor 0x55).toByte() // inside the deflated data
        zip.writeBytes(bytes)

        assertThrows(IOException::class.java) {
            ZipTurbo.extractZip(zip, temporaryFolder.newFolder("out"), onBytes = {}, isCancelled = { false })
        }
    }

    /** java.util.zip reads every entry back with its bytes, UTF-8 name and time. */
    private fun assertReadable(zip: File) {
        ZipFile(zip).use { read ->
            assertTrue(read.getEntry("notes/").isDirectory)
            for ((name, bytes) in files) {
                val entry = read.getEntry(name) ?: error("missing $name")
                assertArrayEquals(name, bytes, read.getInputStream(entry).readBytes())
                assertEquals(name, mtime, entry.time)
            }
        }
    }
}
