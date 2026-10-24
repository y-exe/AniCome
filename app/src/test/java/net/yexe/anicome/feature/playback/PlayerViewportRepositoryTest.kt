package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class PlayerViewportRepositoryTest {
    @Test fun unextAvoidsTheCutoutWithoutAccessibilityAndMirrorsOnRotation() {
        val repository = PlayerViewportRepository { 1000 }
        assertNull(repository.state.value)
        assertEquals(VideoBounds(361f, 0f, 2601f, 1260f), repository.resolve(StreamingService.U_NEXT, 2800, 1260, 162, 0))
        assertEquals(VideoBounds(199f, 0f, 2439f, 1260f), repository.resolve(StreamingService.U_NEXT, 2800, 1260, 0, 162))
        assertEquals(VideoBounds(280f, 0f, 2520f, 1260f), repository.resolve(StreamingService.U_NEXT, 2800, 1260))
        assertNull(repository.resolve(StreamingService.NETFLIX, 2800, 1260, 162, 0))
        assertNull(repository.resolve(StreamingService.U_NEXT, 1260, 2800, 162, 0))
    }

    @Test fun realVideoBoundsOverrideTheUnextCutoutEstimate() {
        val repository = PlayerViewportRepository { 1000 }
        val actual = VideoBounds(280f, 0f, 2520f, 1260f)
        repository.observe(StreamingService.U_NEXT, 2800, 1260, actual, "surface")
        assertEquals(actual, repository.resolve(StreamingService.U_NEXT, 2800, 1260, 162, 0))
    }

    @Test fun followsTheActualDanimeSurfaceInsteadOfPhysicalScreenCenter() {
        val repository = PlayerViewportRepository { 1000 }
        val video = VideoBounds(361f, 0f, 2601f, 1260f)
        repository.observe(StreamingService.D_ANIME, 2800, 1260, video, "surface")
        assertEquals(video, repository.resolve(StreamingService.D_ANIME, 2800, 1260))
        assertEquals(1481f, repository.state.value!!.bounds.let { (it.left + it.right) / 2 })
    }

    @Test fun fitsLetterboxedVideoInsideAppContentOnBothRotations() {
        assertEquals(VideoBounds(361f, 0f, 2601f, 1260f), VideoBounds(162f, 0f, 2800f, 1260f).widescreen())
        assertEquals(VideoBounds(199f, 0f, 2439f, 1260f), VideoBounds(0f, 0f, 2638f, 1260f).widescreen())
        assertEquals(VideoBounds(0f, 60f, 1920f, 1140f), VideoBounds(0f, 0f, 1920f, 1200f).widescreen())
    }

    @Test fun neverReusesAnotherAppOrRotationAndExpiresLostSamples() {
        var time = 1000L
        val repository = PlayerViewportRepository { time }
        repository.observe(StreamingService.D_ANIME, 2800, 1260, VideoBounds(361f, 0f, 2601f, 1260f), "surface")
        assertNull(repository.resolve(StreamingService.NETFLIX, 2800, 1260))
        assertNull(repository.resolve(StreamingService.D_ANIME, 1260, 2800))
        time = 6000
        assertNull(repository.resolve(StreamingService.D_ANIME, 2800, 1260))
    }

    @Test fun rejectsTransitionalOutOfScreenAndPortraitFrames() {
        val repository = PlayerViewportRepository { 1000 }
        for (bounds in listOf(VideoBounds(-81f, -1346f, 1532f, 1071f), VideoBounds(0f, 0f, 3000f, 1260f), VideoBounds(10f, 0f, 10f, 100f))) {
            repository.observe(StreamingService.D_ANIME, 2800, 1260, bounds, "surface")
            assertNull(repository.state.value)
        }
        repository.observe(StreamingService.D_ANIME, 1260, 2800, VideoBounds(0f, 1091f, 1260f, 1799f), "surface")
        assertNull(repository.state.value)
    }
}
