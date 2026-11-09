package app.local1st.files.core.text

import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TextCharsetTest {

    @Test
    fun ascii_isUtf8() {
        val encoding = detectTextEncoding("hello\nworld\n".toByteArray(Charsets.UTF_8))
        assertTrue(encoding.isUtf8)
    }

    @Test
    fun utf8Chinese_isNotGuessedAsGbk() {
        val bytes = "简体中文测试，UTF-8 不应被识别成 GBK".toByteArray(Charsets.UTF_8)
        val encoding = detectTextEncoding(bytes)
        assertTrue(encoding.isUtf8)
        assertEquals("简体中文测试，UTF-8 不应被识别成 GBK", String(bytes, encoding.charset))
    }

    @Test
    fun gbkChinese_isDetectedAndDecodes() {
        val charset = requireChineseCharset()
        val text = "简体中文测试，GBK 文件"
        val bytes = text.toByteArray(Charset.forName("GBK"))
        val encoding = detectTextEncoding(bytes)
        assertFalse(encoding.isUtf8)
        assertEquals(text, String(bytes, encoding.charset))
        assertEquals(text, String(bytes, charset))
    }

    @Test
    fun gb2312Chinese_isDetectedAndDecodes() {
        assumeTrue(Charset.isSupported("GB2312"))
        val text = "国家标准简体中文"
        val bytes = text.toByteArray(Charset.forName("GB2312"))
        val encoding = detectTextEncoding(bytes)
        assertFalse(encoding.isUtf8)
        assertEquals(text, String(bytes, encoding.charset))
        assertEquals("GB2312", encoding.label)
    }

    @Test
    fun gbkExtensionBytes_areLabeledGbk() {
        // Lead 0x81 / trail 0x40 is GBK, not GB2312 (which needs A1–FE for both bytes).
        val bytes = byteArrayOf(0x81.toByte(), 0x40, 0x81.toByte(), 0x40)
        val encoding = detectTextEncoding(bytes)
        assumeTrue(chineseLegacyCharset() != null)
        assertFalse(encoding.isUtf8)
        assertEquals("GBK", encoding.label)
    }

    @Test
    fun asciiHeaderThenGbk_isStillChinese() {
        val charset = Charset.forName("GBK")
        val bytes = ("# header\n" + "你好世界").toByteArray(charset)
        val encoding = detectTextEncoding(bytes)
        assertFalse(encoding.isUtf8)
        assertEquals("# header\n你好世界", String(bytes, encoding.charset))
    }

    @Test
    fun empty_isUtf8() {
        assertTrue(detectTextEncoding(ByteArray(0)).isUtf8)
    }

    @Test
    fun encodeText_roundTripsGbk() {
        val charset = requireChineseCharset()
        val text = "保存时仍用原编码\n"
        val encoded = encodeText(text, charset)
        assertEquals(text, String(encoded, charset))
    }

    @Test
    fun encodeText_unmappableCharacter_throwsWithoutEnglishMessage() {
        val error = assertThrows(IOException::class.java) {
            encodeText("中", Charsets.ISO_8859_1)
        }
        assertNull(error.message)
        assertTrue(isTextCodingFailure(error))
    }

    @Test
    fun isTextCodingFailure_isFalseForPlainIo() {
        assertFalse(isTextCodingFailure(IOException("disk full")))
    }

    @Test
    fun eucKr_withKoreanReader_isKorean() {
        val text = "안녕하세요. 이 파일은 한국어 텍스트 파일입니다.\n"
        val bytes = encoded(text, "EUC-KR")
        val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(Locale.KOREAN)))
        assertEquals("EUC-KR", encoding.label)
        assertEquals(text, String(bytes, encoding.charset))
    }

    @Test
    fun shiftJis_withJapaneseReader_isShiftJis() {
        val text = "これは日本語のテストファイルです。カタカナとひらがなと漢字。\n"
        val bytes = encoded(text, "Shift_JIS")
        val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(Locale.JAPANESE)))
        assertEquals("Shift_JIS", encoding.label)
        assertEquals(text, String(bytes, encoding.charset))
    }

    @Test
    fun eucJp_withJapaneseReader_isNotTakenForShiftJis() {
        val text = "これは日本語のテストファイルです。カタカナとひらがなと漢字。\n"
        val bytes = encoded(text, "EUC-JP")
        val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(Locale.JAPANESE)))
        assertEquals("EUC-JP", encoding.label)
        assertEquals(text, String(bytes, encoding.charset))
    }

    @Test
    fun big5_withTaiwaneseReader_isBig5() {
        val text = "這是一個繁體中文的測試檔案。今天天氣很好，我們去公園散步吧。\n"
        val bytes = encoded(text, "Big5")
        val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(Locale.TAIWAN)))
        assertEquals("Big5", encoding.label)
        assertEquals(text, String(bytes, encoding.charset))
    }

    @Test
    fun windows1256_withArabicReader_isArabic() {
        val text = "مرحبا بالعالم. هذا ملف نصي باللغة العربية، وهو مكتوب من اليمين إلى اليسار.\n"
        val bytes = encoded(text, "windows-1256")
        val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(Locale.forLanguageTag("ar"))))
        assertEquals("Windows-1256", encoding.label)
        assertEquals(text, String(bytes, encoding.charset))
    }

    @Test
    fun gbk_isStillChinese_forASimplifiedChineseOrEnglishReader() {
        val text = "简体中文测试，GBK 文件"
        val bytes = encoded(text, "GBK")
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(locale)))
            assertFalse(encoding.isUtf8)
            assertEquals(text, String(bytes, encoding.charset))
        }
    }

    @Test
    fun latin1_fallsBackToWindows1252_insteadOfReplacementCharacters() {
        val text = "Café crème brûlée, naïve façade — déjà vu.\n"
        val bytes = encoded(text, "windows-1252")
        val encoding = detectTextEncoding(bytes)
        assertEquals("Windows-1252", encoding.label)
        assertEquals(text, String(bytes, encoding.charset))
    }

    @Test
    fun germanLatin1_withGermanReader_isWindows1252() {
        val text = "Größere Häuser für schöne Straßen; Übermäßig überprüfte Äußerungen.\n"
        val bytes = encoded(text, "windows-1252")
        val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(Locale.GERMAN)))
        assertEquals("Windows-1252", encoding.label)
        assertEquals(text, String(bytes, encoding.charset))
    }

    @Test
    fun utf8WithAStrayByte_staysUtf8_evenWithHints() {
        val bytes = "café, naïve, é".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte()) + "\n".toByteArray()
        val encoding = detectTextEncoding(bytes, hints = legacyEncodingHints(listOf(Locale.forLanguageTag("ar"))))
        assertTrue(encoding.isUtf8)
    }

    @Test
    fun hints_followTheReadersLanguagesInOrder() {
        fun names(vararg tags: String) =
            legacyEncodingHints(tags.map { Locale.forLanguageTag(it) }).map { it.name() }
        assertEquals(emptyList<String>(), names("en-US", "zh-CN", "zh-Hans-HK"))
        assertEquals(listOf("windows-1252"), names("de-AT", "fr"))
        assertEquals(listOf("EUC-JP"), names("ja").take(1))
        assertEquals(2, names("ja").size)
        assertEquals(listOf("Big5"), names("zh-TW"))
        assertEquals(listOf("Big5"), names("zh-Hant-CN"))
        assertEquals(listOf("windows-1256"), names("ar-EG", "fa"))
        assertEquals(listOf("windows-1251", "windows-1256"), names("ru", "ar"))
    }

    @Test
    fun everySelectableEncoding_keepsAsciiAndNewlineBytes() {
        val ascii = "plain ASCII 0-9 {}[] <tag/>\r\n"
        val choices = selectableEncodings()
        assertEquals("UTF-8", choices.first().label)
        for (choice in choices) {
            val charset = choice.charset!!
            assertEquals(choice.label, ascii, String(ascii.toByteArray(Charsets.US_ASCII), charset))
            assertEquals(choice.label, listOf<Byte>(0x0A), "\n".toByteArray(charset).toList())
        }
    }

    private fun encoded(text: String, charsetName: String): ByteArray {
        assumeTrue("$charsetName not available", Charset.isSupported(charsetName))
        return text.toByteArray(Charset.forName(charsetName))
    }

    private fun requireChineseCharset(): Charset {
        val cs = chineseLegacyCharset()
        assumeTrue("GB18030/GBK/GB2312 not available", cs != null)
        return cs!!
    }
}
