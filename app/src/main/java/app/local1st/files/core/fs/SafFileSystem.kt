package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SafLocation
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException

/**
 * `saf://` filesystem over a granted document tree. Network I/O, if any, happens in the
 * provider process; this class only talks to [DocumentsBackend].
 */
class SafFileSystem(
    private val backend: DocumentsBackend,
    private val locations: () -> List<SafLocation>,
) : XFileSystem {

    override val scheme: String = XId.SCHEME_SAF

    override fun list(dir: XEntry): List<XEntry> {
        val loc = locationOf(dir.id) ?: throw IOException("Location is no longer available")
        val parentDocs = XId.safDocumentIds(dir.id)
        val parentDocumentId = parentDocs.lastOrNull()
        val children = try {
            backend.listChildren(loc.treeUri, parentDocumentId)
        } catch (e: Exception) {
            throw wrap("Cannot list ${dir.name}", e)
        }
        return children.map { toEntry(loc, parentDocs, it) }
    }

    override fun stat(id: String): XEntry? {
        if (XId.schemeOf(id) != XId.SCHEME_SAF) return null
        val loc = locationOf(id) ?: return null
        val docs = XId.safDocumentIds(id)
        val meta = try {
            backend.query(loc.treeUri, docs.lastOrNull())
        } catch (_: Exception) {
            return null
        } ?: return null
        return toEntry(loc, docs.dropLast(1), meta, rootId = id.takeIf { docs.isEmpty() })
    }

    override fun openIn(entry: XEntry): InputStream {
        val loc = locationOf(entry.id) ?: throw IOException("Location is no longer available")
        val documentId = requiredDocumentId(entry.id)
        return try {
            backend.openIn(loc.treeUri, documentId)
        } catch (e: Exception) {
            throw wrap("Cannot open ${entry.name}", e)
        }
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        requireSafeEntryName(name)
        val loc = writableLocation(parentDir.id)
        val parentDocs = XId.safDocumentIds(parentDir.id)
        val existing = childNamed(loc, parentDocs, name)
        if (existing?.isDirectory == true) throw IOException("$name is a folder")
        val document = existing ?: createExclusive(loc, parentDocs, name, mimeFor(name))
        return try {
            backend.openOut(loc.treeUri, document.documentId)
        } catch (e: Exception) {
            throw wrap("Cannot write $name", e)
        }
    }

    override fun createFile(parentDir: XEntry, name: String): XEntry {
        requireSafeEntryName(name)
        val loc = writableLocation(parentDir.id)
        val parentDocs = XId.safDocumentIds(parentDir.id)
        if (childNamed(loc, parentDocs, name) != null) throw FileAlreadyExistsException(name)
        val created = createExclusive(loc, parentDocs, name, mimeFor(name))
        return toEntry(loc, parentDocs, created)
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        requireSafeEntryName(name)
        val loc = writableLocation(parentDir.id)
        val parentDocs = XId.safDocumentIds(parentDir.id)
        val existing = childNamed(loc, parentDocs, name)
        if (existing != null) {
            if (existing.isDirectory) return toEntry(loc, parentDocs, existing)
            throw IOException("$name already exists")
        }
        val created = try {
            backend.createDocument(loc.treeUri, parentDocs.lastOrNull(), SafDocumentFlags.MIME_DIR, name)
        } catch (e: Exception) {
            throw wrap("Cannot create folder $name", e)
        }
        if (created.name != name) {
            runCatching { backend.deleteDocument(loc.treeUri, created.documentId) }
            throw IOException("Provider created ${created.name} instead of $name")
        }
        return toEntry(loc, parentDocs, created)
    }

    override fun delete(entry: XEntry) {
        val loc = writableLocation(entry.id)
        val docs = XId.safDocumentIds(entry.id)
        if (docs.isEmpty()) throw IOException("Cannot delete a location root")
        if (entry.isDir) {
            for (child in list(entry)) delete(child)
        }
        try {
            backend.deleteDocument(loc.treeUri, docs.last())
        } catch (e: Exception) {
            throw wrap("Cannot delete ${entry.name}", e)
        }
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        requireSafeEntryName(newName)
        val loc = writableLocation(entry.id)
        val docs = XId.safDocumentIds(entry.id)
        if (docs.isEmpty()) throw IOException("Cannot rename a location root")
        val parentDocs = docs.dropLast(1)
        val sibling = childNamed(loc, parentDocs, newName)
        val caseOnly = entry.name.equals(newName, ignoreCase = true)
        if (!caseOnly && sibling != null) throw IOException("$newName already exists")
        val renamed = try {
            backend.renameDocument(loc.treeUri, docs.last(), newName)
        } catch (e: Exception) {
            throw wrap("Cannot rename ${entry.name}", e)
        }
        return toEntry(loc, parentDocs, renamed)
    }

    override fun canWrite(entry: XEntry): Boolean {
        val loc = locationOf(entry.id) ?: return false
        if (!loc.writable) return false
        return entry.canWrite
    }

    fun documentUri(entry: XEntry): String? {
        val loc = locationOf(entry.id) ?: return null
        val documentId = XId.safDocumentIds(entry.id).lastOrNull()
            ?: return backend.documentUri(loc.treeUri, rootDocumentIdPlaceholder(loc))
        return backend.documentUri(loc.treeUri, documentId)
    }

    private fun rootDocumentIdPlaceholder(loc: SafLocation): String {
        val meta = backend.query(loc.treeUri, null) ?: return ""
        return meta.documentId
    }

    private fun locationOf(id: String): SafLocation? {
        val locationId = XId.safLocationId(id)
        if (locationId.isEmpty()) return null
        return locations().firstOrNull { it.id == locationId }
    }

    private fun writableLocation(id: String): SafLocation {
        val loc = locationOf(id) ?: throw IOException("Location is no longer available")
        if (!loc.writable) throw IOException("This location is read-only")
        return loc
    }

    private fun requiredDocumentId(id: String): String =
        XId.safDocumentIds(id).lastOrNull()
            ?: throw IOException("Cannot open a location root as a file")

    private fun childNamed(
        loc: SafLocation,
        parentDocs: List<String>,
        name: String,
    ): SafDocumentMeta? {
        val children = try {
            backend.listChildren(loc.treeUri, parentDocs.lastOrNull())
        } catch (e: Exception) {
            throw wrap("Cannot list ${loc.displayName}", e)
        }
        return children.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun createExclusive(
        loc: SafLocation,
        parentDocs: List<String>,
        name: String,
        mimeType: String,
    ): SafDocumentMeta {
        val created = try {
            backend.createDocument(loc.treeUri, parentDocs.lastOrNull(), mimeType, name)
        } catch (e: Exception) {
            throw wrap("Cannot create $name", e)
        }
        if (created.name != name) {
            runCatching { backend.deleteDocument(loc.treeUri, created.documentId) }
            throw FileAlreadyExistsException(name)
        }
        return created
    }

    private fun toEntry(
        loc: SafLocation,
        parentDocs: List<String>,
        meta: SafDocumentMeta,
        rootId: String? = null,
    ): XEntry {
        val isRoot = rootId != null
        val id = rootId ?: XId.saf(loc.id, parentDocs + meta.documentId)
        val dir = meta.isDirectory
        val write = loc.writable && documentWritable(meta, dir)
        return XEntry(
            id = id,
            name = if (isRoot) loc.displayName else meta.name.ifBlank { loc.displayName },
            isDir = dir,
            size = if (dir) -1L else meta.size,
            mtime = meta.lastModified,
            mime = meta.mimeType.takeUnless { dir },
            hidden = meta.name.startsWith('.'),
            canWrite = write,
            kind = when {
                isRoot -> EntryKind.LOCATION
                dir -> EntryKind.DIR
                else -> EntryKind.FILE
            },
        )
    }

    private fun documentWritable(meta: SafDocumentMeta, dir: Boolean): Boolean {
        val flags = meta.flags
        if (flags == 0) return true
        return if (dir) {
            flags and SafDocumentFlags.DIR_SUPPORTS_CREATE != 0 ||
                flags and SafDocumentFlags.SUPPORTS_WRITE != 0
        } else {
            flags and SafDocumentFlags.SUPPORTS_WRITE != 0
        }
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "log", "csv" -> "text/plain"
            "json" -> "application/json"
            "xml", "html", "htm" -> "text/html"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun wrap(message: String, cause: Exception): IOException =
        if (cause is IOException) {
            IOException(cause.message?.takeIf { it.isNotBlank() } ?: message, cause)
        } else {
            IOException(message, cause)
        }
}

/** Pane-root row for a granted tree. [stat] is null when the provider is gone. */
internal fun safLocationRoot(location: SafLocation, stat: XEntry?): XEntry {
    val fallback = XEntry(
        id = XId.saf(location.id),
        name = location.displayName,
        isDir = true,
        kind = EntryKind.LOCATION,
        canWrite = false,
    )
    val entry = stat ?: fallback
    return entry.copy(
        name = location.displayName,
        kind = EntryKind.LOCATION,
        isDir = true,
        pinned = false,
        badge = if (stat == null) "Not available" else entry.badge,
        canWrite = stat != null && location.writable && entry.canWrite,
    )
}
