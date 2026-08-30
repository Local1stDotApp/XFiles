package app.local1st.files.core.fs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** In-memory [DocumentsBackend] whose document ids are not display names. */
class MemoryDocumentsBackend(
    private val autoRename: Boolean = false,
    private val dead: Boolean = false,
) : DocumentsBackend {

    private data class Node(
        var id: String,
        var name: String,
        var mime: String,
        var bytes: ByteArray = ByteArray(0),
        val children: MutableList<Node> = mutableListOf(),
    )

    private val root = Node(id = "tree-root", name = "root", mime = SafDocumentFlags.MIME_DIR)
    private var nextId = 1

    override fun query(treeUri: String, documentId: String?): SafDocumentMeta? {
        if (dead) throw IOException("gone")
        return find(documentId ?: root.id)?.toMeta()
    }

    override fun listChildren(treeUri: String, parentDocumentId: String?): List<SafDocumentMeta> {
        if (dead) throw IOException("gone")
        val parent = find(parentDocumentId ?: root.id) ?: return emptyList()
        return parent.children.map { it.toMeta() }
    }

    override fun openIn(treeUri: String, documentId: String): InputStream {
        val node = find(documentId) ?: throw IOException("missing")
        return ByteArrayInputStream(node.bytes)
    }

    override fun openOut(treeUri: String, documentId: String): OutputStream {
        val node = find(documentId) ?: throw IOException("missing")
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                node.bytes = toByteArray()
            }
        }
    }

    override fun createDocument(
        treeUri: String,
        parentDocumentId: String?,
        mimeType: String,
        name: String,
    ): SafDocumentMeta {
        val parent = find(parentDocumentId ?: root.id) ?: throw IOException("missing parent")
        val actualName = if (autoRename) "$name (1)" else name
        val node = Node(id = "id-${nextId++}", name = actualName, mime = mimeType)
        parent.children += node
        return node.toMeta()
    }

    override fun deleteDocument(treeUri: String, documentId: String) {
        val parent = parentOf(documentId) ?: throw IOException("missing")
        parent.children.removeAll { it.id == documentId }
    }

    override fun renameDocument(treeUri: String, documentId: String, newName: String): SafDocumentMeta {
        val node = find(documentId) ?: throw IOException("missing")
        node.name = newName
        return node.toMeta()
    }

    private fun find(id: String, node: Node = root): Node? {
        if (node.id == id) return node
        for (child in node.children) find(id, child)?.let { return it }
        return null
    }

    private fun parentOf(id: String, node: Node = root): Node? {
        if (node.children.any { it.id == id }) return node
        for (child in node.children) parentOf(id, child)?.let { return it }
        return null
    }

    private fun Node.toMeta() = SafDocumentMeta(
        documentId = id,
        name = name,
        mimeType = mime,
        size = bytes.size.toLong(),
        lastModified = 0L,
        flags = SafDocumentFlags.DIR_SUPPORTS_CREATE or
            SafDocumentFlags.SUPPORTS_WRITE or
            SafDocumentFlags.SUPPORTS_DELETE or
            SafDocumentFlags.SUPPORTS_RENAME,
    )
}
