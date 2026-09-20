package app.local1st.files.core.text

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Byte encoding of a text document. [charset] is what decode/encode use; [label] is the name to
 * show when it is not UTF-8. GB2312, GBK and GB18030 all decode through the most capable of those
 * the runtime actually has — GB18030 is a superset — so a GBK file does not need a GBK-named
 * decoder to come out as Chinese.
 */
data class TextEncoding(
    val charset: Charset,
    val label: String,
) {
    val isUtf8: Boolean get() = charset == Charsets.UTF_8

    companion object {
        val Utf8 = TextEncoding(Charsets.UTF_8, "UTF-8")
    }
}

/** Leading sample used to guess encoding. Large enough to see past an ASCII header. */
const val CHARSET_PROBE_BYTES = 32 * 1024

/**
 * Guess the byte encoding of a text file from a leading sample.
 *
 * UTF-8 wins whenever the sample is well-formed: ASCII is valid UTF-8, and real UTF-8 Chinese
 * must not be stolen by the GBK scorer — those encodings overlap on many byte values. GB2312 /
 * GBK / GB18030 are tried only after UTF-8 has already lost: a GBK file of Chinese is almost
 * never well-formed UTF-8, which is what makes the split reliable.
 */
fun detectTextEncoding(bytes: ByteArray, length: Int = bytes.size): TextEncoding {
    val n = length.coerceIn(0, bytes.size)
    if (n <= 0) return TextEncoding.Utf8
    val utf8 = utf8Stats(bytes, n)
    if (utf8.invalid == 0) return TextEncoding.Utf8
    val chineseCharset = chineseLegacyCharset() ?: return TextEncoding.Utf8
    val chinese = chineseScore(bytes, n)
    if (!chinese.looksLikeChinese) return TextEncoding.Utf8
    // A UTF-8 file with a single glitch still has many valid multibyte sequences; flipping the
    // whole document to GBK would garble it. A GBK file has almost none of those sequences.
    val keepUtf8 = utf8.validMultibyte >= 8 && utf8.validMultibyte >= utf8.invalid * 4
    if (keepUtf8) return TextEncoding.Utf8
    return TextEncoding(chineseCharset, chinese.label)
}

fun detectTextEncoding(source: ByteWindow): TextEncoding {
    val n = minOf(source.size, CHARSET_PROBE_BYTES.toLong()).toInt()
    if (n <= 0) return TextEncoding.Utf8
    val sample = ByteArray(n)
    val read = source.read(0, sample, 0, n)
    if (read <= 0) return TextEncoding.Utf8
    return detectTextEncoding(sample, read)
}

/**
 * Encodes [text] in [charset] without replacing unmappable characters, so Save cannot silently
 * turn a typed character into `?`.
 */
@Throws(IOException::class)
fun encodeText(text: String, charset: Charset): ByteArray {
    if (charset == Charsets.UTF_8) return text.toByteArray(Charsets.UTF_8)
    val encoder = charset.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        val buf = encoder.encode(CharBuffer.wrap(text))
        val out = ByteArray(buf.remaining())
        buf.get(out)
        out
    } catch (e: CharacterCodingException) {
        throw IOException(null, e)
    }
}

/** True when [error] is (or wraps) a charset encode/decode failure. */
fun isTextCodingFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is CharacterCodingException) return true
        current = current.cause
    }
    return false
}

/**
 * Exclusive index of the last complete character of [charset] in [buf][[start], [end]).
 * Incomplete or malformed bytes at the end are left unconsumed, so the returned index can equal
 * [start] when nothing whole sits in the slice.
 */
internal fun charsetConsumedEnd(buf: ByteArray, start: Int, end: Int, charset: Charset): Int {
    if (end <= start) return start
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(buf)
    input.position(start)
    input.limit(end)
    val output = CharBuffer.allocate((end - start).coerceAtLeast(1))
    decoder.decode(input, output, false)
    return input.position()
}

/**
 * GB18030 if the runtime has it, else GBK, else GB2312. Decoding GB2312 or GBK bytes with a
 * GB18030 decoder is correct; the reverse is not.
 */
internal fun chineseLegacyCharset(): Charset? {
    for (name in arrayOf("GB18030", "GBK", "GB2312")) {
        charsetOrNull(name)?.let { return it }
    }
    return null
}

internal fun charsetOrNull(name: String): Charset? = try {
    if (Charset.isSupported(name)) Charset.forName(name) else null
} catch (_: Exception) {
    null
}

private class Utf8Stats(val validMultibyte: Int, val invalid: Int)

private class ChineseScore(
    val pairs: Int,
    val fourByte: Int,
    val gbkOnlyPairs: Int,
    val gb2312Pairs: Int,
    val bad: Int,
) {
    val good: Int get() = pairs + fourByte
    val looksLikeChinese: Boolean get() = good >= 1 && good >= bad * 5
    val label: String
        get() = when {
            fourByte > 0 && gbkOnlyPairs == 0 && gb2312Pairs == 0 -> "GB18030"
            gbkOnlyPairs > 0 -> "GBK"
            else -> "GB2312"
        }
}

private fun isUtf8Continuation(b: Byte) = (b.toInt() and 0xC0) == 0x80

/**
 * Valid UTF-8 sequences vs bytes that cannot start or continue one. An incomplete sequence at
 * the end of the sample is not invalid: the file may continue past the probe.
 */
private fun utf8Stats(buf: ByteArray, length: Int): Utf8Stats {
    var i = 0
    var validMb = 0
    var invalid = 0
    while (i < length) {
        val lead = buf[i].toInt() and 0xFF
        when {
            lead <= 0x7F -> i++
            lead in 0xC2..0xDF -> {
                if (i + 1 >= length) break
                if (isUtf8Continuation(buf[i + 1])) {
                    validMb++
                    i += 2
                } else {
                    invalid++
                    i++
                }
            }
            lead in 0xE0..0xEF -> {
                if (i + 2 >= length) break
                if (isUtf8Continuation(buf[i + 1]) && isUtf8Continuation(buf[i + 2])) {
                    validMb++
                    i += 3
                } else {
                    invalid++
                    i++
                }
            }
            lead in 0xF0..0xF4 -> {
                if (i + 3 >= length) break
                if (isUtf8Continuation(buf[i + 1]) &&
                    isUtf8Continuation(buf[i + 2]) &&
                    isUtf8Continuation(buf[i + 3])
                ) {
                    validMb++
                    i += 4
                } else {
                    invalid++
                    i++
                }
            }
            else -> {
                invalid++
                i++
            }
        }
    }
    return Utf8Stats(validMb, invalid)
}

/**
 * GB18030 family: ASCII, 2-byte GBK (lead 0x81–0xFE, trail 0x40–0x7E / 0x80–0xFE), or 4-byte
 * GB18030 (second and fourth bytes 0x30–0x39). Isolated high bytes count against the score.
 */
private fun chineseScore(buf: ByteArray, length: Int): ChineseScore {
    var i = 0
    var pairs = 0
    var four = 0
    var bad = 0
    var gb2312 = 0
    var gbkOnly = 0
    while (i < length) {
        val b = buf[i].toInt() and 0xFF
        if (b <= 0x7F) {
            i++
            continue
        }
        if (b < 0x81) {
            bad++
            i++
            continue
        }
        if (i + 1 >= length) break
        val c = buf[i + 1].toInt() and 0xFF
        if (c in 0x30..0x39) {
            if (i + 3 >= length) break
            val d = buf[i + 2].toInt() and 0xFF
            val e = buf[i + 3].toInt() and 0xFF
            if (d in 0x81..0xFE && e in 0x30..0x39) {
                four++
                i += 4
            } else {
                bad++
                i++
            }
            continue
        }
        if (c in 0x40..0x7E || c in 0x80..0xFE) {
            pairs++
            if (b in 0xA1..0xFE && c in 0xA1..0xFE) gb2312++ else gbkOnly++
            i += 2
            continue
        }
        bad++
        i++
    }
    return ChineseScore(pairs, four, gbkOnly, gb2312, bad)
}
