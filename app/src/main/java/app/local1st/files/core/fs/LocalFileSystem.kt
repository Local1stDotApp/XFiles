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
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
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
    /** Volume roots whose `.xfiles-trash` directory is the bin, not a normal folder. */
    private val volumeRoots: () -> List<String> = { emptyList() },
    /** Names a stored grant can see. [File.list] may omit them on a secondary volume. */
    private val grantedNames: (File) -> List<String>? = { dir -> legacySaf?.persistedChildNames(dir) },
    /**
     * Deletes a document the grant already names. Must not open the folder picker:
     * a just-deleted path can still look present, and a new grant can hit another file.
     */
    private val deleteUnseenGranted: (File) -> Unit = { file ->
        legacySaf?.deletePath(file, requestGrant = false)
    },
    /**
     * Name plus whether it is a directory, from a stored grant. An empty
     * [File.list] is not the whole directory on a secondary volume.
     */
    private val grantedChildren: (File) -> List<Pair<String, Boolean>>? = { dir ->
        legacySaf?.persistedChildren(dir)?.map { it.name to it.isDirectory }
    },
    /** Opens a document [File] cannot see. Null when the stored grant has no file there. */
    private val openGranted: (File) -> InputStream? = { file ->
        val saf = legacySaf
        val document = saf?.persistedDocument(file)?.takeIf { !it.isDirectory }
        if (saf == null || document == null) null else saf.openInput(document)
    },
    /** Byte length of a grant document [File] cannot stat. Null when unknown. */
    private val grantSize: (File) -> Long? = { file ->
        legacySaf?.persistedDocument(file)?.takeIf { !it.isDirectory }?.size?.takeIf { it >= 0 }
    },
) : XFileSystem {

    override val scheme: String = XId.SCHEME_FILE

    override fun list(dir: XEntry): List<XEntry> {
        val file = File(dir.path)
        // A trashed directory symlink must not be walked: its children are the live
        // target, and those paths still look like the bin so Delete would unlink them.
        if (isBinSymlink(file) || TrashPaths.crossesVolumeBin(file.absolutePath, volumeRoots())) {
            return emptyList()
        }
        val children = file.listFiles()
        if (children == null) {
            safListing(file)?.let { return it }
            return privilegedListing(file, dir)
        }
        val roots = volumeRoots()
        // Inside the bin the children are the files the user is restoring or moving.
        // Concealing them makes a slow move copy an empty folder and then delete the source.
        val insideBin = roots.isNotEmpty() &&
            TrashPaths.isInsideVolumeBin(file.absolutePath, roots)
        val fromFile = children
            .filter { child ->
                insideBin || roots.isEmpty() ||
                    !TrashPaths.isConcealedBinPath(child.absolutePath, roots)
            }
            .map { child ->
                val (attrs, symlink) = readChildAttrs(child)
                toEntry(child, attrs, countChildren = true, listedSymlink = symlink)
            }
        // File.list can be a non-null empty array while the grant still has the tree.
        // A move copies this listing and then deletes the source.
        val seen = fromFile.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
        return fromFile + grantOnlyEntries(file, seen)
    }

    /**
     * [File.listFiles] is null for a secondary-volume directory parked by moveDocument.
     * A stored grant can still list it. Null means there is no such grant.
     */
    private fun safListing(dirFile: File): List<XEntry>? {
        val docs = grantedChildren(dirFile) ?: return null
        return grantOnlyEntries(dirFile, emptySet(), docs)
    }

    private fun grantOnlyEntries(
        dirFile: File,
        seenLower: Set<String>,
        docs: List<Pair<String, Boolean>> = grantedChildren(dirFile).orEmpty(),
    ): List<XEntry> {
        val roots = volumeRoots()
        val insideBin = roots.isNotEmpty() &&
            TrashPaths.isInsideVolumeBin(dirFile.absolutePath, roots)
        return docs.mapNotNull { (name, isDir) ->
            if (name.lowercase(Locale.ROOT) in seenLower) return@mapNotNull null
            if (name.isEmpty() || name == "." || name == ".." ||
                '/' in name || '\\' in name
            ) {
                return@mapNotNull null
            }
            val child = File(dirFile, name)
            if (!insideBin && roots.isNotEmpty() &&
                TrashPaths.isConcealedBinPath(child.absolutePath, roots)
            ) {
                return@mapNotNull null
            }
            if (nodeSurvivedDelete(child)) {
                val (attrs, symlink) = readChildAttrs(child)
                toEntry(child, attrs, countChildren = isDir, listedSymlink = symlink)
            } else {
                XEntry(
                    id = XId.file(child.absolutePath),
                    name = name,
                    isDir = isDir,
                    size = if (isDir) -1L else grantSize(child) ?: -1L,
                    mime = if (isDir) null else runCatching { FileTypes.mimeOf(name) }.getOrNull(),
                    kind = entryKind(isDir, binSymlink = false, name),
                    localPath = null,
                )
            }
        }
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
        refuseBinSymlink(file, entry.name, open = true)
        if (file.isFile) return FileInputStream(file)
        // The listing can name a child only the grant can see. Copy reads it here.
        openGranted(file)?.let { return it }
        throw IOException("Cannot open ${entry.name}")
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        requireSafeEntryName(name)
        val parent = File(parentDir.path)
        refuseVolumeBinName(parent, name)
        refuseCrossBin(parent, name)
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
        refuseVolumeBinName(file.parentFile, name)
        refuseCrossBin(File(parentDir.path), name)
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
        refuseCrossBin(File(parentDir.path), name)
        // Another spelling is the same directory on FAT. Returning it pours this folder into the bin.
        refuseVolumeBinName(File(parentDir.path), name)
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

    override fun delete(entry: XEntry) = delete(entry, EditorSiblingScan())

    /** [delete] as one of several. [siblings] keeps each folder's listing for the next one. */
    fun delete(entry: XEntry, siblings: EditorSiblingScan) {
        val file = File(entry.path)
        // The link node itself can be unlinked. A path reached through it deletes the target.
        val linkNode = Files.isSymbolicLink(file.toPath())
        if (followsSymlinkOutOfTrashBucket(file) ||
            (!linkNode && TrashPaths.crossesVolumeBin(file.absolutePath, volumeRoots()))
        ) {
            throw IOException("Cannot delete ${entry.name}")
        }
        // A directory symlink must not be listed or handed to SAF. Both follow it.
        if (linkNode) {
            unlinkSymlinkOnly(file, entry.name)
            unlinkEditorSiblings(file, siblings)
            return
        }
        // A regular file File can see has no grant-only children, and once it is gone no
        // grant still holds it. Only other nodes pay for the grant lookups below, each a
        // StorageManager call on API 26–29.
        val seenFile = file.isFile
        // File.exists stays true for a moment after a provider delete on SD/USB.
        // Grant-only children must go first. An empty File listing still rmdirs,
        // and that success used to skip the provider delete of the real tree.
        if (!seenFile) deleteUnlistedGrantChildren(file)
        var accepted = false
        try {
            deleteRecursively(file)
            accepted = true
        } catch (e: IOException) {
            withSafWrite(file, e) { saf, volume, tree ->
                val document = saf.resolve(volume, tree, file)
                if (document != null) {
                    saf.delete(document)
                    accepted = true
                } else if (nodeSurvivedDelete(file)) throw e
            }
        }
        if (accepted && nodeSurvivedDelete(file)) {
            // exists() stays true after delete() returned true on SD/USB. Only a
            // document the stored grant already names may be removed; requesting a
            // grant here can delete a different file with the same spelling.
            runCatching { deleteUnseenGranted(file) }
        }
        if (!seenFile && !nodeSurvivedDelete(file)) {
            // delete() is false for a path File cannot see. A persisted grant may
            // still name the only copy; do not report success before that lookup.
            // A provider error after the node is already gone must not keep the ready sibling.
            try {
                legacySaf?.deletePath(file)
            } catch (e: IOException) {
                // File cannot see a grant-only document. That is not success while
                // the grant still names it; the pane would say the file is gone.
                if (nodeSurvivedDelete(file) || legacySaf?.persistedDocument(file) != null) {
                    throw IOException("Cannot delete ${entry.name}", e)
                }
            }
        }
        if (!accepted && nodeSurvivedDelete(file)) {
            // The payload is still here, so the ready sibling may be the only complete copy.
            throw IOException("Cannot delete ${entry.name}")
        }
        unlinkEditorSiblings(file, siblings)
    }

    private fun unlinkSymlinkOnly(file: File, name: String) {
        if (!file.delete() && Files.isSymbolicLink(file.toPath())) {
            throw IOException("Cannot delete $name")
        }
    }

    private fun deleteUnlistedGrantChildren(dir: File) {
        if (Files.isSymbolicLink(dir.toPath())) return
        val listed = dir.list()
        val listedLower = listed?.map { it.lowercase(Locale.ROOT) }?.toSet()
        val docs = grantedChildren(dir)
        val grantDir = docs?.associate { (name, isDir) -> name.lowercase(Locale.ROOT) to isDir }
        // Walk directories File already listed. Their grant-only children are not
        // in this listing, and an empty rmdir would leave them on the volume.
        if (listed != null) {
            for (name in listed) {
                if (name.isEmpty() || name == "." || name == ".." ||
                    '/' in name || '\\' in name
                ) {
                    continue
                }
                val child = File(dir, name)
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory || grantDir?.get(name.lowercase(Locale.ROOT)) == true) {
                    deleteUnlistedGrantChildren(child)
                }
            }
        }
        if (docs == null) return
        for ((name, isDir) in docs) {
            if (name.isEmpty() || name == "." || name == ".." ||
                '/' in name || '\\' in name
            ) {
                continue
            }
            if (listedLower != null && name.lowercase(Locale.ROOT) in listedLower) continue
            val child = File(dir, name)
            if (Files.isSymbolicLink(child.toPath())) {
                unlinkSymlinkOnly(child, name)
                continue
            }
            if (isDir) deleteUnlistedGrantChildren(child)
            deleteUnseenGranted(child)
        }
    }

    private fun unlinkEditorSiblings(file: File, scan: EditorSiblingScan) {
        val parent = file.parentFile ?: return
        val folder = scan.folders.getOrPut(parent.path) { scanEditorScratch(parent) }
        val deleted = HashSet<String>()
        val listed = folder.listed
        if (listed != null) {
            for (name in listed.remove(file.name).orEmpty()) {
                deleteOneSibling(File(parent, name))
                deleted += name
            }
        } else {
            // list() == null is unreadable, not empty. Drop every editor name the grant still has.
            val known = ArrayList<String>()
            known += ".${file.name}.xfiles-ready"
            known += ".${file.name}.xfiles-tmp"
            known += folder.granted[file.name].orEmpty()
            for (name in known) {
                deleteKnownEditorSibling(parent, name, folder.grantNames)
                deleted += name
            }
        }
        // An empty File listing can still omit the ready copy the grant names.
        // Leaving it merges those bytes onto the next file of the same name.
        for (name in folder.granted.remove(file.name).orEmpty()) {
            if (name !in deleted) deleteKnownEditorSibling(parent, name, folder.grantNames)
        }
    }

    private fun scanEditorScratch(parent: File): FolderScratch {
        val grantNames = grantedNames(parent).orEmpty()
        return FolderScratch(
            listed = parent.list()?.let { scratchByOwner(it.asList()) },
            granted = scratchByOwner(grantNames),
            grantNames = grantNames.toHashSet(),
        )
    }

    private fun deleteKnownEditorSibling(parent: File, name: String, grantNames: Set<String>) {
        val sibling = File(parent, name)
        if (nodeSurvivedDelete(sibling)) {
            deleteOneSibling(sibling)
            return
        }
        if (name in grantNames) deleteUnseenGranted(sibling)
    }

    private fun deleteOneSibling(sibling: File) {
        try {
            deleteRecursively(sibling)
        } catch (e: IOException) {
            val saf = legacySaf ?: throw e
            saf.deletePath(sibling)
        }
        // delete() already returned true. exists() can stay true on SD/USB after that.
        // Failing here aborts before the bin record is dropped and hides the ready copy.
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        requireSafeEntryName(newName)
        val file = File(entry.path)
        refuseCrossBin(file, entry.name)
        val parent = file.parentFile
            ?: throw IOException("Cannot rename ${entry.name}")
        refuseVolumeBinName(parent, newName)
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
        // RandomAccessFile and FileInputStream follow links. A trashed symlink must not
        // write the live target.
        refuseBinSymlink(target, entry.name, open = false)
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
    /** A case variant of `.xfiles-trash` on a volume root is the live bin, which listings then hide. */
    private fun refuseVolumeBinName(parent: File?, name: String) {
        if (parent == null || !name.equals(TrashPaths.DIR_NAME, ignoreCase = true)) return
        if (volumeRoots().any { TrashPaths.samePath(it, parent.absolutePath) }) {
            throw IOException("Cannot use $name")
        }
    }

    private fun refuseCrossBin(file: File, name: String) {
        if (TrashPaths.crossesVolumeBin(file.absolutePath, volumeRoots())) {
            throw IOException("Cannot write $name")
        }
    }

    private fun refuseBinSymlink(file: File, name: String, open: Boolean) {
        val roots = volumeRoots()
        val path = file.absolutePath
        if (!TrashPaths.isVolumeBinSymlink(path, roots) &&
            !TrashPaths.crossesVolumeBin(path, roots)
        ) {
            return
        }
        throw IOException(if (open) "Cannot open $name" else "Cannot save $name")
    }

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

    /** The link itself may be deleted. A path reached by following it may not. */
    private fun isBinSymlink(file: File): Boolean =
        TrashPaths.isVolumeBinSymlink(file.absolutePath, volumeRoots())

    /** Inside a mounted volume bin, or the same `files/<id>/` shape before mounts publish. */
    private fun pathInVolumeBin(path: String): Boolean {
        val roots = volumeRoots()
        return TrashPaths.isInsideVolumeBin(path, roots) ||
            TrashPaths.isUnmountedBinPayload(path, roots)
    }

    private fun followsSymlinkOutOfTrashBucket(file: File): Boolean {
        if (isBinSymlink(file)) return false
        val path = file.absolutePath
        if (!pathInVolumeBin(path)) return false
        val bucket = trashBucketPath(path) ?: return false
        var cursor = file.absoluteFile.parentFile
        var crossedLink = false
        while (cursor != null && !TrashPaths.samePath(cursor.path, bucket)) {
            if (Files.isSymbolicLink(cursor.toPath())) {
                crossedLink = true
                break
            }
            cursor = cursor.parentFile
        }
        if (!crossedLink) return false
        val canonicalBucket = runCatching { trashBucketPath(file.canonicalPath) }.getOrNull()
        return canonicalBucket == null || !TrashPaths.samePath(canonicalBucket, bucket)
    }

    private fun trashBucketPath(path: String): String? {
        val normalized = if (path.length > 1) path.trimEnd('/') else path
        val marker = "/${TrashPaths.DIR_NAME}/files/"
        val idx = normalized.indexOf(marker)
        if (idx < 0) return null
        val rest = normalized.substring(idx + marker.length)
        val slash = rest.indexOf('/')
        val id = if (slash < 0) rest else rest.substring(0, slash)
        if (!TrashPaths.isId(id)) return null
        return normalized.substring(0, idx) + marker + id
    }

    private fun deleteRecursively(file: File) = deleteTrashBytes(file)

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

    /**
     * One lstat for a child [list] is about to show, and a following stat only for a
     * symlink. The second value says whether the child is itself a symlink.
     */
    private fun readChildAttrs(file: File): Pair<BasicFileAttributes?, Boolean> {
        val own = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return null to false
        }
        return if (own.isSymbolicLink) readAttrs(file) to true else own to false
    }

    /**
     * [TrashPaths.crossesVolumeBin] resolves the whole path, one realpath per row. A child of
     * a folder [list] already checked can only cross by being a symlink itself, or by being
     * the bin's own name under a folder that is another spelling of a volume root.
     */
    private fun mayCrossVolumeBin(file: File, listedSymlink: Boolean?): Boolean =
        listedSymlink != false || file.name.equals(TrashPaths.DIR_NAME, ignoreCase = true)

    private fun toEntry(
        file: File,
        attrs: BasicFileAttributes?,
        countChildren: Boolean = false,
        /** Null outside a listing: the whole path is checked. */
        listedSymlink: Boolean? = null,
    ): XEntry {
        val abs = file.absolutePath
        val name = file.name
        val link = isBinSymlink(file)
        // A symlink outside the bin can still resolve inside it. File() and ZipFile
        // follow localPath, so this row must not look like a normal file or archive.
        val crosses = !link && mayCrossVolumeBin(file, listedSymlink) &&
            TrashPaths.crossesVolumeBin(abs, volumeRoots())
        val hidden = link || crosses
        val isDir = attrs?.isDirectory == true && !hidden
        val counts = if (isDir && countChildren) directoryChildCount(file) else -1 to 0
        return XEntry(
            id = XId.file(abs),
            name = name,
            isDir = isDir,
            size = if (isDir || attrs == null || hidden) -1L else attrs.size(),
            mtime = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
            mime = if (isDir) null else FileTypes.mimeOf(name),
            hidden = name.startsWith("."),
            kind = entryKind(isDir, hidden, name),
            childCountHint = counts.first,
            hiddenChildCountHint = counts.second,
            localPath = if (hidden) null else abs,
        )
    }

    private fun toEntry(file: File, document: SafDocument): XEntry {
        val name = file.name
        val link = isBinSymlink(file)
        val crosses = !link && TrashPaths.crossesVolumeBin(file.absolutePath, volumeRoots())
        val hidden = link || crosses
        val isDir = document.isDirectory && !hidden
        return XEntry(
            id = XId.file(file.absolutePath),
            name = name,
            isDir = isDir,
            size = if (isDir || hidden) -1L else document.size,
            mtime = document.lastModified,
            mime = if (isDir) null else document.mimeType,
            hidden = name.startsWith("."),
            kind = entryKind(isDir, hidden, name),
            localPath = if (hidden) null else file.absolutePath,
        )
    }

    /** A bin symlink is a file node even when its name looks like a zip. Search must not open it. */
    private fun entryKind(isDir: Boolean, binSymlink: Boolean, name: String): EntryKind = when {
        isDir -> EntryKind.DIR
        binSymlink -> EntryKind.FILE
        FileTypes.isBrowsableArchive(name) -> EntryKind.ARCHIVE
        else -> EntryKind.FILE
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
 * The editor's `.name.xfiles-ready` and `.name.xfiles-tmp*` siblings in the folders one
 * operation deletes from. Each folder is listed once, so deleting N files of a folder is
 * not N listings of it. A sibling written after that listing is not seen.
 */
class EditorSiblingScan {
    internal val folders = HashMap<String, FolderScratch>()
}

internal class FolderScratch(
    /** Scratch names File listed, by the payload they belong to. Null when File cannot list. */
    val listed: HashMap<String, MutableList<String>>?,
    /** Scratch names the stored grant lists, by payload. */
    val granted: HashMap<String, MutableList<String>>,
    /** Every name the stored grant lists. */
    val grantNames: Set<String>,
)

/** Scratch names by payload. A name that splits more than one way is under each payload. */
private fun scratchByOwner(names: List<String>): HashMap<String, MutableList<String>> {
    val out = HashMap<String, MutableList<String>>()
    for (name in names) {
        for (owner in editorScratchOwnerCandidates(name)) out.getOrPut(owner) { ArrayList(1) } += name
    }
    return out
}

/** True when [file] is still a node. [File.exists] follows links, so a dangling symlink looks gone. */
internal fun nodeSurvivedDelete(file: File): Boolean =
    file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())

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
