package app.local1st.files.core.fs

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RarVolumesTest {
    private val dir: File = Files.createTempDirectory("rar-volumes").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun volumesOf_followsNumberedParts() {
        val parts = listOf("Set.part01.rar", "Set.part02.rar", "Set.part03.rar").map(::touch)
        touch("Set.part05.rar")

        assertEquals(parts, RarVolumes.volumesOf(parts[0]))
        // Opened from the middle: only that volume, as the first one holds the set's start.
        assertEquals(listOf(parts[1]), RarVolumes.volumesOf(parts[1]))
    }

    @Test
    fun volumesOf_followsOldStyleExtensions() {
        val parts = listOf("OLD.RAR", "OLD.R00", "OLD.R01").map(::touch)

        assertEquals(parts, RarVolumes.volumesOf(parts[0]))
    }

    @Test
    fun volumesOf_keepsASingleArchiveAlone() {
        val single = touch("single.rar")
        touch("single.part2.rar")
        touch("other.r00")

        assertEquals(listOf(single), RarVolumes.volumesOf(single))
    }

    private fun touch(name: String): File = File(dir, name).apply { writeText("") }
}
