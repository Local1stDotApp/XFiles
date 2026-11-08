package app.local1st.files.ui.browser

import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevealRowTest {

    private val volume = XEntry(id = XId.file("/storage/emulated/0"), name = "Internal", isDir = true)
    private val dir = XEntry(id = XId.file("/storage/emulated/0/Download"), name = "Download", isDir = true)
    private val hit = XEntry(id = XId.file("/storage/emulated/0/Download/report.pdf"), name = "report.pdf", isDir = false)
    private val path = listOf(volume.id, dir.id, hit.id)

    @Test
    fun aListedHitIsItsOwnRowKeyedLikeTheTree() {
        val row = shownRowOnPath(
            path = path,
            roots = listOf(volume),
            expanded = setOf(volume.id, dir.id),
            children = mapOf(volume.id to listOf(dir), dir.id to listOf(hit)),
            showHidden = false,
        )
        assertEquals(ShownRow(hit.id, treeRowKey(treeRowKey(treeRowKey("", volume.id), dir.id), hit.id)), row)
        assertEquals("|${volume.id}|${dir.id}|${hit.id}", row?.key)
    }

    @Test
    fun aHiddenHitFallsBackToItsFolderUnlessHiddenFilesShow() {
        val dotFile = hit.copy(id = XId.file("/storage/emulated/0/Download/.notes"), name = ".notes", hidden = true)
        val children = mapOf(volume.id to listOf(dir), dir.id to listOf(dotFile))
        val dotPath = listOf(volume.id, dir.id, dotFile.id)
        val expanded = setOf(volume.id, dir.id)

        assertEquals(dir.id, shownRowOnPath(dotPath, listOf(volume), expanded, children, showHidden = false)?.id)
        assertEquals(dotFile.id, shownRowOnPath(dotPath, listOf(volume), expanded, children, showHidden = true)?.id)
    }

    @Test
    fun aHitMissingFromItsFolderListingFallsBackToTheFolder() {
        val row = shownRowOnPath(
            path = path,
            roots = listOf(volume),
            expanded = setOf(volume.id, dir.id),
            children = mapOf(volume.id to listOf(dir), dir.id to emptyList()),
            showHidden = false,
        )
        assertEquals(ShownRow(dir.id, "|${volume.id}|${dir.id}"), row)
    }

    @Test
    fun aCollapsedFolderHidesEverythingBelowIt() {
        val row = shownRowOnPath(
            path = path,
            roots = listOf(volume),
            expanded = setOf(volume.id),
            children = mapOf(volume.id to listOf(dir), dir.id to listOf(hit)),
            showHidden = false,
        )
        assertEquals(dir.id, row?.id)
    }

    @Test
    fun aPathOutsideThePaneRootsHasNoRow() {
        assertNull(shownRowOnPath(path, roots = emptyList(), emptySet(), emptyMap(), showHidden = false))
        assertNull(shownRowOnPath(emptyList(), listOf(volume), emptySet(), emptyMap(), showHidden = false))
    }

    @Test
    fun aBinnedFileIsReachedThroughTheBinRows() {
        val root = "/storage/emulated/0"
        val stored = "$root/.xfiles-trash/files/abc-00000001/trip"
        val binRoot = XEntry(id = XId.TRASH_ROOT, name = "Recycle bin", isDir = true)
        val trashedDir = XEntry(id = XId.file(stored), name = "trip", isDir = true)
        val photo = XEntry(id = XId.file("$stored/a.jpg"), name = "a.jpg", isDir = false)
        val binPath = checkNotNull(binRevealAncestors(photo.id, listOf(root))) + photo.id

        val row = shownRowOnPath(
            path = binPath,
            roots = listOf(volume, binRoot),
            expanded = setOf(XId.TRASH_ROOT, trashedDir.id),
            children = mapOf(XId.TRASH_ROOT to listOf(trashedDir), trashedDir.id to listOf(photo)),
            showHidden = false,
        )
        assertEquals(ShownRow(photo.id, "|${XId.TRASH_ROOT}|${trashedDir.id}|${photo.id}"), row)
    }
}
