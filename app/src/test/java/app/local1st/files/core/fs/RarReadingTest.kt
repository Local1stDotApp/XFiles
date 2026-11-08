package app.local1st.files.core.fs

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RAR through [ArchiveFileSystem]: libarchive in both flavors. The archives are libarchive's own
 * (resources/rar/README.md); the expected MD5s come from macOS bsdtar.
 */
class RarReadingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })

    @Test
    fun encryptedMembers_failToOpenInsteadOfReadingEmpty() {
        val rar5 = fixture("test_read_format_rar5_encrypted.rar")
        assertEquals("8bd263c23ec4275eeb8ba22a9ea63b19", md5Of(rar5, "a.txt"))
        val e = assertThrows(IOException::class.java) { md5Of(rar5, "b.txt") }
        assertEquals("b.txt is password-protected", e.message)
    }

    @Test
    fun rar4VolumeSet_listsTheMembersOfEveryVolume() {
        val first = rar4VolumeSet()

        // The link is left out, as in tar: it has no bytes of its own to show.
        assertEquals(
            mapOf(
                "ppmd_lzss_conversion_test.txt" to 241_647_978L,
                "LibarchiveAddingTest.html" to 20_111L,
                "testdir/test.txt" to 20L,
                "testdir/LibarchiveAddingTest.html" to 20_111L,
            ),
            listFiles(first),
        )
    }

    @Test
    fun rar4VolumeSet_readsPpmdMembersAcrossVolumes() {
        val first = rar4VolumeSet()

        // Starts in volume 3 and ends in volume 4.
        assertEquals("78943e839b05c89a973845de122285a2", md5Of(first, "LibarchiveAddingTest.html"))
        // Runs through volumes 1 to 3, switching between PPMd and LZ on the way.
        assertEquals("3365c66cd2c6140e866496181e566642", md5Of(first, "ppmd_lzss_conversion_test.txt"))
        assertEquals("f03f566a28784ddb31d216e6a5056e83", md5Of(first, "testdir/test.txt"))
    }

    @Test
    fun rar5VolumeSet_readsASolidMemberAcrossVolumes() {
        val first = volumeSet("test_read_format_rar5_multiarchive_solid.part", "01", "02", "03", "04")

        assertEquals(9, listFiles(first).size)
        // Runs through all four volumes, after eight members it depends on.
        assertEquals("de9f91f9cd038989fec8abf25031b42b", md5Of(first, "elf-Linux-ARMv7-ls"))
        assertEquals("c7333a188fb1d2bf893bd5d7d7764879", md5Of(first, "test6.bin"))
    }

    @Test
    fun missingVolume_failsTheMemberThatContinuesInIt() {
        val first = volumeSet("test_read_format_rar_multivolume.part", "0001", "0002")

        assertEquals(mapOf("ppmd_lzss_conversion_test.txt" to 241_647_978L), listFiles(first))
        assertThrows(IOException::class.java) { md5Of(first, "ppmd_lzss_conversion_test.txt") }
    }

    @Test
    fun corruptData_failsTheRead() {
        val archive = fixture("test_read_format_rar5_compressed.rar")
        val bytes = archive.readBytes()
        // Inside test.bin's 361 bytes of data, which end just before the 8-byte end header.
        val inData = bytes.size - 8 - 180
        bytes[inData] = (bytes[inData].toInt() xor 0xFF).toByte()
        archive.writeBytes(bytes)

        assertThrows(IOException::class.java) { md5Of(archive, "test.bin") }
    }

    @Test(timeout = 20_000)
    fun truncatedSolidRar5_failsInsteadOfLoopingForever() {
        // The first 108 bytes of test_read_format_rar5_multiple_files_solid.rar. libarchive
        // returns the same failed header forever to a caller that carries on (libarchive issue
        // 3492); the listing must stop at it instead.
        val archive = fixture("truncated_rar5_multiple_files_solid.rar")

        assertThrows(IOException::class.java) { listFiles(archive) }
    }

    @Test
    fun onePass_readsEveryMemberOfASolidVolumeSet() {
        val first = volumeSet("test_read_format_rar5_multiarchive_solid.part", "01", "02", "03", "04")
        val seen = LinkedHashMap<String, String>()

        fileSystem.forEachFile(archiveEntry(first)) { path, input -> seen[path] = md5(input) }

        assertEquals(listFiles(first).keys, seen.keys)
        assertEquals("c7333a188fb1d2bf893bd5d7d7764879", seen["test6.bin"])
    }

    private fun rar4VolumeSet(): File =
        volumeSet("test_read_format_rar_multivolume.part", "0001", "0002", "0003", "0004")

    private fun volumeSet(prefix: String, vararg numbers: String): File =
        numbers.map { fixture("$prefix$it.rar") }.first()

    private fun fixture(name: String): File {
        val target = File(temporaryFolder.root, name)
        val resource: InputStream = javaClass.getResourceAsStream("/rar/$name")
            ?: error("Missing test archive $name")
        resource.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun archiveEntry(file: File) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = false,
        kind = EntryKind.ARCHIVE,
        localPath = file.absolutePath,
    )

    /** Inner path → size of every file in the archive. */
    private fun listFiles(archive: File): Map<String, Long> {
        val files = LinkedHashMap<String, Long>()
        fun walk(dir: XEntry) {
            for (entry in fileSystem.list(dir)) {
                if (entry.isDir) walk(entry) else files[XId.zipInnerPath(entry.id)] = entry.size
            }
        }
        walk(archiveEntry(archive))
        return files
    }

    private fun md5Of(archive: File, innerPath: String): String {
        val entry = fileSystem.stat(XId.zip(archive.absolutePath, innerPath))
            ?: error("No $innerPath in ${archive.name}")
        return fileSystem.openIn(entry).use(::md5)
    }

    private fun md5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
