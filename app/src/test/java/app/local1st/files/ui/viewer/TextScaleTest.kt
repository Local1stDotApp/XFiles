package app.local1st.files.ui.viewer

import app.local1st.files.core.text.MAX_ROW_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextScaleTest {

    @Test
    fun steps_moveToTheNeighbouringSize() {
        assertEquals(1.15f, textScaleStep(1f, up = true))
        assertEquals(0.85f, textScaleStep(1f, up = false))
        // A scale between steps, as the unwrapped limit leaves it, moves to the step beyond.
        assertEquals(2.5f, textScaleStep(2.2f, up = true))
        assertEquals(2f, textScaleStep(2.2f, up = false))
    }

    @Test
    fun steps_stopAtEitherEnd() {
        assertNull(textScaleStep(TEXT_SCALE_STEPS.last(), up = true))
        assertNull(textScaleStep(TEXT_SCALE_STEPS.first(), up = false))
    }

    @Test
    fun unwrappedLimit_keepsAFullRowInsideTheLayoutLimit() {
        // 12 sp monospace at xxhdpi is about 21.6 px a character; 24 dp of padding is 72 px.
        for (advance in listOf(14f, 21.6f, 30f, 50f)) {
            val padding = 72f
            val limit = unwrappedScaleLimit(advance, padding)
            val widest = MAX_ROW_BYTES * advance * limit + padding
            assertTrue("advance $advance: $widest px", widest <= 262_143f)
        }
    }

    @Test
    fun unwrappedLimit_allowsTodaysSizeOnOrdinaryScreens() {
        assertTrue(unwrappedScaleLimit(21.6f, 72f) > 2f)
    }

    @Test
    fun unwrappedLimit_withoutAMeasurement_changesNothing() {
        assertEquals(1f, unwrappedScaleLimit(0f, 72f))
    }
}
