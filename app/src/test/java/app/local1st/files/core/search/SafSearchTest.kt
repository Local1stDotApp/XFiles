package app.local1st.files.core.search

import app.local1st.files.core.fs.DocumentsBackend
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.MemoryDocumentsBackend
import app.local1st.files.core.fs.SafDocumentFlags
import app.local1st.files.core.fs.SafDocumentMeta
import app.local1st.files.core.fs.SafFileSystem
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.SafLocation
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafSearchTest {

    private val location = SafLocation(
        id = "loc",
        treeUri = "content://provider/tree/root",
        displayName = "NAS",
        writable = true,
    )

    private fun engineOver(backend: DocumentsBackend): Pair<DefaultSearchEngine, SafFileSystem> {
        val fs = SafFileSystem(backend) { listOf(location) }
        return DefaultSearchEngine(FsRegistry().apply { register(fs) }) to fs
    }

    @Test
    fun searchGoesIntoSubfoldersOfADocumentTree() = runBlocking {
        val (engine, fs) = engineOver(MemoryDocumentsBackend())
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        val deep = fs.mkdir(fs.mkdir(root, "a"), "b")
        fs.createFile(deep, "match-deep")
        fs.createFile(root, "match-top")

        val hits = engine.hits(root, "match")

        assertEquals(listOf("match-top", "match-deep"), hits.map { it.entry.name })
        assertEquals(deep.id, hits.last().parentId)
    }

    @Test
    fun aFolderListedInsideItsOwnSubfolderIsSearchedOnce() = runBlocking {
        val backend = FakeBackend { parent ->
            when (parent) {
                null -> listOf(folder("a"))
                "a" -> listOf(folder("b"), file("match"))
                "b" -> listOf(folder("a")) // back up: a → b → a → …
                else -> emptyList()
            }
        }
        val (engine, fs) = engineOver(backend)

        val hits = engine.hits(checkNotNull(fs.stat(XId.saf("loc"))), "match")

        assertEquals(listOf("match"), hits.map { it.entry.name })
        assertEquals(3, backend.listings)
    }

    @Test
    fun aTreeThatNeverEndsStopsAtTheDepthCap() = runBlocking {
        // Every folder holds a new one, each with a fresh document id.
        val backend = FakeBackend { parent -> listOf(folder("${parent.orEmpty()}x")) }
        val (engine, fs) = engineOver(backend)

        engine.hits(checkNotNull(fs.stat(XId.saf("loc"))), "match")

        assertTrue("listed ${backend.listings} folders", backend.listings in 2..64)
    }

    private fun folder(id: String) = meta(id, SafDocumentFlags.MIME_DIR)

    private fun file(id: String) = meta(id, "application/octet-stream")

    private fun meta(id: String, mime: String) = SafDocumentMeta(
        documentId = id,
        name = id,
        mimeType = mime,
        size = 0L,
        lastModified = 0L,
        flags = 0,
    )

    /** A read-only provider whose folders hold whatever [children] says. */
    private class FakeBackend(
        private val children: (parentDocumentId: String?) -> List<SafDocumentMeta>,
    ) : DocumentsBackend {
        var listings = 0
            private set

        override fun query(treeUri: String, documentId: String?) = SafDocumentMeta(
            documentId = documentId ?: "root",
            name = documentId ?: "root",
            mimeType = SafDocumentFlags.MIME_DIR,
            size = 0L,
            lastModified = 0L,
            flags = 0,
        )

        override fun listChildren(treeUri: String, parentDocumentId: String?): List<SafDocumentMeta> {
            listings++
            return children(parentDocumentId)
        }

        override fun openIn(treeUri: String, documentId: String): InputStream = unsupported()

        override fun openOut(treeUri: String, documentId: String): OutputStream = unsupported()

        override fun createDocument(
            treeUri: String,
            parentDocumentId: String?,
            mimeType: String,
            name: String,
        ): SafDocumentMeta = unsupported()

        override fun deleteDocument(treeUri: String, documentId: String) = unsupported()

        override fun renameDocument(
            treeUri: String,
            documentId: String,
            newName: String,
        ): SafDocumentMeta = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }
}
