package app.local1st.files.core.ops

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberedNameTest {

    @Test
    fun aShortNameJustGetsTheNumber() {
        assertEquals("note (1).txt", numberedName("note", 1, ".txt"))
        assertEquals("photos (12)", numberedName("photos", 12))
    }

    @Test
    fun aNameAtTheLimitLosesTheEndOfItsStem() {
        val name = numberedName("a".repeat(251), 1, ".pdf")

        assertEquals("a".repeat(247) + " (1).pdf", name)
        assertEquals(MAX_NAME_BYTES, name.encodeToByteArray().size)
    }

    @Test
    fun theCutKeepsEachCharacterWhole() {
        // Three bytes each in UTF-8.
        assertEquals("中".repeat(82) + " (1).txt", numberedName("中".repeat(83), 1, ".txt"))
        // Four bytes, and two UTF-16 units.
        assertEquals("😀".repeat(61) + " (1).txt", numberedName("😀".repeat(62), 1, ".txt"))
        // An accent written as its own code point stays with its letter.
        assertEquals("é".repeat(82) + " (1).txt", numberedName("é".repeat(83), 1, ".txt"))
    }

    @Test
    fun aCutDropsTheSpaceItEndsOn() {
        assertEquals("a".repeat(246) + " (1).txt", numberedName("a".repeat(246) + " bbbb", 1, ".txt"))
    }

    @Test
    fun anExtensionWithNoRoomBesideItIsCutAsPartOfTheStem() {
        val name = numberedName("a", 1, "." + "x".repeat(253))

        assertEquals("a." + "x".repeat(249) + " (1)", name)
        assertEquals(MAX_NAME_BYTES, name.encodeToByteArray().size)
    }

    @Test
    fun anExtractFolderNamedAfterALongArchiveFits() {
        val stem = "a".repeat(252)

        assertEquals("a".repeat(251) + " (1)", uniqueExtractFolderName(stem, listOf(stem), volumeRoot = false))
    }
}
