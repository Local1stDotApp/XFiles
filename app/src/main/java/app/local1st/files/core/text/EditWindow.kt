package app.local1st.files.core.text

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A slice of a text file that an in-place editor can hold. Compose lays one text field out whole,
 * so the slice is capped; the rest of the file stays on disk and is stitched back on save.
 */
data class EditWindow(
    /** Inclusive byte offset of the first byte represented by [text]. */
    val from: Long,
    /** Exclusive byte offset just past the last byte represented by [text]. */
    val to: Long,
    val fileSize: Long,
    val text: String,
) {
    val isPartial: Boolean get() = from > 0L || to < fileSize
}

/**
 * Reads up to [maxBytes] placed around [centerOffset], so the row on screen sits inside the
 * field rather than only the bytes after it. A file that fits is loaded whole; near either
 * edge the window slides to stay in bounds. Call from an IO dispatcher.
 */
@Throws(IOException::class)
fun loadEditWindowAround(source: ByteWindow, centerOffset: Long, maxBytes: Long): EditWindow {
    val size = source.size
    if (size <= maxBytes || maxBytes <= 0L) return loadEditWindow(source, 0L, maxBytes)
    val center = centerOffset.coerceIn(0L, size)
    var start = (center - maxBytes / 2).coerceAtLeast(0L)
    if (start > size - maxBytes) start = size - maxBytes
    val snapped = snapStart(source, start)
    val window = loadEditWindow(source, if (snapped <= center) snapped else start, maxBytes)
    // snapEnd may drop a partial last line. Reloading from [start] hits the same snap when
    // [start] is 0; start at [center] so the row on screen is kept, sliding back only when
    // the remaining file is shorter than the cap so a tail still fills the window.
    if (center < window.to || window.to >= size) return window
    val retryStart = if (center + maxBytes > size) maxOf(0L, size - maxBytes) else center
    return loadEditWindow(source, retryStart, maxBytes)
}

/**
 * Reads up to [maxBytes] from [startOffset], aligned to UTF-8 character boundaries so a later
 * splice does not cut a character in half. Call from an IO dispatcher.
 */
@Throws(IOException::class)
fun loadEditWindow(source: ByteWindow, startOffset: Long, maxBytes: Long): EditWindow {
    val size = source.size
    val requested = startOffset.coerceIn(0L, size)
    if (size - requested <= 0L || maxBytes <= 0L) {
        return EditWindow(from = requested, to = requested, fileSize = size, text = "")
    }
    val start = includeLeadByte(source, requested)
    val remaining = size - start
    if (remaining <= 0L) {
        return EditWindow(from = start, to = start, fileSize = size, text = "")
    }
    // includeLeadByte may walk back 1–3 bytes; add that lookback so a tail still reaches EOF.
    val extra = (requested - start).coerceAtLeast(0L)
    val want = minOf(maxBytes + extra, remaining).toInt()
    val buf = ByteArray(want)
    val n = source.read(start, buf, 0, want)
    if (n <= 0) return EditWindow(from = start, to = start, fileSize = size, text = "")
    val skip = utf8Start(buf, n)
    val reachedEof = start + n >= size
    val end = snapEnd(buf, n, reachedEof)
    if (skip >= end) {
        return EditWindow(from = start + skip, to = start + skip, fileSize = size, text = "")
    }
    return EditWindow(
        from = start + skip,
        to = start + end,
        fileSize = size,
        text = decodeUtf8(buf, skip, end - skip),
    )
}

private const val NEWLINE = '\n'.code.toByte()
private const val START_SNAP_BYTES = 8 * 1024

/** Previous newline, so the field does not open mid-line. No newline in range: leave [start]. */
private fun snapStart(source: ByteWindow, start: Long): Long {
    if (start <= 0L) return 0L
    val lookback = minOf(start, START_SNAP_BYTES.toLong()).toInt()
    val buf = ByteArray(lookback)
    val n = source.read(start - lookback, buf, 0, lookback)
    if (n <= 0) return start
    for (i in n - 1 downTo 0) {
        if (buf[i] == NEWLINE) return start - lookback + i + 1
    }
    return start
}

/**
 * Prefer the last newline so a window is whole lines. No newline at all (one long line, or a
 * tail shorter than the cap that still is not the end of the file) falls back to a character
 * boundary, and the end of the file takes every remaining byte.
 */
private fun snapEnd(buf: ByteArray, length: Int, reachedEof: Boolean): Int {
    if (reachedEof) return length
    for (i in length - 1 downTo 0) {
        if (buf[i] == NEWLINE) return i + 1
    }
    val end = utf8End(buf, length)
    return if (end > 0) end else length
}

/**
 * If [start] lands inside a character, include that character when its lead byte is within 3
 * bytes. Otherwise leave [start] so [utf8Start] can skip the incomplete sequence.
 */
private fun includeLeadByte(source: ByteWindow, start: Long): Long {
    if (start <= 0L) return start
    val lookback = minOf(start, 3L).toInt()
    val buf = ByteArray(lookback + 1)
    val n = source.read(start - lookback, buf, 0, lookback + 1)
    if (n <= lookback) return start
    val at = lookback
    if (!isUtf8Continuation(buf[at])) return start
    var i = at
    var seen = 0
    while (i > 0 && seen < 3 && isUtf8Continuation(buf[i])) {
        i--
        seen++
    }
    if (isUtf8Continuation(buf[i])) return start
    val expected = utf8ExpectedContinuations(buf[i].toInt() and 0xFF)
    val continuations = at - i
    return if (expected > 0 && continuations in 1..expected) start - lookback + i else start
}

/** Skips a leading incomplete UTF-8 sequence so it stays in the prefix that splice copies. */
private fun utf8Start(buf: ByteArray, length: Int): Int {
    var i = 0
    while (i < length && isUtf8Continuation(buf[i])) i++
    return i
}

/** Drops a trailing incomplete UTF-8 sequence so decoding does not invent a replacement glyph. */
private fun utf8End(buf: ByteArray, length: Int): Int {
    var i = length
    var continuation = 0
    while (i > 0 && continuation < 3 && isUtf8Continuation(buf[i - 1])) {
        i--
        continuation++
    }
    if (i == 0) return length
    val expected = utf8ExpectedContinuations(buf[i - 1].toInt() and 0xFF)
    return if (expected > 0 && continuation < expected) i - 1 else length
}

private fun isUtf8Continuation(b: Byte) = (b.toInt() and 0xC0) == 0x80

private fun utf8ExpectedContinuations(lead: Int): Int = when {
    lead and 0xE0 == 0xC0 -> 1
    lead and 0xF0 == 0xE0 -> 2
    lead and 0xF8 == 0xF0 -> 3
    else -> 0
}

/** Rejects a slice that is still not well-formed, so Save cannot write U+FFFD back. */
private fun decodeUtf8(buf: ByteArray, offset: Int, length: Int): String {
    if (length <= 0) return ""
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(buf, offset, length)).toString()
    } catch (e: CharacterCodingException) {
        throw IOException("Cannot decode text", e)
    }
}
