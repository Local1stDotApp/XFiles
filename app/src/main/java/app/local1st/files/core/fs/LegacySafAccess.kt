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
import java.io.File
import java.io.IOException
import java.io.OutputStream
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

    internal fun createDirectory(treeUri: Uri, parent: SafDocument, name: String): SafDocument {
        val uri = DocumentsContract.createDocument(
            resolver,
            parent.uri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: throw IOException("Provider did not create $name")
        return queryDocument(uri) ?: throw IOException("Cannot read created folder $name")
    }

    internal fun openOutput(
        treeUri: Uri,
        parent: SafDocument,
        name: String,
        mimeType: String,
    ): OutputStream {
        val existing = child(treeUri, parent, name)
        if (existing?.isDirectory == true) throw IOException("$name is a folder")
        val uri = existing?.uri ?: DocumentsContract.createDocument(
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

    internal fun delete(document: SafDocument) {
        if (!DocumentsContract.deleteDocument(resolver, document.uri)) {
            throw IOException("Provider did not delete ${document.name}")
        }
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
