package app.local1st.files.di

import app.local1st.files.R
import app.local1st.files.core.fs.AppsFileSystem
import app.local1st.files.core.fs.ArchiveFileSystem
import app.local1st.files.core.fs.DefaultRootsRepository
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.ResolverDocumentsBackend
import app.local1st.files.core.fs.RootFileSystem
import app.local1st.files.core.fs.SafFileSystem
import app.local1st.files.core.fs.SafLocationActions
import app.local1st.files.core.fs.TrashFileSystem
import app.local1st.files.core.fs.deleteTrashBytes
import app.local1st.files.core.fs.TrashStore
import app.local1st.files.core.fs.TrashVolume
import app.local1st.files.core.fs.VolumeTrashMover
import app.local1st.files.core.fs.unionFileAndGrantNames
import app.local1st.files.core.fs.writeUtf8Atomically
import java.nio.file.Files
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.priv.ShizukuGate
import app.local1st.files.core.ops.DefaultOperationEngine
import app.local1st.files.core.search.DefaultSearchEngine
import kotlinx.coroutines.launch

/** Wires concrete implementations into [Graph]. */
fun initGraph(graph: Graph) {
    ShizukuGate.initialize(Graph.appContext)

    // The local fs falls back to the privileged transport for directories File I/O cannot
    // read (Android/data and Android/obb under scoped storage), so share one instance.
    val volumeRoots = {
        runCatching { graph.roots.currentMountedVolumes().map { it.path } }.getOrDefault(emptyList())
    }
    val rootFs = RootFileSystem(volumeRoots)
    val localFs = LocalFileSystem(
        graph.legacySaf,
        privilegedFallback = rootFs,
        volumeRoots = volumeRoots,
    )
    val saf = graph.legacySaf
    val trash = TrashStore(
        volumes = {
            graph.roots.currentMountedVolumes().map {
                TrashVolume(
                    rootPath = it.path,
                    label = it.label,
                    writable = it.writable,
                )
            }
        },
        unresolvedVolume = { graph.roots.hasUnresolvedVolume() },
        mover = VolumeTrashMover(saf),
        ensureDirectory = { dir ->
            if (saf != null) saf.ensureDirectory(dir)
            else if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("Cannot create Recycle Bin")
        },
        writeText = { file, text ->
            if (saf != null) saf.writeUtf8(file, text)
            else writeUtf8Atomically(file, text)
        },
        removeTree = { file ->
            if (saf != null) saf.deletePath(file) else deleteTrashBytes(file)
        },
        renameFile = { file, newName ->
            val parent = file.parentFile
            val direct = parent != null && file.renameTo(java.io.File(parent, newName))
            direct || (saf != null && saf.renamePath(file, newName))
        },
        replaceFile = { from, onto ->
            val direct = runCatching {
                from.inputStream().use { input ->
                    onto.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }.getOrDefault(false)
            direct || (saf != null && saf.copyOver(from, onto))
        },
        childNames = { dir ->
            unionFileAndGrantNames(dir.list()?.toList(), saf?.persistedChildNames(dir))
        },
        childIsDirectory = { file -> saf?.persistedIsDirectory(file) },
        grantLength = { file ->
            saf?.persistedDocument(file)?.takeIf { !it.isDirectory }?.size
        },
        readText = { file ->
            if (Files.isSymbolicLink(file.toPath())) {
                null
            } else {
                runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
                    ?: saf?.readUtf8(file)
            }
        },
        openRead = { file ->
            if (file.isFile) {
                file.inputStream()
            } else {
                val document = saf?.persistedDocument(file)?.takeIf { !it.isDirectory }
                if (document == null) null else saf.openInput(document)
            }
        },
    )
    graph.trash = trash
    graph.fsRegistry.register(localFs)
    graph.fsRegistry.register(
        ArchiveFileSystem(
            volumeRoots = volumeRoots,
            grantStamp = { file ->
                val document = saf?.persistedDocument(file)?.takeIf { !it.isDirectory }
                    ?: return@ArchiveFileSystem null
                document.size to document.lastModified
            },
            openGrant = { file ->
                val document = saf?.persistedDocument(file)?.takeIf { !it.isDirectory }
                    ?: return@ArchiveFileSystem null
                saf.openInput(document)
            },
        ),
    )
    graph.fsRegistry.register(AppsFileSystem(Graph.appContext))
    graph.fsRegistry.register(rootFs)
    graph.fsRegistry.register(
        TrashFileSystem(
            trash,
            localFs,
            rootName = Graph.appContext.getString(R.string.recycle_bin),
        ) { volume ->
            Graph.appContext.getString(R.string.recycle_bin_unknown_location, volume)
        },
    )
    graph.fsRegistry.register(
        SafFileSystem(
            backend = ResolverDocumentsBackend(Graph.appContext),
            locations = { Graph.safLocations.value.orEmpty() },
        ),
    )

    graph.roots = DefaultRootsRepository(
        Graph.appContext,
        favorites = { Graph.favorites.value.orEmpty() },
        safLocations = { Graph.safLocations.value.orEmpty() },
        statById = { id -> Graph.fsRegistry.forId(id).stat(id) },
    )
    graph.locationActions = SafLocationActions(
        Graph.appContext,
        Graph.settings,
        volumes = { graph.roots.volumes() },
    )
    graph.opEngine = DefaultOperationEngine(
        Graph.appScope,
        graph.fsRegistry,
        Graph.appContext.cacheDir,
        trash,
    )
    graph.searchEngine = DefaultSearchEngine(graph.fsRegistry, volumeRoots)

    // Mirror the root-access settings into the process-wide gate consulted by the fs layer.
    // App-lifetime so file operations honor read-only mode even without a UI in the foreground.
    Graph.appScope.launch { Graph.settings.rootEnabled.collect { PrivilegedAccess.enabled = it } }
    Graph.appScope.launch { Graph.settings.rootReadOnly.collect { PrivilegedAccess.readOnly = it } }
    Graph.appScope.launch {
        Graph.settings.privilegedTransport.collect { PrivilegedAccess.preference = it }
    }
}
