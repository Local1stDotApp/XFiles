package app.local1st.files.core.fs

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import app.local1st.files.core.prefs.SettingsRepo
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** A secondary-volume root whose direct writes need SAF on API 26-29. */
data class SafVolume(
    val id: String,
    val rootPath: String,
    val label: String,
    internal val platformVolume: StorageVolume,
)

/** One system tree-picker request. Equal-volume callers share the same request. */
class SafGrantRequest internal constructor(
    val requestId: Long,
    val volume: SafVolume,
    internal val result: CompletableDeferred<Uri?>,
)

internal data class SafDocument(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val flags: Int,
) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

/**
 * The narrowly scoped SAF bridge used only after a direct File write fails on a
 * secondary volume on API 26-29. It deliberately never handles primary storage,
 * Android/data, or any API 30+ access.
 */
class LegacySafAccess(
    private val context: Context,
    private val settings: SettingsRepo,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    private val requestIds = AtomicLong(1L)
    private val requestLock = Any()
    private val queuedRequests = ArrayDeque<SafGrantRequest>()

    private val _pendingGrant = MutableStateFlow<SafGrantRequest?>(null)
    val pendingGrant: StateFlow<SafGrantRequest?> = _pendingGrant

    /** Returns null unless this is precisely an API 26-29 secondary-volume path. */
    fun secondaryVolumeFor(file: File): SafVolume? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return null
        val path = file.absoluteFile.normalize().path
        return storageManager.storageVolumes.asSequence()
            .filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume ->
                val root = volumeRoot(volume) ?: return@mapNotNull null
                val rootPath = root.absoluteFile.normalize().path.trimEnd('/')
                if (path != rootPath && !path.startsWith("$rootPath/")) return@mapNotNull null
                val relative = path.removePrefix(rootPath).trimStart('/')
                if (relative.equals("Android/data", ignoreCase = true) ||
                    relative.startsWith("Android/data/", ignoreCase = true)
                ) {
                    // The platform tree picker cannot grant Android/data. Keep its direct
                    // behavior unchanged instead of offering a picker that cannot help.
                    return@mapNotNull null
                }
                val id = volume.uuid?.takeIf(String::isNotBlank) ?: root.name
                SafVolume(
                    id = id,
                    rootPath = rootPath,
                    label = volume.getDescription(context).takeIf { it.isNotBlank() } ?: "SD card",
                    platformVolume = volume,
                )
            }
            .firstOrNull()
    }

    /**
     * Waits off the main thread for the one volume-root picker, then returns a validated tree.
     * Keeping the failing filesystem call suspended lets a partially completed operation resume
     * at exactly that call instead of restarting and duplicating earlier work.
     */
    fun validatedTreeOrRequest(volume: SafVolume): Uri? {
        validPersistedTree(volume)?.let { return it }
        val deferred = synchronized(requestLock) {
            val existing = sequenceOf(_pendingGrant.value)
                .plus(queuedRequests.asSequence())
                .filterNotNull()
                .firstOrNull { it.volume.id == volume.id }
            if (existing != null) {
                existing.result
            } else {
                val request = SafGrantRequest(requestIds.getAndIncrement(), volume, CompletableDeferred())
                if (_pendingGrant.value == null) _pendingGrant.value = request
                else queuedRequests.addLast(request)
                request.result
            }
        }
        return runBlocking { deferred.await() }
    }

    /** API 29 can anchor the picker to the actual volume; older releases cannot. */
    fun pickerIntent(request: SafGrantRequest): Intent {
        check(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.volume.platformVolume.createOpenDocumentTreeIntent()
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        return intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }

    /**
     * Handles the current picker result and unblocks every write waiting for that volume.
     * Returns a user-readable error, or null for success/cancellation.
     */
    suspend fun completePendingGrant(uri: Uri?, resultFlags: Int): String? {
        val request = synchronized(requestLock) { _pendingGrant.value } ?: return null
        var granted: Uri? = null
        var takenFlags = 0
        val error = if (uri == null) {
            null
        } else {
            val failure = runCatching {
                require(isExactVolumeRoot(uri, request.volume)) {
                    "Select the root of ${request.volume.label}"
                }
                val takeFlags = resultFlags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(takeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                    "The selected folder did not grant write access"
                }
                resolver.takePersistableUriPermission(uri, takeFlags)
                takenFlags = takeFlags

                checkTreeDocument(uri, request.volume)

                // Android caps persisted grants at 512 and silently LRU-evicts the oldest
                // with no exception or callback. Persist one grant per VOLUME ROOT only;
                // a per-folder scheme would quietly lose access as users browse.
                settings.setSafVolumeTree(request.volume.id, uri.toString())
                granted = uri
            }.exceptionOrNull()
            if (failure != null && takenFlags != 0) {
                runCatching { resolver.releasePersistableUriPermission(uri, takenFlags) }
            }
            failure?.message?.takeIf { it.isNotBlank() }
                ?: if (failure != null) "Could not grant access to ${request.volume.label}" else null
        }

        synchronized(requestLock) {
            request.result.complete(granted)
            _pendingGrant.value = if (queuedRequests.isEmpty()) null else queuedRequests.removeFirst()
        }
        return error
    }

    internal fun resolve(volume: SafVolume, treeUri: Uri, file: File): SafDocument? {
        val root = File(volume.rootPath)
        val normalized = file.absoluteFile.normalize()
        val relative = normalized.path.removePrefix(root.path).trimStart('/')
        var current = queryDocument(rootDocumentUri(treeUri)) ?: return null
        if (relative.isEmpty()) return current
        for (segment in relative.split('/')) {
            current = queryChildren(treeUri, current.documentId)
                .firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current
    }

    internal fun child(treeUri: Uri, parent: SafDocument, name: String): SafDocument? =
        queryChildren(treeUri, parent.documentId)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }

    internal fun children(treeUri: Uri, parent: SafDocument): List<SafDocument> =
        queryChildren(treeUri, parent.documentId)

    /**
     * Names in [dir] when a tree grant is already stored. Does not open the folder picker.
     * Null means the directory could not be listed this way.
     */
    internal fun persistedChildNames(dir: File): List<String>? =
        persistedChildren(dir)?.map { it.name }

    /** Children of [dir] when a tree grant is already stored. Null if this grant cannot see it. */
    internal fun persistedChildren(dir: File): List<SafDocument>? {
        val parent = persistedDocument(dir) ?: return null
        if (!parent.isDirectory) return null
        val volume = secondaryVolumeFor(dir) ?: return null
        val tree = validPersistedTree(volume) ?: return null
        return children(tree, parent)
    }

    /** Whether a stored grant says [file] is a directory. Null if the grant cannot see it. */
    internal fun persistedIsDirectory(file: File): Boolean? = persistedDocument(file)?.isDirectory

    /**
     * The document at [file] when a tree grant is already stored.
     * Does not open the folder picker. Null means this grant cannot see it.
     */
    internal fun persistedDocument(file: File): SafDocument? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return null
        val volume = secondaryVolumeFor(file) ?: return null
        val tree = validPersistedTree(volume) ?: return null
        return resolve(volume, tree, file)
    }

    internal fun createDirectory(treeUri: Uri, parent: SafDocument, name: String): SafDocument {
        val uri = DocumentsContract.createDocument(
            resolver,
            parent.uri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: throw IOException("Provider did not create $name")
        return queryDocument(uri) ?: throw IOException("Cannot read created folder $name")
    }

    /** Creates an empty document and refuses the provider's usual auto-rename-on-conflict path. */
    internal fun createFile(
        treeUri: Uri,
        parent: SafDocument,
        name: String,
        mimeType: String,
    ): SafDocument {
        if (child(treeUri, parent, name) != null) throw FileAlreadyExistsException(name)
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mimeType, name)
            ?: throw IOException("Provider did not create $name")
        val document = queryDocument(uri) ?: throw IOException("Cannot read created file $name")
        if (document.name != name) {
            // ExternalStorageProvider may choose a free suffix instead of failing. That is useful
            // for imports, but surprising for an explicit name: remove only the document this
            // call just created and report the conflict.
            try {
                delete(document)
            } catch (e: Exception) {
                throw IOException("Provider created ${document.name} instead of $name", e)
            }
            throw FileAlreadyExistsException(name)
        }
        return document
    }

    internal fun openOutput(
        treeUri: Uri,
        parent: SafDocument,
        name: String,
        mimeType: String,
    ): OutputStream {
        val existing = child(treeUri, parent, name)
        if (existing?.isDirectory == true) throw IOException("$name is a folder")
        // An existing document must truncate. Mode "w" leaves the old tail on API 29.
        if (existing != null) return openReplacing(existing.uri)
        val uri = DocumentsContract.createDocument(
            resolver,
            parent.uri,
            mimeType,
            name,
        ) ?: throw IOException("Provider did not create $name")
        val descriptor = resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("Provider did not open $name")
        // The operation engine copies bytes through its own input/output streams. Never call
        // DocumentsContract.copyDocument(): ExternalStorageProvider does not support it.
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }

    internal fun openInput(document: SafDocument): InputStream =
        resolver.openInputStream(document.uri)
            ?: throw IOException("Provider did not open ${document.name}")

    /** Text of a document [File.readText] cannot see. Null when the grant has no file there. */
    internal fun readUtf8(file: File): String? {
        val document = persistedDocument(file)?.takeIf { !it.isDirectory } ?: return null
        return runCatching {
            openInput(document).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    internal fun delete(document: SafDocument) {
        if (!DocumentsContract.deleteDocument(resolver, document.uri)) {
            throw IOException("Provider did not delete ${document.name}")
        }
    }

    /**
     * Creates [dir] on a secondary volume. A plain mkdir is tried first; if the
     * platform rejects it, the same tree grant other writes use creates the chain.
     */
    internal fun ensureDirectory(dir: File) {
        if (dir.isDirectory) return
        if (dir.mkdirs() || dir.isDirectory) return
        val volume = secondaryVolumeFor(dir) ?: throw IOException("Cannot create ${dir.name}")
        val tree = validatedTreeOrRequest(volume) ?: throw IOException("Cannot create ${dir.name}")
        val root = File(volume.rootPath)
        val relative = dir.absoluteFile.normalize().path
            .removePrefix(root.absoluteFile.normalize().path)
            .trimStart('/')
        var parent = resolve(volume, tree, root) ?: throw IOException("Cannot create ${dir.name}")
        if (relative.isEmpty()) return
        for (segment in relative.split('/')) {
            if (segment.isEmpty()) continue
            requireSafeEntryName(segment)
            val existing = child(tree, parent, segment)
            parent = when {
                existing == null -> createDirectory(tree, parent, segment)
                existing.isDirectory -> existing
                else -> throw IOException("Cannot create ${dir.name}")
            }
        }
    }

    /** Writes [text] to [file], through the secondary-volume grant when File I/O cannot. */
    internal fun writeUtf8(file: File, text: String) {
        val parent = file.parentFile ?: throw IOException("Cannot write ${file.name}")
        if (parent.isDirectory || parent.mkdirs()) {
            try {
                writeUtf8Atomically(file, text)
                return
            } catch (e: RefusingSymlinkWrite) {
                throw e
            } catch (_: IOException) {
                // The volume rejected a direct write. The grant below is the other path.
            }
        }
        ensureDirectory(parent)
        val volume = secondaryVolumeFor(file) ?: throw IOException("Cannot write ${file.name}")
        val tree = validatedTreeOrRequest(volume) ?: throw IOException("Cannot write ${file.name}")
        val parentDoc = resolve(volume, tree, parent) ?: throw IOException("Cannot write ${file.name}")
        val existing = child(tree, parentDoc, file.name)
        if (existing?.isDirectory == true) throw IOException("Cannot write ${file.name}")
        // octet-stream keeps the requested name. text/plain makes ExternalStorageProvider
        // append .txt, which would hide the sidecar from the bin.
        val document = if (existing != null && existing.name == file.name) {
            existing
        } else {
            createFile(tree, parentDoc, file.name, "application/octet-stream")
        }
        if (document.name != file.name) throw IOException("Cannot write ${file.name}")
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            openReplacing(document.uri).use { it.write(bytes) }
        } catch (e: IOException) {
            // The old document stays until a complete temp exists and has taken its name.
            if (existing == null || existing.uri != document.uri) throw e
            replaceWithTemp(tree, parentDoc, file.name, "application/octet-stream", existing) { created ->
                writeNewDocument(created, bytes)
            }
        }
    }

    private fun writeNewDocument(document: SafDocument, bytes: ByteArray) {
        val descriptor = resolver.openFileDescriptor(document.uri, "w")
            ?: throw IOException("Cannot write ${document.name}")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(bytes) }
    }

    /**
     * Writes [from] over the existing document [onto]. Rename cannot replace a name
     * that is already taken on API 26–29.
     */
    internal fun copyOver(from: File, onto: File): Boolean {
        val volume = secondaryVolumeFor(onto) ?: return false
        val tree = validatedTreeOrRequest(volume) ?: return false
        val parentFile = onto.parentFile ?: return false
        val parent = resolve(volume, tree, parentFile) ?: return false
        val source = resolve(volume, tree, from) ?: return false
        val dest = resolve(volume, tree, onto)
        if (dest?.isDirectory == true) return false
        if (dest == null) {
            val held = child(tree, parent, ".${onto.name}.xfiles-held")?.takeIf { !it.isDirectory }
            if (held != null && putBack(tree, parent, held, onto.name)) {
                val restored = child(tree, parent, onto.name)
                if (restored != null && !restored.isDirectory) {
                    return overwriteWithTemp(tree, parent, source, restored, onto.name)
                }
            }
        }
        if (dest != null) {
            // In-place "wt" can write a prefix and then fail, and a text/* or image/*
            // temp is renamed to add an extension, so the swap never starts. Write a
            // complete octet-stream temp first; that path can put the previous
            // document back. False means it is still at this name.
            return overwriteWithTemp(tree, parent, source, dest, onto.name)
        }
        return runCatching {
            val created = createFile(tree, parent, onto.name, "application/octet-stream")
            if (created.name != onto.name) return@runCatching false
            copyDocument(source, created)
            true
        }.getOrDefault(false)
    }

    /**
     * Writes a complete temp, then renames it onto [name]. API 26–29 cannot rename
     * onto a taken name, so [existing] is moved aside first and deleted only after
     * the temp actually has [name]. A failed swap renames it back.
     */
    private fun replaceWithTemp(
        tree: Uri,
        parent: SafDocument,
        name: String,
        mime: String,
        existing: SafDocument,
        write: (SafDocument) -> Unit,
    ) {
        // A leftover `.$name.xfiles-replace` is the complete bytes from an interrupted
        // swap. A new unique name keeps it until this document is actually in place.
        val tempName = unusedSiblingName(tree, parent, ".$name.xfiles-replace")
            ?: throw IOException("Cannot write $name")
        val created = createFile(tree, parent, tempName, mime)
        try {
            write(created)
        } catch (e: Exception) {
            runCatching { delete(created) }
            throw e
        }
        val holdName = unusedSiblingName(tree, parent, ".$name.xfiles-held")
        if (holdName == null) {
            runCatching { delete(created) }
            throw IOException("Cannot write $name")
        }
        val held = try {
            rename(tree, parent, existing, holdName)
        } catch (e: Exception) {
            recoverFailedHold(tree, parent, name, holdName, created, e)
        }
        val stillAtName = try {
            child(tree, parent, name)
        } catch (e: Exception) {
            recoverFailedHold(tree, parent, name, holdName, created, e, held)
        }
        if (held.name != holdName || stillAtName != null) {
            finishFailedReplace(tree, parent, name, created, held, null)
        }
        val renamed = try {
            rename(tree, parent, created, name)
        } catch (e: Exception) {
            finishFailedReplace(tree, parent, name, created, held, e)
        }
        if (renamed.name != name) {
            finishFailedReplace(tree, parent, name, renamed, held, null)
        }
        if (held.documentId != renamed.documentId) runCatching { delete(held) }
        discardStaleReplaceTemp(tree, parent, name, renamed)
    }

    private fun overwriteWithTemp(
        tree: Uri,
        parent: SafDocument,
        source: SafDocument,
        dest: SafDocument,
        name: String,
    ): Boolean = try {
        replaceWithTemp(tree, parent, name, "application/octet-stream", dest) { created ->
            copyDocument(source, created)
        }
        true
    } catch (lost: SafOriginalNotRestored) {
        throw lost
    } catch (_: Exception) {
        false
    }

    private fun discardStaleReplaceTemp(
        tree: Uri,
        parent: SafDocument,
        name: String,
        keep: SafDocument,
    ) {
        val stale = child(tree, parent, ".$name.xfiles-replace") ?: return
        if (stale.isDirectory || stale.documentId == keep.documentId) return
        runCatching { delete(stale) }
    }

    /** The previous document is not at [name], and renaming it back failed. */
    private class SafOriginalNotRestored(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun unusedSiblingName(tree: Uri, parent: SafDocument, base: String): String? {
        if (child(tree, parent, base) == null) return base
        for (i in 2..99) {
            val candidate = "$base.$i"
            if (child(tree, parent, candidate) == null) return candidate
        }
        return null
    }

    /** Document ids change on rename, so an empty [name] means the previous file is at [holdName]. */
    private fun recoverFailedHold(
        tree: Uri,
        parent: SafDocument,
        name: String,
        holdName: String,
        created: SafDocument,
        cause: Exception,
        knownParked: SafDocument? = null,
    ): Nothing {
        val atName = try {
            child(tree, parent, name)
        } catch (query: Exception) {
            throw SafOriginalNotRestored("Cannot write $name; previous bytes kept as $holdName", query)
        }
        if (atName == null) {
            val parked = knownParked ?: try {
                child(tree, parent, holdName)
            } catch (query: Exception) {
                throw SafOriginalNotRestored("Cannot write $name; previous bytes kept as $holdName", query)
            }
            if (parked != null && !parked.isDirectory) {
                finishFailedReplace(tree, parent, name, created, parked, cause)
            }
            throw SafOriginalNotRestored("Cannot write $name; previous bytes kept as $holdName", cause)
        }
        runCatching { delete(created) }
        throw cause
    }

    /** @return true when [document] is once again the child named [name]. */
    private fun putBack(tree: Uri, parent: SafDocument, document: SafDocument, name: String): Boolean {
        if (document.name == name) return true
        val atName = try {
            child(tree, parent, name)
        } catch (_: Exception) {
            return false
        }
        if (atName != null) return false
        val current = try {
            child(tree, parent, document.name) ?: document
        } catch (_: Exception) {
            document
        }
        val restored = runCatching { rename(tree, parent, current, name) }.getOrNull() ?: return false
        return restored.name == name
    }

    private fun finishFailedReplace(
        tree: Uri,
        parent: SafDocument,
        name: String,
        created: SafDocument,
        held: SafDocument,
        cause: Exception?,
    ): Nothing {
        if (held.name == name) {
            deleteSwapTemp(tree, parent, created, held)
            if (cause != null) throw cause
            throw IOException("Cannot write $name")
        }
        val restored = putBack(tree, parent, held, name)
        if (restored) {
            deleteSwapTemp(tree, parent, created, held)
            if (cause != null) throw cause
            throw IOException("Cannot write $name")
        }
        val atName = try {
            child(tree, parent, name)
        } catch (query: Exception) {
            throw SafOriginalNotRestored(keptAs(tree, parent, name, held), query)
        }
        if (atName == null) throw SafOriginalNotRestored(keptAs(tree, parent, name, held), cause)
        deleteSwapTemp(tree, parent, created, held)
        if (cause != null) throw cause
        throw IOException("Cannot write $name")
    }

    private fun keptAs(tree: Uri, parent: SafDocument, name: String, held: SafDocument): String {
        val kept = runCatching {
            children(tree, parent).firstOrNull { it.documentId == held.documentId || it.name == held.name }?.name
        }.getOrNull() ?: held.name
        return "Cannot write $name; previous bytes kept as $kept"
    }

    private fun deleteSwapTemp(tree: Uri, parent: SafDocument, created: SafDocument, held: SafDocument) {
        val leftover = runCatching { child(tree, parent, created.name) }.getOrNull() ?: return
        if (leftover.isDirectory || leftover.documentId == held.documentId) return
        if (leftover.documentId == created.documentId || leftover.name == created.name) {
            runCatching { delete(leftover) }
        }
    }

    private fun copyDocument(source: SafDocument, dest: SafDocument) {
        val descriptor = resolver.openFileDescriptor(dest.uri, "w")
            ?: throw IOException("Cannot write ${dest.name}")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
            openInput(source).use { it.copyTo(out) }
        }
    }

    /**
     * Mode `"w"` does not truncate on API 29. `"wt"` does; providers that reject it
     * fall back to `"w"` and ftruncate. If that fails the caller replaces the document.
     */
    private fun openReplacing(uri: Uri): OutputStream {
        try {
            val descriptor = resolver.openFileDescriptor(uri, "wt") ?: throw FileNotFoundException()
            return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        } catch (_: FileNotFoundException) {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Provider did not open the file")
            return TruncateOnCloseStream(descriptor)
        }
    }

    private class TruncateOnCloseStream(
        private val descriptor: ParcelFileDescriptor,
    ) : OutputStream() {
        private val delegate = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        private var written = 0L

        override fun write(b: Int) {
            delegate.write(b)
            written++
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            delegate.write(bytes, off, len)
            written += len.toLong()
        }

        override fun flush() = delegate.flush()

        override fun close() {
            var truncateError: IOException? = null
            try {
                Os.ftruncate(descriptor.fileDescriptor, written)
            } catch (e: ErrnoException) {
                truncateError = IOException("Provider did not truncate the file", e)
            }
            delegate.close()
            if (truncateError != null) throw truncateError
        }
    }

    /** Renames [file] inside its parent when [File.renameTo] cannot. */
    internal fun renamePath(file: File, newName: String): Boolean {
        // renameDocument follows a directory symlink and renames the target.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return false
        val volume = secondaryVolumeFor(file) ?: return false
        val parentFile = file.parentFile ?: return false
        val tree = validatedTreeOrRequest(volume) ?: return false
        val parent = resolve(volume, tree, parentFile) ?: return false
        val document = resolve(volume, tree, file) ?: return false
        return runCatching { rename(tree, parent, document, newName).name == newName }.getOrDefault(false)
    }

    /**
     * Deletes [file], through the secondary-volume grant when File.delete cannot.
     * [requestGrant] is false after delete() already returned true: exists() can
     * stay true on SD/USB, and opening the picker can match a different name.
     */
    internal fun deletePath(file: File, requestGrant: Boolean = true) {
        // DocumentsContract.deleteDocument follows a directory symlink and wipes the target.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            if (!file.delete() && java.nio.file.Files.isSymbolicLink(file.toPath())) {
                throw IOException("Cannot delete ${file.name}")
            }
            return
        }
        // An empty File listing is not an empty directory. Remove grant-only children
        // before rmdir, or that success never reaches deleteDocument.
        deleteUnlistedGrantChildren(file)
        if (nodeSurvivedDelete(file) && deleteWithFileApi(file) && unlistedGrantNames(file).isEmpty()) {
            return
        }
        // File.delete is false when the node is invisible. That is not success while
        // the stored grant still names the document, including a directory tree.
        val listed = persistedDocument(file)
        if (listed != null) {
            delete(listed)
            return
        }
        if (!requestGrant || !nodeSurvivedDelete(file)) return
        val volume = secondaryVolumeFor(file) ?: throw IOException("Cannot delete ${file.name}")
        val tree = validatedTreeOrRequest(volume) ?: throw IOException("Cannot delete ${file.name}")
        val document = resolve(volume, tree, file) ?: throw IOException("Cannot delete ${file.name}")
        delete(document)
    }

    /** Deletes documents [File.list] did not return. A non-null empty list is not "no children". */
    internal fun deleteUnlistedGrantChildren(dir: File) {
        if (java.nio.file.Files.isSymbolicLink(dir.toPath())) return
        val listed = dir.list()
        val listedLower = listed?.map { it.lowercase(Locale.ROOT) }?.toSet()
        // A subdirectory File can see still hides grant-only files. rmdir would
        // succeed on the empty listing and leave those documents on the volume.
        if (listed != null) {
            for (name in listed) {
                if (!isPlainChildName(name)) continue
                val child = File(dir, name)
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                val grantDir = persistedDocument(child)?.isDirectory == true
                if (child.isDirectory || grantDir) deleteUnlistedGrantChildren(child)
            }
        }
        for (name in unlistedGrantNames(dir, listedLower)) {
            val child = File(dir, name)
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                if (!child.delete() && java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    throw IOException("Cannot delete $name")
                }
                continue
            }
            val document = persistedDocument(child) ?: continue
            if (document.isDirectory) deleteUnlistedGrantChildren(child)
            delete(document)
        }
    }

    private fun isPlainChildName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

    private fun unlistedGrantNames(
        dir: File,
        listedLower: Set<String>? = dir.list()?.map { it.lowercase(Locale.ROOT) }?.toSet(),
    ): List<String> {
        val granted = persistedChildren(dir) ?: return emptyList()
        return granted.map { it.name }.filter { name ->
            name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name &&
                (listedLower == null || name.lowercase(Locale.ROOT) !in listedLower)
        }
    }

    private fun deleteWithFileApi(file: File): Boolean = runCatching {
        // listFiles() == null must not skip File.delete. An empty directory can
        // still be removed, matching deleteTrashBytes. delete() returning true is
        // enough: exists() can stay true after the node is already gone.
        deleteTrashBytes(file)
        true
    }.getOrDefault(false)

    /**
     * Moves [source] into [destDir] on the same secondary volume. Uses a stored
     * grant, or the same one-time picker other secondary-volume writes already show.
     */
    internal fun moveInto(source: File, destDir: File): Boolean {
        // moveDocument follows a directory symlink and moves the target.
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) return false
        val volume = secondaryVolumeFor(source) ?: return false
        if (secondaryVolumeFor(destDir)?.id != volume.id) return false
        val tree = validatedTreeOrRequest(volume) ?: return false
        val parentFile = source.parentFile ?: return false
        val sourceDoc = resolve(volume, tree, source) ?: return false
        val sourceParent = resolve(volume, tree, parentFile) ?: return false
        val destParent = resolve(volume, tree, destDir) ?: return false
        if (children(tree, destParent).any { it.name == source.name }) return false
        val movedUri = runCatching {
            DocumentsContract.moveDocument(resolver, sourceDoc.uri, sourceParent.uri, destParent.uri)
        }.getOrNull() ?: return false
        // A query after the move throws IOException. That must not skip rollback
        // and the sidecar, or the only copy sits in the bucket with no bin row.
        val moved = runCatching { queryDocument(movedUri) }.getOrNull()
        val landed = runCatching {
            children(tree, destParent).firstOrNull { it.name == source.name }
        }.getOrNull()
        if (moved != null && moved.name == source.name && landed?.documentId == moved.documentId) {
            return true
        }
        // The provider already took the document. Put it back unless that also fails
        // and the returned document still has the requested name — then the caller
        // records a sidecar instead of dropping the only copy.
        val restored = runCatching {
            DocumentsContract.moveDocument(resolver, movedUri, destParent.uri, sourceParent.uri)
        }.getOrNull()
        if (restored != null) return false
        // The provider already moved the document and would not take it back.
        // File may not see the destination. The caller still records the sidecar.
        return safMoveKeptAfterFailedRollback(moved?.name, source.name)
    }

    internal fun rename(
        treeUri: Uri,
        parent: SafDocument,
        document: SafDocument,
        newName: String,
    ): SafDocument {
        val caseOnly = document.name.equals(newName, ignoreCase = true)
        if (!caseOnly && child(treeUri, parent, newName) != null) {
            throw IOException("$newName already exists")
        }
        val renamedUri = DocumentsContract.renameDocument(resolver, document.uri, newName)
            ?: throw IOException("Provider did not rename ${document.name}")
        return queryDocument(renamedUri)
            ?: child(treeUri, parent, newName)
            ?: throw IOException("Cannot read renamed item $newName")
    }

    private fun validPersistedTree(volume: SafVolume): Uri? {
        val stored = runBlocking { settings.safVolumeTrees.first()[volume.id] } ?: return null
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return invalidate(volume)
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val valid = permission?.isReadPermission == true && permission.isWritePermission &&
            runCatching { checkTreeDocument(uri, volume) }.isSuccess
        return if (valid) uri else invalidate(volume, uri)
    }

    private fun invalidate(volume: SafVolume, uri: Uri? = null): Uri? {
        if (uri != null) {
            val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            var flags = 0
            if (permission?.isReadPermission == true) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission?.isWritePermission == true) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) runCatching { resolver.releasePersistableUriPermission(uri, flags) }
        }
        runBlocking { runCatching { settings.setSafVolumeTree(volume.id, null) } }
        return null
    }

    /** Re-query the root before every use: persisted permission alone survives moved/deleted docs. */
    private fun checkTreeDocument(treeUri: Uri, volume: SafVolume) {
        require(isExactVolumeRoot(treeUri, volume)) { "Grant is not the volume root" }
        val root = queryDocument(rootDocumentUri(treeUri))
            ?: throw IOException("Granted volume is no longer available")
        if (!root.isDirectory) throw IOException("Granted document is no longer a folder")
    }

    private fun isExactVolumeRoot(uri: Uri, volume: SafVolume): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return false
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return false
        val expectedRoot = volume.id.trimEnd(':')
        return documentId.endsWith(':') &&
            documentId.dropLast(1).equals(expectedRoot, ignoreCase = true)
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    private fun queryDocument(documentUri: Uri): SafDocument? = try {
        resolver.query(documentUri, PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) row(documentUri, cursor) else null
        }
    } catch (e: Exception) {
        if (e is IOException) throw e
        throw IOException("Cannot query granted storage", e)
    }

    private fun queryChildren(treeUri: Uri, parentDocumentId: String): List<SafDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )
        return try {
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        add(row(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), cursor))
                    }
                }
            }.orEmpty()
                // FileSystemProvider ignores selection and sortOrder. Filter/match callers and
                // ordering therefore stay client-side; never issue one query per property.
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Cannot list granted storage", e)
        }
    }

    private fun row(uri: Uri, cursor: android.database.Cursor): SafDocument = SafDocument(
        uri = uri,
        documentId = cursor.getString(0),
        name = cursor.getString(1) ?: "",
        mimeType = cursor.getString(2) ?: "application/octet-stream",
        size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
        lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
        flags = if (cursor.isNull(5)) 0 else cursor.getInt(5),
    )

    @Suppress("DEPRECATION")
    private fun volumeRoot(volume: StorageVolume): File? {
        // StorageVolume.directory is API 30; P5 intentionally exists only below it.
        val path = runCatching {
            StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
        }.getOrNull()
        return path?.let(::File)
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
