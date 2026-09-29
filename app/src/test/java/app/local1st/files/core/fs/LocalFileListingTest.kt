package app.local1st.files.core.fs

import app.local1st.files.core.util.ExternalOpenResolver
import app.local1st.files.core.util.FileTypes
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileListingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fs = LocalFileSystem()

    @Test
    fun directoryListingIncludesChildCountAndMtime() {
        val parent = temporaryFolder.newFolder("docs")
        val nested = File(parent, "nested").apply { mkdir() }
        File(nested, "c.txt").writeText("c")
        File(nested, "d.txt").writeText("d")
        File(parent, "empty").mkdir()

        val kids = fs.list(entry(parent, isDir = true))

        val nestedEntry = kids.single { it.name == "nested" }
        assertTrue(nestedEntry.isDir)
        assertEquals(2, nestedEntry.childCountHint)
        assertTrue(nestedEntry.mtime > 0)

        assertEquals(0, kids.single { it.name == "empty" }.childCountHint)
    }

    @Test
    fun directoryChildCountSeparatesHiddenNames() {
        val parent = temporaryFolder.newFolder("docs")
        val nested = File(parent, "nested").apply { mkdir() }
        File(nested, "c.txt").writeText("c")
        File(nested, ".secret").writeText("s")
        File(nested, "d.txt").writeText("d")

        val nestedEntry = fs.list(entry(parent, isDir = true)).single { it.name == "nested" }
        assertEquals(3, nestedEntry.childCountHint)
        assertEquals(1, nestedEntry.hiddenChildCountHint)
    }

    @Test
    fun statAndRenameDoNotReaddirForFolderCounts() {
        val parent = temporaryFolder.newFolder("docs")
        val nested = File(parent, "nested").apply { mkdir() }
        File(nested, "c.txt").writeText("c")
        File(nested, "d.txt").writeText("d")

        val listed = fs.list(entry(parent, isDir = true)).single { it.name == "nested" }
        assertEquals(2, listed.childCountHint)
        assertEquals(-1, fs.stat(XId.file(nested.absolutePath))!!.childCountHint)

        val renamed = fs.rename(entry(nested, isDir = true), "nested2")
        assertEquals("nested2", renamed.name)
        assertEquals(-1, renamed.childCountHint)
    }

    @Test
    fun volumeRootOmitsTheRecycleBinDirectory() {
        val volume = temporaryFolder.newFolder("vol")
        File(volume, ".xfiles-trash").mkdirs()
        File(volume, "Download").mkdir()
        val nested = File(volume, "Download/.xfiles-trash").apply { mkdirs() }
        val fs = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })

        val rootNames = fs.list(entry(volume, isDir = true)).map { it.name }
        assertTrue(rootNames.contains("Download"))
        assertFalse(rootNames.contains(".xfiles-trash"))

        val nestedNames = fs.list(entry(File(volume, "Download"), isDir = true)).map { it.name }
        assertTrue(nestedNames.contains(nested.name))
    }

    @Test
    fun volumeRootOmitsADifferentlyCasedRecycleBin() {
        val volume = temporaryFolder.newFolder("vol")
        File(volume, ".XFILES-TRASH").mkdirs()
        File(volume, "keep").mkdir()
        val fs = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })

        val names = fs.list(entry(volume, isDir = true)).map { it.name }

        assertTrue(names.contains("keep"))
        assertTrue(names.none { it.equals(".xfiles-trash", ignoreCase = true) })
    }

    @Test
    fun missingVolumeStillOmitsTheRecycleBinDirectory() {
        val volume = temporaryFolder.newFolder("vol")
        val other = temporaryFolder.newFolder("other")
        File(volume, ".xfiles-trash/files").mkdirs()
        File(volume, "keep").mkdir()
        val fs = LocalFileSystem(volumeRoots = { listOf(other.absolutePath) })

        val names = fs.list(entry(volume, isDir = true)).map { it.name }
        assertTrue(names.contains("keep"))
        assertFalse(names.contains(".xfiles-trash"))
    }

    @Test
    fun listingInsideTheBinShowsItsChildren() {
        val volume = temporaryFolder.newFolder("vol")
        val folder = File(volume, ".xfiles-trash/files/abc-00000001/photos").apply { mkdirs() }
        File(folder, "trip").mkdir()
        val fs = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })

        val names = fs.list(entry(folder, isDir = true)).map { it.name }
        assertTrue(names.contains("trip"))
    }

    @Test
    fun danglingSymlinkStillCountsAsPresent() {
        val volume = temporaryFolder.newFolder("vol")
        val link = File(volume, "gone")
        Files.createSymbolicLink(link.toPath(), File(volume, "missing").toPath())

        assertTrue(nodeSurvivedDelete(link))
        assertTrue(link.delete())
        assertFalse(nodeSurvivedDelete(link))
    }

    @Test
    fun trashedDirectorySymlinkDoesNotExposeOrDeleteTheTarget() {
        val volume = temporaryFolder.newFolder("vol")
        val target = File(volume, "DCIM").apply { mkdirs() }
        val photo = File(target, "a.jpg").apply { writeText("pic") }
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val link = File(bucket, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val fs = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })

        val row = fs.stat(XId.file(link.absolutePath))!!
        assertFalse(row.isDir)
        assertNull(row.localPath)
        assertTrue(fs.list(row).isEmpty())

        val throughLink = File(link, "a.jpg")
        assertThrows(IOException::class.java) { fs.delete(entry(throughLink, isDir = false)) }
        assertEquals("pic", photo.readText())

        fs.delete(row)
        assertFalse(link.exists())
        assertTrue(photo.exists())
    }

    @Test
    fun permanentDeleteLeavesTheReadyCopyWhenTheFileCannotBeRemoved() {
        val parent = temporaryFolder.newFolder("notes")
        val file = File(parent, "note").apply { writeText("short") }
        val ready = File(parent, ".note.xfiles-ready").apply { writeText("complete") }
        assertTrue(parent.setWritable(false))
        try {
            assertThrows(IOException::class.java) { fs.delete(entry(file, isDir = false)) }
            assertEquals("short", file.readText())
            assertEquals("complete", ready.readText())
        } finally {
            parent.setWritable(true)
        }
    }

    @Test
    fun listingIncludesAChildOnlyTheGrantNames() {
        val parent = temporaryFolder.newFolder("docs")
        File(parent, "seen").writeText("a")
        val hidden = File(parent, "hidden")
        val fs = LocalFileSystem(
            grantedChildren = { dir ->
                if (dir.absolutePath == parent.absolutePath) listOf("hidden" to false) else null
            },
            openGranted = { file ->
                if (file.absolutePath == hidden.absolutePath) "body".byteInputStream() else null
            },
        )

        val rows = fs.list(entry(parent, isDir = true))
        val names = rows.map { it.name }
        assertTrue(names.contains("seen"))
        assertTrue(names.contains("hidden"))
        assertEquals("body", fs.openIn(entry(hidden, isDir = false)).bufferedReader().readText())
    }

    @Test
    fun grantOnlyFileKeepsItsViewerKind() {
        val parent = temporaryFolder.newFolder("media")
        val fs = LocalFileSystem(
            grantedChildren = { dir ->
                if (dir.absolutePath == parent.absolutePath) {
                    listOf("photo.jpg" to false, "notes.zip" to false)
                } else {
                    null
                }
            },
        )

        val rows = fs.list(entry(parent, isDir = true)).associateBy { it.name }

        assertEquals(EntryKind.FILE, rows.getValue("photo.jpg").kind)
        assertFalse(rows.getValue("photo.jpg").isDir)
        assertEquals(EntryKind.ARCHIVE, rows.getValue("notes.zip").kind)
    }

    @Test
    fun deleteRemovesGrantOnlyChildrenBeforeAnEmptyDirectory() {
        val parent = temporaryFolder.newFolder("docs")
        val dir = File(parent, "folder").apply { mkdirs() }
        val deleted = ArrayList<String>()
        val fs = LocalFileSystem(
            grantedChildren = { file ->
                if (file.absolutePath == dir.absolutePath) listOf("hidden" to false) else null
            },
            deleteUnseenGranted = { file -> deleted += file.name },
        )

        fs.delete(entry(dir, isDir = true))

        assertFalse(dir.exists())
        assertEquals(listOf("hidden"), deleted)
    }

    @Test
    fun deleteOfADirectorySymlinkDoesNotRemoveTheTarget() {
        val root = temporaryFolder.newFolder("vol")
        val target = File(root, "DCIM").apply { mkdirs() }
        val photo = File(target, "a.jpg").apply { writeText("pic") }
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        var deletedGrantChild = false
        val fs = LocalFileSystem(
            grantedChildren = { dir ->
                if (dir.absolutePath == link.absolutePath) listOf("a.jpg" to false) else null
            },
            deleteUnseenGranted = { file ->
                deletedGrantChild = true
                file.delete()
            },
        )

        fs.delete(entry(link, isDir = false))

        assertFalse(deletedGrantChild)
        assertEquals("pic", photo.readText())
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertTrue(target.isDirectory)
    }

    @Test
    fun deleteWalksGrantOnlyFilesInsideAListedDirectory() {
        val parent = temporaryFolder.newFolder("trashed")
        val sub = File(parent, "sub").apply { mkdir() }
        val deleted = ArrayList<String>()
        val fs = LocalFileSystem(
            grantedChildren = { dir ->
                when (dir.absolutePath) {
                    parent.absolutePath -> listOf("sub" to true)
                    sub.absolutePath -> listOf("secret" to false)
                    else -> null
                }
            },
            deleteUnseenGranted = { file -> deleted += file.name },
        )

        fs.delete(entry(parent, isDir = true))

        assertEquals(listOf("secret"), deleted)
        assertFalse(parent.exists())
    }

    @Test
    fun deleteRemovesAReadySiblingOnlyTheGrantNames() {
        val parent = temporaryFolder.newFolder("notes")
        val file = File(parent, "note").apply { writeText("short") }
        val deleted = ArrayList<String>()
        val fs = LocalFileSystem(
            grantedNames = { dir ->
                if (dir.absolutePath == parent.absolutePath) listOf(".note.xfiles-ready") else null
            },
            deleteUnseenGranted = { target -> deleted += target.name },
        )

        fs.delete(entry(file, isDir = false))

        assertFalse(file.exists())
        assertEquals(listOf(".note.xfiles-ready"), deleted)
    }

    @Test
    fun deletingAFileFileCanSeeDoesNotAskTheGrantForChildren() {
        val parent = temporaryFolder.newFolder("notes")
        val file = File(parent, "note").apply { writeText("x") }
        val folder = File(parent, "sub").apply { mkdirs() }
        val asked = ArrayList<String>()
        val fs = LocalFileSystem(grantedChildren = { dir -> asked += dir.name; null })

        fs.delete(entry(file, isDir = false))
        fs.delete(entry(folder, isDir = true))

        assertFalse(file.exists())
        assertFalse(folder.exists())
        // A folder can still hide grant-only children, so only it asks.
        assertEquals(listOf("sub"), asked)
    }

    @Test
    fun deletesOfOneFolderShareOneSiblingListing() {
        val parent = temporaryFolder.newFolder("notes")
        val files = (0 until 20).map { File(parent, "n$it").apply { writeText("x") } }
        val ready = File(parent, ".n3.xfiles-ready").apply { writeText("saved") }
        val tmp = File(parent, ".n3.xfiles-tmp.12").apply { writeText("part") }
        // n30 is not deleted, so its save stays.
        val other = File(parent, ".n30.xfiles-ready").apply { writeText("other") }
        val grantReads = ArrayList<String>()
        val unseen = ArrayList<String>()
        val fs = LocalFileSystem(
            grantedNames = { dir ->
                grantReads += dir.name
                listOf(".n7.xfiles-ready")
            },
            deleteUnseenGranted = { target -> unseen += target.name },
        )
        val scan = EditorSiblingScan()

        // The selection can hold a sibling as well as its payload. The listing still names
        // it when n3 goes, and deleting a missing sibling is not an error.
        fs.delete(entry(ready, isDir = false), scan)
        for (file in files) fs.delete(entry(file, isDir = false), scan)

        assertEquals(listOf("notes"), grantReads)
        assertTrue(files.none { it.exists() })
        assertFalse(ready.exists())
        assertFalse(tmp.exists())
        assertTrue(other.exists())
        assertEquals(listOf(".n7.xfiles-ready"), unseen)
    }

    @Test
    fun permanentDeleteRemovesKnownSiblingsWhenTheDirectoryCannotBeListed() {
        val parent = temporaryFolder.newFolder("hidden")
        val file = File(parent, "note").apply { writeText("short") }
        val ready = File(parent, ".note.xfiles-ready").apply { writeText("complete") }
        val tmp = File(parent, ".note.xfiles-tmp").apply { writeText("tmp") }
        assertTrue(parent.setReadable(false))
        try {
            if (parent.listFiles() != null) return
            fs.delete(entry(file, isDir = false))
            assertFalse(nodeSurvivedDelete(file))
            assertFalse(nodeSurvivedDelete(ready))
            assertFalse(nodeSurvivedDelete(tmp))
        } finally {
            parent.setReadable(true)
        }
    }

    @Test
    fun deleteTrashBytesRemovesAnEmptyDirectoryThatCannotBeListed() {
        val dir = temporaryFolder.newFolder("unlistable")
        assertTrue(dir.setReadable(false))
        try {
            if (dir.listFiles() != null) return
            deleteTrashBytes(dir)
            assertFalse(nodeSurvivedDelete(dir))
        } finally {
            dir.setReadable(true)
        }
    }

    @Test
    fun directoryDeleteKeepsReadyCopyWhenThePayloadRemains() {
        val parent = temporaryFolder.newFolder("docs")
        val note = File(parent, "note").apply { mkdirs() }
        File(note, "locked").writeText("x")
        val ready = File(parent, ".note.xfiles-ready").apply { writeText("complete") }
        assertTrue(note.setWritable(false))
        try {
            assertThrows(IOException::class.java) { deleteTrashBytes(parent) }
            assertEquals("complete", ready.readText())
            assertTrue(File(note, "locked").exists())
        } finally {
            note.setWritable(true)
        }
    }

    @Test
    fun unresolvedPlainPathIsNotTreatedAsEnteringTheBin() {
        assertFalse(TrashPaths.crossesUnresolvedVolumeBin(lexicalInside = false, pathIsSymlink = false))
        assertFalse(TrashPaths.crossesUnresolvedVolumeBin(lexicalInside = true, pathIsSymlink = false))
    }

    @Test
    fun unresolvedSymlinkOutsideTheBinStaysRefused() {
        assertTrue(TrashPaths.crossesUnresolvedVolumeBin(lexicalInside = false, pathIsSymlink = true))
        assertFalse(TrashPaths.crossesUnresolvedVolumeBin(lexicalInside = true, pathIsSymlink = true))
    }

    @Test
    fun emptyMountListDoesNotWalkOrDeleteThroughABinDirectorySymlink() {
        val root = temporaryFolder.newFolder("vol")
        val live = File(root, "DCIM").apply { mkdirs() }
        val photo = File(live, "a.jpg").apply { writeText("pic") }
        val bucket = File(root, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val link = File(bucket, "link")
        Files.createSymbolicLink(link.toPath(), live.toPath())
        val fs = LocalFileSystem(volumeRoots = { emptyList() })

        assertTrue(fs.list(entry(link, isDir = true)).isEmpty())
        val row = fs.stat(XId.file(link.absolutePath))!!
        assertFalse(row.isDir)
        assertNull(row.localPath)
        assertThrows(IOException::class.java) {
            fs.delete(entry(File(link, "a.jpg"), isDir = false))
        }
        assertEquals("pic", photo.readText())
    }

    @Test
    fun binSymlinkIsRefusedBeforeVolumesAreMounted() {
        val root = temporaryFolder.newFolder("vol")
        val live = File(root, "live").apply { writeText("live") }
        val bucket = File(root, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val link = File(bucket, "link")
        Files.createSymbolicLink(link.toPath(), live.toPath())
        assertTrue(TrashPaths.isVolumeBinSymlink(link.absolutePath, emptyList()))
        val fs = LocalFileSystem(volumeRoots = { emptyList() })

        assertThrows(IOException::class.java) { fs.openIn(entry(link, isDir = false)) }
        assertEquals("live", live.readText())
        assertTrue(ExternalOpenResolver.refusesExternalFile(link.absolutePath, emptyList()))
        assertFalse(ExternalOpenResolver.refusesExternalFile(live.absolutePath, emptyList()))
    }

    @Test
    fun archiveNamedBinSymlinkIsAFileAndDoesNotOpenTheTarget() {
        val root = temporaryFolder.newFolder("vol")
        val live = File(root, "live.aab").apply { writeText("zip-bytes") }
        val bucket = File(root, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val link = File(bucket, "photos.aab")
        Files.createSymbolicLink(link.toPath(), live.toPath())
        val binFs = LocalFileSystem(volumeRoots = { listOf(root.absolutePath) })

        val row = binFs.stat(XId.file(link.absolutePath))!!
        assertEquals(EntryKind.FILE, row.kind)
        assertFalse(row.isDir)
        assertFalse(row.isContainer)
        assertNull(row.localPath)
        assertTrue(binFs.list(row).isEmpty())
        assertEquals("zip-bytes", live.readText())
    }

    @Test
    fun symlinkOutsideTheBinDoesNotListOrDeleteThePayload() {
        val root = temporaryFolder.newFolder("vol")
        val bucket = File(root, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val secret = File(bucket, "secret").apply { writeText("keep") }
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), bucket.toPath())
        val binFs = LocalFileSystem(volumeRoots = { listOf(root.absolutePath) })

        val listed = binFs.list(entry(root, isDir = true)).first { it.name == "link" }
        assertFalse(listed.isDir)
        assertEquals(EntryKind.FILE, listed.kind)
        assertNull(listed.localPath)
        assertTrue(listed.childCountHint < 0)
        assertTrue(binFs.list(entry(link, isDir = true)).isEmpty())
        assertThrows(IOException::class.java) {
            binFs.delete(entry(File(link, "secret"), isDir = false))
        }
        assertEquals("keep", secret.readText())
        assertTrue(secret.isFile)

        val archive = File(bucket, "photos.aab").apply { writeText("zip-bytes") }
        val archiveLink = File(root, "photos.aab")
        Files.createSymbolicLink(archiveLink.toPath(), archive.toPath())
        val archiveRow = binFs.list(entry(root, isDir = true)).first { it.name == "photos.aab" }
        assertEquals(EntryKind.FILE, archiveRow.kind)
        assertNull(archiveRow.localPath)
        assertEquals("zip-bytes", archive.readText())

        assertThrows(IOException::class.java) { binFs.openOut(entry(link, isDir = true), "secret") }
        assertEquals("keep", secret.readText())
        assertThrows(IOException::class.java) { binFs.mkdir(entry(link, isDir = true), "nested") }
        assertFalse(File(bucket, "nested").exists())
    }

    @Test
    fun symlinkInAUserTrashFolderCanBeSaved() {
        val root = temporaryFolder.newFolder("vol")
        val folder = File(root, "Download/.xfiles-trash").apply { mkdirs() }
        val live = File(root, "live.txt").apply { writeText("live") }
        val link = File(folder, "note")
        Files.createSymbolicLink(link.toPath(), live.toPath())
        val binFs = LocalFileSystem(volumeRoots = { listOf(root.absolutePath) })

        binFs.replaceRanges(entry(link, isDir = false), listOf(ByteRangeEdit(0, 4, "xxxx".toByteArray())))

        val landed = if (Files.isSymbolicLink(link.toPath())) live.readText() else link.readText()
        assertEquals("xxxx", landed)
    }

    @Test
    fun danglingSymlinkSourceIsNotTreatedAsMoved() {
        val dir = temporaryFolder.newFolder("links")
        val source = File(dir, "gone")
        Files.createSymbolicLink(source.toPath(), File(dir, "missing").toPath())
        val target = File(dir, "dest")

        assertFalse(movedSourceIsGone(source, target))

        assertTrue(source.renameTo(target))
        assertTrue(movedSourceIsGone(source, target))
        assertTrue(Files.isSymbolicLink(target.toPath()))
    }

    @Test
    fun staleTargetIsNotAFinishedRenameWhenTheSourceWasNeverANode() {
        val dir = temporaryFolder.newFolder("sd")
        val missing = File(dir, "grant-only")
        val occupied = File(dir, "dest").apply { writeText("old") }
        assertFalse(renameLanded(sourceWasNode = false, targetWasNode = true, missing, occupied))

        val source = File(dir, "real").apply { writeText("body") }
        val free = File(dir, "landed")
        assertTrue(source.renameTo(free))
        assertTrue(renameLanded(sourceWasNode = true, targetWasNode = false, source, free))
    }

    @Test
    fun grantOnlyFileUsesTheGrantLength() {
        val parent = temporaryFolder.newFolder("sd")
        val fs = LocalFileSystem(
            grantedChildren = { listOf("pic.jpg" to false) },
            grantSize = { file -> if (file.name == "pic.jpg") 12L else null },
        )

        val row = fs.list(entry(parent, isDir = true)).single()

        assertEquals("pic.jpg", row.name)
        assertEquals(12L, row.size)
        assertNull(row.localPath)
    }

    @Test
    fun mkdirDoesNotReuseTheLiveBin() {
        val volume = temporaryFolder.newFolder("vol")
        File(volume, ".xfiles-trash/files").mkdirs()
        val binFs = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })

        assertThrows(IOException::class.java) {
            binFs.mkdir(entry(volume, isDir = true), ".xfiles-trash")
        }

        assertTrue(File(volume, ".xfiles-trash/files").isDirectory)
        assertFalse(File(volume, ".xfiles-trash/photo").exists())

        assertThrows(IOException::class.java) {
            binFs.mkdir(entry(volume, isDir = true), ".XFILES-TRASH")
        }
        assertTrue(File(volume, ".xfiles-trash/files").isDirectory)
    }

    @Test
    fun createAndRenameDoNotAdoptTheBinName() {
        val volume = temporaryFolder.newFolder("vol")
        val binFs = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val notes = File(volume, "notes").apply { mkdir() }
        File(notes, "a.txt").writeText("a")

        assertThrows(IOException::class.java) {
            binFs.rename(entry(notes, isDir = true), ".xfiles-trash")
        }
        assertThrows(IOException::class.java) {
            binFs.rename(entry(notes, isDir = true), ".XFILES-TRASH")
        }
        assertEquals("a", File(notes, "a.txt").readText())
        assertFalse(File(volume, ".xfiles-trash").exists())

        assertThrows(IOException::class.java) {
            binFs.createFile(entry(volume, isDir = true), ".xfiles-trash")
        }
        assertFalse(File(volume, ".xfiles-trash").exists())
    }

    private fun entry(file: File, isDir: Boolean) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = isDir,
        localPath = file.absolutePath,
    )
}
