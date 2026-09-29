package app.local1st.files.core.fs

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** A mounted volume the bin can move files on. Paths are absolute. */
data class TrashVolume(
    val rootPath: String,
    val label: String,
    val writable: Boolean,
)

/**
 * One trashed file or folder. [storedPath] is the real file inside the volume's bin;
 * [originalRelativePath] is where Restore puts it back, relative to [volumeRoot].
 */
data class TrashRecord(
    val id: String,
    val volumeRoot: String,
    val volumeLabel: String,
    val storedPath: String,
    val name: String,
    val originalRelativePath: String,
    val deletedAt: Long,
    val orphan: Boolean,
    /** Set when the payload is a directory, including one [java.io.File] cannot stat. */
    val storedIsDir: Boolean = false,
) {
    /** Volume label plus the original folder, for the row's secondary text. */
    val badge: String
        get() {
            if (orphan) return "$volumeLabel · original location unknown"
            val folder = originalRelativePath.substringBeforeLast('/', "")
            return if (folder.isEmpty()) volumeLabel else "$volumeLabel · $folder"
        }
}

/** How a user Delete should treat a selection. */
enum class DeleteDisposition { TRASH, PERMANENT, MIXED }

/**
 * Whether a volume's hidden bin can be carried by a move.
 * [UNKNOWN] means deleting the volume might drop a bin that could not be listed.
 */
internal enum class HiddenBin { ABSENT, PRESENT, UNKNOWN }

/** [mode] plus the ids that were trashable on the same mount read. */
data class DeletePlan(
    val mode: DeleteDisposition,
    val trashableIds: Set<String>,
)

/** Moves a file or directory into [destDir], keeping its name. */
fun interface TrashMover {
    fun move(source: File, destDir: File): Boolean
}

/**
 * Per-volume recycle bin. A delete renames the file into `.xfiles-trash` on the same
 * volume and writes a sidecar with the original path. Nothing is copied.
 *
 * [volumes] and [mover] are injected so tests can use a temp directory and a mover
 * that refuses.
 */
class TrashStore(
    private val volumes: () -> List<TrashVolume>,
    /** True while some mounted volume still has no directory. */
    private val unresolvedVolume: () -> Boolean = { false },
    private val mover: TrashMover = TrashMover { source, destDir ->
        val target = File(destDir, source.name)
        !target.exists() && source.renameTo(target)
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomTrashId,
    /** Creates a directory, including through SAF when a plain mkdir cannot. */
    private val ensureDirectory: (File) -> Unit = { dir ->
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create Recycle Bin")
    },
    private val writeText: (File, String) -> Unit = { file, text ->
        writeUtf8Atomically(file, text)
    },
    private val removeTree: (File) -> Unit = ::deleteTrashBytes,
    /** Renames [file] to [newName] in the same directory. */
    private val renameFile: (File, String) -> Boolean = { file, newName ->
        val parent = file.parentFile
        parent != null && file.renameTo(File(parent, newName))
    },
    /**
     * Overwrites [onto] with the bytes of [from]. A rename cannot do this when the
     * destination already exists on a secondary volume.
     */
    private val replaceFile: (File, File) -> Boolean = { from, onto ->
        runCatching {
            from.inputStream().use { input ->
                onto.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    },
    /**
     * Names in a directory when [File.list] cannot see it. Null means this
     * fallback cannot see it either. Used for a SAF move into the bin.
     */
    private val childNames: (File) -> List<String>? = { it.list()?.toList() },
    /** True when a persisted grant says [File] is a directory. Null means unknown. */
    private val childIsDirectory: (File) -> Boolean? = { null },
    /** Byte length of a document [File] cannot stat. Null when the grant cannot see it. */
    private val grantLength: (File) -> Long? = { null },
    /** Sidecar text when [File.readText] cannot see a document the grant wrote. */
    private val readText: (File) -> String? = { file ->
        runCatching {
            if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.isFile) null
            else file.readText(Charsets.UTF_8)
        }.getOrNull()
    },
    /** Bytes of a file, including a document only the grant can see. Null when unreadable. */
    private val openRead: (File) -> java.io.InputStream? = { file ->
        if (file.isFile) file.inputStream() else null
    },
) {
    /** List, move, and restore share a volume's bin files. */
    private val gate = Any()

    /**
     * Bucket ids whose bytes are being restored or deleted.
     * Not [gate]: a folder picker inside the move must not freeze the bin list.
     * The value is the thread that claimed the id, so that thread's own sidecar
     * update is not mistaken for a second operation.
     */
    private val busyBinOwners = HashMap<String, Long>()

    /**
     * One editor-temp merge at a time. Separate from [gate] so the folder picker
     * inside a merge does not freeze bin refresh.
     */
    private val recoverGate = Any()

    /**
     * Bucket ids claimed while the directory is created outside [gate].
     * The SAF picker must not sit on this monitor.
     */
    private val pendingBucketIds = HashSet<String>()

    fun disposition(entries: List<XEntry>, permanent: Boolean): DeleteDisposition =
        planDelete(entries, permanent).mode

    /** One mount read for the dialog and for the ids that must not be unlinked later. */
    fun planDelete(entries: List<XEntry>, permanent: Boolean): DeletePlan {
        if (permanent || entries.isEmpty()) return DeletePlan(DeleteDisposition.PERMANENT, emptySet())
        val mounted = mountedVolumes()
        val trashable = entries.mapNotNullTo(HashSet()) { if (canTrash(it, mounted)) it.id else null }
        val mode = when (trashable.size) {
            0 -> DeleteDisposition.PERMANENT
            entries.size -> DeleteDisposition.TRASH
            else -> DeleteDisposition.MIXED
        }
        return DeletePlan(mode, trashable)
    }

    /** One read of [volumes] for a whole selection. Do not call this per entry. */
    fun mountedVolumes(): List<TrashVolume> = volumes()

    /**
     * Permanent delete is the wrong guess for a file that may sit on a volume
     * whose directory is not visible yet. Paths under a resolved volume are fine.
     */
    fun blocksUnresolved(entry: XEntry): Boolean {
        if (entry.scheme != XId.SCHEME_FILE) return false
        if (!unresolvedVolume()) return false
        val path = entry.localPath ?: entry.path
        val mounted = mountedVolumes()
        val volume = volumeContaining(path, mounted) ?: return true
        if (canTrash(entry, mounted) || volume.writable) return false
        // A pending snapshot can still say read-only after the card is writable.
        // Unlinking then would skip the bin. Wait until the snapshot settles.
        val writable = mounted.map {
            if (TrashPaths.samePath(it.rootPath, volume.rootPath)) it.copy(writable = true) else it
        }
        return canTrash(entry, writable)
    }

    fun canTrash(entry: XEntry): Boolean = canTrash(entry, mountedVolumes())

    fun canTrash(entry: XEntry, mounted: List<TrashVolume>): Boolean {
        if (entry.scheme != XId.SCHEME_FILE || !entry.canWrite) return false
        if (entry.kind != EntryKind.FILE &&
            entry.kind != EntryKind.DIR &&
            entry.kind != EntryKind.ARCHIVE
        ) {
            return false
        }
        val path = entry.localPath ?: entry.path
        val volume = volumeContaining(path, mounted) ?: return false
        if (!volume.writable) return false
        if (TrashPaths.samePath(path, volume.rootPath)) return false
        // Only the volume's own bin is already trashed. A folder the user named
        // `.xfiles-trash` elsewhere still goes through the normal bin path.
        if (TrashPaths.isInsideVolumeBin(path, listOf(volume.rootPath))) return false
        return true
    }

    /**
     * Rename [entry] into its volume's bin. The source is unchanged when this throws.
     * [recoverEdits] is false when the caller already ran [recoverTruncatedEdits] on it.
     * A folder is never walked: its editor siblings are inside it and move with it.
     */
    fun trash(entry: XEntry, recoverEdits: Boolean = true): TrashRecord {
        // The SAF folder picker can block inside the move. Do not hold [gate] across it,
        // or every open waits on the same monitor.
        val mounted = volumes()
        if (!canTrash(entry, mounted)) throw IOException("Cannot move ${entry.name} to the Recycle Bin")
        val path = entry.localPath ?: entry.path
        val volume = volumeContaining(path, mounted)
            ?: throw IOException("Cannot move ${entry.name} to the Recycle Bin")
        val source = File(path)
        // exists() follows links, so a broken symlink looks missing even though the node is there.
        // A listing can still show a document only the grant names; moveInto can move that.
        if (!nodeExists(source) && !grantNames(source)) {
            throw IOException("Cannot move ${entry.name} to the Recycle Bin")
        }
        requireSafeEntryName(source.name)
        // A failed save's complete copy is a sibling, not the file about to be renamed.
        // A folder's ready copies move with it, so a folder stays one rename.
        if (recoverEdits && !isRecoverDirectory(source)) recoverTree(source, parentOwners = null, HashSet())
        val (filesDir, infoDir) = ensureLayout(File(volume.rootPath))
        val relative = TrashPaths.relativeTo(volume.rootPath, path)
        val id = synchronized(gate) { claimBucketId(filesDir) }
        val bucket = File(filesDir, id)
        try {
            // mkdir on a secondary volume can open the folder picker. list() must keep running.
            if (isSymlink(bucket)) throw IOException("Cannot move ${entry.name} to the Recycle Bin")
            if (!directoryVisible(bucket)) ensureDirectory(bucket)
            // ensureDirectory can succeed through the grant while File still cannot see the bucket.
            if (isSymlink(bucket) || !directoryVisible(bucket)) {
                throw IOException("Cannot move ${entry.name} to the Recycle Bin")
            }
        } finally {
            synchronized(gate) { pendingBucketIds.remove(id) }
        }
        if (!mover.move(source, bucket)) {
            abandonBucket(bucket, source)
            throw IOException("Cannot move ${entry.name} to the Recycle Bin")
        }
        val stored = File(bucket, source.name)
        // mover returned true only after the bytes were placed in the bucket. A stale
        // File.exists on the old path must not discard that and delete the new child.
        try {
            writeInfo(File(infoDir, "$id.trashinfo"), source.name, relative, now())
        } catch (e: IOException) {
            val parent = source.parentFile
            if (parent != null && mover.move(stored, parent)) bucket.delete()
            throw e
        }
        return TrashRecord(
            id = id,
            volumeRoot = volume.rootPath,
            volumeLabel = volume.label,
            storedPath = stored.absolutePath,
            name = source.name,
            originalRelativePath = relative,
            deletedAt = now(),
            orphan = false,
        )
    }

    fun list(): List<TrashRecord> = synchronized(gate) {
        val out = ArrayList<TrashRecord>()
        for (volume in volumes()) {
            val root = File(volume.rootPath)
            if (!root.isDirectory) continue
            out += listVolume(volume, root)
        }
        return out
    }

    fun lookup(path: String): TrashRecord? = synchronized(gate) {
        val top = TrashPaths.topLevel(path) ?: return null
        val volume = volumes().firstOrNull { TrashPaths.samePath(it.rootPath, top.volumeRoot) }
            ?: return null
        return readRecord(volume, top.id)
    }

    /**
     * Moves [record] back to its original folder under the name [destName].
     * The caller has already resolved a name that does not exist there.
     */
    fun restore(record: TrashRecord, destName: String, vacated: Boolean = false): File {
        requireSafeEntryName(destName)
        // A folder's ready copies travel inside it, so only a file leaves one behind.
        if (!record.storedIsDir && !isRecoverDirectory(File(record.storedPath))) {
            recoverTruncatedEdit(record.storedPath)
        }
        // Creating the original folder and the SAF move can each wait on the folder
        // picker. Hold [gate] only around the record update so the bin can still refresh.
        val parent = restoreParent(record)
        val dest = File(parent, destName)
        val stored = File(record.storedPath)
        val bucket = stored.parentFile ?: throw IOException("Cannot restore ${record.name}")
        synchronized(gate) {
            claimBinOwner(record.id, "Cannot restore ${record.name}")
            try {
                // Overwrite just moved the occupant. exists() can stay true after that rename.
                // A grant-only occupant is not nodeExists. renameTo would hide it.
                if (!vacated && (nodeExists(dest) || grantNames(dest))) {
                    throw IOException("$destName already exists")
                }
                if (isSymlink(bucket)) throw IOException("Cannot restore ${record.name}")
                // moveDocument can leave the only copy invisible to File. The grant still
                // names it, so the SAF move has to run instead of failing closed.
                if (!nodeExists(stored) && !grantNames(stored)) {
                    throw IOException("Cannot restore ${record.name}")
                }
            } catch (e: Exception) {
                releaseBinOwner(record.id)
                throw e
            }
        }
        try {
            val staged = if (stored.name == destName) {
                stored
            } else {
                // renameTo fails if a real file holds the name. exists() alone can be stale.
                if (!renameFile(stored, destName)) {
                    throw IOException("Cannot restore ${record.name}")
                }
                File(bucket, destName)
            }
            // A true move already put the document in [parent]. File may not see [dest] yet;
            // requiring exists() rolls the restore back and can clobber it with the occupant.
            if (!mover.move(staged, parent)) {
                // The rename may already have succeeded through SAF while File still
                // cannot see the new name. Ask for the old name anyway.
                if (staged.name != stored.name) renameFile(staged, stored.name)
                throw IOException("Cannot restore ${record.name}")
            }
            synchronized(gate) {
                try {
                    removeRecord(record)
                } catch (_: IOException) {
                    // The bytes are already at dest. Failing here makes overwrite put the
                    // occupant back on top of the copy that just landed.
                }
            }
            return dest
        } finally {
            synchronized(gate) { releaseBinOwner(record.id) }
        }
    }

    /** Deletes the stored bytes and the restore record. */
    fun purge(record: TrashRecord) = synchronized(gate) {
        val added = claimBinOwner(record.id, "Cannot delete ${record.name}")
        try {
            val stored = File(record.storedPath)
            if (nodeExists(stored) || grantNames(stored)) {
                removeTree(stored)
                if (nodeExists(stored) || grantNames(stored)) {
                    throw IOException("Cannot delete ${record.name}")
                }
            }
            removeRecord(record)
        } finally {
            if (added) releaseBinOwner(record.id)
        }
    }

    /**
     * Claims the bucket that holds [path] so a restore cannot move it while it is deleted.
     * Null when [path] is not inside a volume bin. Throws when another operation holds it.
     */
    fun claimBinBytes(path: String): String? = synchronized(gate) {
        val roots = volumes().map { it.rootPath }
        if (!TrashPaths.isInsideVolumeBin(path, roots)) return null
        val id = TrashPaths.bucketId(path) ?: return null
        claimBinOwner(id, "Cannot change this item while it is being restored")
        id
    }

    fun releaseBinBytes(id: String) = synchronized(gate) {
        releaseBinOwner(id)
    }

    /** @return false when this thread already held [id]. */
    private fun claimBinOwner(id: String, message: String): Boolean {
        val self = callerThreadId()
        val owner = busyBinOwners[id]
        if (owner != null && owner != self) throw IOException(message)
        if (owner == self) return false
        busyBinOwners[id] = self
        return true
    }

    private fun releaseBinOwner(id: String) {
        if (busyBinOwners[id] == callerThreadId()) busyBinOwners.remove(id)
    }

    @Suppress("DEPRECATION")
    private fun callerThreadId(): Long = Thread.currentThread().id

    /**
     * Drops the restore record for a top-level bin item that a move or permanent
     * delete already took out of the bin. Returns true when a record was removed.
     */
    fun noteRemoved(path: String): Boolean = synchronized(gate) {
        val top = TrashPaths.locate(path) ?: return false
        val mounted = volumes()
        val roots = mounted.map { it.rootPath }
        if (!TrashPaths.isInsideVolumeBin(path, roots)) {
            // A user folder named .xfiles-trash can share the bin's path shape.
            // On a mounted volume that is not our bin: leave the file and any copy alone.
            val onMountedVolume = mounted.any {
                File(it.rootPath).isDirectory && TrashPaths.isInside(it.rootPath, path)
            }
            if (onMountedVolume) return false
            // A directory that still exists was never one of our volumes. Only a
            // missing volume root can be an ejected card whose bin we must not
            // report as deleted after the payload is already gone.
            if (!File(top.volumeRoot).isDirectory &&
                !File(top.infoPath).exists() &&
                !File(top.storedPath).exists()
            ) {
                val label = top.name.ifBlank { File(path).name.ifBlank { "item" } }
                throw IOException("Cannot delete $label")
            }
            return false
        }
        val info = File(top.infoPath)
        val stored = File(top.storedPath)
        // An ejected volume is not a directory here, so a missing path is not proof
        // the sidecar was removed. Reporting success would hide the item until remount.
        if (!volumeMounted(top.volumeRoot, mounted) && !info.exists() && !stored.exists()) {
            val label = top.name.ifBlank { stored.name.ifBlank { "item" } }
            throw IOException("Cannot delete $label")
        }
        val owner = busyBinOwners[top.id]
        if (owner != null && owner != callerThreadId()) {
            val label = top.name.ifBlank { stored.name.ifBlank { "item" } }
            throw IOException("Cannot delete $label")
        }
        // File.delete is false for a document only the grant can see. Dropping the
        // sidecar first hides the row and leaves that copy under the concealed bucket.
        if (grantNames(stored)) {
            removeTree(stored)
            if (nodeExists(stored) || grantNames(stored)) {
                val label = top.name.ifBlank { stored.name.ifBlank { "item" } }
                throw IOException("Cannot delete $label")
            }
        }
        val hadInfo = info.exists() || grantNames(info)
        if (hadInfo) {
            removeTree(info)
            if (info.exists() || grantNames(info)) {
                val label = top.name.ifBlank { stored.name.ifBlank { "item" } }
                throw IOException("Cannot delete $label")
            }
        }
        dropEditorScratch(File(top.bucketPath))
        return hadInfo || !nodeExists(stored)
    }

    private fun listVolume(volume: TrashVolume, root: File): List<TrashRecord> {
        val trash = File(root, TrashPaths.DIR_NAME)
        val filesDir = File(trash, "files")
        val infoDir = File(trash, "info")
        if (isSymlink(trash) || isSymlink(filesDir) || isSymlink(infoDir)) return emptyList()
        if (!directoryVisible(filesDir) && !directoryVisible(infoDir)) return emptyList()
        val infoById = LinkedHashMap<String, File>()
        infoEntries(infoDir).forEach { file ->
            if (!file.name.endsWith(".trashinfo")) return@forEach
            // isFile follows links. A symlink sidecar must not supply path=.
            if (isSymlink(file)) return@forEach
            val id = file.name.removeSuffix(".trashinfo")
            if (TrashPaths.isId(id)) infoById[id] = file
        }
        val out = ArrayList<TrashRecord>()
        val seen = HashSet<String>()
        for ((id, infoFile) in infoById) {
            val bucket = File(filesDir, id)
            // isDirectory follows links. A symlink bucket would list and restore the target.
            if (isSymlink(bucket)) continue
            val parsed = parseInfo(infoFile)
            // A null from loneScratchPayload must still reach the grant. An elvis
            // chain here was compiled as if that call could not return null, so an
            // empty File listing never asked for the moved document.
            val stored = resolveStored(bucket, parsed?.name)
            if (stored == null) {
                // A just-moved secondary-volume file can be invisible to File for a moment.
                // Keep a valid sidecar; the orphan sweep must not delete that bucket.
                // An empty File listing is not an empty bucket when the grant still has a child.
                val granted = grantOnlyPayload(bucket)
                if (granted != null) {
                    seen += id
                    out += if (parsed == null) {
                        orphan(volume, id, granted)
                    } else {
                        TrashRecord(
                            id = id,
                            volumeRoot = volume.rootPath,
                            volumeLabel = volume.label,
                            storedPath = granted.absolutePath,
                            name = parsed.name,
                            originalRelativePath = parsed.path,
                            deletedAt = parsed.deletedAt,
                            orphan = false,
                            storedIsDir = directoryPayload(granted),
                        )
                    }
                    continue
                }
                if (parsed != null && isPlainChildName(parsed.name)) {
                    // The payload can be invisible right after a secondary-volume move.
                    // Deleting the sidecar from a refresh drops the only restore record,
                    // and a throw here used to hide every other row.
                    seen += id
                    val expected = File(bucket, parsed.name)
                    out += TrashRecord(
                        id = id,
                        volumeRoot = volume.rootPath,
                        volumeLabel = volume.label,
                        storedPath = expected.absolutePath,
                        name = parsed.name,
                        originalRelativePath = parsed.path,
                        deletedAt = parsed.deletedAt,
                        orphan = false,
                        storedIsDir = directoryPayload(expected),
                    )
                } else {
                    seen += id
                }
                continue
            }
            if (parsed == null) {
                out += orphan(volume, id, stored)
            } else {
                out += TrashRecord(
                    id = id,
                    volumeRoot = volume.rootPath,
                    volumeLabel = volume.label,
                    storedPath = stored.absolutePath,
                    name = parsed.name,
                    originalRelativePath = parsed.path,
                    deletedAt = parsed.deletedAt,
                    orphan = false,
                    storedIsDir = directoryPayload(stored),
                )
            }
            seen += id
        }
        bucketEntries(filesDir).forEach { bucket ->
            if (isSymlink(bucket)) return@forEach
            if (!directoryVisible(bucket) || !TrashPaths.isId(bucket.name) || bucket.name in seen) return@forEach
            val stored = resolveStored(bucket, null) ?: grantOnlyPayload(bucket) ?: grantOnlyScratch(bucket)
            if (stored == null) {
                // One stuck scratch file must not hide every other bin row.
                try {
                    dropEditorScratch(bucket, deleteIfEmpty = false)
                } catch (_: IOException) {
                }
                return@forEach
            }
            out += orphan(volume, bucket.name, stored)
        }
        return out
    }

    private fun orphan(volume: TrashVolume, id: String, stored: File) = TrashRecord(
        id = id,
        volumeRoot = volume.rootPath,
        volumeLabel = volume.label,
        storedPath = stored.absolutePath,
        name = stored.name,
        originalRelativePath = stored.name,
        deletedAt = stored.lastModified(),
        orphan = true,
        storedIsDir = directoryPayload(stored),
    )

    /** Where [record] would return, without creating missing folders. */
    fun plannedParent(record: TrashRecord): File {
        val volume = volumes().firstOrNull { TrashPaths.samePath(it.rootPath, record.volumeRoot) }
            ?: throw IOException("Cannot restore ${record.name}")
        if (!volume.writable) throw IOException("Cannot restore ${record.name}")
        val parentRelative = if (record.orphan) {
            ""
        } else {
            record.originalRelativePath.substringBeforeLast('/', "")
        }
        return TrashPaths.resolveUnder(File(volume.rootPath), parentRelative)
    }

    /** False when a restore would follow a symlink or leave the volume. */
    fun restoreTargetIsReal(record: TrashRecord): Boolean {
        val parent = runCatching { plannedParent(record) }.getOrNull() ?: return false
        return restoreParentIsReal(File(record.volumeRoot), parent)
    }

    /** Original parent directory, recreated under the volume when it is gone. */
    fun restoreParent(record: TrashRecord): File {
        val parent = plannedParent(record)
        val volumeRoot = File(record.volumeRoot)
        // isDirectory follows links. A symlink folder would make the only copy land elsewhere.
        if (!restoreParentIsReal(volumeRoot, parent)) {
            throw IOException("Cannot restore ${record.name}")
        }
        if (parent.isDirectory) return parent
        if (nodeExists(parent)) throw IOException("Cannot restore ${record.name}")
        ensureDirectory(parent)
        if (!restoreParentIsReal(volumeRoot, parent) || !parent.isDirectory) {
            throw IOException("Cannot restore ${record.name}")
        }
        return parent
    }

    /**
     * A failed text save can leave `.name.xfiles-ready` as the only complete copy of a
     * truncated file. Put it back before the payload leaves the bin. Empty still deletes it.
     */
    fun recoverTruncatedEdit(path: String) {
        recoverTruncatedEdits(listOf(path))
    }

    /**
     * [recoverTruncatedEdit] for several paths. Each folder is listed once, and a file with
     * no editor sibling in that listing is not opened, so a batch costs one directory read
     * per parent instead of one per file. [onFailure] gets each path whose recover threw;
     * by default the first failure is rethrown. Returns the files whose bytes or leftover
     * siblings changed: a listing of their folders no longer matches the disk.
     */
    fun recoverTruncatedEdits(
        paths: Collection<String>,
        onFailure: (String, IOException) -> Unit = { _, error -> throw error },
    ): Set<String> {
        val origins = LinkedHashMap<String, String>()
        val targets = ArrayList<File>(paths.size)
        for (path in paths) {
            val target = recoverTarget(path)
            if (origins.putIfAbsent(target.path, path) == null) targets += target
        }
        val changed = LinkedHashSet<String>()
        for ((parent, files) in targets.groupBy { it.parentFile }) {
            val owners = parent?.let(::scratchOwnersIn)
            for (file in files) {
                try {
                    recoverTree(file, owners, changed)
                } catch (error: IOException) {
                    onFailure(origins.getValue(file.path), error)
                }
            }
        }
        return changed
    }

    /** The file whose editor siblings [path] shares: the top-level payload inside a bin. */
    private fun recoverTarget(path: String): File {
        // A normal file is not a bin path. Merge its ready sibling without asking
        // whether storage is still mounting; the following delete would unlink it.
        if (!TrashPaths.isUnderTrash(path)) return File(path)
        // The mount check is the only part that needs [gate]. The copy itself can
        // open the folder picker and must not sit on that monitor.
        return synchronized(gate) {
            val roots = volumes().map { it.rootPath }
            // A user folder can use the same .xfiles-trash/files/<id> shape.
            // It is not the bin, but its ready sibling is still the complete copy.
            if (!TrashPaths.isInsideVolumeBin(path, roots)) {
                File(path)
            } else {
                val top = TrashPaths.topLevel(path)
                if (top != null) File(top.storedPath) else File(path)
            }
        }
    }

    /**
     * [parentOwners] is one listing of [file]'s folder, or null to list it here.
     * A directory is walked with one listing per folder. Each file it rewrites or cleans
     * up is added to [changed].
     */
    private fun recoverTree(file: File, parentOwners: ScratchOwners?, changed: MutableSet<String>) {
        if (isSymlink(file)) return
        // A directory has no sibling of its own. Walk it without holding [gate],
        // or a folder delete waits on the picker for every child.
        if (isRecoverDirectory(file)) {
            recoverDirectory(file, changed)
            return
        }
        // A normal open has no editor sibling. Do not take a lock just to see that.
        if (!hasEditorSibling(file, parentOwners)) return
        // Open and trash can both merge the same ready sibling. The copy truncates
        // the destination, so the two must not overlap. This is not the bin monitor.
        synchronized(recoverGate) { recoverTreeLocked(file, changed) }
    }

    private fun recoverDirectory(dir: File, changed: MutableSet<String>) {
        val owners = scratchOwnersIn(dir)
        for (name in owners.listed) recoverTree(File(dir, name), owners, changed)
    }

    /**
     * A regular file is not a directory, so only a node [File] cannot classify asks the
     * grant. Asking for every file costs a StorageManager call each on API 26–29.
     */
    private fun isRecoverDirectory(file: File): Boolean =
        file.isDirectory || (!file.isFile && childIsDirectory(file) == true)

    /**
     * One listing of [dir]: every child name, and the names that have an editor sibling.
     * File.list can be empty while the grant still has the children, so both are read.
     */
    private fun scratchOwnersIn(dir: File): ScratchOwners {
        val fromFile = dir.list()
        val names = LinkedHashSet<String>()
        fromFile?.forEach { if (isPlainChildName(it)) names += it }
        childNames(dir)?.forEach { if (isPlainChildName(it)) names += it }
        val owners = HashSet<String>()
        for (name in names) owners += editorScratchOwnerCandidates(name)
        return ScratchOwners(names, owners, complete = fromFile != null)
    }

    private fun hasEditorSibling(file: File, parentOwners: ScratchOwners?): Boolean {
        val parent = file.parentFile ?: return false
        val owners = parentOwners ?: scratchOwnersIn(parent)
        if (file.name in owners.owners) return true
        if (owners.complete) return false
        // File could not list the folder. The ready name and the bare temp name can still be stat'ed.
        val ready = File(parent, ".${file.name}.xfiles-ready")
        val tmp = File(parent, ".${file.name}.xfiles-tmp")
        return ready.exists() || isSymlink(ready) || tmp.exists() || isSymlink(tmp)
    }

    /** A ready or temp sibling that only the stored grant names is still the save. */
    private fun grantHasEditorSibling(parent: File, name: String): Boolean {
        val names = childNames(parent) ?: return false
        if (".${name}.xfiles-ready" in names) return true
        return names.any { isEditorTmpFor(it, name) }
    }

    private fun recoverTreeLocked(file: File, changed: MutableSet<String>) {
        if (isSymlink(file)) return
        val parent = file.parentFile ?: return
        // The payload itself may exist only on the grant. Its ready sibling is still the save.
        if (!file.isFile && !grantHasEditorSibling(parent, file.name)) return
        val ready = File(parent, ".${file.name}.xfiles-ready")
        val tmp = File(parent, ".${file.name}.xfiles-tmp")
        if (isSymlink(ready) || isSymlink(tmp)) {
            throw IOException("Cannot use the editor temp for ${file.name}")
        }
        val siblings = parent.listFiles()
        if (siblings != null && siblings.any { sibling ->
                isEditorTmpFor(sibling.name, file.name) && isSymlink(sibling)
            }
        ) {
            throw IOException("Cannot use the editor temp for ${file.name}")
        }
        val granted = childNames(parent).orEmpty()
        val readyOnGrant = ready.name in granted && !ready.isFile
        val grantedTmp = granted.filter { isEditorTmpFor(it, file.name) }
        // An empty File listing is not proof the ready copy is gone.
        if (!ready.isFile && !readyOnGrant && !tmp.exists() && grantedTmp.isEmpty() &&
            (siblings == null || siblings.none { isEditorTmpFor(it.name, file.name) })
        ) {
            return
        }
        if (ready.isFile || readyOnGrant) {
            val readyLength = knownByteLength(if (ready.isFile) ready.length() else grantLength(ready))
                ?: throw IOException("Cannot use the editor temp for ${file.name}")
            when (compareWithReady(file, ready, readyLength)) {
                ReadyMatch.CUT_OFF -> {
                    if (!replaceFile(ready, file)) {
                        throw IOException("Cannot use the editor temp for ${file.name}")
                    }
                    val written = knownByteLength(if (file.isFile) file.length() else grantLength(file))
                    if (written == null || written != readyLength) {
                        throw IOException("Cannot use the editor temp for ${file.name}")
                    }
                }
                // The save landed; only its leftovers remain.
                ReadyMatch.SAME -> Unit
                // Written after that save, by another app or a later edit. The older ready
                // bytes must not replace it, and the ready copy is not ours to delete.
                ReadyMatch.DIFFERENT -> return
            }
        }
        // The leftovers are rows of the same folder, so its listing is stale even when
        // the file's own bytes were already right.
        changed += file.path
        val removed = HashSet<String>()
        if (ready.exists() || readyOnGrant) {
            removeTree(ready)
            removed += ready.name
        }
        if (siblings != null) {
            siblings.forEach { sibling ->
                if (isEditorTmpFor(sibling.name, file.name) && sibling.name !in removed) {
                    removeTree(sibling)
                    removed += sibling.name
                }
            }
        } else if (tmp.exists()) {
            removeTree(tmp)
            removed += tmp.name
        }
        for (name in grantedTmp) {
            if (name in removed) continue
            removeTree(File(parent, name))
        }
    }

    /**
     * A save cut off while copying [ready] onto [file] leaves [file] as a shorter prefix
     * of it. Any other content was written after that save.
     */
    private fun compareWithReady(file: File, ready: File, readyLength: Long): ReadyMatch {
        val fileLength = knownByteLength(if (file.isFile) file.length() else grantLength(file))
            ?: throw IOException("Cannot use the editor temp for ${file.name}")
        if (fileLength > readyLength) return ReadyMatch.DIFFERENT
        val prefix = try {
            readsSamePrefix(file, ready, fileLength)
        } catch (e: IOException) {
            throw IOException("Cannot use the editor temp for ${file.name}", e)
        }
        return when {
            !prefix -> ReadyMatch.DIFFERENT
            fileLength == readyLength -> ReadyMatch.SAME
            else -> ReadyMatch.CUT_OFF
        }
    }

    private fun readsSamePrefix(a: File, b: File, length: Long): Boolean {
        if (length == 0L) return true
        val left = openRead(a) ?: throw IOException("Cannot read ${a.name}")
        left.use {
            val right = openRead(b) ?: throw IOException("Cannot read ${b.name}")
            right.use {
                val bufA = ByteArray(COMPARE_BUFFER)
                val bufB = ByteArray(COMPARE_BUFFER)
                var remaining = length
                while (remaining > 0) {
                    val want = minOf(remaining, COMPARE_BUFFER.toLong()).toInt()
                    if (!readFully(left, bufA, want) || !readFully(right, bufB, want)) return false
                    for (i in 0 until want) if (bufA[i] != bufB[i]) return false
                    remaining -= want
                }
                return true
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, count: Int): Boolean {
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun isSymlink(file: File): Boolean = isSymbolicLink(file)

    private fun isSymbolicLink(file: File): Boolean =
        java.nio.file.Files.isSymbolicLink(file.toPath())

    /**
     * [PRESENT] when File or the grant can see a real bin directory.
     * [UNKNOWN] when the volume root itself cannot be listed, so a move must stop
     * before it deletes a bin it could not copy.
     */
    internal fun hiddenBin(volumeRoot: String): HiddenBin {
        val root = File(volumeRoot)
        val bin = File(TrashPaths.binRoot(volumeRoot))
        if (isSymlink(bin)) return HiddenBin.ABSENT
        if (bin.isDirectory) return HiddenBin.PRESENT
        if (bin.isFile) return HiddenBin.ABSENT
        when (childIsDirectory(bin)) {
            true -> return HiddenBin.PRESENT
            false -> return HiddenBin.ABSENT
            null -> Unit
        }
        val names = childNames(root) ?: root.list()?.toList()
        if (names != null) {
            return if (names.any { it == TrashPaths.DIR_NAME }) HiddenBin.UNKNOWN else HiddenBin.ABSENT
        }
        return HiddenBin.UNKNOWN
    }

    private fun restoreParentIsReal(volumeRoot: File, parent: File): Boolean {
        val root = volumeRoot.absoluteFile
        val target = parent.absoluteFile
        val rootPath = if (root.path == "/") "/" else root.path.trimEnd('/')
        val targetPath = if (target.path == "/") "/" else target.path.trimEnd('/')
        if (targetPath != rootPath && !targetPath.startsWith("$rootPath/")) return false
        if (targetPath != rootPath) {
            var cursor = root
            for (segment in targetPath.removePrefix("$rootPath/").split('/')) {
                cursor = File(cursor, segment)
                if (java.nio.file.Files.isSymbolicLink(cursor.toPath())) return false
            }
        }
        if (!target.exists()) return true
        val canonRoot = root.canonicalFile.path.let { if (it == "/") "/" else it.trimEnd('/') }
        val canon = target.canonicalFile.path.let { if (it == "/") "/" else it.trimEnd('/') }
        return canon == canonRoot || canon.startsWith("$canonRoot/")
    }

    private fun removeRecord(record: TrashRecord) {
        val root = record.volumeRoot.trimEnd('/').ifEmpty { "/" }
        val info = File(root, "${TrashPaths.DIR_NAME}/info/${record.id}.trashinfo")
        if (info.exists() || grantNames(info)) removeTree(info)
        dropEditorScratch(File(root, "${TrashPaths.DIR_NAME}/files/${record.id}"))
    }

    /**
     * Editor leftovers are not a second bin item.
     * [deleteIfEmpty] is false for a bucket that never showed a payload: an empty
     * listing can mean the moved file is not visible yet.
     */
    private fun dropEditorScratch(bucket: File, deleteIfEmpty: Boolean = true) {
        if (isSymlink(bucket) || !bucket.isDirectory) return
        val children = bucket.listFiles() ?: return
        if (!deleteIfEmpty && children.isEmpty()) return
        children.forEach { child ->
            if (isEditorScratch(child.name)) removeTree(child)
        }
        if (!deleteIfEmpty && children.any { !isEditorScratch(it.name) && it.name != ".nomedia" }) return
        val names = bucket.list() ?: return
        val grantKeepsBucket = childNames(bucket).orEmpty().any { name ->
            isPlainChildName(name) && name != ".nomedia" && !isEditorScratch(name)
        }
        if (names.isEmpty() && !grantKeepsBucket) removeTree(bucket)
    }

    private fun nodeExists(file: File): Boolean =
        file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())

    /**
     * [File] cannot see [file], but a directory listing from the persisted grant
     * still contains its name. [nodeExists] is false for that document.
     */
    private fun grantNames(file: File): Boolean {
        if (nodeExists(file)) return false
        val name = file.name
        if (!isPlainChildName(name)) return false
        val parent = file.parentFile ?: return false
        return childNames(parent)?.contains(name) == true
    }

    private fun directoryPayload(file: File): Boolean =
        (!isSymlink(file) && file.isDirectory) || childIsDirectory(file) == true

    /** [File.isDirectory] is false for a directory that only the persisted grant created. */
    private fun directoryVisible(dir: File): Boolean =
        (!isSymlink(dir) && dir.isDirectory) || childIsDirectory(dir) == true

    private fun parseInfo(file: File): ParsedInfo? = parseInfoText(readText(file))

    /** One bucket, read under the lock. Does not scan other volumes or merge editor temps. */
    private fun readRecord(volume: TrashVolume, id: String): TrashRecord? {
        if (!TrashPaths.isId(id)) return null
        val root = File(volume.rootPath)
        if (!root.isDirectory) return null
        val trashDir = File(root, TrashPaths.DIR_NAME)
        val filesDir = File(trashDir, "files")
        val infoDir = File(trashDir, "info")
        if (isSymlink(trashDir) || isSymlink(filesDir) || isSymlink(infoDir)) return null
        val bucket = File(filesDir, id)
        if (isSymlink(bucket)) return null
        val infoFile = File(infoDir, "$id.trashinfo")
        val infoNamed = !isSymlink(infoFile) &&
            (infoFile.isFile || childNames(infoDir)?.contains(infoFile.name) == true)
        if (infoNamed) {
            val parsed = parseInfo(infoFile)
            // Same resolution as listVolume, including a grant-only child with no readable sidecar.
            val stored = resolveStored(bucket, parsed?.name) ?: grantOnlyPayload(bucket)
            if (stored == null) return null
            return if (parsed == null) {
                orphan(volume, id, stored)
            } else {
                TrashRecord(
                    id = id,
                    volumeRoot = volume.rootPath,
                    volumeLabel = volume.label,
                    storedPath = stored.absolutePath,
                    name = parsed.name,
                    originalRelativePath = parsed.path,
                    deletedAt = parsed.deletedAt,
                    orphan = false,
                    storedIsDir = directoryPayload(stored),
                )
            }
        }
        if (!directoryVisible(bucket)) return null
        val stored = resolveStored(bucket, null) ?: grantOnlyPayload(bucket) ?: return null
        return orphan(volume, id, stored)
    }

    private fun volumeContaining(path: String, mounted: List<TrashVolume>): TrashVolume? =
        mounted
            .filter { TrashPaths.isInside(it.rootPath, path) }
            .maxByOrNull { it.rootPath.trimEnd('/').length }

    private fun volumeMounted(volumeRoot: String, mounted: List<TrashVolume>): Boolean =
        File(volumeRoot).isDirectory &&
            mounted.any { TrashPaths.samePath(it.rootPath, volumeRoot) }

    private fun ensureLayout(volumeRoot: File): Pair<File, File> {
        val trash = File(volumeRoot, TrashPaths.DIR_NAME)
        val files = File(trash, "files")
        val info = File(trash, "info")
        // File.isDirectory follows links. A symlink here would store "deleted" files in the target.
        if (isSymlink(trash) || isSymlink(files) || isSymlink(info)) {
            throw IOException("Cannot create Recycle Bin")
        }
        ensureDirectory(files)
        ensureDirectory(info)
        if (isSymlink(trash) || isSymlink(files) || isSymlink(info)) {
            throw IOException("Cannot create Recycle Bin")
        }
        val nomedia = File(trash, ".nomedia")
        // exists() follows links. A dangling symlink must not be opened by writeText.
        if (isSymlink(nomedia) && (!nomedia.delete() || isSymlink(nomedia))) {
            throw IOException("Cannot create Recycle Bin")
        }
        if (!nomedia.exists()) {
            val created = runCatching { nomedia.createNewFile() }.getOrDefault(false)
            if (!created) {
                if (isSymlink(nomedia)) throw IOException("Cannot create Recycle Bin")
                writeText(nomedia, "")
            }
        }
        if (isSymlink(nomedia)) throw IOException("Cannot create Recycle Bin")
        return files to info
    }

    /** Picks an unused id. The directory is created by the caller, outside [gate]. */
    private fun claimBucketId(filesDir: File): String {
        repeat(5) {
            val id = newId()
            if (!TrashPaths.isId(id) || id in pendingBucketIds) return@repeat
            val bucket = File(filesDir, id)
            if (isSymlink(bucket) || nodeExists(bucket)) return@repeat
            pendingBucketIds += id
            return id
        }
        throw IOException("Cannot create Recycle Bin")
    }

    /**
     * A failed move must not leave the user's bytes inside a new bucket.
     * If the provider already removed [source], move the landed child back.
     */
    private fun abandonBucket(bucket: File, source: File) {
        if (isSymlink(bucket)) return
        val parent = source.parentFile
        if (!source.exists() && parent != null) {
            val leftover = File(bucket, source.name)
            val granted = childNames(bucket).orEmpty()
            val landed = when {
                leftover.exists() || source.name in granted -> leftover
                else -> bucket.listFiles()?.firstOrNull { child ->
                    child.name != ".nomedia" && !isEditorScratch(child.name)
                } ?: granted.firstOrNull { name ->
                    name != ".nomedia" && !isEditorScratch(name)
                }?.let { File(bucket, it) }
            }
            if (landed != null && mover.move(landed, parent)) {
                val back = File(parent, landed.name)
                if (back.exists()) {
                    val names = bucket.list()
                    if (names != null && names.isEmpty()) bucket.delete()
                    return
                }
            }
            return
        }
        // Source still looks present. list() == null means the child may already
        // have landed and must not be removed with the bucket.
        val names = bucket.list() ?: return
        if (names.isEmpty()) return
    }

    private fun writeInfo(dest: File, name: String, relative: String, deletedAt: Long) {
        writeText(dest, encodeInfo(name, relative, deletedAt))
    }

    /**
     * [File.list] may be an empty array after moveDocument, not null. That is not
     * proof the grant has no document; [childNames] includes those names.
     * Kept as statements: folding these into one elvis chain dropped the grant lookup.
     */
    private fun resolveStored(bucket: File, preferredName: String?): File? {
        singleStored(bucket, preferredName)?.let { return it }
        // The sidecar name wins over a lone scratch sibling. Scratch is only the
        // payload when the real name is not in the grant.
        storedWhenUnlisted(bucket, preferredName)?.let { return it }
        if (bucket.isDirectory && bucket.list() != null) {
            loneScratchPayload(bucket)?.let { return it }
        }
        return grantOnlyScratch(bucket)
    }

    /** A negative document size is unknown. Matching -1 to -1 must not delete the ready copy. */
    private fun knownByteLength(length: Long?): Long? = length?.takeIf { it >= 0 }

    /** One scratch-shaped child that File.list missed, when the real name is absent. */
    private fun grantOnlyScratch(bucket: File): File? {
        val names = childNames(bucket).orEmpty().filter { name ->
            isPlainChildName(name) && name != ".nomedia"
        }
        if (names.any { !isEditorScratch(it) }) return null
        val only = names.distinct().singleOrNull() ?: return null
        return if (isEditorScratch(only)) File(bucket, only) else null
    }

    /** One non-scratch child that File.list missed. Used when no sidecar names it. */
    private fun grantOnlyPayload(bucket: File): File? {
        val names = childNames(bucket).orEmpty().filter { name ->
            isPlainChildName(name) && name != ".nomedia" && !isEditorScratch(name)
        }
        val only = names.distinct().singleOrNull() ?: return null
        return File(bucket, only)
    }

    private fun storedWhenUnlisted(bucket: File, preferredName: String?): File? {
        if (preferredName.isNullOrEmpty() || !isPlainChildName(preferredName)) return null
        val direct = File(bucket, preferredName)
        if (nodeExists(direct)) return direct
        val names = childNames(bucket) ?: return null
        return if (preferredName in names) direct else null
    }

    private fun infoEntries(infoDir: File): List<File> = namedChildren(infoDir)

    private fun bucketEntries(filesDir: File): List<File> = namedChildren(filesDir)

    /** An empty [File.list] is not proof the grant has no child. */
    private fun namedChildren(dir: File): List<File> {
        val names = unionFileAndGrantNames(dir.list()?.toList(), childNames(dir)).orEmpty()
        return names.filter { isPlainChildName(it) }.distinct().map { File(dir, it) }
    }

    private fun isPlainChildName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

    /** One scratch-shaped child may be the only remaining copy, so it stays listed. */
    private fun loneScratchPayload(bucket: File): File? {
        val children = bucket.listFiles() ?: return null
        val only = children.filter { it.name != ".nomedia" }.singleOrNull() ?: return null
        return if (isEditorScratch(only.name)) only else null
    }

    private fun singleStored(bucket: File, preferredName: String? = null): File? {
        if (!bucket.isDirectory) return null
        val children = bucket.listFiles() ?: return null
        // Editor leftovers sit beside the payload. They must not turn the row into
        // the bucket directory, which hides Restore and makes Empty delete the id folder.
        // Match the sidecar name before dropping scratch siblings. The payload itself
        // may be named `.note.txt.xfiles-ready`; that filter is only for leftovers.
        if (!preferredName.isNullOrEmpty()) {
            children.firstOrNull { it.name == preferredName }?.let { return it }
        }
        val real = children.filter { it.name != ".nomedia" && !isEditorScratch(it.name) }
        if (!preferredName.isNullOrEmpty()) {
            if (real.size == 1) return real[0]
            return null
        }
        // The volume marker lives next to files/, not in a bucket. A trashed item
        // that is itself named .nomedia is the only child and must stay listed.
        return when {
            real.size == 1 -> real[0]
            real.isEmpty() && children.size == 1 && !isEditorScratch(children[0].name) -> children[0]
            real.isEmpty() -> null
            // Several payloads and no sidecar. The bucket is what Empty deletes;
            // listing only the first child leaves the rest with no row.
            else -> bucket
        }
    }

    /** Child names of one folder, and which of them have an editor sibling there. */
    private class ScratchOwners(
        val listed: Set<String>,
        val owners: Set<String>,
        /** False when [File.list] failed, so [listed] may be missing names. */
        val complete: Boolean,
    )

    private enum class ReadyMatch { CUT_OFF, SAME, DIFFERENT }

    private companion object {
        const val COMPARE_BUFFER = 64 * 1024
    }
}

/**
 * Payload names a scratch file can belong to. `.a.xfiles-tmp.1` is a's temp, but a payload
 * may itself contain `.xfiles-tmp`, so every such split is a candidate.
 */
internal fun editorScratchOwnerCandidates(name: String): List<String> {
    if (!name.startsWith(".")) return emptyList()
    val body = name.substring(1)
    val out = ArrayList<String>(1)
    val ready = ".xfiles-ready"
    if (body.length > ready.length && body.endsWith(ready)) out += body.removeSuffix(ready)
    val tmp = ".xfiles-tmp"
    var at = body.indexOf(tmp)
    while (at >= 0) {
        val end = at + tmp.length
        if (at > 0 && (end == body.length || body[end] == '.')) out += body.substring(0, at)
        at = body.indexOf(tmp, at + 1)
    }
    return out
}

/** True for the text editor's `.name.xfiles-ready` and `.name.xfiles-tmp*` siblings. */
internal fun isEditorTmpFor(fileName: String, entryName: String): Boolean {
    val prefix = ".$entryName.xfiles-tmp"
    return fileName == prefix || fileName.startsWith("$prefix.")
}

internal fun isEditorScratch(name: String): Boolean {
    if (!name.startsWith(".")) return false
    val ready = ".xfiles-ready"
    val readyAt = name.length - ready.length
    if (readyAt > 0 && name.startsWith(ready, readyAt)) return true
    val tmp = ".xfiles-tmp"
    val tmpAt = name.indexOf(tmp)
    if (tmpAt <= 0) return false
    val end = tmpAt + tmp.length
    return end == name.length || name.getOrNull(end) == '.'
}

/** Payload file a `.name.xfiles-ready` or `.name.xfiles-tmp*` sibling belongs to. */
internal fun editorScratchPayloadName(name: String): String? {
    if (!isEditorScratch(name)) return null
    val body = name.removePrefix(".")
    val ready = ".xfiles-ready"
    if (body.endsWith(ready)) return body.removeSuffix(ready).takeIf { it.isNotEmpty() }
    val tmp = ".xfiles-tmp"
    val at = body.indexOf(tmp)
    if (at <= 0) return null
    return body.substring(0, at).takeIf { it.isNotEmpty() }
}

/**
 * Writes [text] to [file] by renaming a new temp file over it.
 * A symlink at the temp or destination path is removed, not followed.
 */
internal fun writeUtf8Atomically(file: File, text: String) {
    val parent = file.parentFile ?: throw IOException("Cannot write ${file.name}")
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot write ${file.name}")
    val tmp = File(parent, file.name + ".tmp")
    removeTempNode(tmp, file.name)
    try {
        Files.newOutputStream(
            tmp.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    } catch (e: FileAlreadyExistsException) {
        if (Files.isSymbolicLink(tmp.toPath())) throw RefusingSymlinkWrite(file.name)
        throw IOException("Cannot write ${file.name}", e)
    }
    if (!tmp.renameTo(file)) {
        val linked = Files.isSymbolicLink(tmp.toPath()) || Files.isSymbolicLink(file.toPath())
        if (!Files.isSymbolicLink(tmp.toPath())) tmp.delete()
        if (linked) throw RefusingSymlinkWrite(file.name)
        throw IOException("Cannot write ${file.name}")
    }
}

/** [File.writeText] follows the last symlink. Unlink that node instead. */
private fun removeTempNode(tmp: File, name: String) {
    val linked = Files.isSymbolicLink(tmp.toPath())
    if (!linked && !tmp.exists()) return
    if (!tmp.delete() || Files.isSymbolicLink(tmp.toPath())) {
        throw if (linked || Files.isSymbolicLink(tmp.toPath())) {
            RefusingSymlinkWrite(name)
        } else {
            IOException("Cannot write $name")
        }
    }
}

/** The temp path is a symlink. Callers must not fall through to a write that follows it. */
internal class RefusingSymlinkWrite(name: String) : IOException("Cannot write $name")

/**
 * [listed] from [File.list] and [granted] from a persisted SAF tree.
 * An empty file listing is not proof the grant has no child.
 */
internal fun unionFileAndGrantNames(listed: List<String>?, granted: List<String>?): List<String>? =
    when {
        listed == null -> granted
        granted == null -> listed
        else -> (listed.asSequence() + granted.asSequence()).distinct().toList()
    }

internal fun deleteTrashBytes(file: File) {
    if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
        val children = file.listFiles()
        if (children != null) {
            // A failed payload delete must keep the ready sibling. A delete that
            // returned is done even if exists() has not caught up, so the sibling goes.
            val scratch = ArrayList<File>()
            val removedPayloads = HashSet<String>()
            for (child in children) {
                if (isEditorScratch(child.name)) {
                    scratch += child
                } else {
                    deleteTrashBytes(child)
                    removedPayloads += child.name
                }
            }
            for (sibling in scratch) {
                val payload = editorScratchPayloadName(sibling.name)
                // A failed payload delete leaves the node and throws above, so this
                // ready copy stays. exists() can also stay true after a successful
                // SD/USB delete; that sibling must still be removed.
                if (payload != null && payload !in removedPayloads &&
                    nodeSurvivedDelete(File(file, payload))
                ) {
                    continue
                }
                deleteTrashBytes(sibling)
            }
        }
    }
    // delete() returning true is success even when exists() has not caught up.
    // Throwing there skips the ready sibling and later restores those bytes onto a new file.
    if (!file.delete() && nodeSurvivedDelete(file)) {
        throw IOException("Cannot delete ${file.absolutePath}")
    }
}

/**
 * Not ThreadLocalRandom: it is seeded once in zygote, so every app process draws the
 * same salts in the same order.
 */
private val trashIdRandom by lazy { java.security.SecureRandom() }

internal fun randomTrashId(): String {
    val now = System.currentTimeMillis().toString(16)
    val salt = trashIdRandom.nextInt().toUInt().toString(16).padStart(8, '0')
    return "$now-$salt"
}

private data class ParsedInfo(val name: String, val path: String, val deletedAt: Long)

private fun encodeInfo(name: String, relative: String, deletedAt: Long): String =
    "name=${percentEncode(name)}\npath=${percentEncode(relative)}\ndeleted=$deletedAt\n"

private fun parseInfoText(text: String?): ParsedInfo? = runCatching {
    if (text == null) return null
    var name: String? = null
    var path: String? = null
    var deleted = 0L
    text.lineSequence().forEach { line ->
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq)
        val value = line.substring(eq + 1)
        when (key) {
            "name" -> name = percentDecode(value)
            "path" -> path = percentDecode(value)
            "deleted" -> deleted = value.toLongOrNull() ?: 0L
        }
    }
    val parsedName = name?.takeIf { it.isNotEmpty() } ?: return null
    val parsedPath = path?.takeIf { it.isNotEmpty() } ?: return null
    ParsedInfo(parsedName, parsedPath, deleted)
}.getOrNull()

private fun percentEncode(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val c = byte.toInt() and 0xff
        val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
            c in '0'.code..'9'.code || c == '-'.code || c == '_'.code ||
            c == '.'.code || c == '/'.code
        if (plain) append(c.toChar()) else append("%" + "%02X".format(c))
    }
}

private fun percentDecode(value: String): String {
    val out = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        if (value[i] == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                out += hex.toByte()
                i += 3
                continue
            }
        }
        val bytes = value[i].toString().toByteArray(Charsets.UTF_8)
        bytes.forEach { out += it }
        i++
    }
    return out.toByteArray().toString(Charsets.UTF_8)
}

/** Path helpers shared by the bin, listings, and search. */
object TrashPaths {
    const val DIR_NAME = ".xfiles-trash"

    private val ID_PATTERN = Regex("[0-9a-f]+-[0-9a-f]{8}")

    fun isId(name: String): Boolean = ID_PATTERN.matches(name)

    fun samePath(a: String, b: String): Boolean = norm(a) == norm(b)

    fun isInside(root: String, path: String): Boolean {
        val r = norm(root)
        val p = norm(path)
        return p == r || p.startsWith("$r/")
    }

    fun isUnderTrash(path: String): Boolean = indexOfBin(norm(path), "") >= 0

    fun relativeTo(root: String, path: String): String {
        val r = norm(root)
        val p = norm(path)
        if (r == "/") return p.trimStart('/')
        return p.removePrefix(r).trimStart('/')
    }

    /** A top-level bin row on one of [volumeRoots], not a user folder with the same shape. */
    fun isRestorableBinItem(path: String, volumeRoots: List<String>): Boolean =
        topLevel(path) != null && isInsideVolumeBin(path, volumeRoots)

    /**
     * A bin path that must not be shown as a normal folder. A `files/<id>` payload
     * whose volume is missing from [volumeRoots] still matches, so a partial mount
     * snapshot cannot reveal `.xfiles-trash`.
     */
    fun isConcealedBinPath(path: String, volumeRoots: List<String>): Boolean {
        val normalized = if (path.length > 1) path.trimEnd('/') else path
        if (volumeRoots.any { isInsideVolumeBin(normalized, listOf(it)) }) return true
        if (!isUnderTrash(normalized)) return false
        if (volumeRoots.isEmpty()) return true
        // A folder the user named .xfiles-trash under a known volume stays visible.
        return volumeRoots.none { isInside(it, normalized) }
    }

    /**
     * True when [path] and its symlink target disagree about being inside a volume bin.
     * A link outside the bin must not be walked into the only remaining copy.
     *
     * A failed `realpath` is not that disagreement. Private directories and
     * `Android/data` throw `EACCES` for the app UID, and treating that as "enters
     * the bin" blanks the root listing and the privileged fallback. Only a symlink
     * the app can see but not resolve is refused here. The transport that will
     * actually follow the link checks again.
     */
    fun crossesVolumeBin(path: String, volumeRoots: List<String>): Boolean {
        if (volumeRoots.isEmpty()) return false
        val file = java.io.File(path).absoluteFile
        val lexicalInside = isInsideVolumeBin(file.path, volumeRoots)
        val canonical = runCatching { file.canonicalPath }.getOrNull()
        if (canonical == null) {
            val link = runCatching {
                java.nio.file.Files.isSymbolicLink(file.toPath())
            }.getOrDefault(false)
            return crossesUnresolvedVolumeBin(lexicalInside, link)
        }
        val canonicalRoots = volumeRoots.map { root ->
            runCatching { java.io.File(root).canonicalPath }.getOrDefault(root)
        }
        val resolvedInside = isInsideVolumeBin(canonical, canonicalRoots)
        return lexicalInside != resolvedInside
    }

    /**
     * `realpath` failed. A plain path the app cannot search must stay reachable
     * for root and the privileged listing. An unresolved symlink outside the bin
     * is still refused, because following it can land in the only remaining copy.
     */
    internal fun crossesUnresolvedVolumeBin(lexicalInside: Boolean, pathIsSymlink: Boolean): Boolean {
        if (lexicalInside) return false
        return pathIsSymlink
    }

    /**
     * A symlink whose own path is inside a volume bin. Handing it out follows the target.
     * An empty mount list still counts `.xfiles-trash/files/<id>/`, matching concealment,
     * so a row can be on screen before the first publish.
     */
    fun isVolumeBinSymlink(path: String, volumeRoots: List<String>): Boolean {
        val insideKnownBin = volumeRoots.isNotEmpty() && isInsideVolumeBin(path, volumeRoots)
        if (!insideKnownBin && !isUnmountedBinPayload(path, volumeRoots)) return false
        return runCatching {
            java.nio.file.Files.isSymbolicLink(java.io.File(path).toPath())
        }.getOrDefault(false)
    }

    /** `/.xfiles-trash/files/<id>/...` that no mounted volume has claimed. */
    internal fun isUnmountedBinPayload(path: String, volumeRoots: List<String>): Boolean {
        if (volumeRoots.any { isInside(it, path) }) return false
        val normalized = norm(path)
        val idx = indexOfBin(normalized, "/files/")
        if (idx < 0) return false
        val rest = normalized.substring(idx + binFilesTail.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return false
        return isId(rest.substring(0, slash))
    }

    /** True for `<volume>/.xfiles-trash` and everything inside that volume's bin. */
    fun isInsideVolumeBin(path: String, volumeRoots: List<String>): Boolean {
        val normalized = norm(path)
        val idx = indexOfBin(normalized, "")
        if (idx < 0) return false
        val volumePart = normalized.substring(0, idx).ifEmpty { "/" }
        return volumeRoots.any { root -> volumePart.equals(norm(root), ignoreCase = true) }
    }

    /** `files/<id>` id for a path inside a bin, or null. The directory name match ignores case. */
    fun bucketId(path: String): String? {
        val normalized = norm(path)
        val idx = indexOfBin(normalized, "/files/")
        if (idx < 0) return null
        val id = normalized.substring(idx + binFilesTail.length).substringBefore('/')
        return id.takeIf { isId(it) }
    }

    fun binRoot(volumeRoot: String): String {
        val root = norm(volumeRoot)
        return if (root == "/") "/$DIR_NAME" else "$root/$DIR_NAME"
    }

    /**
     * A top-level bin item, or the bucket directory itself (`files/<id>`).
     * The bucket form is what Empty deletes when a bucket holds more than one child.
     */
    fun locate(path: String): TopLevelTrash? {
        topLevel(path)?.let { return it }
        val normalized = norm(path)
        val idx = indexOfBin(normalized, "/files/")
        if (idx < 0) return null
        val rest = normalized.substring(idx + binFilesTail.length)
        if ('/' in rest || !isId(rest)) return null
        val volumeRoot = normalized.substring(0, idx).ifEmpty { "/" }
        val base = normalized.substring(0, idx + 1 + DIR_NAME.length)
        val filesPrefix = normalized.substring(0, idx + binFilesTail.length)
        return TopLevelTrash(
            volumeRoot = volumeRoot,
            id = rest,
            name = "",
            bucketPath = filesPrefix + rest,
            infoPath = "$base/info/$rest.trashinfo",
            storedPath = normalized,
        )
    }

    /** `<volume>/.xfiles-trash/files/<id>/<name>` with no further slash in the name. */
    fun topLevel(path: String): TopLevelTrash? {
        val normalized = norm(path)
        val idx = indexOfBin(normalized, "/files/")
        if (idx < 0) return null
        val volumeRoot = normalized.substring(0, idx).ifEmpty { "/" }
        val base = normalized.substring(0, idx + 1 + DIR_NAME.length)
        val filesPrefix = normalized.substring(0, idx + binFilesTail.length)
        val rest = normalized.substring(idx + binFilesTail.length)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.lastIndex) return null
        val id = rest.substring(0, slash)
        val name = rest.substring(slash + 1)
        if ('/' in name || !isId(id)) return null
        return TopLevelTrash(
            volumeRoot = volumeRoot,
            id = id,
            name = name,
            bucketPath = filesPrefix + id,
            infoPath = "$base/info/$id.trashinfo",
            storedPath = "$filesPrefix$id/$name",
        )
    }

    private const val binFilesTail = "/$DIR_NAME/files/"

    /** Index of `/.xfiles-trash` plus [tail]. A longer name such as `.xfiles-trash-old` does not match. */
    private fun indexOfBin(path: String, tail: String): Int {
        val needle = "/$DIR_NAME$tail"
        var from = 0
        while (from < path.length) {
            val idx = path.indexOf(needle, from, ignoreCase = true)
            if (idx < 0) return -1
            val end = idx + needle.length
            if (tail.endsWith("/") || end == path.length || path.getOrNull(end) == '/') return idx
            from = idx + 1
        }
        return -1
    }

    /**
     * [relative] joined onto [volumeRoot]. Each segment must be a plain name, so a
     * hand-edited sidecar cannot point Restore outside the volume.
     */
    fun resolveUnder(volumeRoot: File, relative: String): File {
        val root = volumeRoot.absoluteFile
        if (relative.isEmpty() || relative == ".") return root
        var current = root
        for (segment in relative.split('/')) {
            requireSafeEntryName(segment)
            current = File(current, segment)
        }
        val rootPath = norm(root.path)
        val result = norm(current.path)
        if (result != rootPath && !result.startsWith("$rootPath/")) {
            throw IOException("Recycle Bin entry points outside its volume")
        }
        return current
    }

    private fun norm(path: String): String = if (path == "/") "/" else path.trimEnd('/')
}

data class TopLevelTrash(
    val volumeRoot: String,
    val id: String,
    val name: String,
    val bucketPath: String,
    val infoPath: String,
    val storedPath: String,
)
