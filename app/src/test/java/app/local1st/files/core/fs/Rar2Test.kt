package app.local1st.files.core.fs

import app.local1st.files.core.archive.LibArchiveReader
import app.local1st.files.core.archive.NativeArchive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RAR 2.x archives, which the patched libarchive reads in both flavors (third_party/patches):
 * RAR 2.0 compression, LZ and audio, solid or not, and the blocks of RAR 2.x archives that
 * stopped libarchive before (comments, Unix owners, recovery records). The archives in
 * resources/rar/rar2 were made by RAR 2.80 from gen_sources.py's trees, and rar2.md5 holds the
 * MD5 of every source file (see resources/rar/README.md).
 */
class Rar2Test {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })

    @Test
    fun everyArchive_listsEachMemberAndReadsItOnItsOwn() {
        for ((name, set) in ARCHIVES) {
            val archive = fixture(name)
            val expected = expected(set)

            assertEquals(name, expected.keys, listFiles(archive).keys)
            // In a solid archive each read starts from the first member, decoding the ones before it.
            for ((path, md5) in expected) assertEquals("$name: $path", md5, md5Of(archive, path))
        }
    }

    @Test
    fun everyArchive_readsInOnePass() {
        for ((name, set) in ARCHIVES) {
            val seen = LinkedHashMap<String, String>()

            fileSystem.forEachFile(archiveEntry(fixture(name))) { path, input -> seen[path] = md5(input) }

            assertEquals(name, expected(set), seen)
        }
    }

    @Test
    fun listing_jumpsOverMembersWithoutDecodingThem() {
        val archive = fixture("rar2_solid_lz.rar")

        // Opened for a listing, the reader skips the first three members undecoded, among them
        // the empty first one, which holds the tables. a/b/notes.txt continues them.
        LibArchiveReader.open(listOf(archive), NativeArchive.FORMAT_RAR, forData = false).use { reader ->
            repeat(4) { reader.next() }
            val e = assertThrows(IOException::class.java) { reader.entryStream().readBytes() }
            assertEquals("RAR solid member can't be read without the members before it", e.message)
        }
    }

    @Test
    fun encryptedArchive_listsAndRefusesToOpen() {
        val archive = fixture("rar2_encrypted.rar")

        assertEquals(expected("small").keys, listFiles(archive).keys)
        val e = assertThrows(IOException::class.java) { md5Of(archive, "readme.txt") }
        assertEquals("readme.txt is password-protected", e.message)
    }

    @Test
    fun rarfileArchive_opensDespiteItsOldBlocks() {
        // A RAR 2.0 member followed by a Unix owner subblock and a recovery record, and no
        // end-of-archive block.
        val entries = fileSystem.list(archiveEntry(rarFixture("rar2-unix-owner.rar")))

        assertEquals(listOf("file.txt"), entries.map(XEntry::name))
        val bytes = fileSystem.openIn(entries.single()).use { it.readBytes() }
        assertEquals(4, bytes.size)
        assertEquals(0x7E3265A8L, CRC32().apply { update(bytes) }.value) // the CRC its header holds
    }

    @Test(timeout = 60_000)
    fun damagedArchives_failInsteadOfReadingMembersWrong() {
        val expected = expected("mixed")
        val original = fixture("rar2_solid_mm.rar").readBytes()
        for ((what, bytes) in listOf(
            "cut-in-half" to original.copyOf(original.size / 2),
            "one-byte-changed" to original.copyOf().also { it[it.size * 2 / 3] = (it[it.size * 2 / 3] + 1).toByte() },
        )) {
            val archive = File(temporaryFolder.root, "$what.rar").apply { writeBytes(bytes) }
            var failed = 0
            for ((path, md5) in expected) {
                // A cut archive may not list at all; either way nothing reads wrong.
                val read = try {
                    fileSystem.stat(XId.zip(archive.absolutePath, path))?.let { fileSystem.openIn(it).use(::md5) }
                } catch (_: IOException) {
                    null
                }
                if (read == null) failed++ else assertEquals("$what: $path", md5, read)
            }
            assertTrue(what, failed > 0)
        }
    }

    private fun expected(set: String): Map<String, String> {
        val lines = javaClass.getResourceAsStream("/rar/rar2/rar2.md5")!!.bufferedReader().readLines()
        return lines.map { it.split('\t') }.filter { it[0] == set }.associate { it[1] to it[2] }
    }

    private fun fixture(name: String): File {
        val parts = VOLUMES[name] ?: listOf(name)
        return parts.map { copy("/rar/rar2/$it", it) }.first()
    }

    private fun rarFixture(name: String): File = copy("/rar/$name", name)

    private fun copy(resource: String, name: String): File {
        val target = File(temporaryFolder.root, name)
        val input: InputStream = javaClass.getResourceAsStream(resource) ?: error("Missing test archive $resource")
        input.use { target.outputStream().use { out -> it.copyTo(out) } }
        return target
    }

    private fun archiveEntry(file: File) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = false,
        kind = EntryKind.ARCHIVE,
        localPath = file.absolutePath,
    )

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

    private companion object {
        /** Archive → the source set it was made from, and what it covers. */
        val ARCHIVES = mapOf(
            "rar2_lz.rar" to "edge", // not solid
            "rar2_solid_lz.rar" to "text", // the 64 KB window wraps; the empty first member holds the tables
            "rar2_far.rar" to "far", // matches from more than 256 KB back
            "rar2_audio.rar" to "audio", // audio blocks only, with 1 to 4 channels
            "rar2_solid_mm.rar" to "mixed", // audio and LZ blocks in turn, a switch inside an empty member
            "rar2_solid_edge.rar" to "edge", // empty members among the others
            "rar2_volumes.rar" to "edge", // three volumes, .rar, .r00 and .r01, members across them
            "rar2_owner_rr.rar" to "small", // a Unix owner subblock after each member, then a recovery record
            "rar2_comments.rar" to "small", // an archive comment and a file comment inside their headers
        )
        val VOLUMES = mapOf(
            "rar2_volumes.rar" to listOf("rar2_volumes.rar", "rar2_volumes.r00", "rar2_volumes.r01"),
        )
    }
}
