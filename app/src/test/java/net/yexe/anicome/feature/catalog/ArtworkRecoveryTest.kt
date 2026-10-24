package net.yexe.anicome

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ArtworkRecoveryTest {
    @Test fun watchV4ThumbnailNamesAreSavedInsteadOfBeingDiscarded() {
        val response = org.json.JSONObject("""{"video":{"id":"so43638549","title":"作品 第1話","thumbnail":{"normal":"https://example.com/normal.jpg","middle":"https://example.com/middle.jpg","large":"https://example.com/large.jpg"}},"channel":{"id":"ch2649354","name":"作品"}}""")
        assertEquals("https://example.com/large.jpg", parseNiconicoSource("1712629210", response).thumbnailUrl)
        response.getJSONObject("video").getJSONObject("thumbnail").remove("large")
        assertEquals("https://example.com/middle.jpg", parseNiconicoSource("so43638549", response).thumbnailUrl)
    }
    @Test fun officialRssDescriptionSuppliesTheEpisodeThumbnail() {
        val xml = """<rss><channel><item><title>この素晴らしい世界に祝福を！３ 第1話</title>
            <link>https://www.nicovideo.jp/watch/1712629210</link>
            <description><![CDATA[<p class="nico-thumbnail"><img src="https://nicovideo.cdn.nimg.jp/thumbnails/43743890/43743890.1" /></p>]]></description>
            </item><item><title>この素晴らしい世界に祝福を！３ 第2話</title>
            <link>https://www.nicovideo.jp/watch/1713232024</link>
            <description><![CDATA[<img src="https://nicovideo.cdn.nimg.jp/thumbnails/43760000/43760000.2" />]]></description>
            </item></channel></rss>"""
        val rows = channelFeedCandidates(xml, 2649354)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.thumbnailUrl.startsWith("https://nicovideo.cdn.nimg.jp/thumbnails/") })
        assertNotEquals(rows[0].thumbnailUrl, rows[1].thumbnailUrl)
    }
    @Test fun blankArchivedThumbnailsAreRecoveredAndRememberedWithoutFetchingComments() = runTest {
        val store = MemoryPreferences()
        val settings = SettingsRepository(store, backgroundScope)
        var requests = 0
        val repository = VideoThumbnailRepository(settings) { id, refresh ->
            requests++
            Candidate(id, "作品 第1話", thumbnailUrl = if (refresh) "https://example.com/refreshed.jpg" else "https://example.com/original.jpg")
        }
        assertEquals("https://example.com/original.jpg", repository.thumbnail("so123"))
        testScheduler.runCurrent()
        val reopened = VideoThumbnailRepository(SettingsRepository(store, backgroundScope)) { _, _ -> error("Stored URL expected") }
        assertEquals("https://example.com/original.jpg", reopened.thumbnail("so123"))
        assertEquals("https://example.com/refreshed.jpg", repository.thumbnail("so123", true))
        assertEquals(2, requests)
    }
    @Test fun broadArtworkSearchHandlesProvidersThatRequireFullWidthSeasonDigits() {
        assertTrue(artworkSearchQueries("この素晴らしい世界に祝福を！3").contains("この素晴らしい世界に祝福を"))
        assertEquals("この素晴らしい世界に祝福を！3", artworkSearchQueries("この素晴らしい世界に祝福を！3").first())
    }
    @Test fun posterLookupKeepsSeasonsSeparateAndRejectsMissingImages() {
        val third = AnimeInfo(1, "この素晴らしい世界に祝福を！3", "https://example.com/3.jpg", "", null, 2024, 11, emptyList())
        assertTrue(artworkMatches("この素晴らしい世界に祝福を！ 第3期", third))
        assertFalse(artworkMatches("この素晴らしい世界に祝福を！", third))
        assertFalse(artworkMatches(third.title, third.copy(imageUrl = "")))
    }
}
