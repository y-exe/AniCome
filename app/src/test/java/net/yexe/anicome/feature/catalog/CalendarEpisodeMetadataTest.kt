package net.yexe.anicome

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CalendarEpisodeMetadataTest {
    @get:Rule val folder = TemporaryFolder()
    private val work = AnimeInfo(46474, "葬送のフリーレン", "", "", null, 2023, 28, emptyList(), "kitsu")
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/episode-metadata/frieren-$name.json")!!.readText())
    @Test fun RealJapaneseApiResponseSuppliesAll28TitlesWithoutJikanOrKitsu() = runBlocking {
        val calls = mutableListOf<String>()
        val metadata = EpisodeMetadataRepository(request = { url, _ ->
            calls.add(url)
            when { url.contains("Req=TitleSearch") -> fixture("search"); url.endsWith("TID=6776") -> fixture("detail"); else -> error(url) }
        })
        val titles = (1..28).map { metadata.title(work, it) }
        assertEquals("冒険の終わり", titles.first())
        assertEquals("また会ったときに恥ずかしいからね", titles.last())
        assertTrue(titles.all { !it.isNullOrBlank() })
        assertEquals(2, calls.size)
    }
    @Test fun JapaneseSubtitleReachesTheActualOfficialVideoSearch() = runBlocking {
        val metadata = EpisodeMetadataRepository(request = { url, _ -> if (url.contains("TitleSearch")) fixture("search") else fixture("detail") })
        val query = mutableListOf<String>()
        val source = Candidate("so889901", "葬送のフリーレン 第1話「冒険の終わり」", channelId = 111)
        val comments = CommentRepository(CommentStore(folder.newFolder()), search = { query.add(it); listOf(source) }, load = { _, _ -> listOf(Comment(0, "test")) })
        val saved = comments.obtain(Episode(work.title, 1), episodeTitle = metadata.title(work, 1).orEmpty(), strictMatch = true)
        assertEquals(source.id, saved.info.sourceId)
        assertTrue(query.single().contains("冒険の終わり"))
    }
    @Test fun OldReservationsCanResolveJapaneseTitlesWithoutACatalogId() = runBlocking {
        val metadata = EpisodeMetadataRepository(request = { url, _ -> if (url.contains("TitleSearch")) fixture("search") else fixture("detail") })
        assertEquals("冒険の終わり", metadata.title(work.copy(id = 0, year = null, episodes = null, provider = "syoboi"), 1))
    }
    @Test fun DifferentYearAndAmbiguousTitlesDoNotSelectAnArbitrarySeason() = runBlocking {
        val metadata = EpisodeMetadataRepository(request = { url, _ -> assertTrue(url.contains("TitleSearch")); fixture("search") })
        assertNull(metadata.title(work.copy(id = 0, year = 2025, provider = "syoboi"), 1))
        val ambiguous = EpisodeMetadataRepository(request = { url, _ ->
            assertTrue(url.contains("TitleSearch"))
            JSONObject("""{"Titles":{"1":{"TID":"1","Title":"同名作品","FirstYear":"2023"},"2":{"TID":"2","Title":"同名作品","FirstYear":"2026"}}}""")
        })
        assertNull(ambiguous.title(work.copy(id = 0, title = "同名作品", year = null, provider = "syoboi"), 1))
    }
    @Test fun OnlyNumberedSubtitlesWithinTheSelectedEpisodeCountAreUsed() = runBlocking {
        val metadata = EpisodeMetadataRepository(request = { url, _ -> if (url.contains("TitleSearch")) fixture("search") else
            JSONObject("""{"Titles":{"6776":{"SubTitles":"*00*予告\n*01*START\n*02*本編\n*03*特典\n説明文"}}}""") })
        assertEquals("START", metadata.title(work.copy(episodes = 2), 1))
        assertEquals("本編", metadata.title(work.copy(episodes = 2), 2))
        assertNull(metadata.title(work.copy(episodes = 2), 3))
    }
}
