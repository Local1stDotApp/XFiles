package app.local1st.files.ui.main

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.ui.browser.EntryIcons
import app.local1st.files.ui.viewer.mediaStartIndex
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.ops.canEditCreatedTextFile
import app.local1st.files.core.ops.canMoveSource
import app.local1st.files.core.ops.uniqueExtractFolderName
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationDestinationTest {

    @Test
    fun playbackDoesNotStartOnADifferentFile() {
        assertEquals(1, mediaStartIndex("b", listOf("a", "b")))
        assertNull(mediaStartIndex("hidden", listOf("a", "b")))
    }

    @Test
    fun siblingRecoveryDoesNotBlockTheOpenedFile() {
        val opened = XEntry(id = "file:///storage/a.jpg", name = "a.jpg", isDir = false, localPath = "/storage/a.jpg")
        val sibling = XEntry(id = "file:///storage/b.jpg", name = "b.jpg", isDir = false, localPath = "/storage/b.jpg")
        val recovered = ArrayList<String>()

        val changed = recoverOpenedEntries(listOf(sibling, opened), opened.id) { paths, onFailure ->
            for (path in paths) {
                if (path == sibling.localPath) onFailure(path, IOException("bad temp"))
                else recovered += path
            }
            paths.filterTo(HashSet()) { it in recovered }
        }

        assertEquals(listOf(opened.localPath), recovered)
        assertEquals(setOf(opened.localPath), changed)
        val failure = assertThrows(IOException::class.java) {
            recoverOpenedEntries(listOf(sibling, opened), opened.id) { paths, onFailure ->
                paths.forEach { onFailure(it, IOException("bad temp")) }
                emptySet()
            }
        }
        assertEquals("bad temp", failure.message)
    }

    @Test
    fun grantOnlyFileRowStillHasARecoverPath() {
        val entry = XEntry(
            id = "file:///storage/bin/note.jpg",
            name = "note.jpg",
            isDir = false,
            localPath = null,
        )
        assertEquals("/storage/bin/note.jpg", recoverableLocalPath(entry))
        assertNull(recoverableLocalPath(entry.copy(isDir = true)))
    }

    @Test
    fun writableFilesystemDirectoriesCanBeOtherPaneDestinations() {
        assertTrue(isFileOperationDestination(directory("file:///storage/emulated/0/Download")))
        assertTrue(isFileOperationDestination(directory("root:///data/local/tmp")))
        assertTrue(isFileOperationDestination(directory("saf://loc-1")))
    }

    @Test
    fun missingReadOnlyVirtualAndNonDirectoryTargetsAreRejected() {
        assertFalse(isFileOperationDestination(null))
        assertFalse(
            isFileOperationDestination(
                directory("file:///storage/emulated/0/Download", canWrite = false),
            ),
        )
        assertFalse(isFileOperationDestination(directory("zip:///storage/emulated/0/a.zip!/docs")))
        assertFalse(isFileOperationDestination(directory("apps://@user")))
        assertFalse(
            isFileOperationDestination(
                directory("file:///storage/emulated/0/.xfiles-trash/files/abc-00000001/photos"),
                volumeRoots = listOf("/storage/emulated/0"),
            ),
        )
        assertTrue(
            isFileOperationDestination(
                directory("file:///storage/emulated/0/Download/.xfiles-trash"),
                volumeRoots = listOf("/storage/emulated/0"),
            ),
        )
        assertFalse(
            isFileOperationDestination(
                directory("root:///storage/emulated/0/.xfiles-trash/files/abc-00000001"),
                volumeRoots = listOf("/storage/emulated/0"),
            ),
        )
        assertFalse(
            isFileOperationDestination(
                directory("trash://").copy(kind = EntryKind.RECYCLE_BIN, canWrite = false),
            ),
        )
        assertFalse(
            isFileOperationDestination(
                XEntry(
                    id = "file:///storage/emulated/0/source.txt",
                    name = "source.txt",
                    isDir = false,
                ),
            ),
        )
    }

    @Test
    fun grantOnlyImageStillWantsAThumbnail() {
        val image = XEntry(
            id = "file:///storage/sd/photo",
            name = "photo",
            isDir = false,
            mime = "image/jpeg",
            size = -1,
            localPath = null,
        )
        assertTrue(EntryIcons.wantsThumbnail(image))
        assertFalse(EntryIcons.wantsThumbnail(image.copy(mime = "text/plain")))
        assertFalse(EntryIcons.wantsThumbnail(image.copy(isDir = true)))
    }

    @Test
    fun extractFolderSkipsTheBinNameInAnyCase() {
        assertEquals(
            ".xfiles-trash (1)",
            uniqueExtractFolderName(".xfiles-trash", emptyList(), volumeRoot = true),
        )
        assertEquals(
            ".XFILES-TRASH (1)",
            uniqueExtractFolderName(".XFILES-TRASH", emptyList(), volumeRoot = true),
        )
        assertEquals("photos", uniqueExtractFolderName("photos", emptyList(), volumeRoot = true))
        assertEquals(
            "photos (1)",
            uniqueExtractFolderName("photos", listOf("Photos"), volumeRoot = false, caseInsensitive = true),
        )
        assertEquals(
            ".xfiles-trash",
            uniqueExtractFolderName(".xfiles-trash", emptyList(), volumeRoot = false),
        )
    }

    @Test
    fun locationAndVolumeRootsCannotBeMoved() {
        assertFalse(
            canMoveSource(
                directory("saf://loc-1").copy(kind = EntryKind.LOCATION),
            ),
        )
        assertFalse(
            canMoveSource(
                directory("file:///storage/emulated/0").copy(kind = EntryKind.VOLUME_INTERNAL),
            ),
        )
        assertTrue(canMoveSource(directory("saf://loc-1/docs")))
        assertTrue(
            canMoveSource(
                XEntry(
                    id = "saf://loc-1/file",
                    name = "note.txt",
                    isDir = false,
                    kind = EntryKind.FILE,
                ),
            ),
        )
    }

    @Test
    fun onlyLocalFilesOpenInTheTextEditorAfterCreate() {
        assertTrue(
            canEditCreatedTextFile(
                XEntry(
                    id = "file:///storage/emulated/0/note.txt",
                    name = "note.txt",
                    isDir = false,
                ),
            ),
        )
        assertFalse(
            canEditCreatedTextFile(
                XEntry(
                    id = "saf://loc-1/note",
                    name = "note.txt",
                    isDir = false,
                    kind = EntryKind.FILE,
                ),
            ),
        )
        assertFalse(
            canEditCreatedTextFile(
                XEntry(
                    id = "root:///data/local/tmp/note.txt",
                    name = "note.txt",
                    isDir = false,
                ),
            ),
        )
    }

    private fun directory(id: String, canWrite: Boolean = true) = XEntry(
        id = id,
        name = id.substringAfterLast('/').ifBlank { "/" },
        isDir = true,
        canWrite = canWrite,
    )
}
