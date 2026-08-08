package app.local1st.files.ui.browser

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.SessionDirectory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PaneExpansionPolicyTest {

    @Test
    fun openingFolderCollapsesOnlyItsSiblings() {
        val updated = expandWithCollapsedSiblings(
            expandedIds = setOf(
                "file:///root/a",
                "file:///root/b",
                "file:///other/x",
            ),
            openingId = "file:///root/b",
            topLevelIds = emptySet(),
        )

        assertEquals(setOf("file:///root/b", "file:///other/x"), updated)
    }

    @Test
    fun paneRootsAreVisualSiblingsEvenWhenUriParentsDiffer() {
        val volume = "file:///storage/emulated/0"
        val favorite = "file:///storage/emulated/0/DCIM"
        val apps = "apps://"

        val updated = expandWithCollapsedSiblings(
            expandedIds = setOf(volume, favorite),
            openingId = apps,
            topLevelIds = setOf(volume, favorite, apps),
        )

        assertEquals(setOf(apps), updated)
    }

    @Test
    fun openingAppCollapsesOnlyOtherAppsAndKeepsInstalledCategoryOpen() {
        val appsRoot = "apps://"
        val installed = "apps://@user"
        val firstApp = "apps://com.example.first"
        val secondApp = "apps://com.example.second"
        val visualParents = visualParentsOf(
            mapOf(
                appsRoot to listOf(XEntry(installed, "Installed", isDir = true)),
                installed to listOf(
                    XEntry(firstApp, "First", isDir = false, kind = EntryKind.APP),
                    XEntry(secondApp, "Second", isDir = false, kind = EntryKind.APP),
                ),
            ),
        )

        val updated = expandWithCollapsedSiblings(
            expandedIds = setOf(appsRoot, installed, firstApp),
            openingId = secondApp,
            topLevelIds = setOf(appsRoot),
            visualParents = visualParents,
        )

        assertEquals(setOf(appsRoot, installed, secondApp), updated)
    }

    @Test
    fun enablingPolicyKeepsVisualCategoryContainingFocusedApp() {
        val appsRoot = "apps://"
        val installed = "apps://@user"
        val system = "apps://@system"
        val app = "apps://com.example.app"
        val visualParents = visualParentsOf(
            mapOf(
                appsRoot to listOf(
                    XEntry(installed, "Installed", isDir = true),
                    XEntry(system, "System", isDir = true),
                ),
                installed to listOf(
                    XEntry(app, "Example", isDir = false, kind = EntryKind.APP),
                ),
            ),
        )

        val updated = retainOneExpandedSiblingPerGroup(
            expandedIds = setOf(appsRoot, installed, system, app),
            focusedId = app,
            topLevelIds = setOf(appsRoot),
            visualParents = visualParents,
        )

        assertEquals(setOf(appsRoot, installed, app), updated)
    }

    @Test
    fun enablingPolicyKeepsFocusedBranchAtEveryLoadedLevel() {
        val volume = "file:///volume"
        val apps = "apps://"
        val updated = retainOneExpandedSiblingPerGroup(
            expandedIds = setOf(
                volume,
                apps,
                "file:///volume/a",
                "file:///volume/b",
                "file:///volume/a/x",
                "file:///volume/a/y",
                "file:///volume/b/c",
                "file:///volume/b/d",
            ),
            focusedId = "file:///volume/b/d/deep",
            topLevelIds = setOf(volume, apps),
        )

        assertEquals(
            setOf(
                volume,
                "file:///volume/b",
                "file:///volume/a/x",
                "file:///volume/b/d",
            ),
            updated,
        )
    }

    @Test
    fun restoredIndexFallsBackToNearestVisibleAncestor() {
        val visible = listOf(
            "file:///root",
            "file:///root/a",
            "file:///root/a/visible",
        )

        assertEquals(
            1,
            restoredListIndex(visible, "file:///root/a/.hidden/deep"),
        )
        assertEquals(0, restoredListIndex(visible, null))
    }

    @Test
    fun restoreDropsAncestorsAboveCurrentPaneRoot() {
        val volume = "file:///storage/emulated/0"

        assertEquals(
            setOf(volume, "$volume/Ringtones"),
            reachableExpandedIds(
                expandedIds = setOf(
                    "file:///",
                    "file:///storage",
                    "file:///storage/emulated",
                    volume,
                    "$volume/Ringtones",
                ),
                topLevelIds = setOf(volume, "apps://"),
            ),
        )
    }

    @Test
    fun visualRestorePathSkipsSyntheticArchiveRoot() {
        val volume = "file:///storage/emulated/0"
        val archive = "$volume/files.zip"
        val folder = XId.zip("/storage/emulated/0/files.zip", "folder")

        assertEquals(
            listOf(volume, archive, folder),
            pathInsidePaneRoots(folder, setOf(volume, "apps://")),
        )
    }

    @Test
    fun focusedRestoreUsesFreshRootAndPersistedDescendants() {
        val volumeId = "file:///storage/emulated/0"
        val archiveId = "$volumeId/files.zip"
        val folderId = XId.zip("/storage/emulated/0/files.zip", "folder")
        val freshRoot = XEntry(volumeId, "Internal storage", isDir = true)
        val staleRoot = SessionDirectory.fromEntry(
            XEntry(volumeId, "Old label", isDir = true),
        )
        val archive = SessionDirectory.fromEntry(
            XEntry(
                id = archiveId,
                name = "files.zip",
                isDir = false,
                kind = EntryKind.ARCHIVE,
                localPath = "/storage/emulated/0/files.zip",
            ),
        )
        val folder = SessionDirectory.fromEntry(
            XEntry(folderId, "folder", isDir = true),
        )

        val hints = restorePathHints(
            focusedPath = listOf(volumeId, archiveId, folderId),
            desiredExpanded = setOf(volumeId, archiveId, folderId),
            paneRoots = listOf(freshRoot),
            savedDirectories = listOf(staleRoot, archive, folder),
        )

        assertSame(freshRoot, hints[0])
        assertEquals(EntryKind.ARCHIVE, hints[1].kind)
        assertEquals(folderId, hints[2].id)
    }

    @Test
    fun persistedDirectorySnapshotPrefersFreshTreeEntries() {
        val root = XEntry("file:///root", "root", isDir = true)
        val current = XEntry("file:///root/current", "Current", isDir = true)
        val deep = XEntry("file:///root/current/deep", "deep", isDir = true)
        val stale = SessionDirectory.fromEntry(current.copy(name = "Stale"))

        val saved = sessionDirectoriesFor(
            expandedIds = setOf(root.id, current.id, deep.id),
            focusedId = deep.id,
            paneRoots = listOf(root),
            children = mapOf(root.id to listOf(current), current.id to listOf(deep)),
            savedHints = listOf(stale),
        )

        assertEquals(listOf(root.id, current.id, deep.id), saved.map { it.id })
        assertEquals("Current", saved[1].name)
    }

    @Test
    fun renderSnapshotKeepsBoundedWindowAroundRestoredRow() {
        val nodes = (0 until 50).map { index ->
            TreeNode(
                entry = XEntry("file:///root/$index", index.toString(), isDir = false),
                key = "file:///root\u0000$index",
                depth = 1,
                expanded = false,
                loading = index == 45,
                guides = listOf(false),
                isLastChild = index == 49,
                error = if (index == 45) "transient" else null,
            )
        }

        val snapshot = checkNotNull(sessionRenderSnapshotFor(nodes, "file:///root/45"))

        assertEquals(32, snapshot.nodes.size)
        assertEquals("file:///root/18", snapshot.nodes.first().entry.id)
        assertEquals(27, snapshot.initialIndex)
        assertEquals("file:///root/45", snapshot.nodes[snapshot.initialIndex].entry.id)
        assertEquals("file:///root\u000045", snapshot.nodes[snapshot.initialIndex].key)
    }

    @Test
    fun restorePrefetchRunsIndependentDirectoriesConcurrently() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = RestoreListingSession(
            readFresh = {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, now) }
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                try {
                    release.await()
                    Result.success(emptyList())
                } finally {
                    active.decrementAndGet()
                }
            },
        )
        val dirs = listOf(
            XEntry("file:///a", "a", isDir = true),
            XEntry("file:///b", "b", isDir = true),
        )

        val prefetch = async { prefetchRestoreListings(dirs, session) }
        withTimeout(1_000) { bothStarted.await() }
        release.complete(Unit)
        prefetch.await()

        assertEquals(2, maxActive.get())
        session.close()
    }

    @Test
    fun restorePrefetchSerializesDirectoriesInsideOneArchive() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val archivePath = "/storage/emulated/0/files.zip"
        val session = RestoreListingSession(
            readFresh = {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, now) }
                try {
                    delay(20)
                    Result.success(emptyList())
                } finally {
                    active.decrementAndGet()
                }
            },
        )
        val archive = XEntry(
            id = XId.file(archivePath),
            name = "files.zip",
            isDir = false,
            kind = EntryKind.ARCHIVE,
            localPath = archivePath,
        )
        val inner = XEntry(
            id = XId.zip(archivePath, "folder"),
            name = "folder",
            isDir = true,
        )

        prefetchRestoreListings(listOf(archive, inner), session)

        assertEquals(1, maxActive.get())
        session.close()
    }

    @Test
    fun oneRestoreGenerationCoalescesConcurrentFreshListings() = runBlocking {
        val calls = AtomicInteger()
        val dir = XEntry(id = "file:///shared", name = "shared", isDir = true)
        val child = XEntry(id = "file:///shared/a", name = "a", isDir = false)
        val session = RestoreListingSession(
            readFresh = {
                calls.incrementAndGet()
                delay(20)
                Result.success(listOf(child))
            },
        )

        val results = listOf(
            async { session.list(dir) },
            async { session.list(dir) },
        ).awaitAll()

        assertEquals(listOf(child), results[0].getOrThrow())
        assertEquals(listOf(child), results[1].getOrThrow())
        assertEquals(listOf(child), session.list(dir).getOrThrow())
        assertEquals(1, calls.get())
        session.close()
    }
}
