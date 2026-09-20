package app.local1st.files.core.text

import app.local1st.files.core.fs.ByteRangeEdit
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A whole file as an editor sees it, without holding the file.
 *
 * The rows are the ones the viewer's [TextRowIndex] found; they are what the list shows and what
 * a caret is addressed by. Where the user edits, a stretch of consecutive rows is *loaded*: its
 * bytes are read, decoded and held as an [EditBuffer], and from then on that stretch's rows come
 * from the buffer rather than the file, however many rows the edits turn them into. Everything
 * outside the loaded stretches is still the file, read on demand by row like the viewer does, so
 * a ten-gigabyte log costs a few hundred kilobytes to open for editing and a few megabytes
 * however far the user roams — a piece table whose pieces are the file's own rows.
 *
 * Save is the list of loaded stretches whose text changed, each with the byte range it replaces
 * ([edits]); the file is spliced once for all of them. Undo and redo run across every stretch in
 * the order the edits were made.
 *
 * Loading is two steps because reading is slow and the rows are read on the main thread while
 * they are on screen: [prepareLoad] does the IO on any thread and [install] applies its result
 * where the rows are read. The rest is safe to call from one thread at a time.
 */
class EditDocument(
    private val source: ByteWindow,
    private val index: TextRowIndex,
    val charset: Charset = index.charset,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val maxLoadedBytes: Int = DEFAULT_MAX_LOADED_BYTES,
    private val maxHistory: Int = DEFAULT_MAX_HISTORY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val regions = ArrayList<Region>()
    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()

    /** Rows in the document now: the file's, less the loaded ones, plus what the buffers hold. */
    val size: Int
        get() = synchronized(lock) {
            var n = index.rowCount
            for (r in regions) n += visibleRows(r) - r.origRowCount
            n
        }

    /** True while any loaded stretch reads differently from the file. */
    val isDirty: Boolean get() = synchronized(lock) { regions.any { it.depth != 0 } }

    val canUndo: Boolean get() = synchronized(lock) { undo.isNotEmpty() }
    val canRedo: Boolean get() = synchronized(lock) { redo.isNotEmpty() }

    /** Bytes of file text held in buffers; edits and loads are refused past [maxLoadedBytes]. */
    val loadedBytes: Int get() = synchronized(lock) { loadedBytesLocked() }

    /** Text of row [i] when it is loaded; null for a row still on disk (or out of range). */
    fun rowText(i: Int): String? = synchronized(lock) {
        val at = locate(i) ?: return null
        at.region?.buffer?.get(at.local)
    }

    fun isLoaded(i: Int): Boolean = synchronized(lock) { locate(i)?.region != null }

    /**
     * The file's row that document row [i] is, for a row still on disk — what to hand the
     * viewer's page cache. -1 for a loaded row.
     */
    fun fileRow(i: Int): Int = synchronized(lock) {
        val at = locate(i) ?: return -1
        if (at.region == null) at.origRow else -1
    }

    /**
     * The file's row nearest document row [i], loaded or not — where the viewer should stand
     * when the editor leaves it there.
     */
    fun nearestFileRow(i: Int): Int = synchronized(lock) {
        val at = locate(i.coerceIn(0, (size - 1).coerceAtLeast(0))) ?: return 0
        val r = at.region ?: return at.origRow
        r.origRowStart + at.local.coerceAtMost((r.origRowCount - 1).coerceAtLeast(0))
    }

    /** Document rows of the loaded stretch holding row [i], or null if [i] is not loaded. */
    fun loadedRange(i: Int): IntRange? = synchronized(lock) {
        val r = locate(i)?.region ?: return null
        val start = docStart(r)
        start until start + visibleRows(r)
    }

    /**
     * Lines in the document: the file's count, corrected by what each loaded stretch has done
     * to its own. A trailing newline is not a line, as in the viewer.
     */
    fun lineCount(): Int = synchronized(lock) {
        var n = index.lineCount
        for (r in regions) n += lines(r) - r.origLines
        n
    }

    /** [EditBuffer.replace] on row [i]; null when [i] is not loaded or the size cap is hit. */
    fun replace(i: Int, value: String, selectionStart: Int): EditCaret? = synchronized(lock) {
        val at = locate(i) ?: return null
        val r = at.region ?: return null
        r.buffer.replace(at.local, value, selectionStart, capFor(r))?.let { global(r, it) }
    }

    /**
     * [EditBuffer.mergeWithPrevious] on row [i]. Null at the first row of a loaded stretch: the
     * character before it is on disk, so the caller loads the row above and tries again.
     */
    fun mergeWithPrevious(i: Int): EditCaret? = synchronized(lock) {
        val at = locate(i) ?: return null
        val r = at.region ?: return null
        r.buffer.mergeWithPrevious(at.local)?.let { global(r, it) }
    }

    /** [EditBuffer.mergeWithNext] on row [i]; null where what follows the row is still on disk. */
    fun mergeWithNext(i: Int): EditCaret? = synchronized(lock) {
        val at = locate(i) ?: return null
        val r = at.region ?: return null
        r.buffer.mergeWithNext(at.local)?.let { global(r, it) }
    }

    /** Text between two carets in one loaded stretch; null when they are not in the same one. */
    fun textInRange(from: EditCaret, to: EditCaret): String? = synchronized(lock) {
        val (r, a, b) = sameRegion(from, to) ?: return null
        r.buffer.textInRange(a, b)
    }

    /** [EditBuffer.replaceRange] within one loaded stretch; null otherwise or past the cap. */
    fun replaceRange(from: EditCaret, to: EditCaret, insert: String): EditCaret? = synchronized(lock) {
        val (r, a, b) = sameRegion(from, to) ?: return null
        r.buffer.replaceRange(a, b, insert, capFor(r))?.let { global(r, it) }
    }

    /** Reverses the latest change; the caret to show, or null when there is none to reverse. */
    fun undo(): EditCaret? = synchronized(lock) {
        val entry = undo.removeLastOrNull() ?: return null
        val caret = entry.region.buffer.apply(entry.splice, reverse = true)
        entry.region.depth--
        redo.addLast(entry)
        global(entry.region, caret)
    }

    fun redo(): EditCaret? = synchronized(lock) {
        val entry = redo.removeLastOrNull() ?: return null
        val caret = entry.region.buffer.apply(entry.splice, reverse = false)
        entry.region.depth++
        undo.addLast(entry)
        global(entry.region, caret)
    }

    /** What Save writes: every loaded stretch that differs from the file, in file order. */
    @Throws(IOException::class)
    fun edits(): List<ByteRangeEdit> = synchronized(lock) {
        regions.filter { it.depth != 0 }
            .map { ByteRangeEdit(it.from, it.to, encodeText(it.buffer.toText(), charset)) }
    }

    /**
     * Reads the rows around file row [fileRow] — up to [chunkBytes] of them, centred on it and
     * stopping short of rows already loaded — ready for [install]. Blocking IO, on any thread.
     * Null when the row is already loaded, or is not indexed yet.
     *
     * A file with no rows at all gets an empty stretch at its end, so there is a row to type on.
     */
    @Throws(IOException::class)
    fun prepareLoad(fileRow: Int): PendingLoad? {
        if (index.rowCount == 0) {
            if (!index.isComplete || synchronized(lock) { regions.isNotEmpty() }) return null
            return PendingLoad(source.size, source.size, 0, 0, "", source.size)
        }
        val gap = synchronized(lock) { gapAround(fileRow) } ?: return null
        val startOff = index.rowStart(fileRow)
        if (startOff < 0L) return null
        // Row ends ahead of [fileRow], as far as the budget or the next loaded stretch allows.
        val ends = ArrayList<Long>()
        index.walkRows(fileRow) { row, _, end ->
            if (row >= gap.last + 1) return@walkRows false
            if (ends.isNotEmpty() && end - startOff > chunkBytes) return@walkRows false
            ends.add(end)
            true
        }
        if (ends.isEmpty()) return null
        val forwardWant = minOf(chunkBytes / 2L, ends.last() - startOff)
        val backBudget = chunkBytes - forwardWant
        // Earliest row in the gap within the backward budget: rowStart is monotonic in the row.
        var lo = gap.first
        var hi = fileRow
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val at = index.rowStart(mid)
            if (at >= 0L && startOff - at <= backBudget) hi = mid else lo = mid + 1
        }
        val first = lo
        val from = if (first == fileRow) startOff else index.rowStart(first)
        if (from < 0L) return null
        val forwardBudget = chunkBytes - (startOff - from)
        var take = 1
        while (take < ends.size && ends[take] - startOff <= forwardBudget) take++
        val to = ends[take - 1]
        val text = readText(from, to)
        return PendingLoad(from, to, first, fileRow - first + take, text, startOff)
    }

    /**
     * Puts the rows [prepareLoad] read in place of the file rows they came from, joining them
     * onto any loaded stretch they touch. Stretches never touched by an edit may be dropped to
     * stay under [maxLoadedBytes] — never the one holding [keepRow], the caller's caret. Null
     * when even that is not enough room, or the rows have been loaded meanwhile.
     */
    fun install(load: PendingLoad, keepRow: Int = -1): Loaded? = synchronized(lock) {
        if (load.rowCount == 0) {
            if (regions.isNotEmpty() || index.rowCount != 0) return null
        } else {
            val gap = gapAround(load.rowStart) ?: return null
            if (load.rowStart + load.rowCount - 1 > gap.last) return null
        }
        val kept = if (keepRow >= 0) locate(keepRow)?.takeIf { it.region != null } else null
        val need = encodedByteCount(load.text, charset)
        if (!makeRoom(need, load.rowStart, kept?.region)) return null

        val chunk = EditBuffer(load.text, charset)
        val target = caretAtEncodedOffset(chunk, load.text, (load.targetOffset - load.from).toInt())
        val atEof = load.to >= source.size
        var region = Region(
            buffer = chunk,
            from = load.from,
            to = load.to,
            origRowStart = load.rowStart,
            origRowCount = load.rowCount,
            origLines = if (atEof) countEditLinesOf(load.text) else countNewlines(load.text),
        )
        var targetRow = target.line
        var keptOffset = 0
        var insertAt = regions.indexOfFirst { it.origRowStart > load.rowStart }
        if (insertAt < 0) insertAt = regions.size
        regions.add(insertAt, region)
        // Joined onto its neighbours: two stretches that abut would show the same seam twice.
        val prev = regions.getOrNull(insertAt - 1)
        if (prev != null && prev.origRowStart + prev.origRowCount == region.origRowStart) {
            targetRow += merge(prev, region)
            regions.removeAt(insertAt)
            region = prev
            insertAt--
        }
        val next = regions.getOrNull(insertAt + 1)
        if (next != null && region.origRowStart + region.origRowCount == next.origRowStart) {
            val offset = merge(region, next)
            if (kept?.region === next) keptOffset = offset
            regions.removeAt(insertAt + 1)
        }
        val owner = region
        owner.buffer.onSplice = { splice -> record(owner, splice) }
        val docStart = docStart(region)
        val keptRow = when {
            kept == null -> -1
            // The kept stretch was absorbed: its rows follow the ones that took it over.
            kept.region === next && !regions.contains(next) -> docStart + keptOffset + kept.local
            else -> docStart(kept.region!!) + kept.local
        }
        Loaded(
            row = docStart + targetRow.coerceAtMost(visibleRows(region) - 1),
            column = target.column,
            keptRow = keptRow,
        )
    }

    // -- internals ------------------------------------------------------------------------------

    private class Region(
        var buffer: EditBuffer,
        var from: Long,
        var to: Long,
        var origRowStart: Int,
        var origRowCount: Int,
        var origLines: Int,
        /** Edits applied minus edits undone: zero means the text is the file's again. */
        var depth: Int = 0,
        /** History entries that refer to this stretch. */
        var refs: Int = 0,
    )

    private class Entry(var region: Region, var splice: EditSplice, var at: Long)

    private class Location(val region: Region?, val local: Int, val origRow: Int)

    private fun atEof(r: Region): Boolean = r.to >= source.size

    /** Rows of [r] the document shows; the empty row after a final newline mid-file is not one. */
    private fun visibleRows(r: Region): Int =
        r.buffer.size - if (!atEof(r) && r.buffer.endsWithNewline) 1 else 0

    private fun lines(r: Region): Int = if (atEof(r)) r.buffer.lineCount() else r.buffer.newlineCount()

    private fun loadedBytesLocked(): Int {
        var n = 0
        for (r in regions) n += r.buffer.byteCount
        return n
    }

    private fun capFor(r: Region): Int {
        val others = loadedBytesLocked() - r.buffer.byteCount
        return (maxLoadedBytes - others).coerceAtLeast(0)
    }

    private fun locate(i: Int): Location? {
        if (i < 0) return null
        var docBase = 0
        var origBase = 0
        for (r in regions) {
            val gap = r.origRowStart - origBase
            if (i < docBase + gap) return Location(null, -1, origBase + (i - docBase))
            docBase += gap
            val vis = visibleRows(r)
            if (i < docBase + vis) return Location(r, i - docBase, -1)
            docBase += vis
            origBase = r.origRowStart + r.origRowCount
        }
        val orig = origBase + (i - docBase)
        return if (orig < index.rowCount) Location(null, -1, orig) else null
    }

    private fun docStart(region: Region): Int {
        var docBase = 0
        var origBase = 0
        for (r in regions) {
            docBase += r.origRowStart - origBase
            if (r === region) return docBase
            docBase += visibleRows(r)
            origBase = r.origRowStart + r.origRowCount
        }
        throw IllegalStateException("region not in document")
    }

    private fun global(r: Region, local: EditCaret): EditCaret =
        EditCaret(docStart(r) + local.line, local.column)

    private fun sameRegion(a: EditCaret, b: EditCaret): Triple<Region, EditCaret, EditCaret>? {
        val la = locate(a.line) ?: return null
        val lb = locate(b.line) ?: return null
        val r = la.region ?: return null
        if (lb.region !== r) return null
        return Triple(r, EditCaret(la.local, a.column), EditCaret(lb.local, b.column))
    }

    /** File rows still on disk around [fileRow]: from the stretch before to the one after. */
    private fun gapAround(fileRow: Int): IntRange? {
        if (fileRow < 0) return null
        var start = 0
        for (r in regions) {
            if (fileRow < r.origRowStart) return start..r.origRowStart - 1
            val end = r.origRowStart + r.origRowCount
            if (fileRow < end) return null
            start = end
        }
        val total = index.rowCount
        return if (fileRow < total || total == 0) start..(total - 1).coerceAtLeast(start) else null
    }

    /**
     * Drops untouched stretches, farthest from [nearRow] first, until [need] more bytes fit.
     * Never [keep]. True when they do.
     */
    private fun makeRoom(need: Int, nearRow: Int, keep: Region?): Boolean {
        if (loadedBytesLocked() + need <= maxLoadedBytes) return true
        val candidates = regions
            .filter { it !== keep && it.depth == 0 && it.refs == 0 }
            .sortedByDescending { distance(it, nearRow) }
        for (r in candidates) {
            regions.remove(r)
            if (loadedBytesLocked() + need <= maxLoadedBytes) return true
        }
        return loadedBytesLocked() + need <= maxLoadedBytes
    }

    private fun distance(r: Region, row: Int): Int = when {
        row < r.origRowStart -> r.origRowStart - row
        row >= r.origRowStart + r.origRowCount -> row - (r.origRowStart + r.origRowCount) + 1
        else -> 0
    }

    /** [right] becomes part of [left]; returns how far [right]'s rows moved. */
    private fun merge(left: Region, right: Region): Int {
        val offset = left.buffer.absorb(right.buffer)
        left.to = right.to
        left.origRowCount += right.origRowCount
        left.origLines += right.origLines
        left.depth += right.depth
        left.refs += right.refs
        for (e in undo) rebase(e, right, left, offset)
        for (e in redo) rebase(e, right, left, offset)
        right.buffer.onSplice = null
        return offset
    }

    private fun rebase(e: Entry, from: Region, to: Region, offset: Int) {
        if (e.region !== from) return
        e.region = to
        e.splice.shift(offset)
    }

    private fun record(region: Region, splice: EditSplice) {
        val now = clock()
        for (e in redo) e.region.refs--
        redo.clear()
        val top = undo.lastOrNull()
        if (top != null && top.region === region && top.splice.isRowRewrite && splice.isRowRewrite &&
            top.splice.startRow == splice.startRow && now - top.at < COALESCE_MS
        ) {
            // A burst that types and then deletes the same characters is one rewrite whose
            // after is the before it started from. Drop it: depth was incremented when the
            // first keystroke created this entry, and would otherwise keep the stretch dirty.
            if (sameEditRows(top.splice.before, splice.after)) {
                undo.removeLast()
                region.depth--
                region.refs--
                return
            }
            top.splice = EditSplice(
                startRow = splice.startRow,
                before = top.splice.before,
                after = splice.after,
                byteDelta = top.splice.byteDelta + splice.byteDelta,
                caretBefore = top.splice.caretBefore,
                caretAfter = splice.caretAfter,
            )
            top.at = now
            return
        }
        undo.addLast(Entry(region, splice, now))
        region.depth++
        region.refs++
        while (undo.size > maxHistory) undo.removeFirst().region.refs--
    }

    @Throws(IOException::class)
    private fun readText(from: Long, to: Long): String {
        val length = (to - from).toInt()
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = source.read(from + read, buf, read, length - read)
            if (n <= 0) throw IOException("Unexpected end of file")
            read += n
        }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(buf)).toString()
        } catch (e: CharacterCodingException) {
            throw IOException(null, e)
        }
    }

    private fun caretAtEncodedOffset(buffer: EditBuffer, text: String, relBytes: Int): EditCaret {
        if (charset == Charsets.UTF_8) return buffer.caretAtUtf8Offset(relBytes)
        val encoded = text.toByteArray(charset)
        val n = relBytes.coerceIn(0, encoded.size)
        if (n <= 0) return EditCaret(0, 0)
        return buffer.caretAtCharOffset(String(encoded, 0, n, charset).length)
    }

    /** Rows read from the file, waiting to be [install]ed. */
    class PendingLoad internal constructor(
        val from: Long,
        val to: Long,
        val rowStart: Int,
        val rowCount: Int,
        val text: String,
        val targetOffset: Long,
    )

    /** Where the requested row ended up, and where the caller's caret row did. */
    class Loaded internal constructor(val row: Int, val column: Int, var keptRow: Int)

    companion object {
        const val DEFAULT_CHUNK_BYTES = 512 * 1024
        const val DEFAULT_MAX_LOADED_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_HISTORY = 500

        /** Keystrokes on one row this close together undo as one. */
        private const val COALESCE_MS = 1500L
    }
}

private fun countNewlines(text: String): Int {
    var n = 0
    var at = text.indexOf('\n')
    while (at >= 0) {
        n++
        at = text.indexOf('\n', at + 1)
    }
    return n
}

/** Lines as [countEditLines] counts them: newlines, plus one for an unterminated last line. */
private fun countEditLinesOf(text: String): Int {
    if (text.isEmpty()) return 0
    val newlines = countNewlines(text)
    return if (text.endsWith('\n')) newlines else newlines + 1
}
