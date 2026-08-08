package app.local1st.files.core.fs

import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileCreationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exclusiveCreateNeverTruncatesAnExistingFile() {
        val file = temporaryFolder.root.resolve("note.txt")
        createEmptyFileExclusive(file)
        file.writeText("keep me")

        assertThrows(FileAlreadyExistsException::class.java) {
            createEmptyFileExclusive(file)
        }

        assertEquals("keep me", file.readText())
    }

    @Test
    fun exclusiveCreateProducesAnEmptyFile() {
        val file = temporaryFolder.root.resolve("note.txt")

        createEmptyFileExclusive(file)

        assertTrue(file.isFile)
        assertEquals(0L, file.length())
    }
}
