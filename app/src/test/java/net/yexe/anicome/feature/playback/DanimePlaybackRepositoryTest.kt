package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class DanimePlaybackRepositoryTest {
    private val title = "生徒会にも穴はある！【TV放送版】 第1話 「オスガキにも穴はあるんだよな…」「かいちょーは梅くんに貰われました」"
    private fun node(id: String, text: String = "", description: String = "", visible: Boolean = true) =
        DanimeScreenNode("${StreamingService.D_ANIME.packageName}:id/$id", text, description, visible)
    private val controls get() = listOf(node("title", title), node("time_current", "1:27"), node("time", "23:40"), node("pause", description = "再生一時停止"))
    private val native get() = Playback(connected = true, searchTitle = title, title = "生徒会にも穴はある!", subtitle = "第1話", position = 61000, updatedAt = 1000, speed = 1f, playing = true)

    @Test fun readsActualPlayerTimesAndPauseState() {
        assertEquals(DanimeScreenPlayback(title, 87000, 1420000, true), DanimeScreenParser.parse(controls))
        assertEquals(false, DanimeScreenParser.parse(controls.dropLast(1) + node("pause", description = "再生"))?.playing)
        assertNull(DanimeScreenParser.parse(controls.map { it.copy(visible = false) }))
        assertNull(DanimeScreenParser.parse(controls.filterNot { it.id.endsWith("/time_current") }))
        assertNull(DanimeScreenParser.parse(controls.map { if (it.id.endsWith("/time_current")) it.copy(text = "99:99") else it }))
    }

    @Test fun realVideoTimeReplacesTheUnrelatedNativeClock() {
        var now = 1000L
        val repository = DanimePlaybackRepository { now }
        assertNull(repository.resolve(native).positionAt(now))
        repository.observe(DanimeScreenParser.parse(controls)!!)
        assertEquals(87000L, repository.resolve(native).positionAt(now))
        now += 5000
        assertEquals(92000L, repository.resolve(native.copy(position = 999999)).positionAt(now))
        assertEquals(1420000L, repository.resolve(native).duration)
    }

    @Test fun seekingCorrectsImmediatelyAndHiddenControlsKeepTheAnchor() {
        var now = 1000L
        val repository = DanimePlaybackRepository { now }
        val screen = DanimeScreenParser.parse(controls)!!
        repository.observe(screen)
        now += 10000
        repository.observe(screen.copy(position = 300000))
        assertEquals(300000L, repository.resolve(native).positionAt(now))
        now += 1000
        repository.observe(screen.copy(position = 300000))
        assertEquals(301000L, repository.resolve(native).positionAt(now))
        repository.observe(screen.copy(position = 200000, playing = false))
        now += 10000
        assertEquals(200000L, repository.resolve(native).positionAt(now))
        repository.observe(screen.copy(position = 200000, playing = true))
        now += 1000
        assertEquals(201000L, repository.resolve(native).positionAt(now))
    }

    @Test fun staleClockCannotAttachToAnotherEpisodeOrSession() {
        val repository = DanimePlaybackRepository { 1000 }
        repository.observe(DanimeScreenParser.parse(controls)!!)
        assertNull(repository.resolve(native.copy(searchTitle = "別の作品 第1話" )).positionAt(1000))
        assertNull(repository.resolve(native.copy(searchTitle = title.replace("第1話", "第2話"))).positionAt(1000))
        repository.clear()
        assertNull(repository.resolve(native).positionAt(1000))
    }

    @Test fun frozenScreenTimeStopsEvenIfThePauseDescriptionIsUnchanged() {
        var now = 1000L
        val repository = DanimePlaybackRepository { now }
        val screen = DanimeScreenParser.parse(controls)!!
        repository.observe(screen)
        now += 3000
        repository.observe(screen)
        assertFalse(repository.resolve(native).playing)
        now += 5000
        assertEquals(87000L, repository.resolve(native).positionAt(now))
        repository.observe(screen.copy(position = 88000))
        assertTrue(repository.resolve(native).playing)
    }
}
