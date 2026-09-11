package app.local1st.files.core.fs

import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
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

    private fun entry(file: java.io.File) = XEntry(
        id = XId.file(file.absolutePath),
        name = file.name,
        isDir = false,
        localPath = file.absolutePath,
    )
}
