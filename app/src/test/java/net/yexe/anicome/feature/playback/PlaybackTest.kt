package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class PlaybackTest {
    @Test fun extrapolatesAtPlaybackSpeed() {
        assertEquals(14000L, Playback(position = 10000, updatedAt = 1000, speed = 2f, playing = true).positionAt(3000))
    }
    @Test fun pauseDoesNotAdvance() {
        assertEquals(10000L, Playback(position = 10000, updatedAt = 1000, speed = 1f).positionAt(9000))
    }
    @Test fun unknownPositionStaysUnknown() { assertNull(Playback().positionAt(1000)) }
    @Test fun clampsAtDuration() {
        assertEquals(12000L, Playback(position = 10000, updatedAt = 1000, speed = 1f, playing = true, duration = 12000).positionAt(9000))
    }
    @Test fun seekUsesNewAnchor() {
        val playing = Playback(position = 90000, updatedAt = 1000, speed = 1f, playing = true)
        assertEquals(21000L, playing.copy(position = 20000, updatedAt = 5000).positionAt(6000))
    }
}
