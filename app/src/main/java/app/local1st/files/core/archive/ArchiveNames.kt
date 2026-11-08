package app.local1st.files.core.archive

import app.local1st.files.core.text.charsetOrNull
import app.local1st.files.core.text.chineseLegacyCharset
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.util.Locale

/**
 * Turns entry names stored as bytes into text without losing any of them.
 *
 * Names that are valid UTF-8 are read as UTF-8. Other names were written in a legacy code page
 * that archives don't record: zip tools on Windows use the system's DOS code page, GBK on a
 * Chinese system for example. Those names are read in the code page of the device's language,
 * as Windows would read them, with two refinements:
 *
 * - When the device is set to English, to another language without a code page of its own, or
 *   to a Western European language or Turkish, GBK comes first if the names look like GBK text.
 *   GBK is the most common legacy encoding, and text in those code pages practically never looks
 *   like it, so Chinese archives read right on such devices too.
 * - A code page is only used when it decodes every name. Code page 437, the zip standard's
 *   default, is the last resort; it gives every byte a character.
 *
 * Nothing is ever replaced with a placeholder character. Placeholders made two different names
 * come out the same, and then one entry hid the other.
 */
internal object ArchiveNames {

    private val GB18030: Charset? = chineseLegacyCharset()
    private val CP437: Charset? = charset("IBM437", "cp437")

    /** One name on its own, as the formats libarchive reads hand them over. */
    fun decode(name: ByteArray): String = textOf(name, Charsets.UTF_8.newDecoder()) ?: legacy(listOf(name))[0]

    /**
     * A zip's names. [markedUtf8] tells which ones the zip marks as UTF-8. The rest came from one
     * tool and share one encoding: UTF-8 when every one of them is valid UTF-8, otherwise one
     * legacy code page for all of them.
     */
    fun decodeZip(names: List<ByteArray>, markedUtf8: (Int) -> Boolean): List<String> {
        val utf8 = Charsets.UTF_8.newDecoder()
        val decoded = arrayOfNulls<String>(names.size)
        val unmarked = ArrayList<Int>()
        for ((i, name) in names.withIndex()) {
            when {
                isAscii(name) -> decoded[i] = String(name, Charsets.ISO_8859_1)
                markedUtf8(i) -> decoded[i] = strict(name, utf8)
                else -> unmarked += i
            }
        }
        val unmarkedUtf8 = unmarked.map { strict(names[it], utf8) }
        if (unmarkedUtf8.all { it != null }) {
            unmarked.forEachIndexed { k, i -> decoded[i] = unmarkedUtf8[k] }
        }
        // Everything still missing: unmarked names in a legacy code page, and the odd name
        // marked as UTF-8 that isn't.
        val rest = decoded.indices.filter { decoded[it] == null }
        if (rest.isNotEmpty()) {
            val texts = legacy(rest.map { names[it] })
            rest.forEachIndexed { k, i -> decoded[i] = texts[k] }
        }
        return decoded.map { it!! }
    }

    private fun legacy(names: List<ByteArray>): List<String> {
        val own = codePage(Locale.getDefault())
        if (own != null && !own.yieldsToGbk) strictAll(names, own.charset)?.let { return it }
        if (GB18030 != null && looksLikeGbk(names)) strictAll(names, GB18030)?.let { return it }
        if (own != null && own.yieldsToGbk) strictAll(names, own.charset)?.let { return it }
        if (CP437 != null) strictAll(names, CP437)?.let { return it }
        return names.map { String(it, Charsets.ISO_8859_1) }
    }

    /**
     * Whether [names] read as GBK text: at least half of their two-byte characters lie where
     * GB 2312 keeps its symbols and hanzi, a lead byte of A1-A9 or B0-F7 with a trail byte of
     * A1-FE.
     */
    private fun looksLikeGbk(names: List<ByteArray>): Boolean {
        var pairs = 0
        var gb2312 = 0
        for (name in names) {
            var i = 0
            while (i < name.size) {
                val lead = name[i].toInt() and 0xFF
                if (lead < 0x80 || i + 1 == name.size) {
                    i++
                    continue
                }
                val trail = name[i + 1].toInt() and 0xFF
                pairs++
                if ((lead in 0xA1..0xA9 || lead in 0xB0..0xF7) && trail in 0xA1..0xFE) gb2312++
                i += 2
            }
        }
        return pairs > 0 && gb2312 * 2 >= pairs
    }

    /**
     * @property yieldsToGbk whether names that look like GBK are taken for GBK before this code
     *   page is tried. Only for 850 and 857: they keep their accented letters below A1, or
     *   where two of them rarely meet, so their text practically never passes for GBK. In the
     *   others, Central European 852 and Baltic 775 included, two letters in a row often do.
     */
    private class CodePage(val charset: Charset, val yieldsToGbk: Boolean)

    /** The DOS code page Windows uses for [locale]'s language, or null for 437 and Unicode-only languages. */
    private fun codePage(locale: Locale): CodePage? {
        val (names, yieldsToGbk) = when (locale.language) {
            "zh" -> if (isTraditional(locale)) {
                listOf("x-windows-950", "Big5") to false
            } else {
                listOf("GB18030", "GBK", "GB2312") to false
            }
            "ja" -> listOf("windows-31j", "Shift_JIS") to false
            "ko" -> listOf("x-windows-949", "EUC-KR") to false
            "ru", "uk", "be", "bg", "mk", "kk", "ky", "tg", "tt", "ba", "mn" -> listOf("IBM866") to false
            "sr" -> listOf(if (locale.script == "Latn") "IBM852" else "IBM855") to false
            "el" -> listOf("x-IBM737", "IBM737") to false
            "he", "iw" -> listOf("IBM862") to false
            "ar", "fa", "ur" -> listOf("x-IBM720", "IBM720") to false
            "th" -> listOf("x-IBM874", "IBM874", "TIS-620") to false
            "vi" -> listOf("windows-1258") to false
            "pl", "cs", "sk", "hu", "sl", "hr", "bs", "ro", "sq" -> listOf("IBM852") to false
            "lt", "lv", "et" -> listOf("IBM775") to false
            "tr", "az" -> listOf("IBM857") to true
            "de", "fr", "es", "it", "pt", "nl", "ca", "eu", "gl", "da", "sv", "nb", "nn", "no",
            "fi", "is", "ga", "af", "fo", "lb",
            -> listOf("IBM850") to true
            else -> return null
        }
        val charset = charset(*names.toTypedArray()) ?: return null
        return CodePage(charset, yieldsToGbk)
    }

    private fun isTraditional(locale: Locale): Boolean =
        locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))

    private fun charset(vararg names: String): Charset? = names.firstNotNullOfOrNull(::charsetOrNull)

    private fun isAscii(name: ByteArray): Boolean = name.all { it >= 0 }

    private fun textOf(name: ByteArray, utf8: CharsetDecoder): String? =
        if (isAscii(name)) String(name, Charsets.ISO_8859_1) else strict(name, utf8)

    /** Every name decoded with [charset], or null if any of them isn't valid in it. */
    private fun strictAll(names: List<ByteArray>, charset: Charset): List<String>? {
        val decoder = charset.newDecoder()
        return names.map { strict(it, decoder) ?: return null }
    }

    /** [name] decoded, or null if it isn't valid in the decoder's charset. Decoders report by default. */
    private fun strict(name: ByteArray, decoder: CharsetDecoder): String? = try {
        decoder.decode(ByteBuffer.wrap(name)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
