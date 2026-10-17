package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class BandaiTitleRepositoryTest {
    private var time = 1000L
    private fun repository() = BandaiTitleRepository { time }.also { it.setEnabled(true) }
    private fun bind(repository: BandaiTitleRepository, id: String, episode: String) {
        repository.mediaChanged(id)
        time += 1001
        repository.observeScreen("作品", episode)
    }

    @Test fun keepsTitleWhenFullscreenRemovesTheTextNodes() {
        val repository = repository()
        bind(repository, "first", "#1 はじまり")
        repository.mediaChanged("first")
        assertEquals(BandaiScreenTitle("作品", "#1 はじまり"), repository.title.value)
    }

    @Test fun neverUsesThePreviousEpisodeForAnUnknownVideo() {
        val repository = repository()
        bind(repository, "first", "#1 はじまり")
        repository.mediaChanged("second")
        assertNull(repository.title.value)
        time += 2000
        repository.observeScreen("作品", "#1 はじまり")
        assertNull(repository.title.value)
        repository.observeScreen("作品", "#2 次の話")
        assertEquals(BandaiScreenTitle("作品", "#2 次の話"), repository.title.value)
    }

    @Test fun doesNotOverwriteTheOldVideoWhenScreenChangesBeforeTheMediaSession() {
        val repository = repository()
        bind(repository, "first", "#1 はじまり")
        repository.observeScreen("作品", "#2 次の話")
        assertEquals("#1 はじまり", repository.title.value?.episode)
        bind(repository, "second", "#2 次の話")
        assertEquals("#2 次の話", repository.title.value?.episode)
    }

    @Test fun waitsForTheScreenToSettleAfterTheVideoChanges() {
        val repository = repository()
        repository.mediaChanged("first")
        repository.observeScreen("作品", "#1 はじまり")
        assertNull(repository.title.value)
        time += 1001
        repository.observeScreen("作品", "#1 はじまり")
        assertNotNull(repository.title.value)
    }

    @Test fun recognisesAnEarlierVideoWhenItIsReopened() {
        val repository = repository()
        bind(repository, "first", "#1 はじまり")
        bind(repository, "second", "#2 次の話")
        repository.mediaChanged("first")
        assertEquals("#1 はじまり", repository.title.value?.episode)
    }

    @Test fun disablingTheServiceClearsAllRememberedTitles() {
        val repository = repository()
        bind(repository, "first", "#1 はじまり")
        repository.setEnabled(false)
        assertNull(repository.title.value)
        repository.setEnabled(true)
        repository.mediaChanged("")
        repository.mediaChanged("first")
        assertNull(repository.title.value)
    }

    @Test fun refusesMissingEpisodeNumbers() {
        val repository = repository()
        bind(repository, "first", "作品あらすじ")
        assertNull(repository.title.value)
    }

    @Test fun signedUrlRefreshDoesNotChangeVideoIdentityOrExposeTheToken() {
        val first = bandaiVideoIdentity("", "https://manifest.prod.boltdns.net/video/first/manifest.mpd?token=secret")
        assertEquals(first, bandaiVideoIdentity(null, "https://manifest.prod.boltdns.net/video/first/manifest.mpd?token=renewed"))
        assertFalse(first.contains("secret"))
        assertNotEquals(first, bandaiVideoIdentity(null, "https://manifest.prod.boltdns.net/video/second/manifest.mpd?token=secret"))
        assertEquals("", bandaiVideoIdentity(null, null))
        assertEquals("", bandaiVideoIdentity(null, "not a URL"))
    }
}
