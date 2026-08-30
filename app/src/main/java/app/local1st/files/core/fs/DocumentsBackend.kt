package app.local1st.files.core.fs

import java.io.InputStream
import java.io.OutputStream

/**
 * DocumentsContract flag bits we care about. Values match
 * [android.provider.DocumentsContract.Document] so tests need no Android.
 */
object SafDocumentFlags {
    const val MIME_DIR = "vnd.android.document/directory"
    const val SUPPORTS_WRITE = 1 shl 1
    const val SUPPORTS_DELETE = 1 shl 2
    const val DIR_SUPPORTS_CREATE = 1 shl 3
    const val SUPPORTS_RENAME = 1 shl 6
}

data class SafDocumentMeta(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val flags: Int,
) {
    val isDirectory: Boolean get() = mimeType == SafDocumentFlags.MIME_DIR
}

/**
 * Storage Access Framework operations for one granted tree. [treeUri] is the persisted
 * tree URI string. A null [documentId] means the tree root document.
 */
interface DocumentsBackend {
    fun query(treeUri: String, documentId: String?): SafDocumentMeta?

    fun listChildren(treeUri: String, parentDocumentId: String?): List<SafDocumentMeta>

    fun openIn(treeUri: String, documentId: String): InputStream

    fun openOut(treeUri: String, documentId: String): OutputStream

    fun createDocument(
        treeUri: String,
        parentDocumentId: String?,
        mimeType: String,
        name: String,
    ): SafDocumentMeta

    fun deleteDocument(treeUri: String, documentId: String)

    fun renameDocument(treeUri: String, documentId: String, newName: String): SafDocumentMeta

    /** Content URI suitable for ACTION_VIEW / ACTION_SEND, or null if the backend cannot mint one. */
    fun documentUri(treeUri: String, documentId: String): String? = null
}
