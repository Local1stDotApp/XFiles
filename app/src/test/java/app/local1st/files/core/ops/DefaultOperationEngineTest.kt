package app.local1st.files.core.ops

import app.local1st.files.core.fs.DocumentsBackend
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.MemoryDocumentsBackend
import app.local1st.files.core.fs.SafFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.SafLocation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultOperationEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val location = SafLocation(
        id = "loc",
        treeUri = "content://provider/tree/root",
        displayName = "NAS",
        writable = true,
    )

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun overwriteSafDirectoryReplacesChildren() {
        val fs = SafFileSystem(MemoryDocumentsBackend()) { listOf(location) }
        val engine = engine(fs)
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        val dest = fs.mkdir(root, "dest")
        val src = fs.mkdir(root, "photos")
        write(fs, src, "new.txt", "new")
        val existing = fs.mkdir(dest, "photos")
        write(fs, existing, "old.txt", "old")

        val event = runCopy(
            engine,
            FileOp.Copy(listOf(src), dest),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        val copied = fs.list(dest).single { it.name == "photos" }
        assertEquals(listOf("new.txt"), fs.list(copied).map { it.name })
        assertEquals("new", read(fs, fs.list(copied).single()))
    }

    @Test
    fun overwriteSafFileReplacesContents() {
        val fs = SafFileSystem(MemoryDocumentsBackend()) { listOf(location) }
        val engine = engine(fs)
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        val dest = fs.mkdir(root, "dest")
        val srcDir = fs.mkdir(root, "src")
        write(fs, srcDir, "note.txt", "new")
        write(fs, dest, "note.txt", "old")

        val event = runCopy(
            engine,
            FileOp.Copy(listOf(fs.list(srcDir).single()), dest),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        assertEquals("new", read(fs, fs.list(dest).single { it.name == "note.txt" }))
    }

    @Test
    fun failedSafCopyRemovesPartialDestination() {
        val inner = MemoryDocumentsBackend()
        var failWrites = false
        val backend = object : DocumentsBackend by inner {
            override fun openOut(treeUri: String, documentId: String): OutputStream {
                if (failWrites) {
                    inner.openOut(treeUri, documentId).close()
                    throw IOException("write failed")
                }
                return inner.openOut(treeUri, documentId)
            }
        }
        val fs = SafFileSystem(backend) { listOf(location) }
        val engine = engine(fs)
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        val dest = fs.mkdir(root, "dest")
        val srcDir = fs.mkdir(root, "src")
        write(fs, srcDir, "note.txt", "hello")
        failWrites = true

        val event = runCopy(engine, FileOp.Copy(listOf(fs.list(srcDir).single()), dest))

        assertFalse(event.success)
        assertTrue(fs.list(dest).none { it.name == "note.txt" })
    }

    @Test
    fun moveOfLocationRootFailsWithoutCopying() {
        val fs = SafFileSystem(MemoryDocumentsBackend()) { listOf(location) }
        val destFs = MemoryPathFileSystem()
        val dest = destFs.mkdir(destFs.rootEntry(), "out")
        val registry = FsRegistry().apply {
            register(fs)
            register(destFs)
        }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder())
        val root = checkNotNull(fs.stat(XId.saf("loc")))
        write(fs, root, "keep.txt", "keep")

        val event = runCopy(engine, FileOp.Copy(listOf(root), dest, move = true))

        assertFalse(event.success)
        assertTrue(event.message, event.message.contains("Cannot move"))
        assertTrue(destFs.list(dest).isEmpty())
        assertEquals(listOf("keep.txt"), fs.list(root).map { it.name })
    }

    private fun engine(fs: SafFileSystem): DefaultOperationEngine {
        val registry = FsRegistry().apply { register(fs) }
        return DefaultOperationEngine(scope, registry, temporaryFolder.newFolder())
    }

    private fun write(fs: SafFileSystem, parent: XEntry, name: String, text: String) {
        if (fs.list(parent).none { it.name == name }) fs.createFile(parent, name)
        fs.openOut(parent, name).use { it.write(text.toByteArray()) }
    }

    private fun read(fs: SafFileSystem, entry: XEntry): String =
        fs.openIn(entry).use { it.readBytes().decodeToString() }

    private fun runCopy(
        engine: DefaultOperationEngine,
        op: FileOp.Copy,
        conflict: ConflictResolution? = null,
    ): OpEvent {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<OpEvent>(1)
        val collectJob = scope.launch {
            engine.events.collect { event ->
                holder[0] = event
                latch.countDown()
            }
        }
        Thread.sleep(50)
        val running = engine.submit(op)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && holder[0] == null) {
            if (conflict != null && running.pendingConflict.value != null) {
                running.resolveConflict(conflict)
            }
            Thread.sleep(10)
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "op did not finish: ${running.progress.value}" }
        collectJob.cancel()
        return holder[0]!!
    }
}

private class MemoryPathFileSystem : XFileSystem {
    override val scheme: String = XId.SCHEME_FILE

    private class Node(
        val isDir: Boolean,
        var bytes: ByteArray = ByteArray(0),
        val children: MutableMap<String, Node> = linkedMapOf(),
    )

    private val root = Node(isDir = true)

    fun rootEntry(): XEntry = toEntry("/", "root", root)

    override fun list(dir: XEntry): List<XEntry> {
        val node = walk(dir.path) ?: return emptyList()
        if (!node.isDir) return emptyList()
        return node.children.map { (name, child) -> toEntry(join(dir.path, name), name, child) }
    }

    override fun stat(id: String): XEntry? {
        val path = id.substringAfter("://")
        val node = walk(path) ?: return null
        val name = if (path == "/" || path.isEmpty()) "root" else path.substringAfterLast('/')
        return toEntry(path.ifEmpty { "/" }, name, node)
    }

    override fun openIn(entry: XEntry): InputStream {
        val node = walk(entry.path) ?: throw IOException("missing")
        if (node.isDir) throw IOException("is a folder")
        return ByteArrayInputStream(node.bytes)
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        val parent = walk(parentDir.path) ?: throw IOException("missing")
        val node = parent.children.getOrPut(name) { Node(isDir = false) }
        if (node.isDir) throw IOException("$name is a folder")
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                node.bytes = toByteArray()
            }
        }
    }

    override fun createFile(parentDir: XEntry, name: String): XEntry {
        val parent = walk(parentDir.path) ?: throw IOException("missing")
        if (parent.children.containsKey(name)) throw FileAlreadyExistsException(name)
        val node = Node(isDir = false)
        parent.children[name] = node
        return toEntry(join(parentDir.path, name), name, node)
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        val parent = walk(parentDir.path) ?: throw IOException("missing")
        val existing = parent.children[name]
        if (existing != null) {
            if (existing.isDir) return toEntry(join(parentDir.path, name), name, existing)
            throw IOException("$name already exists")
        }
        val node = Node(isDir = true)
        parent.children[name] = node
        return toEntry(join(parentDir.path, name), name, node)
    }

    override fun delete(entry: XEntry) {
        if (entry.path == "/" || entry.path.isEmpty()) throw IOException("Cannot delete root")
        val trimmed = entry.path.trimEnd('/')
        val name = trimmed.substringAfterLast('/')
        val parentPath = trimmed.substringBeforeLast('/', "")
        val parent = walk(if (parentPath.isEmpty()) "/" else parentPath) ?: throw IOException("missing")
        parent.children.remove(name) ?: throw IOException("missing")
    }

    override fun rename(entry: XEntry, newName: String): XEntry =
        throw IOException("not used")

    override fun canWrite(entry: XEntry): Boolean = true

    private fun walk(path: String): Node? {
        if (path.isEmpty() || path == "/") return root
        var node = root
        for (part in path.trim('/').split('/')) {
            node = node.children[part] ?: return null
        }
        return node
    }

    private fun join(parent: String, name: String): String =
        if (parent == "/" || parent.isEmpty()) "/$name" else "$parent/$name"

    private fun toEntry(path: String, name: String, node: Node) = XEntry(
        id = XId.file(path),
        name = name,
        isDir = node.isDir,
        size = if (node.isDir) -1L else node.bytes.size.toLong(),
        canWrite = true,
        kind = if (node.isDir) EntryKind.DIR else EntryKind.FILE,
    )
}
