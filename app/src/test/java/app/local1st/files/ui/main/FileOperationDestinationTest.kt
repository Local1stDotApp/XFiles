package app.local1st.files.ui.main

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.ops.canEditCreatedTextFile
import app.local1st.files.core.ops.canMoveSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationDestinationTest {

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
                XEntry(
                    id = "file:///storage/emulated/0/source.txt",
                    name = "source.txt",
                    isDir = false,
                ),
            ),
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
