package app.local1st.files.core.text

import java.io.IOException
import java.nio.charset.Charset
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

    private fun requireChineseCharset(): Charset {
        val cs = chineseLegacyCharset()
        assumeTrue("GB18030/GBK/GB2312 not available", cs != null)
        return cs!!
    }
}
