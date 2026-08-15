package app.local1st.files.ui.main

import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationDestinationTest {

    @Test
    fun writableFilesystemDirectoriesCanBeOtherPaneDestinations() {
        assertTrue(isFileOperationDestination(directory("file:///storage/emulated/0/Download")))
        assertTrue(isFileOperationDestination(directory("root:///data/local/tmp")))
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

    private fun directory(id: String, canWrite: Boolean = true) = XEntry(
        id = id,
        name = id.substringAfterLast('/').ifBlank { "/" },
        isDir = true,
        canWrite = canWrite,
    )
}
