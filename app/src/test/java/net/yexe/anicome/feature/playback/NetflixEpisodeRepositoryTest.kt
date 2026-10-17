package net.yexe.anicome

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetflixEpisodeRepositoryTest {
    private val title = "ニャーがヤニねこにゃ"
    private val input = NetflixEpisodeIdentity(title, 1, 1)
    private val official = Candidate("so46495852", "ヤニねこ 第1話「$title」", channelId = 2650159, duration = 1420, commentCount = 98440)
    private val danime = Candidate("so46502808", "ヤニねこ　第1話　$title", channelId = DANIME_CHANNEL, duration = 1420, commentCount = 2974)

    @Test fun readsJapaneseNetflixMetadataWithInvisibleCharacters() {
        assertEquals(input, NetflixEpisodeMetadata.identity(listOf("DISPLAY_SUBTITLE" to "シ\uFEFFー\uFEFFズ\uFEFFン\uFEFF1-1: $title")))
        assertEquals(input, NetflixEpisodeMetadata.identity(listOf("DISPLAY_SUBTITLE" to "S1:E1 - $title")))
    }

    @Test fun genericEpisodeTitlesCannotIdentifyAWork() {
        assertNull(NetflixEpisodeMetadata.identity(listOf("DISPLAY_SUBTITLE" to "S1:E1 - Episode 1")))
        val short = NetflixEpisodeMetadata.identity(listOf("DISPLAY_SUBTITLE" to "シーズン1-1: 出会い"))!!
        assertNull(NetflixEpisodeMetadata.choose(short, listOf(
            official.copy(title = "作品Ａ 第1話「出会い」"),
            official.copy(id = "so2", title = "作品Ｂ 第1話「出会い」")
        )))
    }

    @Test fun shortEpisodeTitleCanIdentifyAnUnambiguousWork() {
        val short = NetflixEpisodeMetadata.identity(listOf("DISPLAY_SUBTITLE" to "シ\uFEFFー\uFEFFズ\uFEFFン\uFEFF1-1: 無職転生"))!!
        val channel = Candidate("so38080161", "無職転生 ～異世界行ったら本気だす～ 第1話「無職転生」", channelId = 2646899, duration = 1422)
        val store = Candidate("so38070762", "無職転生 ～異世界行ったら本気だす～　第1話　無職転生", channelId = DANIME_CHANNEL, duration = 1420)
        assertEquals(channel.id, NetflixEpisodeMetadata.choose(short, listOf(channel, store))?.source?.id)
        assertEquals(listOf("無職転生"), NetflixEpisodeMetadata.queries(short.title))
        assertNull(NetflixEpisodeMetadata.choose(short.copy(season = 2), listOf(channel, store)))
    }

    @Test fun findsOfficialSeriesFromEpisodeTitle() {
        val found = NetflixEpisodeMetadata.choose(input, listOf(danime, official))!!
        assertEquals(Episode("ヤニねこ", 1), found.episode)
        assertEquals(official.id, found.source.id)
    }

    @Test fun rejectsOtherWorksSeasonsAndUserUploads() {
        val other = official.copy(id = "so2", title = "別の作品 第1話「$title」")
        assertNull(NetflixEpisodeMetadata.choose(input, listOf(official, other)))
        assertNull(NetflixEpisodeMetadata.choose(input.copy(season = 2), listOf(official)))
        assertNull(NetflixEpisodeMetadata.choose(input, listOf(official.copy(id = "sm1", channelId = null, userId = 123))))
        assertNull(NetflixEpisodeMetadata.choose(input.copy(title = "異なる長いサブタイトル"), listOf(official)))
    }

    @Test fun resolvesSecondSeasonWithoutMixingFirstSeason() {
        val first = official.copy(title = "別の作品 第1話「$title」")
        val second = official.copy(id = "so2", title = "別の作品 第2期 第1話「$title」")
        assertEquals(second.id, NetflixEpisodeMetadata.choose(input.copy(season = 2), listOf(first, second))?.source?.id)
    }

    @Test fun archivedWorkCanBeResolvedWithoutNetwork() = runTest {
        var requests = 0
        val repository = NetflixEpisodeRepository(backgroundScope, { requests++; emptyList() }, archives = { listOf(official.copy(channelId = null)) })
        repository.select(input)
        runCurrent()
        assertEquals(official.id, repository.match.value?.source?.id)
        assertEquals(0, requests)
    }

    @Test fun repeatedProgressUpdatesAndReopeningReuseTheResolution() = runTest {
        var requests = 0
        val repository = NetflixEpisodeRepository(backgroundScope, { requests++; listOf(official) })
        repository.select(input)
        runCurrent()
        repeat(10) { repository.select(input) }
        repository.select(null)
        repository.select(input)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(official.id, repository.match.value?.source?.id)
    }

    @Test fun staleSearchCannotAttachToTheNextEpisode() = runTest {
        val pending = CompletableDeferred<List<Candidate>>()
        val repository = NetflixEpisodeRepository(backgroundScope, { pending.await() })
        repository.select(input)
        runCurrent()
        repository.select(null)
        pending.complete(listOf(official))
        runCurrent()
        assertNull(repository.match.value)
    }

    @Test fun endCreditsDoNotRescaleOrShiftPlaybackTime() {
        val playback = Playback(position = 600000, updatedAt = 1000, speed = 1f, playing = true, duration = 1494000, commentDuration = 1420000)
        assertEquals(1420000L, playback.commentLookupDuration)
        assertEquals(1494000L, playback.duration)
        assertEquals(601000L, playback.positionAt(2000))
        assertEquals(0L, playback.copy(commentDuration = 0).commentLookupDuration)
        assertEquals(1494000L, playback.copy(commentDuration = null).commentLookupDuration)
    }

    @Test fun compoundTitlesAcceptSlashQuotesAndEllipsisDifferences() {
        val first = "長い最初のサブタイトル"
        val second = "長い二番目のサブタイトル"
        val compound = NetflixEpisodeIdentity("$first.../$second", 1, 1)
        val full = official.copy(title = "作品 第1話「$first…」「$second」")
        val variant = danime.copy(title = "作品【TV放送版】 第1話「$first…」「$second」")
        assertEquals(full.id, NetflixEpisodeMetadata.choose(compound, listOf(variant, full))?.source?.id)
        assertEquals(listOf(compound.title, first, second), NetflixEpisodeMetadata.queries(compound.title))
        assertNull(NetflixEpisodeMetadata.choose(compound.copy(title = first), listOf(full)))
    }

    @Test fun compoundTitleFallsBackToItsPartsWithoutAcceptingUnrelatedEpisodes() = runTest {
        val first = "長い最初のサブタイトル"
        val second = "長い二番目のサブタイトル"
        val identity = NetflixEpisodeIdentity("$first.../$second", 1, 1)
        val full = official.copy(title = "作品 第1話「$first…」「$second」")
        val requests = mutableListOf<String>()
        val repository = NetflixEpisodeRepository(backgroundScope, { query ->
            requests += query
            if (query == identity.title) emptyList() else listOf(full)
        })
        repository.select(identity)
        runCurrent()
        assertEquals(full.id, repository.match.value?.source?.id)
        assertEquals(setOf(identity.title, first, second), requests.toSet())
    }
}
