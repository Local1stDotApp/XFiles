package app.local1st.files.core.ops

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.TrashMover
import app.local1st.files.core.fs.TrashPaths
import app.local1st.files.core.fs.TrashStore
import app.local1st.files.core.fs.TrashVolume
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
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

class RecycleBinOperationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
    fun userDeleteMovesALocalFileToTheBin() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("hello") }

        val event = runOp(harness.engine, FileOp.Delete(listOf(harness.entry(file))))

        assertTrue(event.message, event.success)
        assertTrue(event.message, event.message.contains("Recycle Bin"))
        assertFalse(file.exists())
        assertEquals("hello", File(harness.store.list().single().storedPath).readText())
    }

    @Test
    fun mixedDeleteProgressDoesNotClaimEverythingGoesToTheBin() {
        val harness = harness()
        val inside = File(harness.volume, "note").apply { writeText("a") }
        val outside = File(temporaryFolder.newFolder("other"), "loose").apply { writeText("b") }

        val running = harness.engine.submit(
            FileOp.Delete(listOf(harness.entry(inside), harness.entry(outside))),
        )

        assertTrue(running.progress.value.title.startsWith("Deleting"))
    }

    @Test
    fun permanentDeleteUnlinks() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("hello") }

        val event = runOp(
            harness.engine,
            FileOp.Delete(listOf(harness.entry(file)), permanent = true),
        )

        assertTrue(event.message, event.success)
        assertFalse(file.exists())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun permanentDeleteListsEachFolderOnceForItsSiblings() {
        val volume = temporaryFolder.newFolder("vol")
        val grantReads = ArrayList<String>()
        val local = LocalFileSystem(grantedNames = { dir -> grantReads += dir.name; null })
        val harness = harness(volume = volume, local = local)
        val files = (0 until 30).map { File(volume, "n$it").apply { writeText("x") } }
        val ready = File(volume, ".n4.xfiles-ready").apply { writeText("complete") }

        val event = runOp(
            harness.engine,
            FileOp.Delete(files.map(harness::entry), permanent = true),
        )

        assertTrue(event.message, event.success)
        assertTrue(files.none { it.exists() })
        assertFalse(ready.exists())
        // One read of the folder for all 30 deletes.
        assertEquals(listOf("vol"), grantReads)
    }

    @Test
    fun permanentDeleteRemovesEditorSiblingsAndLeavesOtherTemps() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("short") }
        val ready = File(harness.volume, ".note.xfiles-ready").apply { writeText("complete") }
        val tmp = File(harness.volume, ".note.xfiles-tmp.1").apply { writeText("tmp") }
        val other = File(harness.volume, ".other.xfiles-ready").apply { writeText("keep") }

        val event = runOp(
            harness.engine,
            FileOp.Delete(listOf(harness.entry(file)), permanent = true),
        )

        assertTrue(event.message, event.success)
        assertFalse(file.exists())
        assertFalse(ready.exists())
        assertFalse(tmp.exists())
        assertEquals("keep", other.readText())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun deleteOutsideAVolumeStillUnlinks() {
        val harness = harness()
        val file = temporaryFolder.newFile("loose").apply { writeText("x") }

        val event = runOp(harness.engine, FileOp.Delete(listOf(harness.entry(file))))

        assertTrue(event.message, event.success)
        assertFalse(file.exists())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun overwriteAndMoveDoNotCreateBinEntries() {
        val harness = harness()
        val srcDir = File(harness.volume, "src").apply { mkdir() }
        val destDir = File(harness.volume, "dest").apply { mkdir() }
        File(srcDir, "note").writeText("new")
        File(destDir, "note").writeText("old")
        val src = harness.entry(File(srcDir, "note"))
        val dest = harness.entry(destDir)

        val overwritten = runOp(
            harness.engine,
            FileOp.Copy(listOf(src), dest),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(overwritten.message, overwritten.success)
        assertEquals("new", File(destDir, "note").readText())
        assertTrue(File(srcDir, "note").exists())
        assertTrue(harness.store.list().isEmpty())

        val elsewhere = File(harness.volume, "elsewhere").apply { mkdir() }
        val moved = runOp(
            harness.engine,
            FileOp.Copy(listOf(src), harness.entry(elsewhere), move = true),
        )
        assertTrue(moved.message, moved.success)
        assertFalse(File(srcDir, "note").exists())
        assertEquals("new", File(elsewhere, "note").readText())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun overwriteLeavesTheOccupantWhenAPendingMountLooksReadOnly() {
        val volume = temporaryFolder.newFolder("vol")
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "SD", writable = false)) },
            unresolvedVolume = { true },
        )
        val local = LocalFileSystem()
        val registry = FsRegistry().apply { register(local) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)
        val srcDir = File(volume, "src").apply { mkdir() }
        val destDir = File(volume, "dest").apply { mkdir() }
        val source = File(srcDir, "note").apply { writeText("new") }
        val occupant = File(destDir, "note").apply { writeText("old") }

        val event = runOp(
            engine,
            FileOp.Copy(
                listOf(checkNotNull(local.stat(XId.file(source.absolutePath)))),
                checkNotNull(local.stat(XId.file(destDir.absolutePath))),
            ),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertFalse(event.message, event.success)
        assertTrue(event.message, event.message.contains("Storage is still mounting"))
        assertEquals("old", occupant.readText())
        assertEquals("new", source.readText())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun failedOverwriteRestoresTheDestinationTree() {
        val volume = temporaryFolder.newFolder("vol")
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val fs = object : XFileSystem by local {
            override fun openOut(parentDir: XEntry, name: String): java.io.OutputStream =
                throw IOException("full")
        }
        val harness = harness(volume = volume, local = local, fs = fs)
        val srcDir = File(volume, "src").apply { mkdir() }
        val destRoot = File(volume, "dest").apply { mkdir() }
        val srcFolder = File(srcDir, "folder").apply { mkdir() }
        File(srcFolder, "a.txt").writeText("new")
        val destFolder = File(destRoot, "folder").apply { mkdir() }
        File(destFolder, "a.txt").writeText("old")
        File(destFolder, "b.txt").writeText("keep")

        val event = runOp(
            harness.engine,
            FileOp.Copy(listOf(harness.entry(srcFolder)), harness.entry(destRoot)),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertFalse(event.message, event.success)
        assertEquals("old", File(destFolder, "a.txt").readText())
        assertEquals("keep", File(destFolder, "b.txt").readText())
        assertEquals("new", File(srcFolder, "a.txt").readText())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun restorePutsTheFileBackAndResolvesConflicts() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        File(harness.volume, "note").writeText("other")
        val stored = harness.entry(File(trashed.storedPath))

        val skipped = runOp(
            harness.engine,
            FileOp.Restore(listOf(stored)),
            ConflictResolution(ConflictChoice.SKIP),
        )
        assertTrue(skipped.message, skipped.success)
        assertEquals("other", File(harness.volume, "note").readText())
        assertEquals(1, harness.store.list().size)

        val renamed = runOp(
            harness.engine,
            FileOp.Restore(listOf(harness.entry(File(harness.store.list().single().storedPath)))),
            ConflictResolution(ConflictChoice.RENAME),
        )
        assertTrue(renamed.message, renamed.success)
        assertEquals("other", File(harness.volume, "note").readText())
        assertEquals("hello", File(harness.volume, "note (1)").readText())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun restorePathKeyIgnoresTurkishDotlessI() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals("/storage/img_001.jpg", restoreBatchPathKey("/storage/IMG_001.jpg"))
            assertEquals(
                restoreBatchPathKey("/storage/img_001.jpg"),
                restoreBatchPathKey("/storage/IMG_001.jpg"),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun restoreOverwriteAllKeepsAnEarlierRestore() {
        val harness = harness()
        val first = File(harness.volume, "note").apply { writeText("one") }
        val firstRecord = harness.store.trash(harness.entry(first))
        val second = File(harness.volume, "note").apply { writeText("two") }
        val secondRecord = harness.store.trash(harness.entry(second))
        File(harness.volume, "note").writeText("live")

        val event = runOp(
            harness.engine,
            FileOp.Restore(
                listOf(
                    harness.entry(File(firstRecord.storedPath)),
                    harness.entry(File(secondRecord.storedPath)),
                ),
            ),
            ConflictResolution(ConflictChoice.OVERWRITE, applyToAll = true),
        )

        assertTrue(event.message, event.success)
        assertTrue(event.message, event.message.contains("2"))
        assertEquals("one", File(harness.volume, "note").readText())
        assertEquals("two", File(harness.volume, "note (1)").readText())
        val binned = harness.store.list()
        assertEquals(1, binned.size)
        assertEquals("live", File(binned.single().storedPath).readText())
    }

    @Test
    fun restoreOverwriteAllDoesNotRebinANestedFileInsideItsFolder() {
        val harness = harness()
        val docs = File(harness.volume, "docs").apply { mkdirs() }
        val nested = File(docs, "a").apply { writeText("nested") }
        val fileRecord = harness.store.trash(harness.entry(nested))
        val folderRecord = harness.store.trash(harness.entry(docs))

        val event = runOp(
            harness.engine,
            FileOp.Restore(
                listOf(
                    harness.entry(File(fileRecord.storedPath)),
                    harness.entry(File(folderRecord.storedPath)),
                ),
            ),
            ConflictResolution(ConflictChoice.OVERWRITE, applyToAll = true),
        )

        assertTrue(event.message, event.success)
        assertEquals("nested", File(harness.volume, "docs/a").readText())
        assertTrue(File(harness.volume, "docs (1)").isDirectory)
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun restoreOverwriteAllRenamesWhenTheEarlierFileIsNotVisibleYet() {
        val local = LocalFileSystem()
        val harness = harness(fs = StatHidesSuffix(local, "/note"), local = local)
        val first = File(harness.volume, "note").apply { writeText("one") }
        val firstRecord = harness.store.trash(harness.entry(first))
        val second = File(harness.volume, "note").apply { writeText("two") }
        val secondRecord = harness.store.trash(harness.entry(second))

        val event = runOp(
            harness.engine,
            FileOp.Restore(
                listOf(
                    harness.entry(File(firstRecord.storedPath)),
                    harness.entry(File(secondRecord.storedPath)),
                ),
            ),
            ConflictResolution(ConflictChoice.OVERWRITE, applyToAll = true),
        )

        assertTrue(event.message, event.success)
        assertEquals("one", File(harness.volume, "note").readText())
        assertEquals("two", File(harness.volume, "note (1)").readText())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun failedOverwriteRestoreStillRefreshesTheFolderThatLostTheFile() {
        val harness = harness(mover = TrashMover { source, destDir ->
            if (!destDir.path.contains("${File.separator}.xfiles-trash")) return@TrashMover false
            val target = File(destDir, source.name)
            !target.exists() && source.renameTo(target)
        })
        val file = File(harness.volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        File(harness.volume, "note").writeText("other")

        val event = runOp(
            harness.engine,
            FileOp.Restore(listOf(harness.entry(File(trashed.storedPath)))),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertFalse(event.success)
        assertTrue(event.dirtyDirIds.contains(XId.file(harness.volume.absolutePath)))
        assertTrue(event.dirtyDirIds.contains(XId.TRASH_ROOT))
        assertFalse(File(harness.volume, "note").exists())
        assertEquals(2, harness.store.list().size)
    }

    @Test
    fun overwriteRestoreKeepsTheLandedFileWhenTheSidecarCannotBeDeleted() {
        val volume = temporaryFolder.newFolder("vol")
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            removeTree = { file ->
                if (file.name.endsWith(".trashinfo")) throw IOException("stuck sidecar")
                file.deleteRecursively()
            },
        )
        val local = LocalFileSystem()
        val registry = FsRegistry().apply { register(local) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)
        val file = File(volume, "note").apply { writeText("hello") }
        val trashed = store.trash(checkNotNull(local.stat(XId.file(file.absolutePath))))
        File(volume, "note").writeText("other")

        val event = runOp(
            engine,
            FileOp.Restore(listOf(checkNotNull(local.stat(XId.file(trashed.storedPath))))),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        assertEquals("hello", File(volume, "note").readText())
    }

    @Test
    fun restoreOverwriteReplacesTheOccupant() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        File(harness.volume, "note").writeText("other")

        val event = runOp(
            harness.engine,
            FileOp.Restore(listOf(harness.entry(File(trashed.storedPath)))),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        assertEquals("hello", File(harness.volume, "note").readText())
        val occupant = harness.store.list()
        assertEquals(1, occupant.size)
        assertEquals("other", File(occupant.single().storedPath).readText())
    }

    @Test
    fun restoreOverwriteReplacesAnOccupantWhoseCaseDiffers() {
        val volume = temporaryFolder.newFolder("vol")
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            childNames = { dir ->
                if (dir.absolutePath == volume.absolutePath) listOf("Note")
                else dir.list()?.toList()
            },
        )
        val local = LocalFileSystem()
        val engine = DefaultOperationEngine(
            scope,
            FsRegistry().apply { register(local) },
            temporaryFolder.newFolder(),
            store,
        )
        val original = File(volume, "Note").apply { writeText("hello") }
        val trashed = store.trash(checkNotNull(local.stat(XId.file(original.absolutePath))))
        File(volume, ".xfiles-trash/info").listFiles()
            ?.single { it.name.endsWith(".trashinfo") }
            ?.writeText("name=Note\npath=Note\ndeleted=1\n")
        File(volume, "note").writeText("other")

        val event = runOp(
            engine,
            FileOp.Restore(listOf(checkNotNull(local.stat(XId.file(trashed.storedPath))))),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        assertEquals("hello", File(volume, "Note").readText())
        assertEquals("other", File(store.list().single().storedPath).readText())
    }

    @Test
    fun restoreOverwriteDoesNotFollowASymlinkParent() {
        val harness = harness()
        val outside = temporaryFolder.newFolder("outside")
        val file = File(harness.volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        Files.createSymbolicLink(File(harness.volume, "linked").toPath(), outside.toPath())
        File(outside, "note").writeText("keep")
        File(harness.volume, ".xfiles-trash/info").listFiles()?.single()?.writeText(
            "name=note\npath=linked/note\ndeleted=1\n",
        )

        val event = runOp(
            harness.engine,
            FileOp.Restore(listOf(harness.entry(File(trashed.storedPath)))),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertFalse(event.message, event.success)
        assertEquals("keep", File(outside, "note").readText())
        assertEquals("hello", File(harness.store.list().single().storedPath).readText())
    }

    @Test
    fun restoreOverwriteParksABrokenSymlinkInTheBin() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        Files.createSymbolicLink(File(harness.volume, "note").toPath(), File(harness.volume, "missing").toPath())

        val event = runOp(
            harness.engine,
            FileOp.Restore(listOf(harness.entry(File(trashed.storedPath)))),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        assertEquals("hello", File(harness.volume, "note").readText())
        assertFalse(Files.isSymbolicLink(File(harness.volume, "note").toPath()))
        val parked = harness.store.list().single()
        assertTrue(Files.isSymbolicLink(File(parked.storedPath).toPath()))
    }

    @Test
    fun restoreOverwritePutsTheOccupantBackWhenTheMoveFails() {
        val volume = temporaryFolder.newFolder("vol")
        val mover = TrashMover { source, destDir ->
            val target = File(destDir, source.name)
            if (destDir.absolutePath == volume.absolutePath &&
                source.isFile &&
                source.readText() == "hello"
            ) {
                false
            } else {
                !target.exists() && source.renameTo(target)
            }
        }
        val harness = harness(volume, mover)
        val file = File(volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        File(volume, "note").writeText("other")

        val event = runOp(
            harness.engine,
            FileOp.Restore(listOf(harness.entry(File(trashed.storedPath)))),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertFalse(event.message, event.success)
        assertEquals("other", File(volume, "note").readText())
        val stillBinned = harness.store.list()
        assertEquals(1, stillBinned.size)
        assertEquals("hello", File(stillBinned.single().storedPath).readText())
    }

    @Test
    fun copyingATruncatedBinFileKeepsTheLongerReadyCopy() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("short") }
        val trashed = harness.store.trash(harness.entry(file))
        File(trashed.storedPath).writeText("comp")
        val ready = File(File(trashed.storedPath).parentFile, ".note.xfiles-ready").apply {
            writeText("complete")
        }
        val dest = temporaryFolder.newFolder("dest")

        val event = runOp(
            harness.engine,
            FileOp.Copy(
                listOf(harness.entry(File(trashed.storedPath))),
                harness.entry(dest),
                move = false,
            ),
        )

        assertTrue(event.message, event.success)
        assertEquals("complete", File(dest, "note").readText())
        assertEquals("complete", File(trashed.storedPath).readText())
        assertFalse(ready.exists())
        assertEquals(1, harness.store.list().size)
    }

    @Test
    fun movingATruncatedBinFileKeepsTheLongerReadyCopy() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("short") }
        val trashed = harness.store.trash(harness.entry(file))
        File(trashed.storedPath).writeText("comp")
        File(File(trashed.storedPath).parentFile, ".note.xfiles-ready").writeText("complete")
        val dest = temporaryFolder.newFolder("dest")

        val event = runOp(
            harness.engine,
            FileOp.Copy(
                listOf(harness.entry(File(trashed.storedPath))),
                harness.entry(dest),
                move = true,
            ),
        )

        assertTrue(event.message, event.success)
        assertEquals("complete", File(dest, "note").readText())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun moveRejectsABinNameThatEscapesTheDestination() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        val stored = File(trashed.storedPath)
        val dest = File(harness.volume, "out").apply { mkdir() }
        val victim = File(harness.volume, "escaped.txt").apply { writeText("keep") }
        val evil = harness.entry(stored).copy(name = "../escaped.txt")

        val event = runOp(
            harness.engine,
            FileOp.Copy(listOf(evil), harness.entry(dest), move = true),
        )

        assertFalse(event.message, event.success)
        assertTrue(stored.exists())
        assertEquals("hello", stored.readText())
        assertEquals("keep", victim.readText())
        assertFalse(File(dest, "escaped.txt").exists())
        assertEquals(1, harness.store.list().size)
    }

    @Test
    fun failedSidecarStillRefreshesTheSourceAndTheBin() {
        val volume = temporaryFolder.newFolder("vol")
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            mover = TrashMover { source, destDir ->
                if (source.path.contains("${File.separator}.xfiles-trash${File.separator}")) {
                    return@TrashMover false
                }
                val target = File(destDir, source.name)
                !target.exists() && source.renameTo(target)
            },
            writeText = { _, _ -> throw IOException("Cannot record Recycle Bin entry") },
        )
        val local = LocalFileSystem()
        val registry = FsRegistry().apply { register(local) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)
        val file = File(volume, "note").apply { writeText("hello") }

        val event = runOp(
            engine,
            FileOp.Delete(listOf(checkNotNull(local.stat(XId.file(file.absolutePath))))),
        )

        assertFalse(event.message, event.success)
        assertFalse(file.exists())
        assertTrue(event.dirtyDirIds.contains(XId.file(volume.absolutePath)))
        assertTrue(event.dirtyDirIds.contains(XId.TRASH_ROOT))
    }

    @Test
    fun mixedConfirmDoesNotUnlinkAnIdThatWasTrashable() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("keep") }
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "SD", writable = false)) },
        )
        val local = LocalFileSystem()
        val entry = checkNotNull(local.stat(XId.file(file.absolutePath)))
        val registry = FsRegistry().apply { register(local) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)

        val event = runOp(
            engine,
            FileOp.Delete(listOf(entry), trashableIds = setOf(entry.id)),
        )

        assertFalse(event.message, event.success)
        assertEquals("keep", file.readText())
    }

    @Test
    fun moveToBinConfirmationDoesNotUnlinkWhenTheVolumeIsReadOnly() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("keep") }
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "SD", writable = false)) },
        )
        val local = LocalFileSystem()
        val registry = FsRegistry().apply { register(local) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)

        val event = runOp(
            engine,
            FileOp.Delete(
                listOf(checkNotNull(local.stat(XId.file(file.absolutePath)))),
                mustTrash = true,
            ),
        )

        assertFalse(event.message, event.success)
        assertEquals("keep", file.readText())
    }

    @Test
    fun deleteTrashesWhenTheVolumeAppearsAfterTheFirstMountRead() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("hello") }
        var reads = 0
        val store = TrashStore(
            volumes = {
                reads++
                if (reads == 1) {
                    emptyList()
                } else {
                    listOf(TrashVolume(volume.absolutePath, "Internal", writable = true))
                }
            },
        )
        val local = LocalFileSystem()
        val registry = FsRegistry().apply { register(local) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)

        val event = runOp(
            engine,
            FileOp.Delete(listOf(checkNotNull(local.stat(XId.file(file.absolutePath))))),
        )

        assertTrue(event.message, event.success)
        assertFalse(file.exists())
        assertEquals("hello", File(store.list().single().storedPath).readText())
    }

    @Test
    fun pendingMountStillTrashesAResolvedFileAndKeepsAnOutsideFile() {
        val volume = temporaryFolder.newFolder("vol")
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            unresolvedVolume = { true },
        )
        val local = LocalFileSystem()
        val registry = FsRegistry().apply { register(local) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)
        val inside = File(volume, "note").apply { writeText("hello") }
        val outside = File(temporaryFolder.newFolder("other"), "loose").apply { writeText("keep") }

        val trashed = runOp(engine, FileOp.Delete(listOf(checkNotNull(local.stat(XId.file(inside.absolutePath))))))
        assertTrue(trashed.message, trashed.success)
        assertFalse(inside.exists())
        assertEquals("hello", File(store.list().single().storedPath).readText())

        val blocked = runOp(engine, FileOp.Delete(listOf(checkNotNull(local.stat(XId.file(outside.absolutePath))))))
        assertFalse(blocked.message, blocked.success)
        assertEquals("keep", outside.readText())
    }

    @Test
    fun copyOfAVolumeLeavesTheHiddenRecycleBinBehind() {
        val parent = temporaryFolder.newFolder("parent")
        val volume = File(parent, "vol").apply { mkdir() }
        val dest = File(parent, "out").apply { mkdir() }
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val harness = harness(volume = volume, local = local, fs = local)
        File(volume, "note").writeText("n")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "secret").writeText("keep")

        val event = runOp(
            harness.engine,
            FileOp.Copy(listOf(harness.entry(volume)), harness.entry(dest)),
        )

        assertTrue(event.message, event.success)
        assertEquals("n", File(dest, "vol/note").readText())
        assertFalse(File(dest, "vol/.xfiles-trash").exists())
        assertEquals("keep", File(bucket, "secret").readText())
    }

    @Test
    fun copyOfAFolderNamedLikeTheBinDoesNotMergeIntoIt() {
        val volume = temporaryFolder.newFolder("vol")
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val harness = harness(volume = volume, local = local, fs = local)
        val binSecret = File(volume, ".xfiles-trash/files/abc-00000001/secret").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }
        val folder = File(volume, "Download/.xfiles-trash").apply { mkdirs() }
        File(folder, "photo").writeText("pic")

        val event = runOp(
            harness.engine,
            FileOp.Copy(listOf(harness.entry(folder)), harness.entry(volume)),
        )

        assertTrue(event.message, event.success)
        assertEquals("keep", binSecret.readText())
        assertFalse(File(volume, ".xfiles-trash/photo").exists())
        assertEquals("pic", File(volume, ".xfiles-trash (1)/photo").readText())
    }

    @Test
    fun failedCompressNamedLikeTheBinDoesNotDeleteIt() {
        val volume = temporaryFolder.newFolder("vol")
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val deleted = ArrayList<String>()
        val fs = object : XFileSystem by local {
            override fun openOut(parentDir: XEntry, name: String): java.io.OutputStream =
                throw IOException("refused")

            override fun delete(entry: XEntry) {
                deleted += entry.path
                local.delete(entry)
            }
        }
        val harness = harness(volume = volume, local = local, fs = fs)
        val binSecret = File(volume, ".xfiles-trash/files/abc-00000001/secret").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }
        val note = File(volume, "note").apply { writeText("n") }

        val event = runOp(
            harness.engine,
            FileOp.Compress(listOf(harness.entry(note)), harness.entry(volume), ".XFILES-TRASH"),
        )

        assertFalse(event.message, event.success)
        assertEquals("keep", binSecret.readText())
        assertTrue(File(volume, ".xfiles-trash/files").isDirectory)
        assertTrue(deleted.none { it.contains(".xfiles-trash") || it.contains(".XFILES-TRASH") })
    }

    @Test
    fun moveFailsWhenTheHiddenBinCannotBeListed() {
        val parent = temporaryFolder.newFolder("parent")
        val volume = File(parent, "vol").apply { mkdir() }
        val dest = File(parent, "out").apply { mkdir() }
        File(dest, "vol").mkdir()
        File(volume, "note").writeText("n")
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            childIsDirectory = { file ->
                if (TrashPaths.samePath(file.path, TrashPaths.binRoot(volume.absolutePath))) true else null
            },
        )
        val engine = DefaultOperationEngine(
            scope,
            FsRegistry().apply { register(local) },
            temporaryFolder.newFolder(),
            store,
        )

        val event = runOp(
            engine,
            FileOp.Copy(
                listOf(checkNotNull(local.stat(XId.file(volume.absolutePath)))),
                checkNotNull(local.stat(XId.file(dest.absolutePath))),
                move = true,
            ),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertFalse(event.message, event.success)
        assertEquals("n", File(volume, "note").readText())
    }

    @Test
    fun slowMoveOfAVolumeKeepsTheHiddenRecycleBin() {
        val parent = temporaryFolder.newFolder("parent")
        val volume = File(parent, "vol").apply { mkdir() }
        val dest = File(parent, "out").apply { mkdir() }
        File(dest, "vol").mkdir()
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val harness = harness(volume = volume, local = local, fs = local)
        File(volume, "note").writeText("n")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "secret").writeText("keep")

        val event = runOp(
            harness.engine,
            FileOp.Copy(listOf(harness.entry(volume)), harness.entry(dest), move = true),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        assertEquals("n", File(dest, "vol/note").readText())
        assertEquals("keep", File(dest, "vol/.xfiles-trash/files/abc-00000001/secret").readText())
        assertFalse(File(bucket, "secret").exists())
    }

    @Test
    fun zipOfAVolumeOmitsTheHiddenRecycleBin() {
        val parent = temporaryFolder.newFolder("parent")
        val volume = File(parent, "vol").apply { mkdir() }
        val dest = File(parent, "out").apply { mkdir() }
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val harness = harness(volume = volume, local = local, fs = local)
        File(volume, "note").writeText("n")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "secret").writeText("keep")

        val event = runOp(
            harness.engine,
            FileOp.Compress(listOf(harness.entry(volume)), harness.entry(dest), "vol.zip"),
        )

        assertTrue(event.message, event.success)
        val names = java.util.zip.ZipFile(File(dest, "vol.zip")).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertTrue(names.any { it.endsWith("note") })
        assertTrue(names.none { it.contains(".xfiles-trash") })
        assertEquals("keep", File(bucket, "secret").readText())
    }

    @Test
    fun copyOfABinFolderCopiesSymlinksWithoutReadingTheTarget() {
        val volume = temporaryFolder.newFolder("vol")
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val harness = harness(volume = volume, local = local, fs = local)
        val target = File(volume, "live.txt").apply { writeText("live") }
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val folder = File(bucket, "folder").apply { mkdir() }
        val link = File(folder, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val dest = File(volume, "out").apply { mkdir() }

        val event = runOp(
            harness.engine,
            FileOp.Copy(listOf(harness.entry(folder)), harness.entry(dest)),
        )

        assertTrue(event.message, event.success)
        val copied = File(dest, "folder/link")
        assertTrue(Files.isSymbolicLink(copied.toPath()))
        assertEquals(target.toPath(), Files.readSymbolicLink(copied.toPath()))
        assertEquals("live", target.readText())
        assertTrue(link.exists())
    }

    @Test
    fun copyOfABinSymlinkReplacesAnExistingName() {
        val volume = temporaryFolder.newFolder("vol")
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val harness = harness(volume = volume, local = local, fs = local)
        val target = File(volume, "live").apply { writeText("live") }
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val link = File(bucket, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val dest = File(volume, "out").apply { mkdir() }
        File(dest, "link").writeText("old")

        val event = runOp(
            harness.engine,
            FileOp.Copy(listOf(harness.entry(link)), harness.entry(dest)),
            ConflictResolution(ConflictChoice.OVERWRITE),
        )

        assertTrue(event.message, event.success)
        val copied = File(dest, "link")
        assertTrue(Files.isSymbolicLink(copied.toPath()))
        assertEquals(target.toPath(), Files.readSymbolicLink(copied.toPath()))
        assertEquals("live", target.readText())
    }

    @Test
    fun movingABinItemDropsItsRestoreRecord() {
        val harness = harness()
        val file = File(harness.volume, "note").apply { writeText("hello") }
        val trashed = harness.store.trash(harness.entry(file))
        val destDir = File(harness.volume, "out").apply { mkdir() }

        val event = runOp(
            harness.engine,
            FileOp.Copy(
                listOf(harness.entry(File(trashed.storedPath))),
                harness.entry(destDir),
                move = true,
            ),
        )

        assertTrue(event.message, event.success)
        assertEquals("hello", File(destDir, "note").readText())
        assertTrue(harness.store.list().isEmpty())
    }

    @Test
    fun emptyDoesNotUnlinkAFolderWhileRestoreIsMovingIt() {
        val volume = temporaryFolder.newFolder("vol")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockMove = false
        val mover = TrashMover { source, destDir ->
            if (blockMove) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val target = File(destDir, source.name)
            !target.exists() && source.renameTo(target)
        }
        val local = LocalFileSystem(volumeRoots = { listOf(volume.absolutePath) })
        val deleted = ArrayList<String>()
        val fs = object : XFileSystem by local {
            override fun list(dir: XEntry): List<XEntry> {
                val file = File(dir.localPath ?: dir.path)
                return file.listFiles().orEmpty().map { child ->
                    XEntry(
                        id = XId.file(child.absolutePath),
                        name = child.name,
                        isDir = child.isDirectory,
                        kind = if (child.isDirectory) EntryKind.DIR else EntryKind.FILE,
                        localPath = child.absolutePath,
                    )
                }
            }

            override fun delete(entry: XEntry) {
                deleted += entry.path
                local.delete(entry)
            }
        }
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            mover = mover,
        )
        val engine = DefaultOperationEngine(
            scope,
            FsRegistry().apply { register(fs) },
            temporaryFolder.newFolder(),
            store,
        )
        val folder = File(volume, "folder").apply { mkdirs() }
        File(folder, "a.txt").writeText("a")
        File(folder, "b.txt").writeText("b")
        val record = store.trash(checkNotNull(local.stat(XId.file(folder.absolutePath))))
        blockMove = true
        val restored = CountDownLatch(1)
        scope.launch {
            try {
                store.restore(record, record.name)
            } finally {
                restored.countDown()
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val event = runOp(
            engine,
            FileOp.Delete(
                listOf(checkNotNull(local.stat(XId.file(record.storedPath)))),
                permanent = true,
            ),
        )

        assertFalse(event.message, event.success)
        assertTrue(event.message, event.message.contains("being restored"))
        assertTrue(deleted.isEmpty())
        assertEquals("a", File(record.storedPath, "a.txt").readText())
        assertEquals("b", File(record.storedPath, "b.txt").readText())
        release.countDown()
        assertTrue(restored.await(5, TimeUnit.SECONDS))
        assertEquals("a", File(volume, "folder/a.txt").readText())
        assertEquals("b", File(volume, "folder/b.txt").readText())
    }

    private fun harness(
        volume: File = temporaryFolder.newFolder("vol"),
        mover: TrashMover? = null,
        local: LocalFileSystem = LocalFileSystem(),
        fs: XFileSystem = local,
    ): Harness {
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            mover = mover ?: TrashMover { source, destDir ->
                val target = File(destDir, source.name)
                !target.exists() && source.renameTo(target)
            },
        )
        val registry = FsRegistry().apply { register(fs) }
        val engine = DefaultOperationEngine(scope, registry, temporaryFolder.newFolder(), store)
        return Harness(volume, store, engine, local)
    }

    private class StatHidesSuffix(
        private val local: LocalFileSystem,
        private val suffix: String,
    ) : XFileSystem by local {
        override fun stat(id: String): XEntry? = if (id.endsWith(suffix)) null else local.stat(id)
    }

    private fun runOp(
        engine: DefaultOperationEngine,
        op: FileOp,
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

    private class Harness(
        val volume: File,
        val store: TrashStore,
        val engine: DefaultOperationEngine,
        val local: LocalFileSystem,
    ) {
        fun entry(file: File): XEntry = checkNotNull(local.stat(XId.file(file.absolutePath))).let { stat ->
            if (file.isDirectory) stat else stat.copy(kind = EntryKind.FILE)
        }
    }
}
