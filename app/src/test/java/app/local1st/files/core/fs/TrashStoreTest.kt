package app.local1st.files.core.fs

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

class TrashStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun moveKeepsBytesAndSeparatesSameNames() {
        val volume = temporaryFolder.newFolder("vol")
        val camera = File(volume, "DCIM").apply { mkdirs() }
        val download = File(volume, "Download").apply { mkdirs() }
        val first = File(camera, "photo.jpg").apply { writeText("one") }
        val second = File(download, "photo.jpg").apply { writeText("two") }
        val ids = ArrayDeque(listOf("aaa-00000001", "bbb-00000002"))
        val store = store(volume, newId = { ids.removeFirst() })

        val a = store.trash(fileEntry(first))
        val b = store.trash(fileEntry(second))

        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals("one", File(a.storedPath).readText())
        assertEquals("two", File(b.storedPath).readText())
        assertEquals("DCIM/photo.jpg", a.originalRelativePath)
        assertEquals("Download/photo.jpg", b.originalRelativePath)
        assertEquals(setOf("aaa-00000001", "bbb-00000002"), store.list().map { it.id }.toSet())
        assertTrue(a.badge.contains("DCIM"))
        assertTrue(File(volume, ".xfiles-trash/.nomedia").isFile)
    }

    @Test
    fun moveDirectoryKeepsChildren() {
        val volume = temporaryFolder.newFolder("vol")
        val photos = File(volume, "photos").apply { mkdirs() }
        File(photos, "a.jpg").writeText("a")
        val store = store(volume)

        val record = store.trash(fileEntry(photos, isDir = true))

        assertFalse(photos.exists())
        assertEquals("a", File(record.storedPath, "a.jpg").readText())
    }

    @Test
    fun restoreRecreatesMissingParent() {
        val volume = temporaryFolder.newFolder("vol")
        val nested = File(volume, "sub/dir").apply { mkdirs() }
        val file = File(nested, "a.txt").apply { writeText("a") }
        val store = store(volume)
        val record = store.trash(fileEntry(file))
        File(volume, "sub").deleteRecursively()

        val restored = store.restore(record, "a.txt")

        assertEquals(File(volume, "sub/dir/a.txt").absolutePath, restored.absolutePath)
        assertEquals("a", restored.readText())
        assertTrue(store.list().isEmpty())
        assertFalse(File(volume, ".xfiles-trash/files/${record.id}").exists())
    }

    @Test
    fun restoreRefusesAnOccupiedName() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("old") }
        val store = store(volume)
        val record = store.trash(fileEntry(file))
        File(volume, "a.txt").writeText("new")

        assertThrows(IOException::class.java) { store.restore(store.lookup(record.storedPath)!!, "a.txt") }

        assertEquals("new", File(volume, "a.txt").readText())
        assertEquals("old", File(record.storedPath).readText())
        assertEquals(1, store.list().size)
    }

    @Test
    fun failedMoveThatAlreadyTookTheSourceRollsTheBytesBack() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("safe") }
        val mover = TrashMover { source, destDir ->
            val target = File(destDir, source.name)
            if (source.parentFile?.absolutePath == volume.absolutePath) {
                check(source.renameTo(target))
                return@TrashMover false
            }
            !target.exists() && source.renameTo(target)
        }
        val store = store(volume, mover = mover)

        assertThrows(IOException::class.java) { store.trash(fileEntry(file)) }

        assertEquals("safe", file.readText())
        assertTrue(store.list().isEmpty())
        assertTrue(File(volume, ".xfiles-trash/info").list().isNullOrEmpty())
    }

    @Test
    fun failedRenameLeavesTheSourceAndWritesNoInfo() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("safe") }
        val store = store(volume, mover = TrashMover { _, _ -> false })

        assertThrows(IOException::class.java) { store.trash(fileEntry(file)) }

        assertEquals("safe", file.readText())
        assertTrue(store.list().isEmpty())
        val info = File(volume, ".xfiles-trash/info")
        assertTrue(info.list().isNullOrEmpty())
    }

    @Test
    fun refusesVolumeRootTrashPathAndUnknownVolume() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val inside = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val trashed = File(inside, "a.txt").apply { writeText("a") }
        val outside = temporaryFolder.newFile("loose.txt")

        assertFalse(store.canTrash(fileEntry(volume, isDir = true).copy(kind = EntryKind.VOLUME_INTERNAL)))
        assertFalse(store.canTrash(fileEntry(trashed)))
        assertFalse(store.canTrash(fileEntry(outside)))
        assertFalse(store.canTrash(fileEntry(File(volume, "a.txt")).copy(canWrite = false)))
        val userBin = File(volume, "Download/.xfiles-trash").apply { mkdirs() }
        val nested = File(userBin, "keep.txt").apply { writeText("keep") }
        assertTrue(store.canTrash(fileEntry(nested)))
        assertEquals(
            DeleteDisposition.PERMANENT,
            store.disposition(listOf(fileEntry(outside)), permanent = false),
        )
    }

    @Test
    fun orphanIsListedAndPurgeRemovesBytesAndInfo() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("gone") }
        val store = store(volume, newId = { "abc-00000001" })
        val record = store.trash(fileEntry(file))
        store.purge(record)
        assertFalse(File(record.storedPath).exists())
        assertFalse(File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").exists())
        assertTrue(store.list().isEmpty())

        val bucket = File(volume, ".xfiles-trash/files/def-00000002").apply { mkdirs() }
        File(bucket, "orphan.txt").writeText("lost")
        val listed = store.list()
        assertEquals(1, listed.size)
        assertTrue(listed.single().orphan)
        assertTrue(listed.single().badge.contains("original location unknown"))
        store.purge(listed.single())
        assertTrue(store.list().isEmpty())
        assertFalse(bucket.exists())
    }

    @Test
    fun multiChildBucketIsEmptiedAsOneRow() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "a.txt").writeText("a")
        File(bucket, "b.txt").writeText("b")

        val listed = store.list().single()

        assertEquals(bucket.absolutePath, listed.storedPath)
        store.purge(listed)
        assertFalse(File(bucket, "a.txt").exists())
        assertFalse(File(bucket, "b.txt").exists())
        assertFalse(bucket.exists())
    }

    @Test
    fun differentlyCasedBinIsInsideTheVolumeBin() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, ".XFILES-TRASH/files/abc-00000001/a.txt")
        file.parentFile!!.mkdirs()
        file.writeText("a")
        val store = store(volume)

        assertTrue(TrashPaths.isInsideVolumeBin(file.absolutePath, listOf(volume.absolutePath)))
        assertEquals("a.txt", TrashPaths.topLevel(file.absolutePath)!!.name)
        assertFalse(store.canTrash(fileEntry(file)))
    }

    @Test
    fun trashRecordsTheBinWhenTheOldPathStillLooksPresent() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("safe") }
        val mover = TrashMover { source, destDir ->
            val target = File(destDir, source.name)
            check(source.copyTo(target).exists())
            true
        }
        val store = store(volume, mover = mover)

        val record = store.trash(fileEntry(file))

        assertTrue(file.exists())
        assertEquals("safe", File(record.storedPath).readText())
        assertEquals(listOf("a.txt"), store.list().map { it.name })
    }

    @Test
    fun restoreAcceptsAMoveBeforeTheDestinationFileIsVisible() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("safe") }
        val mover = TrashMover { source, destDir ->
            val target = File(destDir, source.name)
            if (source.path.contains("${File.separator}.xfiles-trash${File.separator}") &&
                destDir.absolutePath == volume.absolutePath
            ) {
                check(source.delete())
                return@TrashMover true
            }
            !target.exists() && source.renameTo(target)
        }
        val store = store(volume, mover = mover)
        val record = store.trash(fileEntry(file))

        val restored = store.restore(store.lookup(record.storedPath)!!, "a.txt")

        assertEquals(File(volume, "a.txt").absolutePath, restored.absolutePath)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun noteRemovedLeavesAUserFolderThatOnlyLooksLikeTheBin() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val fake = File(volume, "Download/.xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val file = File(fake, "keep.txt").apply { writeText("keep") }
        val info = File(volume, "Download/.xfiles-trash/info/abc-00000001.trashinfo")
            .apply { parentFile?.mkdirs(); writeText("name=keep.txt\npath=keep.txt\ndeleted=1\n") }

        assertFalse(store.noteRemoved(file.absolutePath))
        assertFalse(
            TrashPaths.isRestorableBinItem(file.absolutePath, listOf(volume.absolutePath)),
        )

        assertTrue(file.exists())
        assertTrue(info.exists())
    }

    @Test
    fun editorScratchDoesNotHideRestoreOfThePayload() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note.txt").apply { writeText("body") }
        val store = store(volume, newId = { "abc-00000001" })
        val record = store.trash(fileEntry(file))
        File(File(record.storedPath).parentFile, ".note.txt.xfiles-ready").writeText("tmp")
        File(File(record.storedPath).parentFile, ".note.txt.xfiles-tmp.1").writeText("tmp")

        val listed = store.list().single()

        assertEquals("note.txt", listed.name)
        assertEquals(record.storedPath, listed.storedPath)
        assertEquals("note.txt", TrashPaths.topLevel(listed.storedPath)?.name)
    }

    @Test
    fun noteRemovedDoesNotFailADeletedLookalikeOutsideTheVolume() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val outside = temporaryFolder.newFolder("data-local")
        val file = File(outside, ".xfiles-trash/files/abc-00000001/photo.jpg")
        file.parentFile!!.mkdirs()
        file.writeText("x")
        assertTrue(file.delete())

        assertFalse(store.noteRemoved(file.absolutePath))
    }

    @Test
    fun unresolvedReadOnlyFlagBlocksAFileThatCouldBeTrashed() {
        val volume = temporaryFolder.newFolder("vol")
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "SD", writable = false)) },
            unresolvedVolume = { true },
            now = { 1_700_000_000_000L },
        )
        val file = File(volume, "a.txt").apply { writeText("a") }
        val bin = File(volume, ".xfiles-trash/files/abc-00000001/a.txt")
        bin.parentFile!!.mkdirs()
        bin.writeText("kept")

        assertTrue(store.blocksUnresolved(fileEntry(file)))
        assertFalse(store.blocksUnresolved(fileEntry(bin)))
    }

    @Test
    fun unresolvedMountBlocksOnlyPathsOutsideResolvedVolumes() {
        val volume = temporaryFolder.newFolder("vol")
        var pending = true
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            unresolvedVolume = { pending },
            now = { 1_700_000_000_000L },
        )
        val inside = File(volume, "a.txt").apply { writeText("a") }
        val outside = File(temporaryFolder.newFolder("other"), "b.txt").apply { writeText("b") }

        assertFalse(store.blocksUnresolved(fileEntry(inside)))
        assertTrue(store.blocksUnresolved(fileEntry(outside)))
        pending = false
        assertFalse(store.blocksUnresolved(fileEntry(outside)))
    }

    @Test
    fun noteRemovedFailsWhenTheVolumeIsGone() {
        val volume = temporaryFolder.newFolder("vol")
        var online = true
        val store = TrashStore(
            volumes = {
                if (online) listOf(TrashVolume(volume.absolutePath, "Internal", writable = true))
                else emptyList()
            },
            now = { 1_700_000_000_000L },
            newId = { "abc-00000001" },
        )
        val file = File(volume, "a.txt").apply { writeText("a") }
        val record = store.trash(fileEntry(file))
        online = false
        volume.deleteRecursively()

        assertThrows(IOException::class.java) { store.noteRemoved(record.storedPath) }
    }

    @Test
    fun removingARecordDropsEditorScratchLeftInTheBucket() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note.txt").apply { writeText("body") }
        val store = store(volume, newId = { "abc-00000001" })
        val record = store.trash(fileEntry(file))
        val bucket = File(record.storedPath).parentFile!!
        File(bucket, ".note.txt.xfiles-ready").writeText("edit")
        File(bucket, ".note.txt.xfiles-tmp.1").writeText("tmp")

        store.purge(record)

        assertTrue(store.list().isEmpty())
        assertFalse(bucket.exists())
    }

    @Test
    fun invisibleSidecarWithoutAPayloadStaysListed() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo")
        info.parentFile!!.mkdirs()
        info.writeText("name=note\npath=note\ndeleted=1\n")

        val listed = store.list().single()

        assertEquals("note", listed.name)
        assertEquals(File(bucket, "note").absolutePath, listed.storedPath)
        assertTrue(info.exists())
        assertTrue(bucket.exists())
    }

    @Test
    fun orphanScanDoesNotAdoptAScratchFile() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, ".note.txt.xfiles-ready").writeText("edit")
        File(bucket, ".note.txt.xfiles-tmp.2").writeText("tmp")

        assertTrue(store.list().isEmpty())
        assertFalse(bucket.exists())
    }

    @Test
    fun loneScratchNamedPayloadWithoutSidecarStaysListed() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val payload = File(bucket, ".note.txt.xfiles-ready").apply { writeText("only-copy") }

        val listed = store.list().single()

        assertEquals(".note.txt.xfiles-ready", listed.name)
        assertTrue(listed.orphan)
        assertEquals(payload.absolutePath, listed.storedPath)
        assertEquals("only-copy", File(listed.storedPath).readText())
        assertTrue(payload.exists())
    }

    @Test
    fun nomediaSymlinkIsReplacedInsteadOfFollowed() {
        val volume = temporaryFolder.newFolder("vol")
        val outside = temporaryFolder.newFolder("outside")
        val target = File(outside, "planted")
        val trash = File(volume, ".xfiles-trash").apply { mkdirs() }
        Files.createSymbolicLink(File(trash, ".nomedia").toPath(), target.toPath())
        val file = File(volume, "note").apply { writeText("safe") }
        val store = store(volume)

        val record = store.trash(fileEntry(file))

        assertFalse(target.exists())
        assertFalse(Files.isSymbolicLink(File(trash, ".nomedia").toPath()))
        assertTrue(File(trash, ".nomedia").isFile)
        assertEquals("safe", File(record.storedPath).readText())
    }

    @Test
    fun deleteTrashBytesReportsADanglingLinkThatIsStillThere() {
        val dir = temporaryFolder.newFolder("locked")
        val link = File(dir, "gone")
        Files.createSymbolicLink(link.toPath(), File(dir, "missing").toPath())
        assertTrue(dir.setWritable(false))
        try {
            assertThrows(IOException::class.java) { deleteTrashBytes(link) }
            assertTrue(Files.isSymbolicLink(link.toPath()))
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun brokenSymlinkCanBeTrashedListedAndRestored() {
        val volume = temporaryFolder.newFolder("vol")
        val link = File(volume, "link")
        Files.createSymbolicLink(link.toPath(), File(volume, "missing").toPath())
        val store = store(volume)
        val record = store.trash(fileEntry(link))
        assertTrue(Files.isSymbolicLink(File(record.storedPath).toPath()))

        val row = TrashFileSystem(store, LocalFileSystem()).list(TrashFileSystem.rootEntry()).single()
        assertEquals("link", row.name)
        assertFalse(row.isDir)

        store.restore(store.lookup(record.storedPath)!!, "link")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun restoreRefusesASymlinkParent() {
        val volume = temporaryFolder.newFolder("vol")
        val outside = temporaryFolder.newFolder("outside")
        val link = File(volume, "linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val file = File(volume, "a.txt").apply { writeText("safe") }
        val store = store(volume, newId = { "abc-00000001" })
        val record = store.trash(fileEntry(file))
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").writeText(
            "name=a.txt\npath=linked/a.txt\ndeleted=1\n",
        )

        assertThrows(IOException::class.java) {
            store.restore(store.lookup(record.storedPath)!!, "a.txt")
        }

        assertEquals("safe", File(record.storedPath).readText())
        assertFalse(File(outside, "a.txt").exists())
        assertEquals(1, store.list().size)
    }

    @Test
    fun symlinkTrashinfoDoesNotSupplyTheRestorePath() {
        val volume = temporaryFolder.newFolder("vol")
        val outside = temporaryFolder.newFolder("outside")
        val decoy = File(outside, "decoy.trashinfo").apply {
            writeText("name=secret\npath=../outside/secret\ndeleted=1\n")
        }
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "secret").writeText("keep")
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo")
        info.parentFile?.mkdirs()
        Files.createSymbolicLink(info.toPath(), decoy.toPath())
        val store = store(
            volume,
            readText = { file ->
                runCatching { if (file.isFile) file.readText() else null }.getOrNull()
            },
        )

        val record = store.list().single()

        assertTrue(record.orphan)
        assertEquals("secret", record.originalRelativePath)
        assertEquals("secret", store.lookup(record.storedPath)?.originalRelativePath)
        assertEquals("keep", File(record.storedPath).readText())
    }

    @Test
    fun hiddenBinFollowsTheGrantWhenFileCannotSeeTheDirectory() {
        val volume = temporaryFolder.newFolder("vol")
        val bin = File(TrashPaths.binRoot(volume.absolutePath))
        val present = store(
            volume,
            childIsDirectory = { file -> if (file.absolutePath == bin.absolutePath) true else null },
        )
        assertEquals(HiddenBin.PRESENT, present.hiddenBin(volume.absolutePath))

        val absent = store(volume)
        assertEquals(HiddenBin.ABSENT, absent.hiddenBin(volume.absolutePath))

        val unlisted = temporaryFolder.newFile("not-a-dir")
        val unknown = TrashStore(
            volumes = { listOf(TrashVolume(unlisted.absolutePath, "SD", writable = true)) },
        )
        assertEquals(HiddenBin.UNKNOWN, unknown.hiddenBin(unlisted.absolutePath))
    }

    @Test
    fun recoverUsesTheBinRenameIncludingADirectoryChild() {
        val volume = temporaryFolder.newFolder("vol")
        val dir = File(volume, "docs").apply { mkdirs() }
        val child = File(dir, "note").apply { writeText("short") }
        var replacedReady = false
        val store = store(
            volume,
            replaceFile = { from, onto ->
                replacedReady = from.name.endsWith(".xfiles-ready")
                from.inputStream().use { input ->
                    onto.outputStream().use { output -> input.copyTo(output) }
                }
                true
            },
        )
        val record = store.trash(fileEntry(dir, isDir = true))
        val storedDir = File(record.storedPath)
        File(storedDir, "note").writeText("comp")
        File(storedDir, ".note.xfiles-ready").writeText("complete")

        store.recoverTruncatedEdit(record.storedPath)

        assertTrue(replacedReady)
        assertEquals("complete", File(storedDir, "note").readText())
        assertFalse(File(storedDir, ".note.xfiles-ready").exists())
    }

    @Test
    fun restoreKeepsTheLongerReadyCopy() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note.txt").apply { writeText("short") }
        val store = store(volume)
        val record = store.trash(fileEntry(file))
        File(record.storedPath).writeText("comp")
        File(File(record.storedPath).parentFile, ".note.txt.xfiles-ready").writeText("complete")

        val restored = store.restore(record, "note.txt")

        assertEquals("complete", restored.readText())
        assertFalse(File(volume, ".xfiles-trash/files").list()?.any { it.contains("xfiles-ready") } ?: false)
    }

    @Test
    fun concurrentRecoverKeepsTheLongerReadyCopy() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("complete") }
        val ready = File(volume, ".note.xfiles-ready").apply { writeText("complete-bytes") }
        val store = store(volume)
        val threads = List(8) {
            Thread {
                store.recoverTruncatedEdit(file.absolutePath)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("complete-bytes", file.readText())
        assertFalse(ready.exists())
    }

    @Test
    fun failedRenameStillUsesTheProviderWhenTheVacatedNameLooksPresent() {
        assertTrue(
            finishVolumeMove(renamed = false, sourceGoneTargetPresent = false) { true },
        )
        assertFalse(
            finishVolumeMove(renamed = false, sourceGoneTargetPresent = false) { false },
        )
        assertTrue(
            finishVolumeMove(renamed = true, sourceGoneTargetPresent = false) { false },
        )
    }

    @Test
    fun volumeMoverMovesIntoAFreeNameAndRefusesASymlink() {
        val dir = temporaryFolder.newFolder("dir")
        val mover = VolumeTrashMover(null)
        val free = File(temporaryFolder.newFolder("src"), "other").apply { writeText("x") }

        assertTrue(mover.move(free, dir))
        assertEquals("x", File(dir, "other").readText())

        val link = File(dir, "link")
        Files.createSymbolicLink(link.toPath(), File(dir, "other").toPath())
        val src = File(temporaryFolder.newFolder("src2"), "link").apply { writeText("nope") }
        assertFalse(mover.move(src, dir))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("nope", src.readText())

        val live = File(dir, "live").apply { writeText("live") }
        val sourceLink = File(dir, "source-link")
        Files.createSymbolicLink(sourceLink.toPath(), live.toPath())
        val blocked = File(dir, "blocked").apply { writeText("x") }
        assertFalse(mover.move(sourceLink, blocked))
        assertTrue(Files.isSymbolicLink(sourceLink.toPath()))
        assertEquals("live", live.readText())
    }

    @Test
    fun stuckScratchBucketDoesNotHideOtherRows() {
        val volume = temporaryFolder.newFolder("vol")
        val store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            removeTree = { file ->
                if (isEditorScratch(file.name)) throw IOException("stuck")
                deleteTrashBytes(file)
            },
            now = { 1_700_000_000_000L },
            newId = { "abc-00000001" },
        )
        val file = File(volume, "keep.txt").apply { writeText("keep") }
        store.trash(fileEntry(file))
        val scratch = File(volume, ".xfiles-trash/files/def-00000002").apply { mkdirs() }
        File(scratch, ".a.xfiles-ready").writeText("a")
        File(scratch, ".a.xfiles-tmp.1").writeText("b")

        assertEquals(listOf("keep.txt"), store.list().map { it.name })
        assertTrue(scratch.exists())
    }

    @Test
    fun trashTakesTheLongerReadyCopyAndDropsTemps() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("comp") }
        File(volume, ".note.xfiles-ready").writeText("complete")
        File(volume, ".note.xfiles-tmp.1").writeText("tmp")
        val store = store(volume)

        val record = store.trash(fileEntry(file))

        assertEquals("complete", File(record.storedPath).readText())
        assertFalse(File(volume, ".note.xfiles-ready").exists())
        assertFalse(File(volume, ".note.xfiles-tmp.1").exists())
    }

    @Test
    fun numberedEditorTempIsDroppedWithoutReplacingTheFile() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("short") }
        File(volume, ".note.xfiles-tmp.123").writeText("partial")

        store(volume).recoverTruncatedEdit(file.absolutePath)

        assertEquals("short", file.readText())
        assertFalse(File(volume, ".note.xfiles-tmp.123").exists())
    }

    @Test
    fun readySymlinkIsNotAdopted() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("short") }
        val other = File(volume, "other").apply { writeText("complete-bytes") }
        val ready = File(volume, ".note.xfiles-ready")
        Files.createSymbolicLink(ready.toPath(), other.toPath())
        val store = store(volume)

        val failure = assertThrows(IOException::class.java) { store.trash(fileEntry(file)) }
        assertEquals("Cannot use the editor temp for note", failure.message)

        assertEquals("short", file.readText())
        assertEquals("complete-bytes", other.readText())
        assertTrue(Files.isSymbolicLink(ready.toPath()))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun trashLeavesTheSourceWhenTheReadyMergeFails() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("comp") }
        val ready = File(volume, ".note.xfiles-ready").apply { writeText("complete") }
        val store = store(volume, replaceFile = { _, _ -> false })

        assertThrows(IOException::class.java) { store.trash(fileEntry(file)) }

        assertEquals("comp", file.readText())
        assertEquals("complete", ready.readText())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun nestedBinFileRecoversItsReadyCopy() {
        val volume = temporaryFolder.newFolder("vol")
        val dir = File(volume, "docs").apply { mkdirs() }
        File(dir, "note").writeText("short")
        val store = store(volume)
        val record = store.trash(fileEntry(dir, isDir = true))
        val stored = File(record.storedPath, "note")
        stored.writeText("comp")
        File(stored.parentFile, ".note.xfiles-ready").writeText("complete")

        store.recoverTruncatedEdit(stored.absolutePath)

        assertEquals("complete", stored.readText())
        assertFalse(File(stored.parentFile, ".note.xfiles-ready").exists())
    }

    @Test
    fun recoverMergesReadyWhenTheDirectoryCannotBeListed() {
        val volume = temporaryFolder.newFolder("vol")
        val parent = File(volume, "notes").apply { mkdirs() }
        val file = File(parent, "note").apply { writeText("complete") }
        val ready = File(parent, ".note.xfiles-ready").apply { writeText("complete-bytes") }
        assertTrue(parent.setReadable(false))
        try {
            if (parent.listFiles() != null) return
            store(volume).recoverTruncatedEdit(file.absolutePath)
        } finally {
            parent.setReadable(true)
        }
        assertEquals("complete-bytes", file.readText())
        assertFalse(ready.exists())
    }

    @Test
    fun readyMergeDoesNotBlockListing() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("complete") }
        File(volume, ".note.xfiles-ready").writeText("complete-bytes")
        lateinit var bin: TrashStore
        bin = store(
            volume,
            replaceFile = { from, onto ->
                val listed = Thread { bin.list() }
                listed.start()
                listed.join(1_000)
                assertFalse(listed.isAlive)
                from.inputStream().use { input ->
                    onto.outputStream().use { output -> input.copyTo(output) }
                }
                true
            },
        )

        bin.recoverTruncatedEdit(file.absolutePath)

        assertEquals("complete-bytes", file.readText())
    }

    @Test
    fun sidecarTempSymlinkIsNotFollowed() {
        val volume = temporaryFolder.newFolder("vol")
        val victim = File(volume, "victim.txt").apply { writeText("keep") }
        val info = File(volume, ".xfiles-trash/info").apply { mkdirs() }
        Files.createSymbolicLink(
            File(info, "abc-00000001.trashinfo.tmp").toPath(),
            victim.toPath(),
        )
        val file = File(volume, "note").apply { writeText("hello") }

        store(volume).trash(fileEntry(file))

        assertEquals("keep", victim.readText())
        val sidecar = File(info, "abc-00000001.trashinfo")
        assertTrue(sidecar.isFile)
        assertFalse(Files.isSymbolicLink(sidecar.toPath()))
        assertTrue(sidecar.readText().contains("name="))
    }

    @Test
    fun directoryCreateAndRestoreMoveDoNotBlockListing() {
        val volume = temporaryFolder.newFolder("vol")
        val docs = File(volume, "docs").apply { mkdirs() }
        val file = File(docs, "note").apply { writeText("hello") }
        var arm = false
        lateinit var store: TrashStore
        fun assertListRuns() {
            if (!arm) return
            val listed = Thread { store.list() }
            listed.start()
            listed.join(1_000)
            assertFalse(listed.isAlive)
        }
        store = TrashStore(
            volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
            mover = TrashMover { source, destDir ->
                assertListRuns()
                val target = File(destDir, source.name)
                !target.exists() && source.renameTo(target)
            },
            now = { 1L },
            newId = { "abc-00000001" },
            ensureDirectory = { dir ->
                assertListRuns()
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create Recycle Bin")
            },
        )
        val record = store.trash(fileEntry(file))
        assertTrue(docs.delete())
        arm = true

        store.restore(record, "note")

        assertEquals("hello", File(docs, "note").readText())
    }

    @Test
    fun symlinkBinIsNotCreatedOrListed() {
        val volume = temporaryFolder.newFolder("vol")
        val outside = temporaryFolder.newFolder("outside")
        val file = File(volume, "note").apply { writeText("safe") }
        Files.createSymbolicLink(File(volume, ".xfiles-trash").toPath(), outside.toPath())
        val store = store(volume)

        assertThrows(IOException::class.java) { store.trash(fileEntry(file)) }

        assertEquals("safe", file.readText())
        assertFalse(File(outside, ".nomedia").exists())
        assertTrue(store.list().isEmpty())
        assertFalse(outside.list()?.contains("note") ?: false)
    }

    @Test
    fun symlinkBucketIsNotListedOrEmptied() {
        val volume = temporaryFolder.newFolder("vol")
        val outside = temporaryFolder.newFolder("outside")
        val payload = File(outside, "secret").apply { writeText("keep") }
        val ready = File(outside, ".secret.xfiles-ready").apply { writeText("complete") }
        val store = store(volume)
        val files = File(volume, ".xfiles-trash/files").apply { mkdirs() }
        File(volume, ".xfiles-trash/info").mkdirs()
        Files.createSymbolicLink(File(files, "abc-00000001").toPath(), outside.toPath())
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").writeText(
            "name=secret\npath=secret\ndeleted=1\n",
        )

        assertTrue(store.list().isEmpty())
        assertEquals("keep", payload.readText())
        assertEquals("complete", ready.readText())
        assertTrue(Files.isSymbolicLink(File(files, "abc-00000001").toPath()))
    }

    @Test
    fun trashDoesNotAdoptASymlinkBucket() {
        val volume = temporaryFolder.newFolder("vol")
        val outside = temporaryFolder.newFolder("outside")
        val store = store(volume, newId = { "abc-00000001" })
        val files = File(volume, ".xfiles-trash/files").apply { mkdirs() }
        Files.createSymbolicLink(File(files, "abc-00000001").toPath(), outside.toPath())
        val file = File(volume, "note").apply { writeText("safe") }

        assertThrows(IOException::class.java) { store.trash(fileEntry(file)) }

        assertEquals("safe", file.readText())
        assertFalse(File(outside, "note").exists())
        assertTrue(Files.isSymbolicLink(File(files, "abc-00000001").toPath()))
    }

    @Test
    fun sidecarNameBeatsALoneScratchFileTheGrantStillHas() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, ".note.xfiles-ready").writeText("scratch")
        File(volume, ".xfiles-trash/info").mkdirs()
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo")
            .writeText("name=note\npath=note\ndeleted=1\n")
        val store = store(
            volume,
            childNames = { dir ->
                if (dir.absolutePath == bucket.absolutePath) {
                    unionFileAndGrantNames(dir.list()?.toList(), listOf("note"))
                } else {
                    dir.list()?.toList()
                }
            },
        )

        val listed = store.list().single()

        assertEquals("note", listed.name)
        assertEquals(File(bucket, "note").absolutePath, listed.storedPath)
    }

    @Test
    fun restoreRefusesAGrantOnlyOccupant() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("old") }
        val store = store(
            volume,
            newId = { "abc-00000001" },
            childNames = { dir ->
                val listed = dir.list()?.toList()
                if (dir.absolutePath == volume.absolutePath) unionFileAndGrantNames(listed, listOf("note"))
                else listed
            },
        )
        val record = store.trash(fileEntry(file))
        File(record.storedPath).writeText("trashed")
        assertFalse(file.exists())

        assertThrows(IOException::class.java) { store.restore(record, "note") }
        assertEquals("trashed", File(record.storedPath).readText())
    }

    @Test
    fun trashMovesAFileOnlyTheGrantNames() {
        val volume = temporaryFolder.newFolder("vol")
        val missing = File(volume, "note")
        var moved = false
        val store = store(
            volume,
            newId = { "abc-00000001" },
            mover = TrashMover { _, dest ->
                moved = dest.name == "abc-00000001"
                true
            },
            childNames = { dir ->
                if (dir.absolutePath == volume.absolutePath) listOf("note") else dir.list()?.toList()
            },
        )

        val record = store.trash(fileEntry(missing))

        assertTrue(moved)
        assertEquals(missing.name, record.name)
    }

    @Test
    fun folderTrashMergesAReadySiblingOnlyTheGrantLists() {
        val volume = temporaryFolder.newFolder("vol")
        val folder = File(volume, "docs").apply { mkdirs() }
        val replaced = ArrayList<String>()
        val store = store(
            volume,
            replaceFile = { from, onto ->
                replaced += "${from.name}->${onto.name}"
                true
            },
            childNames = { dir ->
                if (dir.absolutePath == folder.absolutePath) listOf("note", ".note.xfiles-ready")
                else dir.list()?.toList()
            },
            grantLength = { file ->
                when (file.name) {
                    ".note.xfiles-ready" -> 4L
                    "note" -> if (replaced.isEmpty()) 2L else 4L
                    else -> null
                }
            },
            openRead = { file ->
                when (file.name) {
                    ".note.xfiles-ready" -> "comp".byteInputStream()
                    "note" -> "co".byteInputStream()
                    else -> null
                }
            },
        )

        store.recoverTruncatedEdit(folder.absolutePath)

        assertEquals(listOf(".note.xfiles-ready->note"), replaced)
    }

    @Test
    fun unparsedSidecarKeepsAGrantOnlyPayload() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("not-a-sidecar")
        }
        val store = store(
            volume,
            childNames = { dir ->
                if (dir.name == bucket.name) listOf("note") else dir.list()?.toList()
            },
        )

        val listed = store.list().single()

        assertEquals("note", listed.name)
        assertEquals("note", store.lookup(listed.storedPath)?.name)
        assertTrue(info.exists())
        assertTrue(bucket.exists())
    }

    @Test
    fun orphanScanAdoptsAGrantOnlyPayload() {
        val volume = temporaryFolder.newFolder("vol")
        File(volume, ".xfiles-trash/files/abc-00000001").mkdirs()
        val store = store(
            volume,
            childNames = { dir ->
                if (dir.name == "abc-00000001") listOf("note") else dir.list()?.toList()
            },
        )

        val listed = store.list().single()

        assertTrue(listed.orphan)
        assertEquals("note", listed.name)
    }

    @Test
    fun restoreKeepsABucketTheGrantStillLists() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=Download/note\ndeleted=1\n")
        }
        val removed = ArrayList<String>()
        val store = store(
            volume,
            mover = TrashMover { _, _ -> true },
            removeTree = { file ->
                removed += file.name
                if (file.exists() && !file.delete()) throw IOException(file.path)
            },
            childNames = { dir ->
                if (dir.name == bucket.name) listOf("note") else dir.list()?.toList()
            },
        )
        val record = TrashRecord(
            id = "abc-00000001",
            volumeRoot = volume.absolutePath,
            volumeLabel = "Internal",
            storedPath = File(bucket, "note").absolutePath,
            name = "note",
            originalRelativePath = "Download/note",
            deletedAt = 1L,
            orphan = false,
        )

        store.restore(record, "note")

        assertTrue(bucket.exists())
        assertFalse(removed.contains(bucket.name))
    }

    @Test
    fun readableSidecarListsAGrantOnlyScratchChild() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=note\ndeleted=1\n")
        }
        val store = store(
            volume,
            childNames = { dir ->
                if (dir.name == bucket.name) listOf(".note.xfiles-ready") else dir.list()?.toList()
            },
        )

        val listed = store.list().single()

        assertEquals("note", listed.name)
        assertTrue(listed.storedPath.endsWith(".note.xfiles-ready"))
    }

    @Test
    fun grantOnlyScratchStaysListedAndIsNotDeleted() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("not-a-sidecar")
        }
        val store = store(
            volume,
            childNames = { dir ->
                if (dir.name == bucket.name) listOf(".note.xfiles-ready") else dir.list()?.toList()
            },
        )

        val listed = store.list().single()

        assertEquals(".note.xfiles-ready", listed.name)
        assertTrue(info.exists())
        assertTrue(bucket.exists())
        assertEquals(listed.name, store.lookup(listed.storedPath)?.name)
    }

    @Test
    fun unknownGrantLengthKeepsTheReadyCopy() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("short") }
        val removed = ArrayList<String>()
        val store = store(
            volume,
            replaceFile = { _, _ -> true },
            removeTree = { target -> removed += target.name },
            childNames = { dir ->
                if (dir.absolutePath == volume.absolutePath) listOf(".note.xfiles-ready")
                else dir.list()?.toList()
            },
            grantLength = { -1L },
        )

        assertThrows(IOException::class.java) { store.recoverTruncatedEdit(file.absolutePath) }

        assertTrue(removed.isEmpty())
        assertEquals("short", file.readText())
    }

    @Test
    fun invisiblePayloadWithSidecarIsNotSwept() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo")
        info.parentFile!!.mkdirs()
        info.writeText("name=note\npath=note\ndeleted=1\n")
        val ready = File(bucket, ".note.xfiles-ready").apply { writeText("complete") }

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("note", listed[0].name)
        assertEquals(ready.absolutePath, listed[0].storedPath)

        assertTrue(bucket.exists())
        assertTrue(info.exists())
        assertEquals("complete", ready.readText())
    }

    @Test
    fun loneScratchWithSidecarCanBeLookedUpAndRestored() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo")
        info.parentFile!!.mkdirs()
        info.writeText("name=note\npath=Download/note\ndeleted=1\n")
        File(bucket, ".note.xfiles-ready").writeText("complete")

        val listed = store.list().single()
        val found = store.lookup(listed.storedPath)
        assertEquals(listed.storedPath, found?.storedPath)
        assertEquals("note", found?.name)

        val restored = store.restore(found!!, "note")
        assertEquals(File(volume, "Download/note").absolutePath, restored.absolutePath)
        assertEquals("complete", restored.readText())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun recoverLeavesAFileWrittenAfterTheSave() {
        val volume = temporaryFolder.newFolder("vol")
        val replaced = ArrayList<String>()
        val store = store(
            volume,
            replaceFile = { from, onto ->
                replaced += onto.name
                from.inputStream().use { input ->
                    onto.outputStream().use { output -> input.copyTo(output) }
                }
                true
            },
        )
        // Longer, same length but different, and shorter but not a prefix of the save.
        val cases = listOf("longer-edited-elsewhere", "edits", "ne")
        for ((i, content) in cases.withIndex()) {
            val file = File(volume, "note$i").apply { writeText(content) }
            val ready = File(volume, ".note$i.xfiles-ready").apply { writeText("saved") }
            val tmp = File(volume, ".note$i.xfiles-tmp.1").apply { writeText("partial") }

            val changed = store.recoverTruncatedEdits(listOf(file.absolutePath))

            assertTrue(changed.isEmpty())
            assertEquals(content, file.readText())
            assertEquals("saved", ready.readText())
            assertTrue(tmp.exists())
        }
        assertTrue(replaced.isEmpty())
    }

    @Test
    fun recoverDropsLeftoversWhenTheSaveAlreadyLanded() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("saved") }
        val ready = File(volume, ".note.xfiles-ready").apply { writeText("saved") }
        val replaced = ArrayList<String>()
        val store = store(volume, replaceFile = { _, onto -> replaced += onto.name; true })

        val changed = store.recoverTruncatedEdits(listOf(file.absolutePath))

        // The hidden ready row is gone from the folder, so it still needs a re-read.
        assertEquals(setOf(file.path), changed)
        assertEquals("saved", file.readText())
        assertFalse(ready.exists())
        assertTrue(replaced.isEmpty())
    }

    @Test
    fun recoverRestoresAnEmptiedFile() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("") }
        val ready = File(volume, ".note.xfiles-ready").apply { writeText("saved") }

        store(volume).recoverTruncatedEdit(file.absolutePath)

        assertEquals("saved", file.readText())
        assertFalse(ready.exists())
    }

    @Test
    fun batchRecoverListsEachFolderOnceAndSkipsFilesWithoutSiblings() {
        val volume = temporaryFolder.newFolder("vol")
        val folder = File(volume, "notes").apply { mkdirs() }
        val files = (0 until 50).map { File(folder, "n$it").apply { writeText("comp") } }
        File(folder, ".n7.xfiles-ready").writeText("complete")
        val listed = ArrayList<String>()
        val store = store(
            volume,
            childNames = { dir ->
                listed += dir.name
                dir.list()?.toList()
            },
        )
        val failures = ArrayList<String>()

        val changed = store.recoverTruncatedEdits(files.map { it.absolutePath }) { path, _ -> failures += path }

        assertTrue(failures.isEmpty())
        assertEquals(setOf(files[7].path), changed)
        assertEquals("complete", files[7].readText())
        assertTrue(files.filterIndexed { i, _ -> i != 7 }.all { it.readText() == "comp" })
        // One listing to find the owners, one inside the merge for n7 itself.
        assertEquals(2, listed.count { it == "notes" })
    }

    @Test
    fun batchRecoverReportsEachFailureAndKeepsGoing() {
        val volume = temporaryFolder.newFolder("vol")
        val bad = File(volume, "bad").apply { writeText("comp") }
        Files.createSymbolicLink(File(volume, ".bad.xfiles-ready").toPath(), File(volume, "elsewhere").toPath())
        val good = File(volume, "good").apply { writeText("comp") }
        File(volume, ".good.xfiles-ready").writeText("complete")
        val failures = ArrayList<String>()

        val changed = store(volume).recoverTruncatedEdits(listOf(bad.absolutePath, good.absolutePath)) { path, _ ->
            failures += path
        }

        assertEquals(listOf(bad.absolutePath), failures)
        assertEquals(setOf(good.path), changed)
        assertEquals("complete", good.readText())
    }

    @Test
    fun folderRecoverReportsTheNestedFileItRewrote() {
        val volume = temporaryFolder.newFolder("vol")
        val folder = File(volume, "docs").apply { mkdirs() }
        val sub = File(folder, "sub").apply { mkdirs() }
        val note = File(sub, "note").apply { writeText("comp") }
        File(sub, ".note.xfiles-ready").writeText("complete")
        File(folder, "plain").writeText("plain")

        val changed = store(volume).recoverTruncatedEdits(listOf(folder.absolutePath))

        // The stale listing is sub's, not the folder that was asked for.
        assertEquals(setOf(note.path), changed)
        assertEquals("complete", note.readText())
    }

    @Test
    fun folderTrashIsOneRenameAndItsReadyCopyTravelsWithIt() {
        val volume = temporaryFolder.newFolder("vol")
        val folder = File(volume, "docs").apply { mkdirs() }
        File(folder, "note").writeText("comp")
        File(folder, ".note.xfiles-ready").writeText("complete")
        val listed = ArrayList<String>()
        val store = store(
            volume,
            childNames = { dir ->
                listed += dir.name
                dir.list()?.toList()
            },
        )

        val record = store.trash(fileEntry(folder, isDir = true))

        assertFalse(listed.contains("docs"))
        val stored = File(record.storedPath, "note")
        assertEquals("comp", stored.readText())
        store.recoverTruncatedEdit(stored.absolutePath)
        assertEquals("complete", stored.readText())
        assertFalse(File(record.storedPath, ".note.xfiles-ready").exists())
    }

    @Test
    fun scratchOwnerCandidatesCoverOddNames() {
        assertEquals(listOf("a.txt"), editorScratchOwnerCandidates(".a.txt.xfiles-ready"))
        assertEquals(listOf("a.txt"), editorScratchOwnerCandidates(".a.txt.xfiles-tmp.123"))
        assertEquals(
            listOf("x", "x.xfiles-tmp"),
            editorScratchOwnerCandidates(".x.xfiles-tmp.xfiles-tmp.9"),
        )
        assertTrue(editorScratchOwnerCandidates("a.txt").isEmpty())
        assertTrue(editorScratchOwnerCandidates(".xfiles-ready").isEmpty())
    }

    @Test
    fun recoverMergesAnOrdinaryReadyCopyWithoutABin() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val file = File(volume, "note").apply { writeText("comp") }
        val ready = File(volume, ".note.xfiles-ready").apply { writeText("complete") }

        store.recoverTruncatedEdit(file.absolutePath)

        assertEquals("complete", file.readText())
        assertFalse(ready.exists())
    }

    @Test
    fun recoverMergesAReadyCopyInALookalikeTrashFolder() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume)
        val fake = File(volume, "Download/.xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val file = File(fake, "note").apply { writeText("complete") }
        val ready = File(fake, ".note.xfiles-ready").apply { writeText("complete-longer") }

        store.recoverTruncatedEdit(file.absolutePath)

        assertEquals("complete-longer", file.readText())
        assertFalse(ready.exists())
    }

    @Test
    fun listingDoesNotMergeAReadyCopy() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("short") }
        val store = store(volume)
        val record = store.trash(fileEntry(file))
        File(record.storedPath).writeText("x")
        val ready = File(File(record.storedPath).parentFile, ".note.xfiles-ready").apply {
            writeText("complete")
        }

        val listed = store.list().single()

        assertEquals("x", File(listed.storedPath).readText())
        assertEquals("complete", ready.readText())
        assertEquals("x", File(store.lookup(record.storedPath)!!.storedPath).readText())
    }

    @Test
    fun recoverMergesAReadyCopyOnlyTheGrantNames() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("compl") }
        val removed = ArrayList<String>()
        val store = store(
            volume,
            openRead = { target ->
                if (target.name == ".note.xfiles-ready") "complete!".byteInputStream()
                else if (target.isFile) target.inputStream() else null
            },
            replaceFile = { from, onto ->
                check(from.name == ".note.xfiles-ready")
                onto.writeText("complete!")
                true
            },
            removeTree = { target ->
                removed += target.name
                if (target.exists() && !target.delete()) throw IOException(target.path)
            },
            childNames = { dir ->
                if (dir.absolutePath == volume.absolutePath) listOf(".note.xfiles-ready")
                else dir.list()?.toList()
            },
            grantLength = { target -> if (target.name == ".note.xfiles-ready") 9L else null },
        )

        store.recoverTruncatedEdit(file.absolutePath)

        assertEquals("complete!", file.readText())
        assertEquals(listOf(".note.xfiles-ready"), removed)
    }

    @Test
    fun failedTrashMovesAGrantedBucketChildBack() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("body") }
        val moved = ArrayList<String>()
        val store = store(
            volume,
            newId = { "abc-00000001" },
            mover = TrashMover { source, destDir ->
                moved += "${source.name}->${destDir.name}"
                if (destDir.name == "abc-00000001") {
                    check(source.delete())
                    false
                } else {
                    true
                }
            },
            childNames = { dir ->
                if (dir.name == "abc-00000001") listOf("note") else dir.list()?.toList()
            },
        )

        assertThrows(IOException::class.java) { store.trash(fileEntry(file)) }

        assertEquals(listOf("note->abc-00000001", "note->vol"), moved)
    }

    @Test
    fun trashProceedsWhenTheBucketExistsOnlyOnTheGrant() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "note").apply { writeText("body") }
        var moved = false
        val store = store(
            volume,
            newId = { "abc-00000001" },
            ensureDirectory = ensure@{ dir ->
                if (dir.name == "abc-00000001") return@ensure
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create Recycle Bin")
            },
            mover = TrashMover { source, _ ->
                moved = true
                check(source.delete())
                true
            },
            childIsDirectory = { dir -> dir.name == "abc-00000001" },
        )

        val record = store.trash(fileEntry(file))

        assertTrue(moved)
        assertEquals("note", record.name)
        assertFalse(File(record.storedPath).isDirectory)
    }

    @Test
    fun emptyInfoListingStillShowsASidecarTheGrantNames() {
        val volume = temporaryFolder.newFolder("vol")
        File(volume, ".xfiles-trash/info").mkdirs()
        File(volume, ".xfiles-trash/files/abc-00000001").mkdirs()
        val text = "name=note\npath=Download/note\ndeleted=1\n"
        val store = store(
            volume,
            childNames = { dir ->
                when (dir.name) {
                    "info" -> listOf("abc-00000001.trashinfo")
                    "abc-00000001" -> listOf("note")
                    else -> dir.list()?.toList()
                }
            },
            readText = { file ->
                if (file.name == "abc-00000001.trashinfo") text else null
            },
        )

        val listed = store.list().single()

        assertEquals("note", listed.name)
        assertEquals("note", store.lookup(listed.storedPath)?.name)
    }

    @Test
    fun failedRollbackKeepsAMoveTheProviderAlreadyCommitted() {
        assertTrue(safMoveKeptAfterFailedRollback(null, "note"))
        assertTrue(safMoveKeptAfterFailedRollback("note", "note"))
        assertFalse(safMoveKeptAfterFailedRollback("other", "note"))
    }

    @Test
    fun emptyFileListingIsNotProofTheGrantHasNoChild() {
        assertEquals(listOf("note"), unionFileAndGrantNames(emptyList(), listOf("note")))
        assertEquals(listOf("a", "b"), unionFileAndGrantNames(listOf("a"), listOf("a", "b")))
        assertEquals(listOf("a"), unionFileAndGrantNames(null, listOf("a")))
        assertEquals(listOf("a"), unionFileAndGrantNames(listOf("a"), null))
        assertNull(unionFileAndGrantNames(null, null))
    }

    @Test
    fun emptyBucketListingStillShowsAChildTheGrantNames() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=Download/note\ndeleted=1\n")
        }
        assertTrue(bucket.list() != null && bucket.list()!!.isEmpty())
        val store = store(
            volume,
            childNames = { dir ->
                unionFileAndGrantNames(
                    dir.list()?.toList(),
                    if (dir.absolutePath == bucket.absolutePath) listOf("note") else null,
                )
            },
        )

        val listed = store.list().single()

        assertEquals("note", listed.name)
        assertEquals(File(bucket, "note").absolutePath, listed.storedPath)
    }

    @Test
    fun noteRemovedDeletesAChildMissingFromAnEmptyListing() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=Download/note\ndeleted=1\n")
        }
        val granted = mutableSetOf("note")
        val removed = ArrayList<String>()
        val store = store(
            volume,
            removeTree = { file ->
                removed += file.name
                granted.remove(file.name)
                if (file.exists() && !file.delete()) throw IOException(file.absolutePath)
            },
            childNames = { dir ->
                unionFileAndGrantNames(
                    dir.list()?.toList(),
                    if (dir.absolutePath == bucket.absolutePath) granted.toList() else null,
                )
            },
        )

        assertTrue(store.noteRemoved(File(bucket, "note").absolutePath))

        assertEquals("note", removed[0])
        assertEquals(info.name, removed[1])
        assertFalse(info.exists())
    }

    @Test
    fun unreadableBucketStaysListedWhenTheGrantNamesTheChild() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(bucket, "note").writeText("kept")
        val info = File(volume, ".xfiles-trash/info").apply { mkdirs() }
        File(info, "abc-00000001.trashinfo").writeText("name=note\npath=Download/note\ndeleted=1\n")
        assertTrue(bucket.setReadable(false))
        try {
            if (bucket.list() != null) return
            val store = store(
                volume,
                childNames = { dir ->
                    if (dir.name == bucket.name) listOf("note") else dir.list()?.toList()
                },
            )
            val listed = store.list().single()
            assertEquals("note", listed.name)
            assertEquals(File(bucket, "note").absolutePath, listed.storedPath)
            assertEquals(listed.storedPath, store.lookup(listed.storedPath)?.storedPath)
        } finally {
            bucket.setReadable(true)
        }
    }

    @Test
    fun scratchNamedPayloadStaysListed() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, ".note.txt.xfiles-ready").apply { writeText("body") }
        val store = store(volume, newId = { "abc-00000001" })

        val record = store.trash(fileEntry(file))

        val listed = store.list().single()
        assertEquals(".note.txt.xfiles-ready", listed.name)
        assertEquals(record.storedPath, listed.storedPath)
        assertEquals("body", File(listed.storedPath).readText())
    }

    @Test
    fun trashedNomediaFileStaysListed() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, ".nomedia").apply { writeText("marker") }
        val store = store(volume, newId = { "abc-00000001" })

        store.trash(fileEntry(file))

        val listed = store.list()
        assertEquals(listOf(".nomedia"), listed.map { it.name })
        assertEquals("marker", File(listed.single().storedPath).readText())
        assertTrue(File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").isFile)
    }

    @Test
    fun restoreRejectsAPathThatEscapesTheVolume() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("a") }
        val store = store(volume, newId = { "abc-00000001" })
        val record = store.trash(fileEntry(file))
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").writeText(
            "name=a.txt\npath=../escaped.txt\ndeleted=1\n",
        )

        val listed = store.lookup(record.storedPath)!!
        assertThrows(IOException::class.java) { store.restore(listed, "escaped.txt") }

        assertTrue(File(record.storedPath).exists())
        assertFalse(File(volume.parentFile, "escaped.txt").exists())
    }

    @Test
    fun failedRestoreRenameRollsTheBucketNameBackWithoutAFileExistsCheck() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("safe") }
        val renames = ArrayList<String>()
        val store = store(
            volume,
            mover = TrashMover { source, destDir ->
                val target = File(destDir, source.name)
                if (destDir.absolutePath == volume.absolutePath) return@TrashMover false
                !target.exists() && source.renameTo(target)
            },
            renameFile = { renamed, newName ->
                renames += "${renamed.name}->$newName"
                true
            },
        )
        val record = store.trash(fileEntry(file))

        assertThrows(IOException::class.java) { store.restore(record, "other.txt") }

        assertEquals(listOf("a.txt->other.txt", "other.txt->a.txt"), renames)
        assertEquals("a.txt", store.list().single().name)
        assertTrue(File(record.storedPath).exists())
    }

    @Test
    fun renamedBucketChildStaysListedUnderTheSidecar() {
        val volume = temporaryFolder.newFolder("vol")
        val file = File(volume, "a.txt").apply { writeText("safe") }
        val store = store(volume, newId = { "abc-00000001" })
        val record = store.trash(fileEntry(file))
        val renamed = File(File(record.storedPath).parentFile, "other.txt")
        check(File(record.storedPath).renameTo(renamed))

        val listed = store.list().single()

        assertEquals("a.txt", listed.name)
        assertEquals(renamed.absolutePath, listed.storedPath)
        assertEquals("safe", File(listed.storedPath).readText())
    }

    @Test
    fun restoreMovesAPayloadOnlyTheGrantCanSee() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=Download/note\ndeleted=1\n")
        }
        val stored = File(bucket, "note")
        val moved = ArrayList<String>()
        var stillInBucket = true
        val store = store(
            volume,
            mover = TrashMover { source, destDir ->
                moved += "${source.absolutePath}->${destDir.absolutePath}"
                stillInBucket = false
                true
            },
            childNames = { dir ->
                if (dir.absolutePath == bucket.absolutePath && stillInBucket) listOf("note")
                else dir.list()?.toList()
            },
        )
        val record = TrashRecord(
            id = "abc-00000001",
            volumeRoot = volume.absolutePath,
            volumeLabel = "Internal",
            storedPath = stored.absolutePath,
            name = "note",
            originalRelativePath = "Download/note",
            deletedAt = 1L,
            orphan = false,
        )

        val restored = store.restore(record, "note")

        assertEquals(File(volume, "Download/note").absolutePath, restored.absolutePath)
        assertEquals(listOf("${stored.absolutePath}->${File(volume, "Download").absolutePath}"), moved)
        assertFalse(info.exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun restoreKeepsTheSidecarWhenAGrantedPayloadCannotMove() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=Download/note\ndeleted=1\n")
        }
        val store = store(
            volume,
            mover = TrashMover { _, _ -> false },
            childNames = { dir ->
                if (dir.absolutePath == bucket.absolutePath) listOf("note") else dir.list()?.toList()
            },
        )
        val record = TrashRecord(
            id = "abc-00000001",
            volumeRoot = volume.absolutePath,
            volumeLabel = "Internal",
            storedPath = File(bucket, "note").absolutePath,
            name = "note",
            originalRelativePath = "Download/note",
            deletedAt = 1L,
            orphan = false,
        )

        assertThrows(IOException::class.java) { store.restore(record, "note") }

        assertTrue(info.exists())
    }

    @Test
    fun noteRemovedDeletesAGrantedPayloadBeforeTheSidecar() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=Download/note\ndeleted=1\n")
        }
        val stored = File(bucket, "note")
        val invisible = mutableSetOf(stored.absolutePath)
        val removed = ArrayList<String>()
        val store = store(
            volume,
            removeTree = { file ->
                removed += file.absolutePath
                invisible.remove(file.absolutePath)
                if (file.exists() && !file.delete()) throw IOException(file.absolutePath)
            },
            childNames = { dir ->
                val listed = dir.list()?.toList().orEmpty()
                val extra = invisible.map(::File)
                    .filter { it.parentFile?.absolutePath == dir.absolutePath }
                    .map { it.name }
                listed + extra
            },
        )

        assertTrue(store.noteRemoved(stored.absolutePath))

        assertEquals(stored.absolutePath, removed[0])
        assertEquals(info.absolutePath, removed[1])
        assertFalse(info.exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun noteRemovedKeepsTheSidecarWhenTheGrantStillNamesThePayload() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        val info = File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=note\npath=Download/note\ndeleted=1\n")
        }
        val stored = File(bucket, "note")
        val removed = ArrayList<String>()
        val store = store(
            volume,
            removeTree = { file -> removed += file.name },
            childNames = { dir ->
                if (dir.absolutePath == bucket.absolutePath) listOf("note") else dir.list()?.toList()
            },
        )

        assertThrows(IOException::class.java) { store.noteRemoved(stored.absolutePath) }

        assertEquals(listOf("note"), removed)
        assertTrue(info.exists())
    }

    @Test
    fun grantedDirectoryIsListedAsADirectoryWhenFileCannotSeeIt() {
        val volume = temporaryFolder.newFolder("vol")
        val bucket = File(volume, ".xfiles-trash/files/abc-00000001").apply { mkdirs() }
        File(volume, ".xfiles-trash/info/abc-00000001.trashinfo").apply {
            parentFile?.mkdirs()
            writeText("name=photos\npath=Download/photos\ndeleted=1\n")
        }
        assertTrue(bucket.setReadable(false, false))
        try {
            assertNull(bucket.list())
            val store = store(
                volume,
                childNames = { dir ->
                    if (dir.name == bucket.name) listOf("photos") else dir.list()?.toList()
                },
                childIsDirectory = { file -> if (file.name == "photos") true else null },
            )
            val blind = object : XFileSystem by LocalFileSystem() {
                override fun stat(id: String): XEntry? = null
            }

            val row = TrashFileSystem(store, blind).list(TrashFileSystem.rootEntry()).single()

            assertEquals("photos", row.name)
            assertTrue(row.isDir)
            assertEquals(EntryKind.DIR, row.kind)
        } finally {
            bucket.setReadable(true, false)
        }
    }

    @Test
    fun payloadInvisibleToFileStillAppearsInTheBin() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume, newId = { "abc-00000001" })
        val file = File(volume, "note").apply { writeText("kept") }
        val record = store.trash(fileEntry(file))
        val blind = object : XFileSystem by LocalFileSystem() {
            override fun stat(id: String): XEntry? = null
        }

        val row = TrashFileSystem(store, blind).list(TrashFileSystem.rootEntry()).single()

        assertEquals("note", row.name)
        assertEquals(XId.file(record.storedPath), row.id)
        assertNull(row.localPath)
    }

    @Test
    fun orphanRowUsesTheProvidedBadge() {
        val volume = temporaryFolder.newFolder("vol")
        val store = store(volume, newId = { "abc-00000001" })
        val bucket = File(volume, ".xfiles-trash/files/def-00000002").apply { mkdirs() }
        File(bucket, "orphan.txt").writeText("lost")
        val fs = TrashFileSystem(store, statOnlyFileSystem()) { volumeLabel -> "LOC:$volumeLabel" }

        val row = fs.list(TrashFileSystem.rootEntry()).single()

        assertEquals("LOC:Internal", row.badge)
    }

    private fun store(
        volume: File,
        mover: TrashMover = TrashMover { source, destDir ->
            val target = File(destDir, source.name)
            !target.exists() && source.renameTo(target)
        },
        newId: () -> String = { "abc-00000001" },
        renameFile: (File, String) -> Boolean = { renamed, newName ->
            val parent = renamed.parentFile
            parent != null && renamed.renameTo(File(parent, newName))
        },
        replaceFile: (File, File) -> Boolean = { from, onto ->
            from.inputStream().use { input ->
                onto.outputStream().use { output -> input.copyTo(output) }
            }
            true
        },
        childNames: (File) -> List<String>? = { it.list()?.toList() },
        childIsDirectory: (File) -> Boolean? = { null },
        grantLength: (File) -> Long? = { null },
        readText: (File) -> String? = { file ->
            runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
        },
        ensureDirectory: (File) -> Unit = { dir ->
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create Recycle Bin")
        },
        removeTree: (File) -> Unit = ::deleteTrashBytes,
        openRead: (File) -> java.io.InputStream? = { if (it.isFile) it.inputStream() else null },
    ) = TrashStore(
        volumes = { listOf(TrashVolume(volume.absolutePath, "Internal", writable = true)) },
        mover = mover,
        now = { 1_700_000_000_000L },
        newId = newId,
        renameFile = renameFile,
        replaceFile = replaceFile,
        removeTree = removeTree,
        childNames = childNames,
        childIsDirectory = childIsDirectory,
        grantLength = grantLength,
        readText = readText,
        ensureDirectory = ensureDirectory,
        openRead = openRead,
    )

    private fun statOnlyFileSystem() = object : XFileSystem {
        override val scheme: String = XId.SCHEME_FILE
        override fun list(dir: XEntry): List<XEntry> = emptyList()
        override fun stat(id: String): XEntry = XEntry(
            id = id,
            name = id.substringAfterLast('/'),
            isDir = false,
            localPath = id.substringAfter("://"),
        )
        override fun openIn(entry: XEntry) = error("unused")
        override fun openOut(parentDir: XEntry, name: String) = error("unused")
        override fun createFile(parentDir: XEntry, name: String) = error("unused")
        override fun mkdir(parentDir: XEntry, name: String) = error("unused")
        override fun delete(entry: XEntry) = error("unused")
        override fun rename(entry: XEntry, newName: String) = error("unused")
        override fun canWrite(entry: XEntry) = false
    }

    private fun fileEntry(file: File, isDir: Boolean = file.isDirectory) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = isDir,
        kind = if (isDir) EntryKind.DIR else EntryKind.FILE,
        canWrite = true,
        localPath = file.absolutePath,
    )
}
