package app.local1st.files.ui.viewer

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackTest {

    @Test
    fun toggle_whilePlaying_pauses() {
        val fake = FakePlayer(Player.STATE_READY)
        togglePlayback(fake.player, playing = true)
        assertEquals(listOf("pause"), fake.calls)
    }

    @Test
    fun toggle_afterTheEnd_rewindsBeforePlaying() {
        // play() alone leaves an ended player ended: the audio screen's dead play button.
        val fake = FakePlayer(Player.STATE_ENDED)
        togglePlayback(fake.player, playing = false)
        assertEquals(listOf("seekToDefaultPosition", "play"), fake.calls)
    }

    @Test
    fun toggle_afterAnError_preparesBeforePlaying() {
        val fake = FakePlayer(Player.STATE_IDLE)
        togglePlayback(fake.player, playing = false)
        assertEquals(listOf("prepare", "play"), fake.calls)
    }

    @Test
    fun toggle_whenPaused_justPlays() {
        val fake = FakePlayer(Player.STATE_READY)
        togglePlayback(fake.player, playing = false)
        assertEquals(listOf("play"), fake.calls)
    }

    @Test
    fun speeds_readAsTheReaderWritesNumbers() {
        assertEquals("0.25×", formatSpeed(0.25f, Locale.US))
        assertEquals("1×", formatSpeed(1f, Locale.US))
        assertEquals("1.5×", formatSpeed(1.5f, Locale.US))
        assertEquals("3×", formatSpeed(3f, Locale.US))
        assertEquals("1,5×", formatSpeed(1.5f, Locale.GERMANY))
    }

    @Test
    fun speeds_includeNormalAndStayAscending() {
        assertTrue(1f in PLAYBACK_SPEEDS)
        assertEquals(PLAYBACK_SPEEDS.sorted(), PLAYBACK_SPEEDS)
    }

    /** Only what [togglePlayback] touches; everything else answers with a zero value. */
    private class FakePlayer(private val state: Int) {
        val calls = mutableListOf<String>()
        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "play", "pause", "prepare", "seekToDefaultPosition" -> {
                    calls += method.name
                    null
                }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "FakePlayer"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player
    }
}
