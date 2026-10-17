package net.yexe.anicome

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EpisodeMetadataTest {
    private fun legacyRepository(request: suspend (String, String?) -> JSONObject) = EpisodeMetadataRepository(request = { url, body ->
        if (url.startsWith("https://cal.syoboi.jp/")) JSONObject("{\"Titles\":{}}") else request(url, body)
    })
    private fun work(provider: String = "jikan") = AnimeInfo(111, "テスト作品", "", "", null, 2026, 2, emptyList(), provider)
    @Test fun SelectedJikanIdLoadsJapaneseTitlesOnceForAllEpisodes() = runBlocking {
        var calls = 0
        val repository = legacyRepository(request = { url, _ ->
            calls++; assertTrue(url.contains("/anime/111/episodes"))
            JSONObject("""{"data":[{"mal_id":1,"title_japanese":"再会"},{"mal_id":2,"title":"English title","title_japanese":null}],"pagination":{"has_next_page":false}}""")
        })
        assertEquals("再会", repository.title(work(), 1))
        assertNull(repository.title(work(), 2))
        assertEquals(1, calls)
    }
    @Test fun KitsuPaginationEndsAndCachesNativeTitles() = runBlocking {
        val requests = mutableListOf<String>()
        val repository = legacyRepository(request = { url, _ ->
            requests.add(url)
            if (url.endsWith("offset%5D=0")) JSONObject("""{"data":[{"attributes":{"number":1,"titles":{"ja_jp":"再会"}}}],"links":{"next":"next"}}""")
            else JSONObject("""{"data":[{"attributes":{"number":2,"titles":{"ja_jp":"冒険"}}}],"links":{"next":null}}""")
        })
        assertEquals("再会", repository.title(work("kitsu"), 1))
        assertEquals("冒険", repository.title(work("kitsu"), 2))
        assertEquals(2, requests.size)
    }
    @Test fun KitsuEnglishOnlyEpisodesUseTheMalMapping() = runBlocking {
        val repository = legacyRepository(request = { url, _ -> when {
            url.contains("kitsu.app") && url.contains("/episodes?") -> JSONObject("""{"data":[{"attributes":{"number":1,"canonicalTitle":"Reunion","titles":{"en_us":"Reunion"}}}]}""")
            url.endsWith("/mappings") -> JSONObject("""{"data":[{"attributes":{"externalSite":"myanimelist/anime","externalId":"555"}}]}""")
            url.contains("/anime/555/episodes") -> JSONObject("""{"data":[{"mal_id":1,"title_japanese":"再会"}]}""")
            else -> error(url)
        } })
        assertEquals("再会", repository.title(work("kitsu"), 1))
    }
    @Test fun AniListIdIsConvertedBeforeRequestingJikan() = runBlocking {
        val repository = legacyRepository(request = { url, body ->
            if (url.contains("graphql")) { assertTrue(body!!.contains("id: 111")); JSONObject("""{"data":{"Media":{"idMal":555}}}""") }
            else { assertTrue(url.contains("/anime/555/episodes")); JSONObject("""{"data":[{"mal_id":1,"title_japanese":"再会"}]}""") }
        })
        assertEquals("再会", repository.title(work("anilist"), 1))
    }
    @Test fun PartialNativeTitlesAreFilledBeforeOtherWorkersReadTheCache() = runBlocking {
        val repository = legacyRepository(request = { url, _ -> when {
            url.contains("kitsu.app") && url.contains("/episodes?") -> JSONObject("""{"data":[{"attributes":{"number":1,"titles":{"ja_jp":"再会"}}},{"attributes":{"number":2,"titles":{"en_us":"Adventure"}}}]}""")
            url.endsWith("/mappings") -> JSONObject("""{"data":[{"attributes":{"externalSite":"myanimelist/anime","externalId":"555"}}]}""")
            else -> JSONObject("""{"data":[{"mal_id":1,"title_japanese":"再会"},{"mal_id":2,"title_japanese":"冒険"}]}""")
        } })
        assertEquals("再会", repository.title(work("kitsu"), 1))
        assertEquals("冒険", repository.title(work("kitsu"), 2))
    }
    @Test fun ProviderFailureDoesNotHammerTheApiForEveryEpisode() = runBlocking {
        var calls = 0
        val repository = legacyRepository(request = { _, _ -> calls++; throw java.io.IOException("503") })
        assertNull(repository.title(work(), 1)); assertNull(repository.title(work(), 2))
        assertEquals(1, calls)
    }
    @Test(expected = CancellationException::class) fun CancellationIsNeverConvertedToMissingMetadata(): Unit = runBlocking {
        legacyRepository(request = { _, _ -> throw CancellationException() }).title(work(), 1)
        Unit
    }
    @Test fun ConcurrentWorkersShareOneEpisodeListRequest() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val repository = legacyRepository(request = { _, _ -> calls++; started.complete(Unit); release.await(); JSONObject("""{"data":[{"mal_id":1,"title_japanese":"再会"},{"mal_id":2,"title_japanese":"冒険"}]}""") })
        val jobs = listOf(1, 2, 1).map { n -> async { repository.title(work(), n) } }
        started.await(); release.complete(Unit)
        assertEquals(listOf("再会", "冒険", "再会"), jobs.awaitAll())
        assertEquals(1, calls)
    }
    @Test fun ReservationsKeepTheSelectedProviderAndId() = runBlocking {
        val reservations = mutableListOf<Pair<Episode, AnimeInfo>>()
        val repository = SaveCommentRepository(reserve = { error("作品IDを失ってはいけない") }, reserveSelected = { episode, info -> reservations.add(episode to info) })
        val selected = work("kitsu")
        repository.save(listOf(Episode(selected.title, 1), Episode(selected.title, 2)), selected)
        assertEquals(listOf(1, 2), reservations.map { it.first.number })
        assertTrue(reservations.all { it.second.id == 111 && it.second.provider == "kitsu" })
    }
    @Test fun RepeatedEpisodeTitlesCannotIdentifyAnUnnumberedVideo() = runBlocking {
        val repository = legacyRepository(request = { _, _ -> JSONObject("""{"data":[{"mal_id":1,"title_japanese":"再会"},{"mal_id":2,"title_japanese":"再会"}]}""") })
        assertNull(repository.title(work(), 1)); assertNull(repository.title(work(), 2))
    }
    @Test fun CurrentKitsuEndpointUsesValidPageSizesAndJapaneseLanguageKeys() = runBlocking {
        val repository = legacyRepository(request = { url, _ ->
            assertTrue(url.startsWith("https://kitsu.app/")); assertTrue(url.contains("limit%5D=20"))
            JSONObject("""{"data":[{"attributes":{"number":1,"titles":{"ja":"再会"}}},{"attributes":{"number":2,"titles":{"ja":"冒険"}}}]}""")
        })
        assertEquals("再会", repository.title(work("kitsu"), 1))
    }
    @Test fun RetryingAReservationKeepsTheExactEpisodeAndCatalogIdentity() {
        val tags = setOf("series:テスト作品 第2期", "episode:3", "catalog:kitsu:111:2026:12", "label:テスト作品 第2期 第3話")
        val episode = reservationEpisode(tags)!!
        val catalog = reservationCatalog(tags, episode)!!
        assertEquals(Episode("テスト作品 第2期", 3), episode)
        assertEquals(111, catalog.id); assertEquals("kitsu", catalog.provider); assertEquals(2026, catalog.year); assertEquals(12, catalog.episodes)
    }
    @Test fun OldReservationsWithoutCatalogTagsRemainReadable() {
        val tags = setOf("label:テスト作品 第3話")
        val episode = reservationEpisode(tags)!!
        assertEquals(3, episode.number); assertNull(reservationCatalog(tags, episode))
    }
}
