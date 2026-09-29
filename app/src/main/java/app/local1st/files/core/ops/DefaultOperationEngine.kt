package app.local1st.files.core.ops

import app.local1st.files.core.fs.EditorSiblingScan
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.HiddenBin
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.TrashPaths
import app.local1st.files.core.fs.TrashRecord
import app.local1st.files.core.fs.TrashStore
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.requireSafeEntryName
import app.local1st.files.core.util.FileTypes
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val COPY_BUFFER_SIZE = 128 * 1024

/** Case fold for overwrite-all memory. Locale.ROOT so Turkish I/ı does not split one name. */
internal fun restoreBatchPathKey(path: String): String =
    path.trimEnd('/').lowercase(Locale.ROOT)

/** Minimum interval between StateFlow progress publications (~10 updates/s). */
private const val PUBLISH_INTERVAL_NANOS = 100_000_000L

/** Characters not allowed in FAT/exFAT filenames; an app's label becomes the copied file's name. */
private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

/** Shared empty stream for zip directory entries. */
private val EMPTY_INPUT: InputStream get() = object : InputStream() {
    override fun read(): Int = -1
}

/**
 * Executes [FileOp]s, one coroutine per op on [Dispatchers.IO].
 *
 * Scheme routing rules used throughout:
 *  - `registry.forEntry(e)` resolves the fs used to browse *into* `e` (an ARCHIVE file
 *    routes to the zip fs), so it is used for `list`/`mkdir`/`openOut` on containers.
 *  - `registry.forScheme(e.scheme)` resolves the fs that owns `e` *as a node*, so it is
 *    used for `openIn`/`delete`: copying an ARCHIVE file streams its raw bytes via the
 *    file fs, while a `zip://` source (an entry inside an archive) streams decompressed
 *    content via the zip fs.
 */
class DefaultOperationEngine(
    private val scope: CoroutineScope,
    private val registry: FsRegistry,
    private val cacheDir: File,
    private val trash: TrashStore = TrashStore(volumes = { emptyList() }),
) : OperationEngine {

    private val nextId = AtomicLong(1L)

    private val _active = MutableStateFlow<List<RunningOp>>(emptyList())
    override val active: StateFlow<List<RunningOp>> = _active

    private val _events = MutableSharedFlow<OpEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<OpEvent> = _events

    override fun submit(op: FileOp): RunningOp {
        val running = RunningOpImpl(nextId.getAndIncrement(), titleFor(op))
        // LAZY start so `running.job` is assigned before the coroutine can observe it.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            execute(op, running)
        }
        running.job = job
        _active.update { it + running }
        job.start()
        return running
    }

    private suspend fun execute(op: FileOp, running: RunningOpImpl) {
        val dirty = LinkedHashSet<String>()
        try {
            val message = when (op) {
                is FileOp.Copy -> runCopy(op, running, dirty)
                is FileOp.Delete -> runDelete(op, running, dirty)
                is FileOp.Restore -> runRestore(op, running, dirty)
                is FileOp.Compress -> runCompress(op, running, dirty)
                is FileOp.Extract -> runExtract(op, running, dirty)
            }
            running.finish(OpState.DONE)
            _events.tryEmit(OpEvent(message, success = true, dirtyDirIds = dirty.toSet()))
        } catch (e: CancellationException) {
            running.finish(OpState.CANCELLED)
            _events.tryEmit(
                OpEvent("${verbFor(op)} cancelled", success = false, dirtyDirIds = dirty.toSet()),
            )
            throw e
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            running.finish(OpState.FAILED, error = reason)
            _events.tryEmit(
                OpEvent(
                    "${verbFor(op)} failed: $reason",
                    success = false,
                    dirtyDirIds = dirty.toSet(),
                ),
            )
        } finally {
            _active.update { list -> list.filterNot { it === running } }
        }
    }

    // ----------------------------------------------------------------- Copy / Move

    private suspend fun runCopy(
        op: FileOp.Copy,
        t: RunningOpImpl,
        dirty: MutableSet<String>,
    ): String {
        val destDir = op.destDir
        val destFs = registry.forEntry(destDir)
        // Destination names listed once; names we write are added to the set as we go,
        // which is equivalent to re-listing after each write for conflict purposes.
        // Case-insensitive for local storage (FAT/emulated) so conflicts aren't missed.
        val destNames = nameSetFor(destDir)
        destNames += destFs.list(destDir).map { it.name }
        // Listings hide this name. A fast rename onto it would become the live bin.
        if (isConcealedBinName(destDir, TrashPaths.DIR_NAME)) destNames += TrashPaths.DIR_NAME
        dirty += destDir.id

        // Guard: never copy/move a container into itself or one of its own descendants;
        // that recurses on a live listing and fills the disk with nested copies.
        val validSources = op.sources.filterNot { isSelfOrInside(destDir, it) }
        if (op.move) {
            validSources.firstOrNull { !canMoveSource(it) }?.let { blocked ->
                throw IOException("Cannot move ${blocked.name}")
            }
        }
        val rejected = op.sources.size - validSources.size

        // Move fast path: same-scheme local rename covers instant same-volume moves.
        // Only attempted when the target name is free; conflicts take the slow path
        // so the user still gets the conflict dialog.
        var movedFast = 0
        val pending = ArrayList<XEntry>(validSources.size)
        val siblings = EditorSiblingScan()
        // Copy, share, and open read these paths. A cut-off save's ready sibling is the
        // real bytes. One listing per source folder, not one per source.
        markRecovered(trash.recoverTruncatedEdits(validSources.map { it.localPath ?: it.path }), dirty)
        for (src in validSources) {
            if (op.move && tryFastRename(src, destDir, destNames)) {
                movedFast++
                XId.parent(src.id)?.let(dirty::add)
                forgetTrash(src, dirty)
            } else {
                pending += src
            }
        }

        // Installed apps copy out as an installable package: a single .apk, or every split
        // bundled into one .apks. Resolve each app's APKs once here so the scan total, the
        // output name, and the copy itself all agree on what's being written.
        val appExports = HashMap<String, AppExport>()
        val perSource = pending.map { src ->
            if (src.kind == EntryKind.APP) {
                val export = appExportFor(src).also { appExports[src.id] = it }
                ScanTotals(export.totalBytes, 1)
            } else {
                scanTree(src, t, carryBin = op.move)
            }
        }
        t.setTotals(
            totalBytes = perSource.sumOf { it.bytes },
            totalItems = perSource.sumOf { it.items } + movedFast,
        )
        if (movedFast > 0) t.itemsDone(movedFast)
        t.setState(OpState.RUNNING)

        var remembered: ConflictChoice? = null
        var processed = movedFast
        for ((i, src) in pending.withIndex()) {
            t.ensureActive()
            var name = appExports[src.id]?.name ?: src.name
            if (isConcealedBinName(destDir, name)) {
                name = uniqueName(name, src.isDir, destNames)
            }
            var displaced: TrashRecord? = null
            var copiedApp = false
            try {
                if (name in destNames) {
                    val choice = remembered ?: t.awaitConflict(Conflict(src, name)).let { res ->
                        if (res.applyToAll) remembered = res.choice
                        res.choice
                    }
                    when (choice) {
                        ConflictChoice.SKIP -> {
                            t.skipItems(perSource[i])
                            continue
                        }
                        ConflictChoice.OVERWRITE -> {
                            // Overwriting a dir replaces it wholesale, not per child.
                            // Overwriting the source itself would destroy the bytes we
                            // are about to read, so skip instead.
                            val existing = childNamed(destDir, name)
                            if (existing != null && isSelfOrInside(src, existing)) {
                                t.skipItems(perSource[i])
                                continue
                            }
                            if (existing != null) displaced = displaceForOverwrite(existing, dirty)
                        }
                        ConflictChoice.RENAME -> name = uniqueName(name, src.isDir, destNames)
                    }
                }
                val export = appExports[src.id]
                if (export != null) {
                    copyAppPackage(src, destDir, name, export, t)
                    copiedApp = true
                } else {
                    copyTree(src, destDir, name, t, carryBin = op.move)
                }
                if (displaced != null) {
                    try {
                        trash.purge(displaced)
                    } catch (_: IOException) {
                        // The replacement is already in place. The occupant stays recoverable.
                    }
                    dirty += XId.TRASH_ROOT
                }
            } catch (cancelled: CancellationException) {
                undoOverwrite(displaced, destDir, name)
                throw cancelled
            } catch (failure: Exception) {
                undoOverwrite(displaced, destDir, name)
                throw failure
            }
            destNames += name
            // An app isn't a real file on a writable fs — there's nothing to remove after a "move".
            if (op.move && !copiedApp) {
                deleteSource(src, siblings)
                forgetTrash(src, dirty)
                XId.parent(src.id)?.let(dirty::add)
            }
            processed++
        }
        return when {
            rejected > 0 && processed == 0 -> "Cannot copy a folder into itself"
            rejected > 0 -> "${if (op.move) "Moved" else "Copied"} ${countLabel(processed)} ($rejected skipped)"
            else -> "${if (op.move) "Moved" else "Copied"} ${countLabel(processed)}"
        }
    }

    private fun tryFastRename(
        src: XEntry,
        destDir: XEntry,
        destNames: MutableSet<String>,
    ): Boolean {
        if (src.scheme != XId.SCHEME_FILE || destDir.scheme != XId.SCHEME_FILE) return false
        if (destDir.kind == EntryKind.ARCHIVE) return false
        if (src.name in destNames) return false
        // Sidecar display names are not filesystem names and may contain a slash.
        if (!isSafeFastRenameName(src.name)) return false
        val renamed = File(src.path).renameTo(File(destDir.path, src.name))
        if (renamed) destNames += src.name
        return renamed
    }

    private fun copyTree(
        src: XEntry,
        destParent: XEntry,
        name: String,
        t: RunningOpImpl,
        carryBin: Boolean,
    ) {
        // A bin row's display name can contain a slash. Joining it would escape destParent.
        if (!isSafeFastRenameName(name)) throw IOException("Invalid name: $name")
        t.ensureActive()
        t.current(src.name)
        if (src.isDir) {
            val newDir = registry.forEntry(destParent).mkdir(destParent, name)
            t.itemsDone(1)
            for (child in childrenForTransfer(src, carryBin)) {
                copyTree(child, newDir, child.name, t, carryBin)
            }
        } else {
            copyFile(src, destParent, name, t)
            t.itemsDone(1)
        }
    }

    private fun copyFile(src: XEntry, destParent: XEntry, name: String, t: RunningOpImpl) {
        var completed = false
        var created = false
        try {
            if (copyVolumeBinSymlink(src, destParent, name)) {
                completed = true
                return
            }
            created = true
            registry.forScheme(src.scheme).openIn(src).use { input ->
                registry.forEntry(destParent).openOut(destParent, name).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        t.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        t.bytesDone(n.toLong())
                    }
                }
            }
            completed = true
        } finally {
            // A bin-symlink copy that fails before the link exists must not delete
            // the name overwrite already removed. A partial file copy still does.
            if (!completed && created) runCatching { deleteChildIfExists(destParent, name) }
        }
    }

    /**
     * Copies the link node. [LocalFileSystem.openIn] refuses a bin symlink because
     * a stream would be the live target, and that refusal used to abort the whole tree.
     */
    private fun copyVolumeBinSymlink(src: XEntry, destParent: XEntry, name: String): Boolean {
        if (src.scheme != XId.SCHEME_FILE || destParent.scheme != XId.SCHEME_FILE) return false
        if (!isSafeFastRenameName(name)) return false
        val roots = trash.mountedVolumes().map { it.rootPath }
        if (!TrashPaths.isVolumeBinSymlink(src.path, roots)) return false
        val dest = File(destParent.localPath ?: destParent.path, name).toPath()
        // exists() stays true after a successful SD/USB delete. Replace that node
        // instead of failing and leaving the overwritten name gone.
        Files.copy(
            File(src.path).toPath(),
            dest,
            LinkOption.NOFOLLOW_LINKS,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return true
    }

    // ----------------------------------------------------------------- App package

    /**
     * Copies an installed app out as an *installable* package: a single `.apk` when the app has
     * no splits, or all of its APKs (base + splits) bundled into one `.apks` when it does — a
     * lone base APK of a split app can't be installed on its own. To grab just one split, the
     * user expands the app and copies that APK explicitly (an ordinary file copy).
     */
    private fun copyAppPackage(
        app: XEntry,
        destParent: XEntry,
        name: String,
        export: AppExport,
        t: RunningOpImpl,
    ) {
        t.ensureActive()
        t.current(app.name)
        if (export.apks.isEmpty()) throw IOException("No APK found for '${app.name}'")
        val destFs = registry.forEntry(destParent)
        var completed = false
        try {
            destFs.openOut(destParent, name).use { out ->
                if (export.bundled) writeApksBundle(export, out, t) else streamApk(export.apks.first(), out, t)
            }
            completed = true
        } finally {
            if (!completed) runCatching { deleteChildIfExists(destParent, name) }
        }
        t.itemsDone(1)
    }

    private fun streamApk(apk: File, out: OutputStream, t: RunningOpImpl) {
        FileInputStream(apk).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                t.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                t.bytesDone(n.toLong())
            }
        }
    }

    /** Bundles all of an app's APKs into one `.apks` zip (STORED — APKs don't recompress). */
    private fun writeApksBundle(export: AppExport, out: OutputStream, t: RunningOpImpl) {
        val offset = t.doneBytesSnapshot()
        val sources = export.apks.map { apk ->
            ZipTurbo.ZipSource(apk.name, isDir = false, apk.lastModified()) { FileInputStream(apk) }
        }
        val done = AtomicLong(0)
        val publisher = scope.launch { while (isActive) { t.setBytesDone(offset + done.get()); delay(120) } }
        // Parallel scatter buffers to cacheDir; fall back to sequential when temp space is tight.
        val useParallel = export.totalBytes < cacheDir.usableSpace
        try {
            if (useParallel) {
                ZipTurbo.createZip(sources, out, cacheDir, onBytes = { done.set(it) }, isCancelled = { !t.isActive() })
            } else {
                ZipTurbo.createZipSequential(sources, out, onBytes = { done.set(it) }, isCancelled = { !t.isActive() })
            }
        } catch (e: Exception) {
            if (!t.isActive()) throw CancellationException("cancelled")
            throw e
        } finally {
            publisher.cancel()
        }
        t.setBytesDone(offset + done.get())
    }

    /**
     * Resolves how [app] copies out: the APK files backing it (base first, then splits) and the
     * export filename. Names it after the app's label so copies don't all land as `base.apk`.
     */
    private fun appExportFor(app: XEntry): AppExport {
        val apks = runCatching { registry.forEntry(app).list(app) }
            .getOrDefault(emptyList())
            .filter { !it.isDir && it.extension == "apk" }
            .mapNotNull { it.localPath?.let(::File)?.takeIf(File::isFile) }
            .ifEmpty { listOfNotNull(app.localPath?.let(::File)?.takeIf(File::isFile)) }
        val label = sanitizeFileName(app.name)
            .ifBlank { app.path.substringAfterLast('/').ifBlank { "app" } }
        val ext = if (apks.size > 1) "apks" else "apk"
        return AppExport("$label.$ext", apks, apks.sumOf { it.length() })
    }

    private fun sanitizeFileName(name: String): String =
        ILLEGAL_FILENAME_CHARS.replace(name, "_").trim().trimEnd('.', ' ')

    // ----------------------------------------------------------------- Delete

    private fun runDelete(op: FileOp.Delete, t: RunningOpImpl, dirty: MutableSet<String>): String {
        // A trash is one rename, so don't walk the tree just to count it.
        val trashMounted = if (op.permanent) null else trash.mountedVolumes()
        val toBin = op.sources.map { src -> trashMounted != null && trash.canTrash(src, trashMounted) }
        val perSource = op.sources.mapIndexed { i, src -> if (toBin[i]) 1 else countTree(src, t) }
        t.setTotals(totalBytes = 0L, totalItems = perSource.sum())
        t.setState(OpState.RUNNING)
        // Merge cut-off saves for the whole selection first: one listing per folder
        // instead of one per file, and nothing has moved yet if a merge fails. A folder's
        // ready copies are inside it and move with it, so folders are not walked.
        trash.recoverTruncatedEdits(
            op.sources.filterIndexed { i, src -> toBin[i] && !src.isDir }.map { it.localPath ?: it.path },
        )
        var deleted = 0
        var trashed = 0
        val siblings = EditorSiblingScan()
        for ((i, src) in op.sources.withIndex()) {
            t.ensureActive()
            t.current(src.name)
            if (toBin[i]) {
                trashMoved(src, dirty, recoverEdits = false)
                trashed += perSource[i]
            } else if (trashLate(op, src)) {
                // The mount list used to count the tree can miss a volume that
                // finishes mounting during that walk. Do not unlink it.
                trashMoved(src, dirty)
                trashed += 1
            } else {
                if (trash.blocksUnresolved(src)) throw IOException("Storage is still mounting")
                // The dialog already promised a bin move. A later read-only or
                // settled mount must not turn that into an unlink.
                if (op.mustTrash || src.id in op.trashableIds) {
                    throw IOException("Cannot move ${src.name} to the Recycle Bin")
                }
                val path = src.localPath ?: src.path
                val claimed = trash.claimBinBytes(path)
                try {
                    deleteSource(src, siblings)
                    forgetTrash(src, dirty)
                } finally {
                    if (claimed != null) trash.releaseBinBytes(claimed)
                }
                deleted += perSource[i]
            }
            XId.parent(src.id)?.let(dirty::add)
            t.itemsDone(perSource[i])
        }
        return when {
            trashed > 0 && deleted == 0 -> "Moved ${countLabel(trashed)} to the Recycle Bin"
            trashed > 0 -> "Moved ${countLabel(trashed)} to the Recycle Bin, deleted ${countLabel(deleted)}"
            else -> "Deleted ${countLabel(deleted)}"
        }
    }

    private suspend fun runRestore(
        op: FileOp.Restore,
        t: RunningOpImpl,
        dirty: MutableSet<String>,
    ): String {
        t.setTotals(totalBytes = 0L, totalItems = op.sources.size)
        t.setState(OpState.RUNNING)
        var restored = 0
        var skipped = 0
        var remembered: ConflictChoice? = null
        // Paths this batch already put back. Overwrite-all must not trash those and then
        // report every item restored.
        val restoredHere = HashSet<String>()
        for (src in op.sources) {
            t.ensureActive()
            t.current(src.name)
            val path = src.localPath ?: src.path
            val record = trash.lookup(path) ?: throw IOException("Cannot restore ${src.name}")
            // A symlink parent must fail before overwrite trashes whatever the link points at.
            if (!trash.restoreTargetIsReal(record)) {
                throw IOException("Cannot restore ${src.name}")
            }
            val planned = trash.plannedParent(record)
            val parentEntry = XEntry(
                id = XId.file(planned.absolutePath),
                name = planned.name.ifEmpty { planned.absolutePath },
                isDir = true,
                kind = EntryKind.DIR,
                localPath = planned.absolutePath,
            )
            val destNames = nameSetFor(parentEntry)
            // File.list misses children that only the stored grant names. list() unions them.
            val listed = runCatching {
                registry.forEntry(parentEntry).list(parentEntry).map { it.name }
            }.getOrNull()
            if (listed != null) destNames.addAll(listed)
            else if (planned.isDirectory) planned.list()?.let(destNames::addAll)
            val parentKey = pathKey(planned.absolutePath)
            for (restoredPath in restoredHere) {
                val prefix = "$parentKey/"
                if (!restoredPath.startsWith(prefix)) continue
                val rest = restoredPath.removePrefix(prefix)
                if ('/' !in rest) destNames += rest
            }
            var name = record.name
            // The file currently at the original name. Put it in the bin first so a failed
            // restore can move it back; never unlink it.
            var displaced: TrashRecord? = null
            try {
                if (name in destNames) {
                    val choice = remembered ?: t.awaitConflict(Conflict(src, name)).let { res ->
                        if (res.applyToAll) remembered = res.choice
                        res.choice
                    }
                    when (choice) {
                        ConflictChoice.SKIP -> {
                            skipped++
                            t.itemsDone(1)
                            continue
                        }
                        ConflictChoice.OVERWRITE -> {
                            val existing = childNamed(parentEntry, name)
                                ?: symlinkOccupant(parentEntry, name)
                            val occupantPath = existing?.let { it.localPath ?: it.path }
                            val claimed = File(planned, name).absolutePath
                            // stat can miss a file this batch just wrote via SAF.
                            if (restoredHereCovers(claimed, restoredHere) ||
                                (occupantPath != null && restoredHereCovers(occupantPath, restoredHere))
                            ) {
                                name = uniqueName(name, src.isDir, destNames)
                            } else if (existing != null) {
                                displaced = trash.trash(existing)
                                dirty += XId.TRASH_ROOT
                                // Restore may still fail. The folder already lost this name.
                                dirty += parentEntry.id
                            }
                        }
                        ConflictChoice.RENAME -> name = uniqueName(name, src.isDir, destNames)
                    }
                }
                val parent = trash.restoreParent(record)
                // nameSetFor already folded case. The listing spelling can still differ.
                val vacated = displaced != null && name.equals(displaced.name, ignoreCase = true)
                trash.restore(record, name, vacated = vacated)
                restoredHere += pathKey(File(parent, name).absolutePath)
                restored++
                dirty += XId.TRASH_ROOT
                ancestorIds(parent, record.volumeRoot).forEach(dirty::add)
                t.itemsDone(1)
            } catch (cancelled: CancellationException) {
                restoreDisplaced(displaced)
                throw cancelled
            } catch (failure: Exception) {
                restoreDisplaced(displaced)
                throw failure
            }
        }
        return if (skipped > 0) {
            "Restored ${countLabel(restored)} ($skipped skipped)"
        } else {
            "Restored ${countLabel(restored)}"
        }
    }

    private fun pathKey(path: String): String = restoreBatchPathKey(path)

    /** True when this batch already restored [occupantPath] or a file inside it. */
    private fun restoredHereCovers(occupantPath: String, restoredHere: Set<String>): Boolean {
        val key = pathKey(occupantPath)
        val prefix = "$key/"
        return restoredHere.any { it == key || it.startsWith(prefix) }
    }

    /** The destination folder, created when the original parent was removed with the file. */
    private fun ancestorIds(parent: File, volumeRoot: String): List<String> {
        val ids = ArrayList<String>()
        var cursor: File? = parent
        while (cursor != null) {
            ids += XId.file(cursor.absolutePath)
            if (TrashPaths.samePath(cursor.path, volumeRoot)) break
            cursor = cursor.parentFile
        }
        return ids
    }

    /** Best-effort count for progress scaling; real errors surface from delete itself. */
    private fun countTree(entry: XEntry, t: RunningOpImpl): Int {
        t.ensureActive()
        var count = 1
        if (entry.isDir) {
            t.current(entry.name)
            val children = try {
                registry.forEntry(entry).list(entry)
            } catch (_: IOException) {
                emptyList()
            }
            for (child in children) count += countTree(child, t)
        }
        return count
    }

    // ----------------------------------------------------------------- Compress

    private suspend fun runCompress(
        op: FileOp.Compress,
        t: RunningOpImpl,
        dirty: MutableSet<String>,
    ): String {
        val destDir = op.destDir
        val destFs = registry.forEntry(destDir)
        val destNames = nameSetFor(destDir)
        destNames += destFs.list(destDir).map { it.name }
        // Listings hide this name. Cleanup must not treat a failed zip as that directory.
        if (isConcealedBinName(destDir, TrashPaths.DIR_NAME)) destNames += TrashPaths.DIR_NAME
        dirty += destDir.id
        markRecovered(trash.recoverTruncatedEdits(op.sources.map { it.localPath ?: it.path }), dirty)

        val perSource = op.sources.map { scanTree(it, t, carryBin = false) }
        val totalBytes = perSource.sumOf { it.bytes }
        t.setTotals(
            totalBytes = totalBytes,
            totalItems = perSource.sumOf { it.items },
        )

        var archiveName = op.archiveName
        if (isConcealedBinName(destDir, archiveName)) {
            archiveName = uniqueName(archiveName, isDir = false, taken = destNames)
        }
        if (archiveName in destNames) {
            val conflictSource = op.sources.firstOrNull() ?: destDir
            val resolution = t.awaitConflict(Conflict(conflictSource, archiveName))
            when (resolution.choice) {
                // Skipping the only output means there is nothing left to do.
                ConflictChoice.SKIP -> throw CancellationException("Skipped by user")
                ConflictChoice.OVERWRITE -> deleteChildIfExists(destDir, archiveName)
                ConflictChoice.RENAME ->
                    archiveName = uniqueName(archiveName, isDir = false, taken = destNames)
            }
        }
        t.setState(OpState.RUNNING)
        t.current(archiveName)

        // The output archive lives in destDir; if a source folder contains destDir we must
        // not zip the archive into itself (read-while-write loop until the disk fills).
        val outputId = XId.child(destDir, archiveName)
        val sources = ArrayList<ZipTurbo.ZipSource>()
        for (src in op.sources) collectZipSources(src, src.name, outputId, sources)

        // The parallel scatter creator buffers compressed data in cacheDir; when there isn't
        // enough temp space, stream sequentially instead of risking a full internal disk.
        val useParallel = totalBytes < cacheDir.usableSpace

        val done = java.util.concurrent.atomic.AtomicLong(0)
        val publisher = scope.launch { while (isActive) { t.setBytesDone(done.get()); delay(120) } }
        var completed = false
        try {
            destFs.openOut(destDir, archiveName).use { rawOut ->
                if (useParallel) {
                    ZipTurbo.createZip(
                        sources = sources,
                        out = rawOut,
                        cacheDir = cacheDir,
                        onBytes = { done.set(it) },
                        isCancelled = { !t.isActive() },
                    )
                } else {
                    ZipTurbo.createZipSequential(
                        sources = sources,
                        out = rawOut,
                        onBytes = { done.set(it) },
                        isCancelled = { !t.isActive() },
                    )
                }
            }
            completed = true
        } catch (e: Exception) {
            if (!t.isActive()) throw CancellationException("cancelled")
            throw e
        } finally {
            publisher.cancel()
            if (!completed) runCatching { deleteChildIfExists(destDir, archiveName) }
        }
        t.setBytesDone(done.get())
        return "Created $archiveName"
    }

    /** Flattens a source subtree into [ZipTurbo.ZipSource]s with archive-relative paths. */
    private fun collectZipSources(
        entry: XEntry,
        relPath: String,
        outputId: String,
        out: MutableList<ZipTurbo.ZipSource>,
    ) {
        if (entry.id == outputId) return
        if (entry.isDir) {
            out += ZipTurbo.ZipSource(relPath, isDir = true, entry.mtime) { EMPTY_INPUT }
            for (child in childrenForTransfer(entry, carryBin = false)) {
                collectZipSources(child, "$relPath/${child.name}", outputId, out)
            }
        } else {
            val fs = registry.forScheme(entry.scheme)
            out += ZipTurbo.ZipSource(relPath, isDir = false, entry.mtime) { fs.openIn(entry) }
        }
    }

    // ----------------------------------------------------------------- Extract

    private suspend fun runExtract(
        op: FileOp.Extract,
        t: RunningOpImpl,
        dirty: MutableSet<String>,
    ): String {
        val archive = op.archive
        val destDir = op.destDir
        dirty += destDir.id

        val archiveFile = archive.localPath?.let(::File)
        val zipFamily =
            archive.extension in setOf("zip", "jar", "apk", "aab") + FileTypes.apkBundleExtensions

        // Fast path: parallel multi-handle extraction to a local directory.
        val directLocalWrites = (registry.forScheme(XId.SCHEME_FILE) as? LocalFileSystem)
            ?.supportsDirectBulkWrites(destDir) ?: true
        if (zipFamily && archiveFile != null && archiveFile.isFile &&
            destDir.scheme == XId.SCHEME_FILE && directLocalWrites
        ) {
            val destFile = File(destDir.path)
            if (!destFile.isDirectory && !destFile.mkdirs()) {
                throw IOException("Cannot create ${destDir.name}")
            }
            t.current(archive.name)
            t.setTotals(totalBytes = ZipTurbo.totalUncompressed(archiveFile), totalItems = 0)
            t.setState(OpState.RUNNING)

            val done = java.util.concurrent.atomic.AtomicLong(0)
            val publisher = scope.launch { while (isActive) { t.setBytesDone(done.get()); delay(120) } }
            try {
                ZipTurbo.extractZip(
                    archiveFile = archiveFile,
                    destDir = destFile,
                    onBytes = { done.set(it) },
                    isCancelled = { !t.isActive() },
                )
            } catch (e: Exception) {
                if (!t.isActive()) throw CancellationException("cancelled")
                throw e
            } finally {
                publisher.cancel()
            }
            t.setBytesDone(done.get())
            return "Extracted ${archive.name}"
        }

        // Fallback: sequential extraction (7z/tar/rar, or non-local destination).
        val archiveRoot = archive.copy(kind = EntryKind.ARCHIVE)
        val children = registry.forEntry(archiveRoot).list(archiveRoot)
        val perSource = children.map { scanTree(it, t, carryBin = false) }
        t.setTotals(perSource.sumOf { it.bytes }, perSource.sumOf { it.items })
        t.setState(OpState.RUNNING)
        val taken = nameSetFor(destDir)
        taken += registry.forEntry(destDir).list(destDir).map { it.name }
        if (isConcealedBinName(destDir, TrashPaths.DIR_NAME)) taken += TrashPaths.DIR_NAME
        for (child in children) {
            val childName = if (isConcealedBinName(destDir, child.name)) {
                uniqueName(child.name, child.isDir, taken).also { taken += it }
            } else {
                child.name
            }
            copyTree(child, destDir, childName, t, carryBin = false)
        }
        return "Extracted ${archive.name}"
    }

    // ----------------------------------------------------------------- Shared helpers

    /**
     * Recursively totals a source subtree. Dirs are entered via [FsRegistry.forEntry];
     * an ARCHIVE file is *not* entered (`isDir == false`) — it is copied as raw bytes,
     * so its on-disk size is what counts. Entries inside an archive (`zip://` ids)
     * report decompressed sizes, matching what their [app.local1st.files.core.fs.XFileSystem.openIn] streams.
     */
    private fun scanTree(entry: XEntry, t: RunningOpImpl, carryBin: Boolean): ScanTotals {
        val totals = ScanTotals()
        scanInto(entry, totals, t, carryBin)
        return totals
    }

    private fun scanInto(entry: XEntry, totals: ScanTotals, t: RunningOpImpl, carryBin: Boolean) {
        t.ensureActive()
        totals.items++
        if (entry.isDir) {
            t.current(entry.name)
            for (child in childrenForTransfer(entry, carryBin)) {
                scanInto(child, totals, t, carryBin)
            }
        } else {
            totals.bytes += entry.size.coerceAtLeast(0L)
        }
    }

    /**
     * Listings hide a volume's `.xfiles-trash`. Only a move deletes the source,
     * so only a move copies the bin; copy and zip would put deleted files back.
     */
    private fun childrenForTransfer(dir: XEntry, carryBin: Boolean): List<XEntry> {
        val listed = registry.forEntry(dir).list(dir)
        if (!carryBin) return listed
        val bin = volumeBinChild(dir) ?: return listed
        if (listed.any { it.name == TrashPaths.DIR_NAME }) return listed
        return listed + bin
    }

    private fun volumeBinChild(dir: XEntry): XEntry? {
        if (!dir.isDir) return null
        if (dir.scheme != XId.SCHEME_FILE && dir.scheme != XId.SCHEME_ROOT) return null
        val path = dir.localPath ?: dir.path
        val volume = trash.mountedVolumes().firstOrNull { TrashPaths.samePath(it.rootPath, path) }
            ?: return null
        val binPath = TrashPaths.binRoot(volume.rootPath)
        when (trash.hiddenBin(volume.rootPath)) {
            HiddenBin.ABSENT -> return null
            // File cannot see a grant-only bin, and neither can the grant listing.
            // Copying nothing and then deleting the source would drop the only copies.
            HiddenBin.UNKNOWN -> throw IOException("Cannot move ${dir.name}")
            HiddenBin.PRESENT -> Unit
        }
        return XEntry(
            id = "${dir.scheme}://$binPath",
            name = TrashPaths.DIR_NAME,
            isDir = true,
            kind = EntryKind.DIR,
            localPath = if (dir.scheme == XId.SCHEME_FILE) binPath else null,
        )
    }

    /** True when [name] is the hidden bin of the volume [parent] itself. */
    private fun isConcealedBinName(parent: XEntry, name: String): Boolean {
        if (parent.scheme != XId.SCHEME_FILE && parent.scheme != XId.SCHEME_ROOT) return false
        // Root volumes can be case-insensitive too. An exact compare still hits the live bin.
        if (!name.equals(TrashPaths.DIR_NAME, ignoreCase = true)) return false
        val parentPath = parent.localPath ?: parent.path
        return trash.mountedVolumes().any { TrashPaths.samePath(it.rootPath, parentPath) }
    }

    /** Name set for a destination dir; case-insensitive for local storage. */
    private fun nameSetFor(destDir: XEntry): MutableSet<String> =
        if (destDir.scheme == XId.SCHEME_FILE) java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
        else HashSet()

    /** True when [ancestor] is [container] itself or an ancestor of it (scheme-aware). */
    private fun isSelfOrInside(container: XEntry, ancestor: XEntry): Boolean {
        var cur: String? = container.id
        while (cur != null) {
            if (cur == ancestor.id) return true
            cur = XId.parent(cur)
        }
        return false
    }

    private fun deleteChildIfExists(parentDir: XEntry, name: String) {
        if (!isSafeFastRenameName(name)) return
        // stat still sees the hidden bin. Deleting that name would empty the recycle bin.
        if (isConcealedBinName(parentDir, name)) return
        val existing = childNamed(parentDir, name) ?: return
        registry.forScheme(existing.scheme).delete(existing)
    }

    /**
     * Destination child for [name]. SAF ids are provider document ids, not display names, so
     * [XId.child] cannot be used to look them up.
     */
    /** A dangling symlink is invisible to [stat] and [File.exists], but it still occupies the name. */
    private fun symlinkOccupant(parentDir: XEntry, name: String): XEntry? {
        if (parentDir.scheme != XId.SCHEME_FILE) return null
        val file = java.io.File(parentDir.localPath ?: parentDir.path, name)
        if (!java.nio.file.Files.isSymbolicLink(file.toPath())) return null
        return XEntry(
            id = XId.file(file.absolutePath),
            name = name,
            isDir = false,
            kind = EntryKind.FILE,
            localPath = file.absolutePath,
        )
    }

    private fun childNamed(parentDir: XEntry, name: String): XEntry? {
        if (parentDir.scheme == XId.SCHEME_SAF || parentDir.scheme == XId.SCHEME_FILE) {
            val listed = runCatching { registry.forEntry(parentDir).list(parentDir) }.getOrNull()
            listed?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        }
        val childId = XId.child(parentDir, name)
        return registry.forId(childId).stat(childId)
    }

    private fun uniqueName(name: String, isDir: Boolean, taken: Set<String>): String {
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    private fun titleFor(op: FileOp): String = when (op) {
        is FileOp.Copy ->
            "${if (op.move) "Moving" else "Copying"} ${countLabel(op.sources.size)}"
        is FileOp.Delete ->
            if (deleteGoesToBin(op))
                "Moving ${countLabel(op.sources.size)} to the Recycle Bin"
            else "Deleting ${countLabel(op.sources.size)}"
        is FileOp.Restore -> "Restoring ${countLabel(op.sources.size)}"
        is FileOp.Compress -> "Creating ${op.archiveName}"
        is FileOp.Extract -> "Extracting ${op.archive.name}"
    }

    private fun verbFor(op: FileOp): String = when (op) {
        is FileOp.Copy -> if (op.move) "Move" else "Copy"
        is FileOp.Delete -> if (deleteGoesToBin(op)) "Move" else "Delete"
        is FileOp.Restore -> "Restore"
        is FileOp.Compress -> "Compress"
        is FileOp.Extract -> "Extract"
    }

    /** True when this delete is a bin move, including a confirm that forbids unlinking. */
    private fun deleteGoesToBin(op: FileOp.Delete): Boolean =
        !op.permanent && (op.mustTrash || deletesToBin(op))

    /** A move or permanent delete of a top-level bin item drops its restore record. */
    private fun forgetTrash(entry: XEntry, dirty: MutableSet<String>) {
        if (trash.noteRemoved(entry.localPath ?: entry.path)) dirty += XId.TRASH_ROOT
    }

    /**
     * Park a replaceable occupant in the bin so a failed copy can put it back.
     * A destination that cannot be trashed is still removed permanently.
     * A pending mount can look read-only after the card is writable; deleting
     * then skips the bin, so that case fails and leaves the occupant in place.
     */
    private fun displaceForOverwrite(existing: XEntry, dirty: MutableSet<String>): TrashRecord? {
        if (trash.canTrash(existing)) {
            val record = trash.trash(existing)
            dirty += XId.TRASH_ROOT
            XId.parent(existing.id)?.let(dirty::add)
            return record
        }
        if (trash.blocksUnresolved(existing)) throw IOException("Storage is still mounting")
        registry.forScheme(existing.scheme).delete(existing)
        forgetTrash(existing, dirty)
        return null
    }

    /** Drop a partial replacement, then return the occupant the failed copy displaced. */
    private fun undoOverwrite(displaced: TrashRecord?, destDir: XEntry, name: String) {
        if (displaced == null) return
        val partial = childNamed(destDir, name)
        if (partial != null) {
            val partialPath = partial.localPath ?: partial.path
            if (!TrashPaths.isInsideVolumeBin(partialPath, listOf(displaced.volumeRoot))) {
                runCatching { registry.forScheme(partial.scheme).delete(partial) }
            }
        }
        restoreDisplaced(displaced)
    }

    /** Moves an overwritten occupant back to the name restore failed to claim. */
    private fun restoreDisplaced(displaced: TrashRecord?) {
        if (displaced == null) return
        try {
            trash.restore(displaced, displaced.name, vacated = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The occupant is still a bin record, so the newer bytes stay recoverable.
        }
    }

    /**
     * Marks the source and the bin dirty even when [TrashStore.trash] throws
     * after the bytes have already left the folder.
     */
    private fun trashMoved(src: XEntry, dirty: MutableSet<String>, recoverEdits: Boolean = true) {
        try {
            trash.trash(src, recoverEdits)
        } finally {
            dirty += XId.TRASH_ROOT
            XId.parent(src.id)?.let(dirty::add)
        }
    }

    /** Local deletes of one operation share [siblings]: each folder is listed once. */
    private fun deleteSource(src: XEntry, siblings: EditorSiblingScan) {
        val fs = registry.forScheme(src.scheme)
        if (fs is LocalFileSystem) fs.delete(src, siblings) else fs.delete(src)
    }

    /**
     * A merged save changed a source's size and removed its hidden siblings. A file inside
     * a copied folder sits below the source, so its own folder is the one to re-read.
     */
    private fun markRecovered(paths: Set<String>, dirty: MutableSet<String>) {
        for (path in paths) File(path).parent?.let { dirty += XId.file(it) }
    }

    /** True when a fresh mount list can still move [src] to the bin. */
    private fun trashLate(op: FileOp.Delete, src: XEntry): Boolean {
        if (op.permanent) return false
        return trash.canTrash(src, trash.mountedVolumes())
    }

    private fun deletesToBin(op: FileOp.Delete): Boolean {
        if (op.permanent || op.sources.isEmpty()) return false
        val mounted = trash.mountedVolumes()
        return op.sources.all { trash.canTrash(it, mounted) }
    }

    private fun countLabel(n: Int): String = if (n == 1) "1 item" else "$n items"

    private fun isSafeFastRenameName(name: String): Boolean = try {
        requireSafeEntryName(name)
        true
    } catch (_: IOException) {
        false
    }
}

private class ScanTotals(var bytes: Long = 0L, var items: Int = 0)

/** An installed app resolved for copy-out: the APK files backing it and the name to write them as. */
private class AppExport(
    val name: String,
    val apks: List<File>,
    val totalBytes: Long,
) {
    val bundled: Boolean get() = apks.size > 1
}

/**
 * Per-op handle. All counter mutations happen on the op's own coroutine;
 * [resolveConflict]/[cancel] are the only cross-thread entry points.
 */
private class RunningOpImpl(
    override val id: Long,
    private val title: String,
) : RunningOp {

    lateinit var job: Job

    private val _progress = MutableStateFlow(OpProgress(title = title))
    override val progress: StateFlow<OpProgress> = _progress

    private val _pendingConflict = MutableStateFlow<Conflict?>(null)
    override val pendingConflict: StateFlow<Conflict?> = _pendingConflict

    private val conflictReply = AtomicReference<CompletableDeferred<ConflictResolution>?>(null)

    private var state = OpState.SCANNING
    private var totalBytes = 0L
    private var doneBytes = 0L
    private var totalItems = 0
    private var doneItems = 0
    private var currentItem = ""
    private var lastPublishNanos = 0L

    override fun resolveConflict(resolution: ConflictResolution) {
        conflictReply.get()?.complete(resolution)
    }

    override fun cancel() {
        job.cancel()
    }

    fun ensureActive() = job.ensureActive()

    fun isActive(): Boolean = job.isActive

    /** Absolute progress setter used by parallel zip workers (via a single publisher). */
    @Synchronized
    fun setBytesDone(total: Long) {
        doneBytes = total
        publish(force = true)
    }

    /** Bytes copied so far — the offset a bundled app's zip progress builds on. */
    @Synchronized
    fun doneBytesSnapshot(): Long = doneBytes

    fun setTotals(totalBytes: Long, totalItems: Int) {
        this.totalBytes = totalBytes
        this.totalItems = totalItems
        publish(force = true)
    }

    fun setState(state: OpState) {
        this.state = state
        publish(force = true)
    }

    fun current(name: String) {
        currentItem = name
        publish()
    }

    fun bytesDone(n: Long) {
        doneBytes += n
        publish()
    }

    fun itemsDone(count: Int) {
        doneItems += count
        publish()
    }

    /** Marks a skipped source's whole subtree as done so the fraction stays truthful. */
    fun skipItems(totals: ScanTotals) {
        doneBytes += totals.bytes
        doneItems += totals.items
        publish(force = true)
    }

    /** Suspends the op coroutine until the UI calls [resolveConflict] (or the op is cancelled). */
    suspend fun awaitConflict(conflict: Conflict): ConflictResolution {
        val reply = CompletableDeferred<ConflictResolution>()
        conflictReply.set(reply)
        setState(OpState.AWAITING_CONFLICT)
        _pendingConflict.value = conflict
        try {
            return reply.await()
        } finally {
            conflictReply.set(null)
            _pendingConflict.value = null
            setState(OpState.RUNNING)
        }
    }

    fun finish(finalState: OpState, error: String? = null) {
        state = finalState
        _pendingConflict.value = null
        _progress.value = snapshot(error)
    }

    private fun publish(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishNanos = now
        _progress.value = snapshot(error = null)
    }

    private fun snapshot(error: String?) = OpProgress(
        title = title,
        state = state,
        totalBytes = totalBytes,
        doneBytes = doneBytes,
        totalItems = totalItems,
        doneItems = doneItems,
        currentItem = currentItem,
        error = error,
    )
}
