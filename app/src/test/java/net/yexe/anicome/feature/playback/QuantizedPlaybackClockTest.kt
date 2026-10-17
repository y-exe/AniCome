package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class QuantizedPlaybackClockTest {
    @Test fun primeRepeatedSecondReadingsDoNotRewindOrFreezeTheClock() {
        var time = 1000L
        val repository = PrimePlaybackRepository { time }
        val sample = PrimeScreenPlayback("作品", "第1話", 4000, 240000)
        repository.observe(sample)
        time += 1000
        repository.observe(sample.copy(position = 5000))
        time += 350
        repository.observe(sample.copy(position = 5000))
        assertEquals(5350L, repository.playback.value!!.positionAt(time))
        time += 500
        repository.observe(sample.copy(position = 5000))
        assertEquals(5850L, repository.playback.value!!.positionAt(time))
        time += 500
        repository.observe(sample.copy(position = 6000))
        assertEquals(6350L, repository.playback.value!!.positionAt(time))
        repository.observe(sample.copy(position = null))
        time += 5000
        assertEquals(11350L, repository.playback.value!!.positionAt(time))
        repository.observe(sample.copy(position = 90000))
        assertEquals(90000L, repository.playback.value!!.positionAt(time))
        time += 500
        repository.observe(sample.copy(position = 10000))
        assertEquals(10000L, repository.playback.value!!.positionAt(time))
    }

    @Test fun danimeKeepsSubsecondProgressAcrossRepeatedAndAdvancingLabels() {
        var time = 1000L
        val repository = DanimePlaybackRepository { time }
        val native = Playback(connected = true, title = "作品", subtitle = "第1話", searchTitle = "作品 第1話", playing = true, speed = 1f)
        val sample = DanimeScreenPlayback("作品 第1話", 5000, 240000, true)
        repository.observe(sample)
        time += 350
        repository.observe(sample)
        assertEquals(5350L, repository.resolve(native).positionAt(time))
        time += 500
        repository.observe(sample)
        assertEquals(5850L, repository.resolve(native).positionAt(time))
        time += 500
        repository.observe(sample.copy(position = 6000))
        assertEquals(6350L, repository.resolve(native).positionAt(time))
        time += 500
        repository.observe(sample.copy(position = 6000))
        assertEquals(6850L, repository.resolve(native).positionAt(time))
    }
}
