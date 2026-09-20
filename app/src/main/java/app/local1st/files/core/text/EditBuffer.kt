package app.local1st.files.core.text

import java.nio.charset.Charset

/**
 * Longest row an editor field is allowed to hold, in characters.
 *
 * The same hard ceiling as [MAX_ROW_BYTES], for the same reason: Compose cannot represent a
 * layout dimension over 262143 px. The editor's rows wrap rather than run off the side, so what
 * a single unbroken row costs is height — and a stretch with no newline anywhere in it (minified
 * JSON, a one-line CSV, a log written without terminators) is one line half a megabyte long,
 * which lays out hundreds of thousands of pixels tall. Rows are therefore broken up by length as
 * well as at newlines, and [EditBuffer.toText] puts a newline back only where the file had one.
 */
const val MAX_EDIT_ROW_CHARS = 4096

/** Newlines looked at when deciding what terminator Enter should write. */
private const val NEWLINE_SAMPLE = 64

/**
 * Rows of one loaded stretch of a file being edited. Compose lays one text field out whole, so
 * the editor holds the stretch as rows and only the focused row is a field; [toText] is what
 * Save writes back over the stretch's bytes. An [EditDocument] keeps one of these per stretch.
 *
 * A row is a *piece* of a line, not a line: it ends either at the terminator the file actually
 * uses there — which is kept, so a CRLF file is neither shown with stray carriage returns nor
 * rewritten as LF — or, for a line too long to measure, at [MAX_EDIT_ROW_CHARS] with no
 * terminator at all. [splitEditRows] / [joinEditRows] is lossless either way, including for a
 * trailing newline, which is an empty last row rather than a phantom line.
 *
 * [byteCount] is the size of [toText] in [charset] (the file encoding), what the document's
 * size cap is measured in.
 */
class EditBuffer(
    text: String,
    private val charset: Charset = Charsets.UTF_8,
    maxRowChars: Int = MAX_EDIT_ROW_CHARS,
) {
    private val maxRowChars = maxRowChars.coerceAtLeast(2)

    /** What a newly typed Enter writes, so typing in a CRLF file does not mix terminators. */
    private val newline = dominantNewline(text)
    private val rows = splitEditRows(text, this.maxRowChars)

    var byteCount: Int = encodedByteCount(text, charset)
        private set

    /** True after a mutation that changed the text. Read on leave, not every frame. */
    var isDirty: Boolean = false
        private set

    /**
     * Told of every change to the rows, in terms that can be reversed. An [EditDocument] keeps
     * these as its undo history; a buffer on its own keeps none.
     */
    internal var onSplice: ((EditSplice) -> Unit)? = null

    val size: Int get() = rows.size

    operator fun get(index: Int): String = rows[index].text

    fun toText(): String = joinEditRows(rows)

    /**
     * Line count matching [countEditLines], so the header does not jump between the viewer
     * (which ignores a trailing newline) and the editor (which keeps it as an empty last row).
     * A line broken up across several rows still counts once.
     */
    fun lineCount(): Int = countEditLines(rows)

    /** Terminators in the text: the lines this buffer adds to a document it is only part of. */
    fun newlineCount(): Int {
        var n = 0
        for (row in rows) if (row.breakText.isNotEmpty()) n++
        return n
    }

    /**
     * True when the text ends in a terminator, so the last row is the empty one after it. That
     * row is a place to type only when nothing follows the buffer; inside a document it is the
     * same position as the start of whatever comes next, and is not shown twice.
     */
    val endsWithNewline: Boolean
        get() = rows.size >= 2 && rows.last().let { it.text.isEmpty() && it.breakText.isEmpty() } &&
            rows[rows.size - 2].breakText.isNotEmpty()

    /**
     * Takes over the rows of [right], which holds the text that follows this buffer's. An empty
     * row after a final terminator here is the same position as [right]'s first row and goes;
     * a last row with no terminator is a piece of a line that continues in [right], and stays a
     * piece beside it. Returns how far [right]'s row indices moved, so records kept about its
     * rows can follow them. Not a change to the text, so [onSplice] is not told.
     */
    internal fun absorb(right: EditBuffer): Int {
        val last = rows.lastOrNull()
        val dropPhantom = last != null && last.text.isEmpty() && last.breakText.isEmpty() &&
            (rows.size == 1 || rows[rows.size - 2].breakText.isNotEmpty())
        if (dropPhantom) rows.removeAt(rows.lastIndex)
        val offset = rows.size
        rows.addAll(right.rows)
        byteCount += right.byteCount
        if (right.isDirty) isDirty = true
        return offset
    }

    /**
     * Puts rows back the way an earlier [EditSplice] found them ([reverse] true) or the way it
     * left them (false). The document's undo and redo; the change is not reported again.
     */
    internal fun apply(splice: EditSplice, reverse: Boolean): EditCaret {
        val remove = if (reverse) splice.after else splice.before
        val insert = if (reverse) splice.before else splice.after
        rows.subList(splice.startRow, splice.startRow + remove.size).clear()
        rows.addAll(splice.startRow, insert)
        byteCount += if (reverse) -splice.byteDelta else splice.byteDelta
        isDirty = true
        return if (reverse) splice.caretBefore else splice.caretAfter
    }

    /**
     * Row covering [utf8Offset] in the UTF-8 of [toText]. The viewer hands the byte offset of
     * the row on screen; a stretch is loaded around that row, so this is the row to open on
     * rather than the start of the stretch.
     */
    fun lineAtUtf8Offset(utf8Offset: Int): Int = caretAtUtf8Offset(utf8Offset).line

    /**
     * Row and column covering [utf8Offset] in the UTF-8 of [toText]. Column is a character
     * index, so a viewer row that continues a wrapped line opens inside that line instead of
     * at column 0. An offset in the middle of a multibyte character lands on that character.
     */
    fun caretAtUtf8Offset(utf8Offset: Int): EditCaret {
        if (rows.isEmpty()) return EditCaret(0, 0)
        var remaining = utf8Offset.coerceAtLeast(0)
        for (i in rows.indices) {
            val rowBytes = utf8ByteCount(rows[i].text)
            // Terminators are ASCII, so their byte and character lengths are the same.
            val span = rowBytes + rows[i].breakText.length
            if (remaining < span || i == rows.lastIndex) {
                return EditCaret(i, columnAtUtf8Offset(rows[i].text, remaining.coerceAtMost(rowBytes)))
            }
            remaining -= span
        }
        val last = rows.lastIndex
        return EditCaret(last, rows[last].text.length)
    }

    /**
     * Row and column covering [charOffset] in [toText]. Used when the file's bytes are not
     * UTF-8, so a viewer row offset cannot be treated as a UTF-8 index into the decoded slice.
     */
    fun caretAtCharOffset(charOffset: Int): EditCaret {
        if (rows.isEmpty()) return EditCaret(0, 0)
        var remaining = charOffset.coerceAtLeast(0)
        for (i in rows.indices) {
            val rowChars = rows[i].text.length
            val span = rowChars + rows[i].breakText.length
            if (remaining < span || i == rows.lastIndex) {
                return EditCaret(i, remaining.coerceAtMost(rowChars))
            }
            remaining -= span
        }
        val last = rows.lastIndex
        return EditCaret(last, rows[last].text.length)
    }

    /**
     * Replaces [index] with [value]. Newlines become extra rows (Enter, or a paste), and a
     * [value] past [MAX_EDIT_ROW_CHARS] is broken up the same way the text was on load.
     * [selectionStart] is the caret offset in [value], mapped onto the resulting rows.
     * Returns null if the text would exceed [maxBytes]; the buffer is unchanged.
     */
    fun replace(index: Int, value: String, selectionStart: Int, maxBytes: Int): EditCaret? {
        if (index !in rows.indices) return null
        if (value == rows[index].text) {
            return EditCaret(index, selectionStart.coerceIn(0, value.length))
        }
        val caret = spliceRows(index, index, value, selectionStart, maxBytes) ?: return null
        isDirty = true
        return caret
    }

    /**
     * Backspace at column 0: deletes the one document character in front of this row. Between
     * rows the file separates with a newline that is the newline, and the rows join; between
     * the pieces of a line too long to be one row there is no character there at all, so what
     * goes is the last character of the piece before it. Bytes only fall, so there is no cap
     * to check.
     */
    fun mergeWithPrevious(index: Int): EditCaret? {
        if (index <= 0 || index >= rows.size) return null
        val prev = rows[index - 1]
        if (prev.breakText.isNotEmpty()) {
            return mutate(
                spliceRows(
                    index - 1,
                    index,
                    prev.text + rows[index].text,
                    prev.text.length,
                    Int.MAX_VALUE,
                ),
            )
        }
        val text = prev.text
        if (text.isEmpty()) return null
        val cut = text.offsetByCodePoints(text.length, -1)
        return mutate(spliceRows(index - 1, index - 1, text.substring(0, cut), cut, Int.MAX_VALUE))
    }

    /**
     * Forward-delete at the end of this row: deletes the one document character behind it. The
     * mirror of [mergeWithPrevious] — the newline goes and the rows join, or, where the row is
     * only a piece of a longer line, the first character of the next piece goes. The caret
     * stays at the join either way.
     */
    fun mergeWithNext(index: Int): EditCaret? {
        if (index < 0 || index >= rows.lastIndex) return null
        val row = rows[index]
        if (row.breakText.isNotEmpty()) {
            return mutate(
                spliceRows(
                    index,
                    index + 1,
                    row.text + rows[index + 1].text,
                    row.text.length,
                    Int.MAX_VALUE,
                ),
            )
        }
        val next = rows[index + 1].text
        if (next.isEmpty()) return null
        val cut = next.offsetByCodePoints(0, 1)
        spliceRows(index + 1, index + 1, next.substring(cut), 0, Int.MAX_VALUE) ?: return null
        isDirty = true
        return EditCaret(index, row.text.length)
    }

    /**
     * Text from [from] to [to], in document order, including the terminators between rows —
     * and nothing between the pieces of a line that was only broken up to be measurable.
     * Used for copy of a selection that may span rows.
     */
    fun textInRange(from: EditCaret, to: EditCaret): String {
        if (rows.isEmpty()) return ""
        val (start, end) = orderedCarets(from, to)
        val startRow = start.line.coerceIn(0, rows.lastIndex)
        val endRow = end.line.coerceIn(0, rows.lastIndex)
        val startCol = start.column.coerceIn(0, rows[startRow].text.length)
        val endCol = end.column.coerceIn(0, rows[endRow].text.length)
        if (startRow == endRow) {
            if (startCol >= endCol) return ""
            return rows[startRow].text.substring(startCol, endCol)
        }
        val sb = StringBuilder()
        sb.append(rows[startRow].text, startCol, rows[startRow].text.length)
        for (i in startRow until endRow) {
            sb.append(rows[i].breakText)
            val next = rows[i + 1].text
            if (i + 1 == endRow) sb.append(next, 0, endCol) else sb.append(next)
        }
        return sb.toString()
    }

    /**
     * Replaces the range [from]–[to] with [insert], which may contain newlines.
     * Returns null if the text would exceed [maxBytes]; the buffer is unchanged.
     * The caret is left at the end of [insert].
     */
    fun replaceRange(from: EditCaret, to: EditCaret, insert: String, maxBytes: Int): EditCaret? {
        if (rows.isEmpty()) return null
        val (start, end) = orderedCarets(from, to)
        val startRow = start.line.coerceIn(0, rows.lastIndex)
        val endRow = end.line.coerceIn(0, rows.lastIndex)
        val startCol = start.column.coerceIn(0, rows[startRow].text.length)
        val endCol = end.column.coerceIn(0, rows[endRow].text.length)
        val deleted = textInRange(EditCaret(startRow, startCol), EditCaret(endRow, endCol))
        val prefix = rows[startRow].text.substring(0, startCol)
        val suffix = rows[endRow].text.substring(endCol)
        val caret = spliceRows(
            startRow,
            endRow,
            prefix + insert + suffix,
            prefix.length + insert.length,
            maxBytes,
        ) ?: return null
        if (deleted != insert) isDirty = true
        return caret
    }

    /**
     * Replaces rows [startRow]–[endRow] with [value], which is document text and may contain
     * newlines. The terminator of [endRow] is inherited by the last row [value] becomes, so a
     * splice never invents or drops a line ending of its own, and [caretOffset] is a character
     * index into [value]. Returns null if the text would exceed [maxBytes].
     */
    private fun spliceRows(
        startRow: Int,
        endRow: Int,
        value: String,
        caretOffset: Int,
        maxBytes: Int,
    ): EditCaret? {
        val split = splitValue(value, rows[endRow].breakText, newline, maxRowChars, caretOffset)
        val before = rows.subList(startRow, endRow + 1)
        val delta = spanBytes(split.rows) - spanBytes(before)
        // Character-boundary snap can load a few bytes over the cap; don't block non-growing edits.
        if (delta > 0 && byteCount + delta > maxBytes) return null
        val caret = EditCaret(startRow + split.caretRow, split.caretColumn)
        // An empty row with no terminator holds no document position of its own: it is column 0
        // of the row after it. Dropping it keeps an edit that empties one piece of a long line
        // from leaving a blank row in the middle of that line. The caret needs no adjustment —
        // it already reads as column 0 of whatever ends up at that index.
        val tail = split.rows.lastOrNull()
        if (endRow < rows.lastIndex && tail != null && tail.text.isEmpty() && tail.breakText.isEmpty()) {
            split.rows.removeAt(split.rows.lastIndex)
        }
        val listener = onSplice
        val record = if (listener != null && !sameEditRows(before, split.rows)) {
            EditSplice(
                startRow = startRow,
                before = ArrayList(before),
                after = ArrayList(split.rows),
                byteDelta = delta,
                caretBefore = EditCaret(startRow, changeColumn(before, split.rows)),
                caretAfter = caret,
            )
        } else {
            null
        }
        before.clear()
        rows.addAll(startRow, split.rows)
        byteCount += delta
        if (record != null) listener?.invoke(record)
        return caret
    }

    /** Where in the first row the change begins — where the caret belongs once it is undone. */
    private fun changeColumn(before: List<EditRow>, after: List<EditRow>): Int {
        val a = before.firstOrNull()?.text ?: return 0
        val b = after.firstOrNull()?.text ?: return 0
        var i = 0
        val n = minOf(a.length, b.length)
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /** Document bytes of [span], excluding the terminator of its last row. */
    private fun spanBytes(span: List<EditRow>): Int {
        var bytes = 0
        for (i in span.indices) {
            bytes += encodedByteCount(span[i].text, charset)
            if (i < span.lastIndex) bytes += encodedByteCount(span[i].breakText, charset)
        }
        return bytes
    }

    private fun mutate(caret: EditCaret?): EditCaret? {
        if (caret != null) isDirty = true
        return caret
    }
}

data class EditCaret(val line: Int, val column: Int)

/** One row of an [EditBuffer]: its text, and the terminator that follows it in the file. */
internal class EditRow(val text: String, val breakText: String)

/**
 * One change to a buffer's rows: [before] stood at [startRow] and [after] stands there now.
 * Rows are immutable, so both sides can be kept as they are and swapped back in later.
 * [startRow] is mutable because rows can move when a buffer is absorbed into another.
 */
internal class EditSplice(
    var startRow: Int,
    val before: List<EditRow>,
    val after: List<EditRow>,
    val byteDelta: Int,
    var caretBefore: EditCaret,
    var caretAfter: EditCaret,
) {
    /** True for a single row rewritten as a single row — what typing does. */
    val isRowRewrite: Boolean get() = before.size == 1 && after.size == 1

    fun shift(rowsDown: Int) {
        if (rowsDown == 0) return
        startRow += rowsDown
        caretBefore = EditCaret(caretBefore.line + rowsDown, caretBefore.column)
        caretAfter = EditCaret(caretAfter.line + rowsDown, caretAfter.column)
    }
}

internal fun sameEditRows(a: List<EditRow>, b: List<EditRow>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i].text != b[i].text || a[i].breakText != b[i].breakText) return false
    }
    return true
}

internal fun splitEditRows(text: String, maxRowChars: Int = MAX_EDIT_ROW_CHARS): MutableList<EditRow> =
    splitValue(text, tailBreak = "", newline = "\n", maxRowChars = maxRowChars, caretOffset = 0).rows

internal fun joinEditRows(rows: List<EditRow>): String {
    val sb = StringBuilder()
    for (row in rows) {
        sb.append(row.text)
        sb.append(row.breakText)
    }
    return sb.toString()
}

internal fun countEditLines(rows: List<EditRow>): Int {
    if (rows.isEmpty()) return 0
    var lines = 0
    for (row in rows) if (row.breakText.isNotEmpty()) lines++
    return if (rows.last().text.isEmpty()) lines else lines + 1
}

private class ValueRows(
    val rows: MutableList<EditRow>,
    val caretRow: Int,
    val caretColumn: Int,
)

/**
 * Rows for [value]: split at newlines, then broken up so no row is longer than [maxRowChars].
 * The last row inherits [tailBreak], the terminator of whatever it is replacing. A newline
 * [value] already spells as CRLF stays CRLF; a bare one becomes [newline], which is how a
 * stretch loaded from a CRLF file keeps its terminators consistent when Enter is pressed.
 *
 * [caretOffset] is a character index into [value] and comes back as a row and a column. It is
 * counted against the text as [value] spells it, not as the rows store it, so converting a bare
 * newline to CRLF cannot shift the caret.
 */
private fun splitValue(
    value: String,
    tailBreak: String,
    newline: String,
    maxRowChars: Int,
    caretOffset: Int,
): ValueRows {
    val out = ArrayList<EditRow>()
    var remaining = caretOffset.coerceAtLeast(0)
    var caretRow = -1
    var caretColumn = 0
    var segmentStart = 0
    while (true) {
        val newlineAt = value.indexOf('\n', segmentStart)
        val lastSegment = newlineAt < 0
        val crlf = !lastSegment && newlineAt > segmentStart && value[newlineAt - 1] == '\r'
        val segmentEnd = when {
            lastSegment -> value.length
            crlf -> newlineAt - 1
            else -> newlineAt
        }
        val breakText = when {
            lastSegment -> tailBreak
            crlf -> "\r\n"
            else -> newline
        }
        // How many characters of [value] the terminator took, which is not how many the row
        // stores it in, and is zero for the last row because its terminator came from the file.
        val breakInValue = if (lastSegment) 0 else if (crlf) 2 else 1
        var at = segmentStart
        while (true) {
            val cut = chunkEnd(value, at, segmentEnd, maxRowChars)
            val lastChunk = cut == segmentEnd
            val text = value.substring(at, cut)
            out.add(EditRow(text, if (lastChunk) breakText else ""))
            if (caretRow < 0) {
                val span = text.length + if (lastChunk) breakInValue else 0
                if (remaining < span) {
                    caretRow = out.lastIndex
                    caretColumn = remaining.coerceAtMost(text.length)
                } else {
                    remaining -= span
                }
            }
            at = cut
            if (lastChunk) break
        }
        if (lastSegment) break
        segmentStart = newlineAt + 1
    }
    if (caretRow < 0) {
        caretRow = out.lastIndex
        caretColumn = out.last().text.length
    }
    return ValueRows(out, caretRow, caretColumn)
}

/**
 * End of the chunk starting at [at]. Never inside a surrogate pair: half a pair cannot be
 * encoded back out, so a break there would not survive Save.
 */
private fun chunkEnd(value: String, at: Int, segmentEnd: Int, maxRowChars: Int): Int {
    if (segmentEnd - at <= maxRowChars) return segmentEnd
    val cut = at + maxRowChars
    val splitsPair = Character.isHighSurrogate(value[cut - 1]) && Character.isLowSurrogate(value[cut])
    return if (splitsPair && cut - 1 > at) cut - 1 else cut
}

/** The terminator the text mostly uses, which is the one Enter should go on writing. */
private fun dominantNewline(text: String): String {
    var crlf = 0
    var lf = 0
    var at = text.indexOf('\n')
    while (at >= 0 && crlf + lf < NEWLINE_SAMPLE) {
        if (at > 0 && text[at - 1] == '\r') crlf++ else lf++
        at = text.indexOf('\n', at + 1)
    }
    return if (crlf > lf) "\r\n" else "\n"
}

internal fun encodedByteCount(s: String, charset: Charset): Int {
    if (charset == Charsets.UTF_8) return utf8ByteCount(s)
    return s.toByteArray(charset).size
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
