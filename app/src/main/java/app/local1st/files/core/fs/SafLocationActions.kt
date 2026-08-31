package app.local1st.files.core.fs

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.local1st.files.core.prefs.Favorite
import app.local1st.files.core.prefs.SafLocation
import app.local1st.files.core.prefs.SettingsRepo
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

sealed interface AddLocationResult {
    data object Cancelled : AddLocationResult
    data class Added(val name: String) : AddLocationResult
    data class AlreadyAdded(val name: String) : AddLocationResult
    data class AlreadyLocal(val name: String) : AddLocationResult
    data class PinnedLocal(val name: String) : AddLocationResult
    data class Failed(val reason: String) : AddLocationResult
}

/**
 * Grants, persists and removes SAF document-tree locations. The picker itself is launched
 * from the UI; this class consumes the result and updates [SettingsRepo].
 */
class SafLocationActions(
    context: Context,
    private val settings: SettingsRepo,
    private val volumes: () -> List<Volume>,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    private val _pickerNonce = MutableStateFlow(0L)
    val pickerNonce: StateFlow<Long> = _pickerNonce
    private val launchedNonce = AtomicLong(0L)

    fun requestPicker() {
        _pickerNonce.update { it + 1 }
    }

    /** True once per [requestPicker] nonce so rotation/recomposition cannot relaunch the picker. */
    fun takePickerLaunch(nonce: Long): Boolean {
        while (true) {
            val launched = launchedNonce.get()
            if (nonce <= launched) return false
            if (launchedNonce.compareAndSet(launched, nonce)) return true
        }
    }

    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    )

    suspend fun addFromPicker(uri: Uri?, resultFlags: Int): AddLocationResult {
        if (uri == null) return AddLocationResult.Cancelled
        if (!DocumentsContract.isTreeUri(uri)) {
            return AddLocationResult.Failed("That is not a folder")
        }
        val writable = try {
            val requested = resultFlags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val flags = if (requested != 0) {
                requested
            } else {
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            try {
                resolver.takePersistableUriPermission(uri, flags)
                flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
            } catch (_: SecurityException) {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                false
            }
        } catch (e: SecurityException) {
            return AddLocationResult.Failed(
                e.message?.takeIf { it.isNotBlank() } ?: "Could not keep access",
            )
        }
        val displayName = queryDisplayName(uri) ?: fallbackName(uri)
        val treeUri = uri.toString()
        val existing = settings.safLocations.first().firstOrNull { it.treeUri == treeUri }
        if (existing != null) {
            return AddLocationResult.AlreadyAdded(existing.displayName)
        }
        val localPath = localPathForExternalStorageTree(
            authority = uri.authority,
            treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull(),
            volumes = volumes(),
        )
        if (localPath != null) {
            release(uri)
            return addLocalShortcut(localPath, displayName)
        }
        val location = SafLocation(
            id = UUID.randomUUID().toString(),
            treeUri = treeUri,
            displayName = displayName,
            writable = writable,
            createdAt = System.currentTimeMillis(),
        )
        val updated = settings.safLocations.first() + location
        settings.setSafLocations(updated)
        return AddLocationResult.Added(location.displayName)
    }

    suspend fun remove(locationId: String) {
        val current = settings.safLocations.first()
        val location = current.firstOrNull { it.id == locationId } ?: return
        runCatching { release(Uri.parse(location.treeUri)) }
        settings.setSafLocations(current.filter { it.id != locationId })
    }

    private suspend fun addLocalShortcut(path: String, displayName: String): AddLocationResult {
        val volumeRoot = volumes().any { volume ->
            val root = volume.entry.localPath?.trimEnd('/') ?: return@any false
            path == root
        }
        if (volumeRoot) return AddLocationResult.AlreadyLocal(displayName)
        val id = XId.file(path)
        val current = settings.favorites.first()
        if (current.any { it.id == id }) return AddLocationResult.AlreadyLocal(displayName)
        settings.setFavorites(current + Favorite(id, isDir = true))
        return AddLocationResult.PinnedLocal(displayName)
    }

    private fun queryDisplayName(treeUri: Uri): String? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri) ?: return null,
        )
        return try {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fallbackName(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        val relative = documentId.substringAfter(':', documentId).trim('/')
        return relative.substringAfterLast('/').ifBlank {
            uri.authority?.substringAfterLast('.')?.ifBlank { null } ?: "Location"
        }
    }

    private fun release(uri: Uri) {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }
}

/** Canonical guide; the Chinese page is the same path under `/zh/`. */
const val ADD_LOCATION_GUIDE_URL = "https://xfiles.local1st.app/add-location"
const val ADD_LOCATION_GUIDE_URL_ZH = "https://xfiles.local1st.app/zh/add-location"

fun addLocationGuideUrl(context: Context): String {
    val locales = context.resources.configuration.locales
    val language = if (locales.isEmpty) {
        java.util.Locale.getDefault().language
    } else {
        locales[0].language
    }
    return if (language == "zh") ADD_LOCATION_GUIDE_URL_ZH else ADD_LOCATION_GUIDE_URL
}

internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Maps an ExternalStorageProvider tree onto a real path under a currently mounted volume.
 * Remote providers (rclone, Nextcloud, …) return null and stay `saf://`.
 */
internal fun localPathForExternalStorageTree(
    authority: String?,
    treeDocumentId: String?,
    volumes: List<Volume>,
): String? {
    if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val documentId = treeDocumentId?.takeIf { it.isNotBlank() } ?: return null
    val colon = documentId.indexOf(':')
    if (colon < 0) return null
    val volumeKey = documentId.substring(0, colon)
    val relative = documentId.substring(colon + 1).trimStart('/')
    val root = volumeRootFor(volumeKey, volumes) ?: return null
    return if (relative.isEmpty()) root else "$root/$relative"
}

private fun volumeRootFor(volumeKey: String, volumes: List<Volume>): String? {
    if (volumeKey.equals("primary", ignoreCase = true)) {
        return volumes.firstOrNull { it.entry.kind == EntryKind.VOLUME_INTERNAL }
            ?.entry?.localPath?.trimEnd('/')
    }
    return volumes.firstOrNull { volume ->
        val path = volume.entry.localPath?.trimEnd('/') ?: return@firstOrNull false
        path.equals("/storage/$volumeKey", ignoreCase = true) ||
            path.endsWith("/$volumeKey", ignoreCase = true)
    }?.entry?.localPath?.trimEnd('/')
}
