package app.local1st.files.core.fs

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every format and compression method libxfarchive reads, through [ArchiveFileSystem], against
 * what bsdtar and Commons Compress extract from the same files (resources/archives/README.md).
 */
class ArchiveFormatsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })

    @Test
    fun everyFileListsAndReadsLikeTheReferenceTools() {
        val failures = mutableListOf<String>()
        for ((archive, expected) in expected()) {
            val root = archiveEntry(fixture(archive))
            val actual = try {
                listFiles(root).associate { entry ->
                    pathOf(entry) to "${entry.size}\t${fileSystem.openIn(entry).use(::md5)}"
                }
            } catch (e: Exception) {
                failures += "$archive: $e"
                continue
            }
            if (actual != expected) failures += "$archive: expected $expected, got $actual"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun onePassVisitsTheSameFilesWithTheSameBytes() {
        val failures = mutableListOf<String>()
        for ((archive, expected) in expected()) {
            val root = archiveEntry(fixture(archive))
            val seen = LinkedHashMap<String, String>()
            try {
                fileSystem.forEachFile(root) { innerPath, input -> seen[nfc(innerPath)] = md5(input) }
            } catch (e: Exception) {
                failures += "$archive: $e"
                continue
            }
            val expectedMd5 = expected.mapValues { it.value.substringAfter('\t') }
            if (seen != expectedMd5) failures += "$archive: expected $expectedMd5, got $seen"
        }
        assertEquals(emptyList<String>(), failures)
    }

    /** archive → (path → "size\tmd5") */
    private fun expected(): Map<String, Map<String, String>> {
        val text = javaClass.getResourceAsStream("/archives/expected.tsv")!!.bufferedReader().readText()
        val rows = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        return rows.groupBy({ it[0] }) { it }.mapValues { (_, list) ->
            list.associate { nfc(it[1]) to "${it[2]}\t${it[3]}" }
        }
    }

    private fun listFiles(dir: XEntry): List<XEntry> = fileSystem.list(dir).flatMap {
        if (it.isDir) listFiles(it) else listOf(it)
    }

    private fun pathOf(entry: XEntry): String = nfc(XId.zipInnerPath(entry.id))

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun archiveEntry(file: File) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = false,
        kind = EntryKind.ARCHIVE,
        localPath = file.absolutePath,
    )

    private fun fixture(name: String): File {
        val target = File(temporaryFolder.root, name)
        if (!target.exists()) {
            javaClass.getResourceAsStream("/archives/$name")!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }

    private fun md5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
