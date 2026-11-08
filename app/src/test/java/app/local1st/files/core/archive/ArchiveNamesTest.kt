package app.local1st.files.core.archive

import app.local1st.files.core.fs.ArchiveFileSystem
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.ops.ZipTurbo
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Entry names in legacy code pages: read in the right one, and never two names as one. */
class ArchiveNamesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private val gbk = Charset.forName("GBK")

    /** As a zip from Chinese Windows has them. 淺 is 9C 5C in GBK: its second byte is a backslash. */
    private val chineseNames = listOf("中文.txt", "张王.txt", "资料/说明.txt", "淺色.txt")

    @Test
    fun utf8Names_readAsUtf8_withOrWithoutTheFlag() {
        val names = listOf("中文.txt", "日本語/ファイル.txt", "café.txt", "plain.txt")
        val bytes = names.map { it.toByteArray(Charsets.UTF_8) }

        assertEquals(names, ArchiveNames.decodeZip(bytes) { true })
        assertEquals(names, ArchiveNames.decodeZip(bytes) { false })
        assertEquals(names, bytes.map(ArchiveNames::decode))
    }

    @Test
    fun gbkNames_readAsChinese_whateverTheDeviceLanguage() {
        val bytes = chineseNames.map { it.toByteArray(gbk) }

        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.GERMANY, Locale("es", "ES"))) {
            Locale.setDefault(locale)
            assertEquals("$locale", chineseNames, ArchiveNames.decodeZip(bytes) { false })
            assertEquals("$locale", chineseNames, bytes.map(ArchiveNames::decode))
        }
    }

    @Test
    fun gbkNameThatIsAlsoValidUtf8_followsTheRestOfTheZip() {
        // 说 is CB B5 in GBK, which is also UTF-8 for ˵. The zip's other name is GBK, so this one is too.
        val bytes = listOf("说.txt", "中文.txt").map { it.toByteArray(gbk) }
        Locale.setDefault(Locale.US)

        assertEquals(listOf("说.txt", "中文.txt"), ArchiveNames.decodeZip(bytes) { false })
    }

    @Test
    fun dosNames_readInTheCodePageOfTheDeviceLanguage() {
        val cases = listOf(
            Triple(Locale.US, "IBM437", listOf("café.txt", "Müller.txt", "naïve résumé.txt")),
            Triple(Locale("pt", "BR"), "IBM850", listOf("São Paulo.txt", "Ação.txt")),
            Triple(Locale.GERMANY, "IBM850", listOf("Größe.txt", "Ärger.txt")),
            Triple(Locale("pl", "PL"), "IBM852", listOf("Zażółć gęślą jaźń.txt")),
            // ýš and Ąž pair up like GBK; these code pages come before the GBK check.
            Triple(Locale("cs", "CZ"), "IBM852", listOf("Výška.txt")),
            Triple(Locale("lt", "LT"), "IBM775", listOf("Ąžuolas.txt")),
            Triple(Locale("tr", "TR"), "IBM857", listOf("Ağaç işleri.txt")),
            // 1258 has no ệ: Vietnamese Windows stores it as ê and a combining dot below.
            Triple(Locale("vi", "VN"), "windows-1258", listOf("Tài liê\u0323u.txt")),
            Triple(Locale("ru", "RU"), "IBM866", listOf("Документ.txt", "отчёт.txt")),
            Triple(Locale("el", "GR"), "x-IBM737", listOf("Έγγραφο.txt")),
            Triple(Locale.JAPAN, "windows-31j", listOf("テスト.txt", "表.txt", "漢字/ファイル.txt")),
            Triple(Locale.KOREA, "x-windows-949", listOf("한글.txt", "문서/보고서.txt")),
            Triple(Locale.TRADITIONAL_CHINESE, "Big5", listOf("繁體中文.txt", "檔案/說明.txt")),
        )
        for ((locale, codePage, names) in cases) {
            val bytes = names.map { encodeStrictly(it, Charset.forName(codePage)) }
            Locale.setDefault(locale)
            assertEquals("$locale", names, ArchiveNames.decodeZip(bytes) { false })
        }
    }

    @Test
    fun japaneseNames_onAnEnglishDevice_areNotTakenForGbk() {
        val bytes = listOf("テスト.txt", "ファイル.txt").map { it.toByteArray(Charset.forName("windows-31j")) }
        Locale.setDefault(Locale.US)

        // Code page 437, as Windows set to English shows them: unreadable, but nothing is lost.
        assertEquals(bytes.map { String(it, Charset.forName("IBM437")) }, ArchiveNames.decodeZip(bytes) { false })
    }

    @Test
    fun everyByteValue_staysDistinct_inEveryLanguage() {
        val names = (0x80..0xFF).map { byteArrayOf(it.toByte(), '.'.code.toByte(), 'x'.code.toByte()) } +
            (0x80..0xFF).map { byteArrayOf('a'.code.toByte(), it.toByte(), it.toByte()) }
        val locales = listOf(
            Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.JAPAN, Locale.KOREA,
            Locale("ru", "RU"), Locale("el", "GR"), Locale("he", "IL"), Locale("ar", "EG"), Locale("th", "TH"),
            Locale("vi", "VN"), Locale.GERMANY, Locale("pl", "PL"), Locale("tr", "TR"), Locale("lt", "LT"),
        )
        for (locale in locales) {
            Locale.setDefault(locale)
            val decoded = ArchiveNames.decodeZip(names) { false }
            assertEquals("$locale", names.size, decoded.toSet().size)
            assertFalse("$locale", decoded.any { '�' in it })
        }
    }

    @Test
    fun namesReadOneAtATime_neverGetPlaceholders() {
        val names = (0x80..0xFF).map { byteArrayOf(it.toByte(), '.'.code.toByte(), 'x'.code.toByte()) } +
            chineseNames.map { it.toByteArray(gbk) }
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.JAPAN, Locale("ru", "RU"), Locale.GERMANY)) {
            Locale.setDefault(locale)
            val decoded = names.map(ArchiveNames::decode)
            assertEquals("$locale", names.size, decoded.toSet().size)
            assertFalse("$locale", decoded.any { '�' in it })
        }
    }

    @Test
    fun gbkZip_browsesAndExtractsEveryFileUnderItsName() {
        Locale.setDefault(Locale.US)
        val zip = gbkZip("gbk.zip", chineseNames.associateWith { "content of $it" })
        val expected = chineseNames.associateWith { "content of $it" }

        val listed = listFiles(zip)
        assertEquals(expected.keys, listed)
        for (name in chineseNames) {
            val entry = fileSystem.stat(XId.zip(zip.absolutePath, name)) ?: error("No $name")
            assertEquals(expected[name], fileSystem.openIn(entry).use { String(it.readBytes()) })
        }
        val onePass = HashMap<String, String>()
        fileSystem.forEachFile(archiveEntry(zip)) { path, input -> onePass[path] = String(input.readBytes()) }
        assertEquals(expected, onePass)

        val dest = temporaryFolder.newFolder("out")
        ZipTurbo.extractZip(zip, dest, onBytes = {}, isCancelled = { false })
        val written = dest.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dest).path to it.readText() }
        assertEquals(expected, written)
    }

    @Test
    fun gbkZip_namesTheEntryInErrors() {
        Locale.setDefault(Locale.US)
        val zip = gbkZip("crc.zip", mapOf("中文.txt" to "some stored text"), stored = true)
        val bytes = zip.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("some stored text")
        bytes[at] = (bytes[at].toInt() xor 0x20).toByte()
        zip.writeBytes(bytes)

        val entry = fileSystem.stat(XId.zip(zip.absolutePath, "中文.txt")) ?: error("No entry")
        val e = assertThrows(IOException::class.java) { fileSystem.openIn(entry).use { it.readBytes() } }
        assertEquals("中文.txt is corrupt (CRC mismatch)", e.message)
    }

    @Test
    fun gbkTar_listsChineseNames() {
        Locale.setDefault(Locale.US)
        val tar = temporaryFolder.root.resolve("gbk.tar")
        TarArchiveOutputStream(tar.outputStream(), "GBK").use { out ->
            for (name in chineseNames) {
                val data = "content of $name".toByteArray()
                out.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                out.write(data)
                out.closeArchiveEntry()
            }
        }

        assertEquals(chineseNames.toSet(), listFiles(tar))
        val entry = fileSystem.stat(XId.zip(tar.absolutePath, "淺色.txt")) ?: error("No entry")
        assertEquals("content of 淺色.txt", fileSystem.openIn(entry).use { String(it.readBytes()) })
    }

    @Test
    fun legacyNamesWithTheUnicodePathField_useThatField() {
        Locale.setDefault(Locale.US)
        val zip = temporaryFolder.root.resolve("unicode-extra.zip")
        ZipArchiveOutputStream(zip).use { out ->
            // A code page that can't hold the name, so only the Info-ZIP field has it right.
            out.setEncoding("IBM437")
            out.setUseLanguageEncodingFlag(false)
            out.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS)
            out.putArchiveEntry(ZipArchiveEntry("中文.txt"))
            out.write("x".toByteArray())
            out.closeArchiveEntry()
        }

        assertEquals(setOf("中文.txt"), listFiles(zip))
    }

    private val fileSystem = ArchiveFileSystem(mimeOf = { null })

    /** Unlike toByteArray, fails on a character the code page can't hold instead of writing a stand-in. */
    private fun encodeStrictly(text: String, charset: Charset): ByteArray =
        charset.newEncoder().encode(CharBuffer.wrap(text)).let { ByteArray(it.remaining()).also(it::get) }

    /** A zip as Chinese Windows writes it: GBK names, no UTF-8 flag, made on MS-DOS. */
    private fun gbkZip(name: String, files: Map<String, String>, stored: Boolean = false): File {
        val zip = temporaryFolder.root.resolve(name)
        ZipArchiveOutputStream(zip).use { out ->
            out.setEncoding("GBK")
            out.setUseLanguageEncodingFlag(false)
            out.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
            for ((path, text) in files) {
                out.putArchiveEntry(ZipArchiveEntry(path).apply { if (stored) method = ZipEntry.STORED })
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
        }
        return zip
    }

    private fun archiveEntry(file: File) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = false,
        kind = EntryKind.ARCHIVE,
        localPath = file.absolutePath,
    )

    private fun listFiles(archive: File): Set<String> {
        val files = LinkedHashSet<String>()
        fun walk(dir: XEntry) {
            for (entry in fileSystem.list(dir)) {
                if (entry.isDir) walk(entry) else files += XId.zipInnerPath(entry.id)
            }
        }
        walk(archiveEntry(archive))
        assertTrue(files.none { '�' in it })
        return files
    }
}
