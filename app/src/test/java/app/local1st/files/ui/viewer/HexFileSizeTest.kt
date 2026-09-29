package app.local1st.files.ui.viewer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HexFileSizeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun localFileUsesItsLengthNotTheStaleEntrySize() {
        val file = temporaryFolder.newFile("note.txt")
        file.writeText("complete-bytes")
        assertEquals(file.length(), hexFileSize(file, entrySize = 4))
    }

    @Test
    fun missingLocalFileKeepsTheEntrySize() {
        val missing = File(temporaryFolder.root, "gone")
        assertEquals(4L, hexFileSize(missing, entrySize = 4))
    }
}
