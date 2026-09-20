package app.local1st.files.core.fs

import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileReplaceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fs = LocalFileSystem()

    @Test
    fun replaceContents_rewritesTheWholeFile() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("old")

        fs.replaceContents(entry(file), "new".toByteArray())

        assertEquals("new", file.readText())
    }

    @Test
    fun replaceRange_rewritesOnlyTheSpecifiedSpan() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("AAAABBBBCCCC")

        fs.replaceRange(entry(file), from = 4, to = 8, replacement = "xx".toByteArray())

        assertEquals("AAAAxxCCCC", file.readText())
    }

    @Test
    fun replaceRange_canGrowOrShrinkTheFile() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("HEAD-MID-TAIL")

        fs.replaceRange(entry(file), from = 5, to = 8, replacement = "MIDDLE".toByteArray())
        assertEquals("HEAD-MIDDLE-TAIL", file.readText())

        fs.replaceRange(entry(file), from = 5, to = 11, replacement = ByteArray(0))
        assertEquals("HEAD--TAIL", file.readText())
    }

    @Test
    fun replaceRange_atTheStartAndEnd() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("ABCDEF")

        fs.replaceRange(entry(file), from = 0, to = 2, replacement = "xx".toByteArray())
        assertEquals("xxCDEF", file.readText())

        fs.replaceRange(entry(file), from = 4, to = 6, replacement = "yy".toByteArray())
        assertEquals("xxCDyy", file.readText())
    }

    @Test
    fun replaceRange_rejectsASpanPastTheEnd() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("abc")

        assertThrows(java.io.IOException::class.java) {
            fs.replaceRange(entry(file), from = 0, to = 4, replacement = ByteArray(0))
        }
        assertEquals("abc", file.readText())
    }

    @Test
    fun replaceRange_leavesTheOriginalWhenSiblingTmpCannotBeWritten() {
        val dir = temporaryFolder.newFolder("locked")
        val file = java.io.File(dir, "note.txt")
        file.writeText("original")
        check(dir.setReadOnly())

        try {
            assertThrows(IOException::class.java) {
                fs.replaceRange(entry(file), from = 0, to = 8, replacement = "x".toByteArray())
            }
            assertEquals("original", file.readText())
            assertFalse(
                dir.listFiles()?.any {
                    it.name == ".note.txt.xfiles-ready" ||
                        it.name == ".note.txt.xfiles-tmp" ||
                        it.name.startsWith(".note.txt.xfiles-tmp.")
                } == true,
            )
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun replaceRange_promotesLeftoverTmpWhenTargetWasTruncated() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("")
        val leftover = java.io.File(file.parentFile, ".note.txt.xfiles-ready")
        leftover.writeText("recovered-content")
        val smaller = java.io.File(file.parentFile, ".note.txt.xfiles-tmp.1")
        smaller.writeText("tiny")

        val wrote = fs.replaceRange(
            entry(file),
            from = 0,
            to = leftover.length(),
            replacement = "must-not-resplice".toByteArray(),
        )

        assertFalse(wrote)
        assertEquals("recovered-content", file.readText())
        assertFalse(leftover.exists())
        assertFalse(smaller.exists())
    }

    @Test
    fun replaceRange_promotesLongerLeftoverEvenWhenSpanFitsTheTruncatedFile() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("short")
        val leftover = java.io.File(file.parentFile, ".note.txt.xfiles-ready")
        leftover.writeText("recovered-content")

        val wrote = fs.replaceRange(entry(file), from = 0, to = 5, replacement = "short".toByteArray())

        assertFalse(wrote)
        assertEquals("recovered-content", file.readText())
        assertFalse(leftover.exists())
    }

    @Test
    fun replaceRange_ignoresIncompleteTmpLongerThanTarget() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("original")
        val incomplete = java.io.File(file.parentFile, ".note.txt.xfiles-tmp.123")
        incomplete.writeText("INCOMPLETE-AND-LONGER-THAN-ORIGINAL")

        val wrote = fs.replaceRange(entry(file), from = 0, to = 8, replacement = "changed!".toByteArray())

        assertTrue(wrote)
        assertEquals("changed!", file.readText())
        assertFalse(incomplete.exists())
    }

    @Test
    fun replaceRange_keepsCompleteTmpWhenReadyNameIsBlocked() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("original")
        val parent = checkNotNull(file.parentFile)
        val readyDir = java.io.File(parent, ".note.txt.xfiles-ready")
        check(readyDir.mkdir())
        check(java.io.File(readyDir, "child").createNewFile())
        try {
            assertThrows(IOException::class.java) {
                fs.replaceRange(entry(file), from = 0, to = 8, replacement = "changed!".toByteArray())
            }
            assertEquals("original", file.readText())
            assertTrue(
                parent.listFiles()?.any {
                    it.isFile && it.name.startsWith(".note.txt.xfiles-tmp") && it.length() > 0L
                } == true,
            )
        } finally {
            readyDir.deleteRecursively()
        }
    }

    @Test
    fun replaceRange_doesNotOverwriteLeftoverWhenTargetIsIntact() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("AAAABBBBCCCC")
        val leftover = java.io.File(file.parentFile, ".note.txt.xfiles-ready")
        leftover.writeText("STALE")

        val wrote = fs.replaceRange(entry(file), from = 4, to = 8, replacement = "xx".toByteArray())

        assertTrue(wrote)
        assertEquals("AAAAxxCCCC", file.readText())
        assertFalse(leftover.exists())
    }

    @Test
    fun replaceRanges_splicesEveryRangeInOnePass() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("AAAA-BBBB-CCCC-DDDD")

        val wrote = fs.replaceRanges(
            entry(file),
            listOf(
                ByteRangeEdit(15, 19, "dd".toByteArray()),
                ByteRangeEdit(0, 4, "a".toByteArray()),
                ByteRangeEdit(10, 14, "CCCCCC".toByteArray()),
            ),
        )

        assertTrue(wrote)
        assertEquals("a-BBBB-CCCCCC-dd", file.readText())
    }

    @Test
    fun replaceRanges_rejectsOverlappingRanges() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("ABCDEF")

        assertThrows(IOException::class.java) {
            fs.replaceRanges(
                entry(file),
                listOf(ByteRangeEdit(0, 3, "x".toByteArray()), ByteRangeEdit(2, 4, "y".toByteArray())),
            )
        }
        assertEquals("ABCDEF", file.readText())
    }

    @Test
    fun replaceRanges_withNoEditsWritesNothing() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("ABCDEF")
        val before = file.lastModified()

        assertTrue(fs.replaceRanges(entry(file), emptyList(), FileStamp.of(file)))

        assertEquals("ABCDEF", file.readText())
        assertEquals(before, file.lastModified())
    }

    @Test
    fun sameLengthEditsOnALargeFile_areWrittenInPlace() {
        val inPlace = LocalFileSystem(inPlaceMinBytes = 8)
        val file = temporaryFolder.newFile("big.txt")
        file.writeText("AAAABBBBCCCC")
        val inode = java.nio.file.Files.getAttribute(file.toPath(), "unix:ino")

        val wrote = inPlace.replaceRanges(
            entry(file),
            listOf(ByteRangeEdit(0, 2, "xx".toByteArray()), ByteRangeEdit(8, 12, "cccc".toByteArray())),
        )

        assertTrue(wrote)
        assertEquals("xxAABBBBcccc", file.readText())
        // The same file, not a replacement: an in-place write keeps the inode.
        assertEquals(inode, java.nio.file.Files.getAttribute(file.toPath(), "unix:ino"))
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.startsWith(".big.txt.xfiles") })
    }

    @Test
    fun editsThatChangeLength_orASmallFile_stillGoThroughTheRename() {
        val inPlace = LocalFileSystem(inPlaceMinBytes = 8)
        val file = temporaryFolder.newFile("big.txt")
        file.writeText("AAAABBBBCCCC")
        val inode = java.nio.file.Files.getAttribute(file.toPath(), "unix:ino")

        assertTrue(inPlace.replaceRange(entry(file), 0, 4, "a".toByteArray()))
        assertEquals("aBBBBCCCC", file.readText())
        assertTrue(inode != java.nio.file.Files.getAttribute(file.toPath(), "unix:ino"))

        val small = temporaryFolder.newFile("small.txt")
        small.writeText("abc")
        val smallInode = java.nio.file.Files.getAttribute(small.toPath(), "unix:ino")
        assertTrue(inPlace.replaceRange(entry(small), 0, 3, "xyz".toByteArray()))
        assertEquals("xyz", small.readText())
        assertTrue(smallInode != java.nio.file.Files.getAttribute(small.toPath(), "unix:ino"))
    }

    @Test
    fun anInPlaceWriteThatFailsPartWay_isPutBack_soTheSaveCanBeRetried() {
        val dir = temporaryFolder.newFolder("locked")
        val file = java.io.File(dir, "big.txt")
        file.writeText("AAAABBBBCCCC")
        check(file.setLastModified(file.lastModified() - 60_000))
        val stamp = FileStamp.of(file)
        val inPlace = LocalFileSystem(inPlaceMinBytes = 8, openInPlace = HalfWriteFault(failAt = 1))
        val edits = listOf(
            ByteRangeEdit(0, 4, "xxxx".toByteArray()),
            ByteRangeEdit(8, 12, "cccc".toByteArray()),
        )
        // No sibling tmp can be made here, so the splice the failed write falls back to fails too.
        check(dir.setReadOnly())

        try {
            assertThrows(IOException::class.java) { inPlace.replaceRanges(entry(file), edits, stamp) }
            // Half of "xxxx" went in before the failure; the file is the original again, stamp
            // included, rather than torn and looking like another app's change.
            assertEquals("AAAABBBBCCCC", file.readText())
            assertTrue(stamp.matches(file))
        } finally {
            dir.setWritable(true)
        }

        assertTrue(inPlace.replaceRanges(entry(file), edits, stamp))
        assertEquals("xxxxBBBBcccc", file.readText())
    }

    @Test
    fun anInPlaceWriteThatFailsPartWay_stillEndsAsTheEdit_whenTheSpliceWorks() {
        val file = temporaryFolder.newFile("big.txt")
        file.writeText("AAAABBBBCCCC")
        val inode = java.nio.file.Files.getAttribute(file.toPath(), "unix:ino")
        val inPlace = LocalFileSystem(inPlaceMinBytes = 8, openInPlace = HalfWriteFault(failAt = 1))

        val wrote = inPlace.replaceRanges(
            entry(file),
            listOf(ByteRangeEdit(4, 8, "bbbb".toByteArray())),
            FileStamp.of(file),
        )

        assertTrue(wrote)
        assertEquals("AAAAbbbbCCCC", file.readText())
        // The splice wrote it, not the in-place path: the file was replaced.
        assertTrue(inode != java.nio.file.Files.getAttribute(file.toPath(), "unix:ino"))
    }

    @Test
    fun aFileChangedSinceItWasRead_isNotSplicedOver() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("AAAABBBBCCCC")
        val stamp = FileStamp.of(file)
        file.writeText("AAAABBBBCCCC-appended")
        file.setLastModified(stamp.mtime + 10_000)

        assertThrows(FileChangedException::class.java) {
            fs.replaceRanges(entry(file), listOf(ByteRangeEdit(4, 8, "xx".toByteArray())), stamp)
        }
        assertEquals("AAAABBBBCCCC-appended", file.readText())
    }

    @Test
    fun aFileStillCarryingItsStamp_isSpliced_andAStaleLeftoverIsDiscarded() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("AAAABBBBCCCC")
        val stamp = FileStamp.of(file)
        // A complete tmp from an earlier save that never landed: longer than the target, which
        // without the stamp would read as "the target was truncated, restore this".
        val leftover = java.io.File(file.parentFile, ".note.txt.xfiles-ready")
        leftover.writeText("OLDER-SAVE-THAT-IS-LONGER")

        val wrote = fs.replaceRanges(entry(file), listOf(ByteRangeEdit(4, 8, "xx".toByteArray())), stamp)

        assertTrue(wrote)
        assertEquals("AAAAxxCCCC", file.readText())
        assertFalse(leftover.exists())
    }

    @Test
    fun aTruncatedTargetWithACompleteLeftover_isRestoredEvenWithAStamp() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("AAAABBBBCCCC")
        val stamp = FileStamp.of(file)
        val leftover = java.io.File(file.parentFile, ".note.txt.xfiles-ready")
        leftover.writeText("COMPLETE-SAVE")
        file.writeText("AAA")
        file.setLastModified(stamp.mtime + 10_000)

        val wrote = fs.replaceRanges(entry(file), listOf(ByteRangeEdit(4, 8, "xx".toByteArray())), stamp)

        assertFalse(wrote)
        assertEquals("COMPLETE-SAVE", file.readText())
    }

    @Test
    fun skipExactly_advancesByTheRequestedCount() {
        val file = temporaryFolder.newFile("data.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        FileInputStream(file).use { input ->
            skipExactly(input, 3)
            assertEquals(4, input.read())
        }
    }

    @Test
    fun skipExactly_throwsWhenTheStreamEndsEarly() {
        val file = temporaryFolder.newFile("data.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        FileInputStream(file).use { input ->
            assertThrows(IOException::class.java) { skipExactly(input, 5) }
        }
        ByteArrayInputStream(byteArrayOf(1, 2, 3)).use { input ->
            assertThrows(IOException::class.java) { skipExactly(input, 5) }
        }
    }

    /**
     * Opens files as [LocalFileSystem] does, except that the [failAt]th write across every file
     * opened stops half way through, as a write to a failing device would. Later writes work,
     * so what the failure leaves behind can be put back.
     */
    private class HalfWriteFault(private val failAt: Int) : (java.io.File) -> RandomAccessFile {
        private var writes = 0

        override fun invoke(file: java.io.File): RandomAccessFile = object : RandomAccessFile(file, "rw") {
            override fun write(b: ByteArray) {
                if (++writes == failAt) {
                    super.write(b, 0, b.size / 2)
                    throw IOException("device fell off")
                }
                super.write(b)
            }
        }
    }

    private fun entry(file: java.io.File) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = false,
        localPath = file.absolutePath,
    )
}
