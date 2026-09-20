package app.local1st.files.core.fs

import android.os.Build
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.util.FileTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException

/**
 * Local disk filesystem for `file://` ids, backed by [java.io.File].
 * All methods are blocking and expected to run on Dispatchers.IO.
 */
class LocalFileSystem(
    private val legacySaf: LegacySafAccess? = null,
    private val privilegedFallback: XFileSystem? = null,
    private val inPlaceMinBytes: Long = IN_PLACE_MIN_BYTES,
    /** How an in-place write opens its file; tests hand in one that fails part way through. */
    private val openInPlace: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
) : XFileSystem {

    override val scheme: String = XId.SCHEME_FILE

    override fun list(dir: XEntry): List<XEntry> {
        val file = File(dir.path)
        val children = file.listFiles() ?: return privilegedListing(file, dir)
        return children.map { toEntry(it, readAttrs(it), countChildren = true) }
    }

    /**
     * Scoped storage hides Android/data and Android/obb from File I/O on API 30+, and
     * MANAGE_EXTERNAL_STORAGE does not cover them — the FUSE layer keys that access off the
     * ext_data_rw/ext_obb_rw supplementary GIDs, which only the shell uid holds. A privileged
     * transport runs there, so retry the listing through it.
     *
     * The children come back with root:// ids on purpose: every later operation on them
     * (open, copy, thumbnail) then routes to the same transport instead of failing again.
     */
    private fun privilegedListing(file: File, dir: XEntry): List<XEntry> {
        val directError = IOException(
            if (file.exists()) "Cannot read ${dir.name}"
            else "Folder not found: ${dir.name}",
        )
        val fallback = privilegedFallback ?: throw directError
        if (!PrivilegedAccess.usable()) throw directError
        return try {
            fallback.list(dir.copy(id = XId.root(file.absolutePath)))
        } catch (e: IOException) {
            // Keep the message the user's action actually produced, but carry the privileged
            // failure as the cause so a dead transport is still diagnosable.
            throw IOException(directError.message, e)
        }
    }

    override fun stat(id: String): XEntry? {
        val file = File(id.substringAfter("://"))
        val attrs = readAttrs(file) ?: return null
        return toEntry(file, attrs)
    }

    override fun openIn(entry: XEntry): InputStream {
        val file = File(entry.path)
        if (!file.isFile) throw IOException("Cannot open ${entry.name}")
        return FileInputStream(file)
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        requireSafeEntryName(name)
        val parent = File(parentDir.path)
        if (!parent.isDirectory && !parent.mkdirs()) {
            val directError = IOException("Cannot create folder ${parent.absolutePath}")
            return withSafWrite(parent, directError) { saf, volume, tree ->
                val parentDocument = saf.resolve(volume, tree, parent) ?: throw directError
                saf.openOutput(
                    tree,
                    parentDocument,
                    name,
                    FileTypes.mimeOf(name) ?: "application/octet-stream",
                )
            }
        }
        try {
            return FileOutputStream(File(parent, name))
        } catch (e: IOException) {
            val directError = IOException("Cannot write $name in ${parentDir.name}", e)
            return withSafWrite(File(parent, name), directError) { saf, volume, tree ->
                val parentDocument = saf.resolve(volume, tree, parent) ?: throw directError
                saf.openOutput(
                    tree,
                    parentDocument,
                    name,
                    FileTypes.mimeOf(name) ?: "application/octet-stream",
                )
            }
        }
    }

    override fun createFile(parentDir: XEntry, name: String): XEntry {
        requireSafeEntryName(name)
        val file = File(parentDir.path, name)
        try {
            createEmptyFileExclusive(file)
        } catch (e: FileAlreadyExistsException) {
            // Never turn a create action into an accidental truncate, including when the
            // existing item is a directory.
            throw e
        } catch (e: IOException) {
            val directError = IOException("Cannot create file $name in ${parentDir.name}", e)
            return withSafWrite(file, directError) { saf, volume, tree ->
                val parent = saf.resolve(volume, tree, File(parentDir.path)) ?: throw directError
                val document = saf.createFile(
                    tree,
                    parent,
                    name,
                    FileTypes.mimeOf(name) ?: "text/plain",
                )
                toEntry(file, document)
            }
        }
        return toEntry(file, readAttrs(file))
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        requireSafeEntryName(name)
        val dir = File(parentDir.path, name)
        // Idempotent: copy ops re-create destination subfolders that may already exist.
        if (dir.isDirectory) return toEntry(dir, readAttrs(dir))
        if (!dir.mkdirs()) {
            val directError = IOException("Cannot create folder $name in ${parentDir.name}")
            return withSafWrite(dir, directError) { saf, volume, tree ->
                val parent = saf.resolve(volume, tree, File(parentDir.path)) ?: throw directError
                val existing = saf.child(tree, parent, name)
                val document = when {
                    existing == null -> saf.createDirectory(tree, parent, name)
                    existing.isDirectory -> existing
                    else -> throw directError
                }
                toEntry(dir, document)
            }
        }
        return toEntry(dir, readAttrs(dir))
    }

    override fun delete(entry: XEntry) {
        val file = File(entry.path)
        try {
            deleteRecursively(file)
        } catch (e: IOException) {
            withSafWrite(file, e) { saf, volume, tree ->
                val document = saf.resolve(volume, tree, file)
                if (document != null) saf.delete(document)
                else if (file.exists()) throw e
            }
        }
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        requireSafeEntryName(newName)
        val file = File(entry.path)
        val parent = file.parentFile
            ?: throw IOException("Cannot rename ${entry.name}")
        val target = File(parent, newName)
        // A case-only rename ("photo.jpg" -> "Photo.jpg") points at the same file on
        // case-insensitive storage; allow it instead of tripping the exists() guard.
        val caseOnly = target.absolutePath.equals(file.absolutePath, ignoreCase = true)
        if (!caseOnly && target.exists()) {
            throw IOException("$newName already exists")
        }
        if (!file.renameTo(target)) {
            val directError = IOException("Cannot rename ${entry.name} to $newName")
            return withSafWrite(file, directError) { saf, volume, tree ->
                val parentDocument = saf.resolve(volume, tree, parent) ?: throw directError
                val document = saf.resolve(volume, tree, file) ?: throw directError
                val renamed = saf.rename(tree, parentDocument, document, newName)
                toEntry(target, renamed)
            }
        }
        return toEntry(target, readAttrs(target))
    }

    override fun canWrite(entry: XEntry): Boolean = File(entry.path).canWrite()

    /**
     * Saves an edited local file with the existing atomic File path when that works.
     * Only its failed API 26-29 secondary-volume case falls through to SAF.
     */
    fun replaceContents(entry: XEntry, bytes: ByteArray) {
        replaceRange(entry, 0L, File(entry.localPath ?: entry.path).length(), bytes)
    }

    /**
     * Replaces bytes `[from, to)` with [replacement], streaming the unchanged prefix and suffix
     * so a small edit in a large file does not have to sit in memory as a whole snapshot.
     *
     * @return true if [replacement] was written; false if a previous complete tmp was restored
     *   instead, so the caller must reload from disk rather than treat the in-memory buffer as saved.
     */
    fun replaceRange(entry: XEntry, from: Long, to: Long, replacement: ByteArray): Boolean =
        replaceRanges(entry, listOf(ByteRangeEdit(from, to, replacement)))

    /**
     * Replaces several byte ranges of a file in one pass. The ranges are of the file as it was
     * read and must not overlap; unchanged bytes between them are streamed, not held.
     *
     * With [expected], the file must still carry that stamp — the one it had when the edits were
     * read — or nothing is written and [FileChangedException] is thrown: another writer's bytes
     * would otherwise be spliced at offsets that no longer mean anything. The stamp also settles
     * what a leftover complete tmp means. A target that still matches is intact, so the leftover
     * is an earlier save that never landed and is superseded by this one; a target that does not
     * match, with a longer complete tmp beside it, is the truncated remains of a save that was
     * cut off, and the tmp is restored instead.
     *
     * Edits that keep every range the same length are written in place on a large file: the
     * alternative is rewriting the whole file to change a few bytes of it, at the cost of a
     * second copy's worth of free space. In-place writes are not atomic, so small files — where
     * the rewrite is cheap — keep the rename.
     *
     * @return true if the edits were written; false if a previous complete tmp was restored
     *   instead, so the caller must reload from disk rather than treat its buffers as saved.
     */
    fun replaceRanges(entry: XEntry, edits: List<ByteRangeEdit>, expected: FileStamp? = null): Boolean {
        val target = File(entry.localPath ?: entry.path)
        val parent = target.parentFile ?: throw IOException("Cannot save ${entry.name}")
        val sorted = edits.sortedBy { it.from }
        var last = 0L
        for (edit in sorted) {
            if (edit.from < 0L || edit.to < edit.from || edit.from < last) {
                throw IOException("Cannot save ${entry.name}")
            }
            last = edit.to
        }
        val intact = expected != null && expected.matches(target)
        val leftovers = listXfilesTmps(parent, entry.name)
        val recovered = if (intact) {
            null
        } else {
            leftovers
                .filter { isReadyTmpName(it.name, entry.name) && it.length() > target.length() }
                .maxByOrNull { it.length() }
        }
        if (recovered != null) {
            commitStaged(recovered, target, entry)
            leftovers.forEach { if (it != recovered) it.delete() }
            return false
        }
        if (expected != null && !intact) throw FileChangedException(entry.name)
        if (last > target.length()) {
            throw IOException("Cannot save ${entry.name}")
        }
        leftovers.forEach { if (intact || it.length() <= target.length()) it.delete() }
        if (sorted.isEmpty()) return true
        if (writeInPlaceIfSameLength(target, sorted)) return true
        val created = newXfilesTmp(parent, entry.name)
        try {
            writeSpliced(created, target, sorted)
        } catch (e: Throwable) {
            created.delete()
            rethrowIfCancelled(e)
            val spliceError = e as? IOException ?: IOException("Cannot save ${entry.name}", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) throw spliceError
            // Sibling staging failed, so [target] is still the original. SAF must splice
            // from that intact file, never from a half-written tmp.
            return writeRangeViaSaf(target, entry, sorted, staged = null, spliceError)
        }
        val tmp = markReady(created, parent, entry.name)
        commitStaged(tmp, target, entry)
        listXfilesTmps(parent, entry.name).forEach { it.delete() }
        return true
    }

    /**
     * Writes [edits] over the bytes they replace when every one is the same length as its range
     * and the file is big enough for a rewrite to hurt. Synced before returning. False when the
     * edits do not qualify, the file cannot be opened for writing, or a write fails part way,
     * so the caller splices.
     *
     * A write that fails part way has replaced some of the bytes and bumped the modification
     * time, and the file is then neither what the caller read nor the edit. The bytes it
     * replaces are read first and put back on failure, stamp included: the splice that follows
     * then starts from the original, and if it fails too the caller's retry is checked against
     * the stamp it holds rather than refused as another app's change.
     */
    private fun writeInPlaceIfSameLength(target: File, edits: List<ByteRangeEdit>): Boolean {
        if (target.length() < inPlaceMinBytes) return false
        if (edits.any { it.bytes.size.toLong() != it.to - it.from }) return false
        val file = try {
            openInPlace(target)
        } catch (e: IOException) {
            return false
        }
        val mtime = target.lastModified()
        val originals = ArrayList<ByteArray>(edits.size)
        var started = 0
        try {
            file.use {
                for (edit in edits) {
                    val original = ByteArray(edit.bytes.size)
                    it.seek(edit.from)
                    it.readFully(original)
                    originals.add(original)
                }
                for (edit in edits) {
                    started++
                    if (edit.bytes.isEmpty()) continue
                    it.seek(edit.from)
                    it.write(edit.bytes)
                }
                it.fd.sync()
            }
            return true
        } catch (e: IOException) {
            restoreInPlace(target, edits, originals, started, mtime)
            return false
        }
    }

    /**
     * Puts back the bytes of the first [started] of [edits] after an in-place write failed.
     * Best effort: a device that is failing writes may refuse these too, and the file is then
     * torn either way — though the splice that follows still rewrites those ranges whole. The
     * modification time is only put back once every byte is, so the stamp never vouches for
     * bytes other than the ones it was taken from.
     */
    private fun restoreInPlace(
        target: File,
        edits: List<ByteRangeEdit>,
        originals: List<ByteArray>,
        started: Int,
        mtime: Long,
    ) {
        if (started == 0) return
        try {
            openInPlace(target).use { file ->
                for (i in 0 until started) {
                    if (originals[i].isEmpty()) continue
                    file.seek(edits[i].from)
                    file.write(originals[i])
                }
                file.fd.sync()
            }
        } catch (e: IOException) {
            return
        }
        target.setLastModified(mtime)
    }

    /**
     * SAF `openOutput` truncates the real document first, so the payload has to already
     * exist somewhere else. A complete sibling tmp is that payload; otherwise the original
     * file is still intact and is spliced onto a SAF document of a different name first.
     *
     * @return false if a previous complete tmp was restored instead of [replacement].
     */
    private fun writeRangeViaSaf(
        target: File,
        entry: XEntry,
        edits: List<ByteRangeEdit>,
        staged: File?,
        directError: IOException,
    ): Boolean {
        var wroteReplacement = true
        withSafWrite(target, directError) { saf, volume, tree ->
            val parent = target.parentFile?.let { saf.resolve(volume, tree, it) }
                ?: throw directError
            val mime = entry.mime ?: FileTypes.mimeOf(entry.name) ?: "application/octet-stream"
            if (staged != null) {
                saf.openOutput(tree, parent, entry.name, mime)
                    .use { out -> staged.inputStream().use { it.copyTo(out) } }
                return@withSafWrite
            }
            val leftoverSaf = saf.children(tree, parent)
                .filter {
                    isReadyTmpName(it.name, entry.name) &&
                        !it.isDirectory &&
                        it.size > target.length()
                }
                .maxByOrNull { it.size }
            if (leftoverSaf != null) {
                try {
                    copySafDocument(saf, tree, parent, entry.name, mime, leftoverSaf)
                } catch (e: Throwable) {
                    rethrowIfCancelled(e)
                    try {
                        copySafDocument(saf, tree, parent, entry.name, mime, leftoverSaf)
                    } catch (retry: Throwable) {
                        rethrowIfCancelled(retry)
                        throw e
                    }
                }
                runCatching { saf.delete(leftoverSaf) }
                wroteReplacement = false
                return@withSafWrite
            }
            val tempName = ".${entry.name}.xfiles-tmp.${System.nanoTime()}"
            val stale = saf.child(tree, parent, tempName)
            if (stale?.isDirectory == true) throw directError
            try {
                saf.openOutput(tree, parent, tempName, mime).use { out ->
                    spliceTo(out, target, edits)
                }
            } catch (e: Throwable) {
                rethrowIfCancelled(e)
                saf.child(tree, parent, tempName)?.let { runCatching { saf.delete(it) } }
                throw e
            }
            val spliced = saf.child(tree, parent, tempName) ?: throw directError
            val readyName = ".${entry.name}.xfiles-ready"
            saf.child(tree, parent, readyName)?.let { runCatching { saf.delete(it) } }
            val payload = saf.rename(tree, parent, spliced, readyName)
            try {
                copySafDocument(saf, tree, parent, entry.name, mime, payload)
            } catch (e: Throwable) {
                rethrowIfCancelled(e)
                // openOutput already truncated the document; copy [payload] onto it once more.
                try {
                    copySafDocument(saf, tree, parent, entry.name, mime, payload)
                } catch (retry: Throwable) {
                    rethrowIfCancelled(retry)
                    throw e
                }
            }
            runCatching { saf.delete(payload) }
        }
        return wroteReplacement
    }

    private fun copySafDocument(
        saf: LegacySafAccess,
        tree: android.net.Uri,
        parent: SafDocument,
        name: String,
        mime: String,
        payload: SafDocument,
    ) {
        saf.openOutput(tree, parent, name, mime).use { out ->
            saf.openInput(payload).use { it.copyTo(out) }
        }
    }

    /**
     * Commits a complete sibling tmp onto [target]. After SAF `openOutput` truncates the real
     * document, [tmp] is the only complete copy and is left in place if the copy fails.
     * API 30+ has no SAF fallback; the tmp is still kept so a later save can recover it.
     */
    private fun commitStaged(tmp: File, target: File, entry: XEntry) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Throwable) {
            rethrowIfCancelled(e)
            val moveError = e as? IOException ?: IOException("Cannot save ${entry.name}", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) throw moveError
            try {
                writeRangeViaSaf(target, entry, emptyList(), staged = tmp, moveError)
            } catch (safError: Throwable) {
                rethrowIfCancelled(safError)
                try {
                    writeRangeViaSaf(target, entry, emptyList(), staged = tmp, moveError)
                } catch (retry: Throwable) {
                    rethrowIfCancelled(retry)
                    throw safError
                }
            }
            tmp.delete()
        }
    }

    private fun newXfilesTmp(parent: File, name: String): File =
        Files.createTempFile(parent.toPath(), ".${name}.xfiles-tmp.", "").toFile()

    private fun markReady(tmp: File, parent: File, name: String): File {
        val ready = File(parent, ".${name}.xfiles-ready")
        try {
            Files.move(
                tmp.toPath(),
                ready.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Throwable) {
            rethrowIfCancelled(e)
            Files.move(tmp.toPath(), ready.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return ready
    }

    private fun listXfilesTmps(parent: File, name: String): List<File> =
        parent.listFiles { file -> file.isFile && isXfilesTmpName(file.name, name) }
            ?.asList()
            .orEmpty()

    private fun isReadyTmpName(fileName: String, entryName: String): Boolean =
        fileName == ".${entryName}.xfiles-ready"

    private fun isXfilesTmpName(fileName: String, entryName: String): Boolean {
        if (isReadyTmpName(fileName, entryName)) return true
        val prefix = ".${entryName}.xfiles-tmp"
        return fileName == prefix || fileName.startsWith("$prefix.")
    }

    private fun rethrowIfCancelled(e: Throwable) {
        if (e is CancellationException || e is InterruptedException) throw e
    }

    private fun writeSpliced(tmp: File, source: File, edits: List<ByteRangeEdit>) {
        tmp.outputStream().use { spliceTo(it, source, edits) }
    }

    /** [source] with each of [edits] (sorted, non-overlapping) put in place of its range. */
    private fun spliceTo(out: OutputStream, source: File, edits: List<ByteRangeEdit>) {
        val only = edits.singleOrNull()
        if (only != null && only.from == 0L && only.to == source.length()) {
            out.write(only.bytes)
            return
        }
        FileInputStream(source).use { input ->
            var at = 0L
            for (edit in edits) {
                copyExactly(input, out, edit.from - at)
                out.write(edit.bytes)
                skipExactly(input, edit.to - edit.from)
                at = edit.to
            }
            input.copyTo(out)
        }
    }

    private fun copyExactly(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(1 shl 16)
        var left = count
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Unexpected end of file")
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Parallel ZipTurbo writes directly to File; secondary volumes use XFileSystem streams. */
    fun supportsDirectBulkWrites(entry: XEntry): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            legacySaf?.secondaryVolumeFor(File(entry.path)) == null

    private fun deleteRecursively(file: File) {
        // Never descend through symlinks; delete only the link itself.
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        if (!file.delete() && file.exists()) {
            throw IOException("Cannot delete ${file.absolutePath}")
        }
    }

    /**
     * All of an entry's metadata from ONE stat. The old per-field java.io.File calls
     * (isDirectory/length/lastModified/canRead/canWrite) were five separate syscalls,
     * each a FUSE round trip on /sdcard — big directories paid it thousands of times.
     * Access checks are deferred to the actual operation, which reports its own error.
     */
    private fun readAttrs(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    } catch (e: IOException) {
        null // vanished mid-listing or broken symlink
    }

    private fun toEntry(
        file: File,
        attrs: BasicFileAttributes?,
        countChildren: Boolean = false,
    ): XEntry {
        val abs = file.absolutePath
        val name = file.name
        val isDir = attrs?.isDirectory == true
        val counts = if (isDir && countChildren) directoryChildCount(file) else -1 to 0
        return XEntry(
            id = XId.file(abs),
            name = name,
            isDir = isDir,
            size = if (isDir || attrs == null) -1L else attrs.size(),
            mtime = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
            mime = if (isDir) null else FileTypes.mimeOf(name),
            hidden = name.startsWith("."),
            kind = when {
                isDir -> EntryKind.DIR
                FileTypes.isBrowsableArchive(name) -> EntryKind.ARCHIVE
                else -> EntryKind.FILE
            },
            childCountHint = counts.first,
            hiddenChildCountHint = counts.second,
            localPath = abs,
        )
    }

    private fun toEntry(file: File, document: SafDocument): XEntry {
        val name = file.name
        val isDir = document.isDirectory
        return XEntry(
            id = XId.file(file.absolutePath),
            name = name,
            isDir = isDir,
            size = if (isDir) -1L else document.size,
            mtime = document.lastModified,
            mime = if (isDir) null else document.mimeType,
            hidden = name.startsWith("."),
            kind = when {
                isDir -> EntryKind.DIR
                FileTypes.isBrowsableArchive(name) -> EntryKind.ARCHIVE
                else -> EntryKind.FILE
            },
            localPath = file.absolutePath,
        )
    }

    /**
     * Extra readdir during parent [list] so unexpanded folder rows can show a count.
     * Returns (total, hidden); hidden names start with `.`, matching [XEntry.hidden].
     */
    private fun directoryChildCount(file: File): Pair<Int, Int> {
        val names = file.list() ?: return -1 to 0
        return names.size to names.count { it.startsWith(".") }
    }

    /**
     * API 30+ never enters this branch. On API 26-29, SAF is considered only after the
     * existing direct File write actually failed and only when the target is on a secondary
     * volume; primary storage and Android/data retain their direct behavior.
     */
    private fun <T> withSafWrite(
        target: File,
        directError: IOException,
        write: (LegacySafAccess, SafVolume, android.net.Uri) -> T,
    ): T {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) throw directError
        val saf = legacySaf ?: throw directError
        val volume = saf.secondaryVolumeFor(target) ?: throw directError
        val tree = saf.validatedTreeOrRequest(volume) ?: throw directError
        return try {
            write(saf, volume, tree)
        } catch (e: FileAlreadyExistsException) {
            throw e
        } catch (e: IOException) {
            throw IOException(directError.message, e)
        } catch (e: RuntimeException) {
            throw IOException(directError.message, e)
        }
    }
}

/**
 * Files at least this large take same-length edits in place. Below it a rewrite is a few
 * milliseconds, and the rename keeps a crash mid-save from leaving a half-written file.
 */
const val IN_PLACE_MIN_BYTES = 16L * 1024 * 1024

/** CREATE_NEW is the invariant behind the UI's promise that creating never overwrites. */
internal fun createEmptyFileExclusive(file: File) {
    Files.newOutputStream(
        file.toPath(),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    ).use { }
}

/**
 * Advances [input] by exactly [count] bytes or throws. [FileInputStream.skip] is an lseek
 * that can move past EOF and still report success, so a later copy would silently drop the
 * suffix; this checks the channel size for files and otherwise reads and discards.
 */
internal fun skipExactly(input: InputStream, count: Long) {
    if (count <= 0L) return
    if (input is FileInputStream) {
        val channel = input.channel
        val remaining = channel.size() - channel.position()
        if (remaining < count) throw IOException("Unexpected end of file")
        channel.position(channel.position() + count)
        return
    }
    val buf = ByteArray(1 shl 16)
    var left = count
    while (left > 0) {
        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (n < 0) throw IOException("Unexpected end of file")
        left -= n
    }
}
