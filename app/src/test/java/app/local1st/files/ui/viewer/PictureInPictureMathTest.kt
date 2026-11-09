package app.local1st.files.ui.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureInPictureMathTest {

    @Test
    fun aspect_followsPixelsAndTheirShape() {
        assertEquals(16f / 9f, videoAspect(1920, 1080, 1f)!!, 0.001f)
        // Anamorphic PAL: 720×576 stored, shown 16:9.
        assertEquals(16f / 9f, videoAspect(720, 576, 64f / 45f)!!, 0.001f)
        assertNull(videoAspect(0, 1080, 1f))
    }

    @Test
    fun aspect_isPulledIntoTheRangeTheSystemAccepts() {
        assertEquals(2.38f, clampPipAspect(3f), 0.0001f)
        assertEquals(1f / 2.38f, clampPipAspect(0.2f), 0.0001f)
        assertEquals(1.5f, clampPipAspect(1.5f), 0.0001f)
    }

    @Test
    fun fitted_landscapeVideoOnAPortraitScreen_isLetterboxed() {
        assertArrayEquals(intArrayOf(0, 896, 1080, 1504), fittedVideoBounds(0, 0, 1080, 2400, 16f / 9f))
    }

    @Test
    fun fitted_portraitVideoOnALandscapeScreen_isPillarboxed() {
        assertArrayEquals(intArrayOf(896, 0, 1504, 1080), fittedVideoBounds(0, 0, 2400, 1080, 9f / 16f))
    }

    @Test
    fun fitted_aShapeTheWindowCannotTake_givesNoHint() {
        assertNull(fittedVideoBounds(0, 0, 2400, 1080, 3f))
        assertNull(fittedVideoBounds(0, 0, 0, 0, 16f / 9f))
    }
}
