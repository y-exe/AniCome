package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class PrimePlaybackRepositoryTest {
    private val title = "転生したらスライムだった件"
    private val episode = "シーズン4エピソード1 第73話 新しい日々"
    private fun nodes(visible: Boolean = true, progress: String = "プレーヤーの進捗状況バー. 2分, 38秒. 残り21分, 32秒") = listOf(
        PrimeScreenNode(description = "ビデオプレーヤー"),
        PrimeScreenNode(text = title, visible = visible),
        PrimeScreenNode(text = episode, visible = visible),
        PrimeScreenNode(description = progress, visible = visible),
        PrimeScreenNode(description = "速度, 1.5x", id = "test_tag:topBar_Overlay:topBar_Panel:topBar_speedButton", visible = visible)
    )

    @Test fun portraitAndLandscapeUseTheSamePlayerSemantics() {
        val screen = PrimeScreenParser.parse(nodes())!!
        assertEquals("$title 第4期", screen.title)
        assertEquals("第73話 新しい日々", screen.episode)
        assertEquals(158000L, screen.position)
        assertEquals(1450000L, screen.duration)
        assertEquals(1.5f, screen.speed)
    }

    @Test fun hiddenControlsRetainTitleWithoutReusingHiddenTimes() {
        val screen = PrimeScreenParser.parse(nodes(visible = false))!!
        assertEquals("第73話 新しい日々", screen.episode)
        assertNull(screen.position)
        assertNull(screen.speed)
    }

    @Test fun ignoresTitlesOutsideTheVideoPlayer() {
        assertNull(PrimeScreenParser.parse(nodes().drop(1)))
    }

    @Test fun supportsEnglishProgressAndSeasonLocalEpisodeNumber() {
        val screen = PrimeScreenParser.parse(listOf(
            PrimeScreenNode(description = "Video player"), PrimeScreenNode(text = "Example"),
            PrimeScreenNode(text = "Season 2, Episode 3 The return"),
            PrimeScreenNode(description = "Player progress bar. 1 hour, 2 minutes, 3 seconds. Remaining 4 minutes, 5 seconds")
        ))!!
        assertEquals("Example 第2期", screen.title)
        assertEquals("第3話 The return", screen.episode)
        assertEquals(3723000L, screen.position)
        assertEquals(3968000L, screen.duration)
    }

    @Test fun hiddenControlsKeepClockRunningAndReappearanceCorrectsIt() {
        var time = 1000L
        val repo = PrimePlaybackRepository { time }
        val screen = PrimeScreenParser.parse(nodes())!!.copy(speed = 1f)
        repo.observe(screen)
        time += 1000
        repo.observe(screen.copy(position = 159000))
        val key = repo.playback.value!!.key
        time += 20000
        repo.observe(PrimeScreenParser.parse(nodes(false))!!)
        assertEquals(179000L, repo.playback.value!!.positionAt(time))
        assertEquals(key, repo.playback.value!!.key)
        repo.observe(screen.copy(position = 170000))
        assertEquals(170000L, repo.playback.value!!.positionAt(time))
    }

    @Test fun detectsPauseResumeAndBackwardSeek() {
        var time = 1000L
        val repo = PrimePlaybackRepository { time }
        val screen = PrimeScreenParser.parse(nodes())!!
        repo.observe(screen)
        time += 1000; repo.observe(screen.copy(position = 159000))
        assertTrue(repo.playback.value!!.playing)
        time += 2000; repo.observe(screen.copy(position = 159000))
        assertFalse(repo.playback.value!!.playing)
        time += 1000; repo.observe(screen.copy(position = 160000))
        assertTrue(repo.playback.value!!.playing)
        time += 1000; repo.observe(screen.copy(position = 10000))
        assertEquals(10000L, repo.playback.value!!.positionAt(time))
    }

    @Test fun changingEpisodeDoesNotReusePreviousClockOrDuration() {
        var time = 1000L
        val repo = PrimePlaybackRepository { time }
        val screen = PrimeScreenParser.parse(nodes())!!
        repo.observe(screen)
        time += 1000; repo.observe(screen.copy(position = 159000))
        repo.observe(screen.copy(episode = "第74話", position = null, duration = 0, speed = null))
        assertEquals(-1L, repo.playback.value!!.position)
        assertEquals(0L, repo.playback.value!!.duration)
        assertFalse(repo.playback.value!!.playing)
    }

    @Test fun rotationGapKeepsClockButLeavingPlayerStopsIt() {
        var time = 1000L
        val repo = PrimePlaybackRepository { time }
        val screen = PrimeScreenParser.parse(nodes())!!
        repo.observe(screen)
        time += 1000; repo.observe(screen.copy(position = 159000))
        repo.absent()
        time += 1000; repo.absent()
        assertTrue(repo.playback.value!!.playing)
        time += 1000; repo.absent()
        assertFalse(repo.playback.value!!.playing)
        repo.clear()
        assertNull(repo.playback.value)
    }

    @Test fun reopeningSameEpisodeDoesNotReuseItsOldClock() {
        var time = 1000L
        val repo = PrimePlaybackRepository { time }
        val screen = PrimeScreenParser.parse(nodes())!!
        repo.observe(screen)
        time += 1000; repo.observe(screen.copy(position = 159000))
        val key = repo.playback.value!!.key
        repo.resetClock()
        repo.observe(PrimeScreenParser.parse(nodes(false))!!)
        assertEquals(key, repo.playback.value!!.key)
        assertNull(repo.playback.value!!.positionAt(time))
        assertFalse(repo.playback.value!!.playing)
        time += 1000; repo.observe(screen.copy(position = 0))
        time += 1000; repo.observe(screen.copy(position = 1000))
        assertEquals(1000L, repo.playback.value!!.positionAt(time))
        assertTrue(repo.playback.value!!.playing)
    }
}
