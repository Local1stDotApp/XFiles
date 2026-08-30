package app.local1st.files.core.fs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [DocumentsBackend] over the platform [ContentResolver]. */
class ResolverDocumentsBackend(context: Context) : DocumentsBackend {
    private val resolver: ContentResolver = context.contentResolver

    override fun query(treeUri: String, documentId: String?): SafDocumentMeta? {
        val tree = parseTree(treeUri)
        val id = documentId ?: treeDocumentId(tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return queryUri(uri)
    }

    override fun listChildren(treeUri: String, parentDocumentId: String?): List<SafDocumentMeta> {
        val tree = parseTree(treeUri)
        val parentId = parentDocumentId ?: treeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return try {
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(row(cursor))
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            throw IOException("Cannot list granted storage", e)
        }
    }

    override fun openIn(treeUri: String, documentId: String): InputStream {
        val uri = documentContentUri(treeUri, documentId)
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Provider did not open the file")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    override fun openOut(treeUri: String, documentId: String): OutputStream {
        val uri = documentContentUri(treeUri, documentId)
        // "wt" truncates on Android 10+ file URIs. Some document providers (Drive, …) reject
        // it with FileNotFoundException instead of returning null, so the "w" fallback has to
        // be a catch, not `?:`.
        val descriptor = try {
            resolver.openFileDescriptor(uri, "wt")
        } catch (_: FileNotFoundException) {
            resolver.openFileDescriptor(uri, "w")
        } ?: throw IOException("Provider did not open the file for writing")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }

    override fun createDocument(
        treeUri: String,
        parentDocumentId: String?,
        mimeType: String,
        name: String,
    ): SafDocumentMeta {
        val tree = parseTree(treeUri)
        val parentId = parentDocumentId ?: treeDocumentId(tree)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            ?: throw IOException("Provider did not create $name")
        return queryUri(created) ?: throw IOException("Cannot read created $name")
    }

    override fun deleteDocument(treeUri: String, documentId: String) {
        val uri = documentContentUri(treeUri, documentId)
        if (!DocumentsContract.deleteDocument(resolver, uri)) {
            throw IOException("Provider did not delete the document")
        }
    }

    override fun renameDocument(treeUri: String, documentId: String, newName: String): SafDocumentMeta {
        val uri = documentContentUri(treeUri, documentId)
        val renamed = DocumentsContract.renameDocument(resolver, uri, newName)
            ?: throw IOException("Provider did not rename the document")
        return queryUri(renamed) ?: throw IOException("Cannot read renamed $newName")
    }

    override fun documentUri(treeUri: String, documentId: String): String =
        documentContentUri(treeUri, documentId).toString()

    private fun documentContentUri(treeUri: String, documentId: String): Uri {
        val tree = parseTree(treeUri)
        val id = documentId.ifEmpty { treeDocumentId(tree) }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private fun parseTree(treeUri: String): Uri {
        val uri = Uri.parse(treeUri)
        if (!DocumentsContract.isTreeUri(uri)) throw IOException("Not a document tree")
        return uri
    }

    private fun treeDocumentId(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree)
            ?: throw IOException("Tree has no document id")

    private fun queryUri(uri: Uri): SafDocumentMeta? = try {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) row(cursor) else null
        }
    } catch (e: Exception) {
        throw IOException("Cannot query granted storage", e)
    }

    private fun row(cursor: android.database.Cursor): SafDocumentMeta = SafDocumentMeta(
        documentId = cursor.getString(0) ?: "",
        name = cursor.getString(1) ?: "",
        mimeType = cursor.getString(2) ?: "application/octet-stream",
        size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
        lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
        flags = if (cursor.isNull(5)) 0 else cursor.getInt(5),
    )

    private companion object {
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
