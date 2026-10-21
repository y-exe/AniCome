package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class HuluPlaybackRepositoryTest {
    private val snapshot = HuluScreenSnapshot("ご注文はうさぎですか??", "笑顔とフラッシュがやかましい これが私の自称姉です", "シーズン2, 第1羽 / 23分", "9:16", "23:38")
    private val screen get() = checkNotNull(HuluScreenParser.parse(snapshot))

    @Test fun parsesHuluEpisodeUnitsAndSeasonWithoutGuessingFromShortTitles() {
        val result = screen
        assertEquals(556000L, result.position)
        assertEquals(1418000L, result.duration)
        assertEquals(1, EpisodeMatcher.parse(result.title, result.episode)!!.number)
        assertEquals(2, animeWorkIdentity(result.title).season)
        assertNull(HuluScreenParser.parse(snapshot.copy(episodeDetail = "23分")))
        assertNull(HuluScreenParser.parse(snapshot.copy(playerVisible = false)))
        assertNull(HuluScreenParser.parse(snapshot.copy(episodeTitle = "")))
        assertEquals(13, EpisodeMatcher.parse(screen.title, HuluScreenParser.parse(snapshot.copy(episodeDetail = "シーズン2, 第13占 / 23分"))!!.episode)!!.number)
    }

    @Test fun parsesEnglishDetailsAndRejectsInvalidClocks() {
        assertTrue(HuluScreenParser.parse(snapshot.copy(episodeDetail = "Season 2, Episode 4 / 23 min"))!!.episode.startsWith("第4話"))
        assertNull(HuluScreenParser.parse(snapshot.copy(currentTime = "99:99")))
        assertNull(HuluScreenParser.parse(snapshot.copy(currentTime = "24:00")))
        assertNull(HuluScreenParser.parse(snapshot.copy(totalTime = "0:00")))
    }

    @Test fun repeatedSecondNeverRewindsOrReanchorsTheRunningClock() {
        var now = 1000L
        val repo = HuluPlaybackRepository { now }
        repo.observe(screen.copy(position = 5000))
        now = 2000; repo.observe(screen.copy(position = 6000))
        assertTrue(repo.playback.value!!.playing)
        val anchor = repo.playback.value!!.updatedAt
        now = 2350; repo.observe(screen.copy(position = 6000))
        assertEquals(6350L, repo.playback.value!!.positionAt(now))
        now = 2850; repo.observe(screen.copy(position = 6000))
        assertEquals(anchor, repo.playback.value!!.updatedAt)
        assertEquals(6850L, repo.playback.value!!.positionAt(now))
    }

    @Test fun pauseAndPausedSeekDoNotLeaveAnElapsedSessionClockRunning() {
        var now = 1000L
        val repo = HuluPlaybackRepository { now }
        repo.observe(screen.copy(position = 5000))
        now = 2000; repo.observe(screen.copy(position = 6000))
        now = 4000; repo.observe(screen.copy(position = 6000))
        assertFalse(repo.playback.value!!.playing)
        now = 9000; repo.observe(screen.copy(position = 300000))
        assertFalse(repo.playback.value!!.playing)
        assertEquals(300000L, repo.playback.value!!.positionAt(12000))
        now = 10000; repo.observe(screen.copy(position = 301000))
        assertTrue(repo.playback.value!!.playing)
        assertEquals(301500L, repo.playback.value!!.positionAt(10500))
    }

    @Test fun seeksAndEpisodeChangesReplaceTheAnchor() {
        var now = 1000L
        val repo = HuluPlaybackRepository { now }
        repo.observe(screen)
        now += 1000; repo.observe(screen.copy(position = screen.position + 1000))
        now += 1000; repo.observe(screen.copy(position = 100000))
        assertEquals(100000L, repo.playback.value!!.positionAt(now))
        val previousKey = repo.playback.value!!.key
        now += 1000; repo.observe(screen.copy(episode = "第2話 灰色兎と灰かぶり姫", position = 0))
        assertNotEquals(previousKey, repo.playback.value!!.key)
        assertEquals(0L, repo.playback.value!!.positionAt(now))
        assertFalse(repo.playback.value!!.playing)
    }

    @Test fun briefRotationGapKeepsClockButLeavingPlayerClearsIt() {
        var now = 1000L
        val repo = HuluPlaybackRepository { now }
        repo.observe(screen)
        repo.absent()
        now += 500; repo.absent()
        assertNotNull(repo.playback.value)
        now += 2000; repo.absent()
        assertNull(repo.playback.value)
    }
}
