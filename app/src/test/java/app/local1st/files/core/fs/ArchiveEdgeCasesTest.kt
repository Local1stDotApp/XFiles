package app.local1st.files.core.fs

import app.local1st.files.core.ops.ZipTurbo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Archives that are empty, damaged, mislabeled or odd in some way, read through the public API. */
class ArchiveEdgeCasesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })
    private val hello = "hello world\n".toByteArray()
    private val lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(200).toByteArray()

    // ---- empty and not-an-archive -------------------------------------------------------

    @Test
    fun emptyArchives_listAsEmptyFolders() {
        assertEquals(emptyMap<String, String>(), readAll(file("empty.zip", zip())))
        assertEquals(emptyMap<String, String>(), readAll(file("empty.tar", ByteArray(10240))))
        // tar and bsdtar read a zero-byte tar as an empty archive.
        assertEquals(emptyMap<String, String>(), readAll(file("zero.tar", ByteArray(0))))
    }

    @Test
    fun compressedEmptyFile_isOneEmptyFile() {
        val archive = file("notes.txt.gz", gzip(ByteArray(0)))

        assertEquals(mapOf("notes.txt" to ""), readAll(archive))
        assertEquals(mapOf("notes.txt" to ""), readOnePass(archive))
    }

    @Test
    fun emptyGarbageAndPlainText_failToOpen() {
        val random = Random(1)
        for (ext in listOf("zip", "7z", "rar", "tar.gz", "tgz", "gz", "xz", "bz2", "zst", "iso", "cpio", "deb")) {
            for ((label, bytes) in listOf("zero" to ByteArray(0), "garbage" to random.nextBytes(4096), "text" to hello)) {
                if (label == "zero" && (ext == "tar.gz" || ext == "tgz")) continue // an empty tar, as above
                val archive = file("$label.$ext", bytes)
                try {
                    fileSystem.list(entryFor(archive))
                    fail("$label.$ext opened")
                } catch (_: IOException) {
                    // expected
                }
            }
        }
    }

    // ---- zip structure --------------------------------------------------------------------

    @Test
    fun zipAfterOtherData_opensWithEitherOffsetConvention() {
        val entries = listOf(stored("hello.txt", hello), deflated("lorem.txt", lorem))
        val expected = mapOf("hello.txt" to text(hello), "lorem.txt" to text(lorem))
        val prefix = Random(2).nextBytes(10_000)

        // Prepended without fixing offsets (cat stub.exe x.zip), and with them fixed (zip -A).
        assertEquals(expected, readAll(file("unadjusted.zip", prefix + zip(entries))))
        assertEquals(expected, readAll(file("adjusted.zip", zip(entries, prefix = prefix, adjustOffsets = true))))
    }

    @Test
    fun endRecordInsideTheComment_isNotTakenForTheRealOne() {
        val fake = Le().u32(0x06054b50).u16(0).u16(0).u16(9).u16(9).u32(1000).u32(5).u16(0).toByteArray()
        val longComment = ByteArray(65535) { 'c'.code.toByte() }

        assertEquals(mapOf("hello.txt" to text(hello)), readAll(file("fake.zip", zip(listOf(stored("hello.txt", hello)), comment = fake + "end".toByteArray()))))
        assertEquals(mapOf("hello.txt" to text(hello)), readAll(file("long.zip", zip(listOf(stored("hello.txt", hello)), comment = longComment))))
    }

    @Test
    fun moreEntriesThanTheClassicEndRecordHolds_allList() {
        val archive = temporaryFolder.root.resolve("many.zip")
        ZipArchiveOutputStream(archive).use { z ->
            for (i in 0 until 70_000) {
                val e = ZipArchiveEntry("d%02d/%05d".format(i / 1000, i))
                e.method = ZipArchiveEntry.STORED
                e.size = 0
                e.crc = 0
                z.putArchiveEntry(e)
                z.closeArchiveEntry()
            }
            z.putArchiveEntry(ZipArchiveEntry("last.txt"))
            z.write(hello)
            z.closeArchiveEntry()
        }
        val root = fileSystem.list(entryFor(archive))

        assertEquals(71, root.size)
        assertEquals(1000, fileSystem.list(root.first { it.name == "d69" }).size)
        assertEquals(text(hello), fileSystem.openIn(root.first { it.name == "last.txt" }).use { text(it.readBytes()) })
    }

    @Test
    fun encryptedAndUnsupportedEntries_failAlone() {
        val aesExtra = Le().u16(0x9901).u16(7).u16(2).bytes("AE".toByteArray()).bytes(byteArrayOf(3)).u16(8).toByteArray()
        val archive = file(
            "mixed.zip",
            zip(
                listOf(
                    RawEntry("zipcrypto.txt", Random(3).nextBytes(12) + hello, 0, crc(hello), hello.size.toLong(), flags = 0x801),
                    RawEntry("aes.txt", Random(4).nextBytes(40), 99, 0, 12, flags = 0x801, extra = aesExtra),
                    RawEntry("wavpack.bin", Random(5).nextBytes(100), 97, 0, 200),
                    stored("open.txt", hello),
                ),
            ),
        )
        val entries = fileSystem.list(entryFor(archive)).associateBy { it.name }

        assertEquals(text(hello), fileSystem.openIn(entries.getValue("open.txt")).use { text(it.readBytes()) })
        for (name in listOf("zipcrypto.txt", "aes.txt")) {
            val error = assertIoException { fileSystem.openIn(entries.getValue(name)).use { it.readBytes() } }
            assertTrue(error.message, error.message!!.contains("password-protected"))
        }
        val error = assertIoException { fileSystem.openIn(entries.getValue("wavpack.bin")).use { it.readBytes() } }
        assertTrue(error.message, error.message!!.contains("unsupported compression method"))
    }

    @Test
    fun damagedEntryData_failsTheRead() {
        val deflatedLorem = deflate(lorem)
        val badCrc = file("crc.zip", zip(listOf(RawEntry("c.txt", deflatedLorem, 8, crc(lorem) xor 1, lorem.size.toLong()))))
        val pastTheEnd = file("short.zip", zip(listOf(RawEntry("c.txt", hello, 0, crc(hello), 1_000_000, compSize = 1_000_000))))
        val badLocalHeader = file("local.zip", zip(listOf(stored("a.txt", hello))).also { it[0] = 'X'.code.toByte() })

        for (archive in listOf(badCrc, pastTheEnd, badLocalHeader)) {
            val entry = fileSystem.list(entryFor(archive)).single()
            assertIoException { fileSystem.openIn(entry).use { it.readBytes() } }
            assertIoException { ZipTurbo.extractZip(archive, temporaryFolder.newFolder(), {}, { false }) }
        }
    }

    @Test
    fun oddNames_browseInsideTheArchive() {
        val archive = file(
            "names.zip",
            zip(
                listOf(
                    stored("../../evil.txt", "evil".toByteArray()),
                    stored("/abs.txt", "abs".toByteArray()),
                    stored("..\\..\\win.txt", "win".toByteArray(), madeBy = 20),
                    stored("a/../../b.txt", "b".toByteArray()),
                    stored("./c.txt", "c".toByteArray()),
                    stored("d//e.txt", "e".toByteArray()),
                    stored("", "nameless".toByteArray()),
                    stored(".", "dot".toByteArray()),
                    stored("dir\\sub\\fat.txt", "fat".toByteArray(), madeBy = 20),
                ),
            ),
        )

        assertEquals(
            mapOf(
                "evil.txt" to "evil", "abs.txt" to "abs", "win.txt" to "win", "a/b.txt" to "b", "c.txt" to "c",
                "d/e.txt" to "e", "dir/sub/fat.txt" to "fat",
            ),
            readAll(archive),
        )
    }

    @Test
    fun namesDifferingOnlyInCase_extractInArchiveOrder() {
        val big = Random(6).nextBytes(3 shl 20)
        val small = "small".toByteArray()
        val archive = file("case.zip", zip(listOf(deflated("Data.bin", big), stored("data.bin", small))))
        val dest = temporaryFolder.newFolder()

        ZipTurbo.extractZip(archive, dest, {}, { false })

        val written = dest.listFiles()!!.associate { it.name to it.readBytes() }
        if (written.size == 1) {
            // Storage that ignores case keeps one file: the later entry, whole, never a mix.
            assertArrayEquals(small, written.values.single())
        } else {
            assertArrayEquals(big, written.getValue("Data.bin"))
            assertArrayEquals(small, written.getValue("data.bin"))
        }
    }

    // ---- tar and compressed streams --------------------------------------------------------

    @Test
    fun repeatedTarPath_readsTheLastCopyWithItsSize() {
        val archive = file("dup.tar", tar { it.file("dup.txt", "first".toByteArray()); it.file("dup.txt", "second".toByteArray()) })
        val entry = fileSystem.list(entryFor(archive)).single()

        assertEquals(6L, entry.size)
        assertEquals("second", fileSystem.openIn(entry).use { text(it.readBytes()) })
    }

    @Test
    fun compressionIsFoundByContent_notByName() {
        val plainTar = tar { it.file("a.txt", hello) }

        assertEquals(mapOf("a.txt" to text(hello)), readAll(file("gzip.tar.xz", gzip(plainTar))))
        assertEquals(mapOf("a.txt" to text(hello)), readAll(file("plain.tar.gz", plainTar)))
    }

    @Test
    fun truncatedArchives_failInsteadOfReadingShort() {
        val big = Random(7).nextBytes(100_000)
        val plainTar = tar { it.file("a.txt", hello); it.file("big.bin", big) }

        for (archive in listOf(
            file("cut.tar", plainTar.copyOf(512 + 512 + 512 + 50_000)),
            file("cut.tar.gz", gzip(plainTar).let { it.copyOf(it.size / 2) }),
            file("cut.txt.gz", gzip(lorem).let { it.copyOf(it.size / 2) }),
        )) {
            try {
                val all = readAll(archive)
                fail("${archive.name} read as $all")
            } catch (_: IOException) {
                // expected
            }
        }
    }

    // ---- damage in general -------------------------------------------------------------------

    /**
     * Truncates and corrupts every test archive in a few places. Reading one may fail, but only
     * with an IOException, and never hangs or brings the process down.
     */
    @Test
    fun damagedArchives_failWithIOExceptionsOnly() {
        val resources = listOf("archives", "rar", "rar/solid", "rar/rar2").flatMap { dir ->
            File(javaClass.classLoader!!.getResource(dir)!!.toURI()).listFiles()!!
                .filter { it.isFile && it.extension.lowercase() in DAMAGE_EXTENSIONS }
        }
        assertTrue(resources.size > 50)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            for ((seedIndex, seed) in resources.withIndex()) {
                val original = seed.readBytes()
                val random = Random(seedIndex)
                val variants = List(16) { original.copyOf(original.size * it / 16) } + List(24) {
                    original.copyOf().also { b ->
                        repeat(1 + random.nextInt(4)) {
                            val at = if (random.nextBoolean()) random.nextInt(minOf(b.size, 1024)) else random.nextInt(b.size)
                            b[at] = random.nextInt(256).toByte()
                        }
                    }
                }
                for ((variantIndex, bytes) in variants.withIndex()) {
                    val dir = temporaryFolder.root.resolve("damage/$seedIndex-$variantIndex").apply { mkdirs() }
                    val archive = dir.resolve(seed.name).apply { writeBytes(bytes) }
                    val future = pool.submit {
                        try {
                            readAll(archive, swallowReadErrors = true)
                            readOnePass(archive)
                        } catch (_: IOException) {
                            // expected for damaged data
                        }
                    }
                    try {
                        future.get(30, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        fail("${seed.name} variant $variantIndex hangs")
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw AssertionError("${seed.name} variant $variantIndex", e.cause)
                    }
                    dir.deleteRecursively()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun file(name: String, bytes: ByteArray): File =
        temporaryFolder.root.resolve(name).apply { writeBytes(bytes) }

    private fun entryFor(archive: File) = XEntry(
        id = XId.file(archive.absolutePath),
        name = archive.name,
        isDir = false,
        kind = EntryKind.ARCHIVE,
        localPath = archive.absolutePath,
    )

    /** Inner path to contents of every file, browsing folder by folder. */
    private fun readAll(archive: File, swallowReadErrors: Boolean = false): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun walk(dir: XEntry) {
            for (e in fileSystem.list(dir)) {
                if (e.isDir) {
                    walk(e)
                } else {
                    try {
                        result[XId.zipInnerPath(e.id)] = fileSystem.openIn(e).use { text(it.readBytes()) }
                    } catch (error: IOException) {
                        if (!swallowReadErrors) throw error
                    }
                }
            }
        }
        walk(entryFor(archive))
        return result
    }

    private fun readOnePass(archive: File): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fileSystem.forEachFile(entryFor(archive)) { path, input -> result[path] = text(input.readBytes()) }
        return result
    }

    private fun assertIoException(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        throw AssertionError("expected an IOException")
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(9, true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun tar(build: (TarArchiveOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { out -> TarArchiveOutputStream(out).use(build) }.toByteArray()

    private fun TarArchiveOutputStream.file(name: String, data: ByteArray) {
        val entry = TarArchiveEntry(name)
        entry.size = data.size.toLong()
        putArchiveEntry(entry)
        write(data)
        closeArchiveEntry()
    }

    /** A zip entry written byte for byte, so its fields can be wrong on purpose. */
    private class RawEntry(
        name: String,
        val payload: ByteArray,
        val method: Int,
        val crc: Long,
        val size: Long,
        val compSize: Long = payload.size.toLong(),
        val flags: Int = 0x800,
        val madeBy: Int = (3 shl 8) or 20,
        val extra: ByteArray = ByteArray(0),
    ) {
        val name = name.toByteArray(Charsets.UTF_8)
    }

    private fun stored(name: String, data: ByteArray, madeBy: Int = (3 shl 8) or 20) =
        RawEntry(name, data, 0, crc(data), data.size.toLong(), madeBy = madeBy)

    private fun deflated(name: String, data: ByteArray) = RawEntry(name, deflate(data), 8, crc(data), data.size.toLong())

    private class Le : ByteArrayOutputStream() {
        fun u16(v: Int) = apply {
            write(v and 0xFF)
            write((v shr 8) and 0xFF)
        }

        fun u32(v: Long) = apply { for (i in 0 until 4) write(((v shr (8 * i)) and 0xFF).toInt()) }

        fun bytes(b: ByteArray) = apply { write(b) }
    }

    private fun zip(
        entries: List<RawEntry> = emptyList(),
        prefix: ByteArray = ByteArray(0),
        adjustOffsets: Boolean = false,
        comment: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = Le().bytes(prefix)
        val base = if (adjustOffsets) 0 else prefix.size
        val offsets = entries.map { e ->
            val offset = (out.size() - base).toLong()
            out.u32(0x04034b50).u16(20).u16(e.flags).u16(e.method).u16(0).u16(0x5C21)
                .u32(e.crc).u32(e.compSize).u32(e.size).u16(e.name.size).u16(e.extra.size)
                .bytes(e.name).bytes(e.extra).bytes(e.payload)
            offset
        }
        val cdStart = out.size() - base
        for ((i, e) in entries.withIndex()) {
            out.u32(0x02014b50).u16(e.madeBy).u16(20).u16(e.flags).u16(e.method).u16(0).u16(0x5C21)
                .u32(e.crc).u32(e.compSize).u32(e.size).u16(e.name.size).u16(e.extra.size).u16(0).u16(0).u16(0)
                .u32(0).u32(offsets[i]).bytes(e.name).bytes(e.extra)
        }
        val cdSize = out.size() - base - cdStart
        out.u32(0x06054b50).u16(0).u16(0).u16(entries.size).u16(entries.size)
            .u32(cdSize.toLong()).u32(cdStart.toLong()).u16(comment.size).bytes(comment)
        return out.toByteArray()
    }

    private companion object {
        val DAMAGE_EXTENSIONS = setOf(
            "zip", "zipx", "7z", "rar", "tar", "tgz", "tbz", "txz", "tlz", "lz4", "zst", "iso", "cpio", "ar",
        )
    }
}
