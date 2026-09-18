package app.local1st.files.core.text

/**
 * Lines of an in-place editor window. Compose lays one text field out whole, so the editor
 * holds the slice as rows and only the focused row is a field; [toText] is what Save writes.
 *
 * [split] / [join] is lossless: `join(split(s)) == s` for every string, including a trailing
 * newline, which is an empty last row rather than a phantom line.
 */
class EditBuffer(text: String) {
    private val lines = splitEditLines(text)
    var byteCount: Int = utf8ByteCount(text)
        private set

    val size: Int get() = lines.size

    operator fun get(index: Int): String = lines[index]

    fun toText(): String = joinEditLines(lines)

    /**
     * Line count matching [countEditLines], so the header does not jump between the viewer
     * (which ignores a trailing newline) and the editor (which keeps it as an empty last row).
     */
    fun lineCount(): Int = countEditLines(lines)

    /**
     * Line covering [utf8Offset] in the UTF-8 of [toText]. The viewer hands the byte offset of
     * the row on screen; the editor window is a slice around that row, so this is the line to
     * open on rather than the start of the slice.
     */
    fun lineAtUtf8Offset(utf8Offset: Int): Int = caretAtUtf8Offset(utf8Offset).line

    /**
     * Line and column covering [utf8Offset] in the UTF-8 of [toText]. Column is a character
     * index, so a viewer row that continues a wrapped line opens inside that line instead of
     * at column 0. An offset in the middle of a multibyte character lands on that character.
     */
    fun caretAtUtf8Offset(utf8Offset: Int): EditCaret {
        if (lines.isEmpty()) return EditCaret(0, 0)
        var remaining = utf8Offset.coerceAtLeast(0)
        for (i in lines.indices) {
            val lineBytes = utf8ByteCount(lines[i])
            val withNl = lineBytes + if (i < lines.lastIndex) 1 else 0
            if (remaining < withNl || i == lines.lastIndex) {
                return EditCaret(i, columnAtUtf8Offset(lines[i], remaining.coerceAtMost(lineBytes)))
            }
            remaining -= withNl
        }
        val last = lines.lastIndex
        return EditCaret(last, lines[last].length)
    }

    /**
     * Replaces [index] with [value]. Newlines become extra rows (Enter, or a paste).
     * [selectionStart] is the caret offset in [value], mapped onto the resulting rows.
     * Returns null if the window would exceed [maxBytes]; the buffer is unchanged.
     */
    fun replace(index: Int, value: String, selectionStart: Int, maxBytes: Int): EditCaret? {
        if (index !in lines.indices) return null
        val old = lines[index]
        val delta = utf8ByteCount(value) - utf8ByteCount(old)
        if (byteCount + delta > maxBytes) return null
        if (value == old) {
            return EditCaret(index, selectionStart.coerceIn(0, value.length))
        }
        val parts = value.split('\n')
        if (parts.size == 1) {
            lines[index] = value
        } else {
            lines.removeAt(index)
            lines.addAll(index, parts)
        }
        byteCount += delta
        return caretIn(value, selectionStart, index)
    }

    /**
     * Backspace at column 0: append this row to the previous one. Bytes only fall (the
     * newline is dropped), so there is no cap to check.
     */
    fun mergeWithPrevious(index: Int): EditCaret? {
        if (index <= 0 || index >= lines.size) return null
        val prev = lines[index - 1]
        lines[index - 1] = prev + lines[index]
        lines.removeAt(index)
        byteCount -= 1
        return EditCaret(index - 1, prev.length)
    }

    /**
     * Forward-delete at the end of this row: append the next row. Same as [mergeWithPrevious]
     * on the next index; the caret stays at the join.
     */
    fun mergeWithNext(index: Int): EditCaret? = mergeWithPrevious(index + 1)

    /**
     * Text from [from] to [to], in document order, including the newlines between rows.
     * Used for copy of a selection that may span rows.
     */
    fun textInRange(from: EditCaret, to: EditCaret): String {
        if (lines.isEmpty()) return ""
        val (start, end) = orderedCarets(from, to)
        val startLine = start.line.coerceIn(0, lines.lastIndex)
        val endLine = end.line.coerceIn(0, lines.lastIndex)
        val startCol = start.column.coerceIn(0, lines[startLine].length)
        val endCol = end.column.coerceIn(0, lines[endLine].length)
        if (startLine == endLine) {
            if (startCol >= endCol) return ""
            return lines[startLine].substring(startCol, endCol)
        }
        val sb = StringBuilder()
        sb.append(lines[startLine].substring(startCol))
        for (i in startLine + 1 until endLine) {
            sb.append('\n')
            sb.append(lines[i])
        }
        sb.append('\n')
        sb.append(lines[endLine].substring(0, endCol))
        return sb.toString()
    }

    /**
     * Replaces the range [from]–[to] with [insert], which may contain newlines.
     * Returns null if the window would exceed [maxBytes]; the buffer is unchanged.
     * The caret is left at the end of [insert].
     */
    fun replaceRange(from: EditCaret, to: EditCaret, insert: String, maxBytes: Int): EditCaret? {
        if (lines.isEmpty()) return null
        val (start, end) = orderedCarets(from, to)
        val startLine = start.line.coerceIn(0, lines.lastIndex)
        val endLine = end.line.coerceIn(0, lines.lastIndex)
        val startCol = start.column.coerceIn(0, lines[startLine].length)
        val endCol = end.column.coerceIn(0, lines[endLine].length)
        val deleted = textInRange(EditCaret(startLine, startCol), EditCaret(endLine, endCol))
        val delta = utf8ByteCount(insert) - utf8ByteCount(deleted)
        if (byteCount + delta > maxBytes) return null
        val prefix = lines[startLine].substring(0, startCol)
        val suffix = lines[endLine].substring(endCol)
        val value = prefix + insert + suffix
        val parts = value.split('\n')
        lines.subList(startLine, endLine + 1).clear()
        lines.addAll(startLine, parts)
        byteCount += delta
        return caretIn(value, prefix.length + insert.length, startLine)
    }
}

data class EditCaret(val line: Int, val column: Int)

internal fun splitEditLines(text: String): MutableList<String> =
    text.split('\n').toMutableList()

internal fun joinEditLines(lines: List<String>): String = lines.joinToString("\n")

internal fun countEditLines(lines: List<String>): Int {
    if (lines.isEmpty()) return 0
    if (lines.size == 1 && lines[0].isEmpty()) return 0
    return if (lines.last().isEmpty()) lines.size - 1 else lines.size
}

internal fun utf8ByteCount(s: String): Int {
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        bytes += utf8CodePointBytes(cp)
        i += Character.charCount(cp)
    }
    return bytes
}

/** Character index in [s] at which [utf8Offset] bytes have been consumed. */
internal fun columnAtUtf8Offset(s: String, utf8Offset: Int): Int {
    if (utf8Offset <= 0 || s.isEmpty()) return 0
    var bytes = 0
    var i = 0
    while (i < s.length) {
        if (bytes >= utf8Offset) return i
        val cp = s.codePointAt(i)
        val n = utf8CodePointBytes(cp)
        if (bytes + n > utf8Offset) return i
        bytes += n
        i += Character.charCount(cp)
    }
    return s.length
}

private fun utf8CodePointBytes(cp: Int): Int = when {
    cp < 0x80 -> 1
    cp < 0x800 -> 2
    cp < 0x10000 -> 3
    else -> 4
}

private fun orderedCarets(a: EditCaret, b: EditCaret): Pair<EditCaret, EditCaret> {
    val aFirst = a.line < b.line || (a.line == b.line && a.column <= b.column)
    return if (aFirst) a to b else b to a
}

private fun caretIn(value: String, selectionStart: Int, baseLine: Int): EditCaret {
    val end = selectionStart.coerceIn(0, value.length)
    var line = baseLine
    var column = 0
    for (i in 0 until end) {
        if (value[i] == '\n') {
            line++
            column = 0
        } else {
            column++
        }
    }
    return EditCaret(line, column)
}
