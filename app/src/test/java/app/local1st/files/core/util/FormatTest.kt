package app.local1st.files.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun detailsJoinsNonEmptyParts() {
        assertEquals("12 items · 2026/7/19", Format.details("12 items", "2026/7/19"))
        assertEquals("12 items", Format.details("12 items", ""))
        assertEquals("2026/7/19", Format.details("", "2026/7/19"))
        assertEquals("", Format.details("", ""))
        assertEquals("217 KB · 2026/7/19", Format.details("217 KB", "2026/7/19"))
    }
}
