package app.local1st.files.core.fs

import app.local1st.files.core.archive.LibArchiveReader
import app.local1st.files.core.archive.NativeArchive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Solid RAR 4, which the patched libarchive reads in both flavors (third_party/patches). The
 * archives in resources/rar/solid were made by RAR 6.24 from gen_sources.py's trees, and
 * rar4_solid.md5 holds the MD5 of every source file (see resources/rar/README.md).
 */
class SolidRar4Test {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })

    @Test
    fun everyArchive_listsEachMemberAndReadsItOnItsOwn() {
        for ((name, set) in ARCHIVES) {
            val archive = fixture(name)
            val expected = expected(set)

            assertEquals(name, expected.keys, listFiles(archive).keys)
            // Each read starts from the archive's first member, decoding the ones before it.
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
        val archive = fixture("rar4_solid_lz.rar")

        // Opened for a listing, the reader skips a/one.txt and a/short.txt undecoded, so
        // a/b/notes.txt, which continues them, can't be decoded.
        LibArchiveReader.open(listOf(archive), NativeArchive.FORMAT_RAR, forData = false).use { reader ->
            repeat(4) { reader.next() }
            val e = assertThrows(IOException::class.java) { reader.entryStream().readBytes() }
            assertEquals("RAR solid member can't be read without the members before it", e.message)
        }
    }

    @Test
    fun encryptedSolidArchive_listsAndRefusesToOpen() {
        val archive = rarFixture("test_read_format_rar4_solid_encrypted.rar")

        assertEquals(listOf("a.txt", "b.txt", "c.txt", "d.txt"), fileSystem.list(archiveEntry(archive)).map(XEntry::name))
        val e = assertThrows(IOException::class.java) { md5Of(archive, "c.txt") }
        assertEquals("c.txt is password-protected", e.message)
    }

    @Test(timeout = 60_000)
    fun damagedArchives_failInsteadOfReadingMembersWrong() {
        val expected = expected("text")
        val original = fixture("rar4_solid_lz.rar").readBytes()
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
        val lines = javaClass.getResourceAsStream("/rar/solid/rar4_solid.md5")!!.bufferedReader().readLines()
        return lines.map { it.split('\t') }.filter { it[0] == set }.associate { it[1] to it[2] }
    }

    private fun fixture(name: String): File {
        val parts = VOLUMES[name] ?: listOf(name)
        return parts.map { copy("/rar/solid/$it", it) }.first()
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
            "rar4_solid_lz.rar" to "text", // LZ only; the 64 KB window wraps
            "rar4_solid_ppmd.rar" to "text", // PPMd only
            "rar4_solid_groups.rar" to "text", // solid groups of three members
            "rar4_solid_volumes.part1.rar" to "text", // two volumes, members across them
            "rar4_solid_edge_lz.rar" to "edge", // empty members among the others
            "rar4_solid_edge_auto.rar" to "edge", // ... switching between PPMd and LZ
            "rar4_solid_edge_ppmd.rar" to "edge",
            "rar4_solid_e8.rar" to "x86", // x86 filter
            "rar4_solid_ppmd_e8.rar" to "x86", // x86 filter in PPMd blocks
            "rar4_solid_audio.rar" to "audio",
            "rar4_solid_rgb.rar" to "rgb",
            "rar4_solid_delta.rar" to "delta",
            "rar4_solid_stored.rar" to "stored", // stored members among compressed ones
        )
        val VOLUMES = mapOf(
            "rar4_solid_volumes.part1.rar" to listOf("rar4_solid_volumes.part1.rar", "rar4_solid_volumes.part2.rar"),
        )
    }
}
