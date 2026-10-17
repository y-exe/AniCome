package net.yexe.anicome

import org.junit.Test
import org.junit.Assert.*

class NiconicoSourceTest {
    @Test fun resolvesIdsAndSharedUrlsToCanonicalWatchUrls() {
        val expected = "https://www.nicovideo.jp/watch/so12345"
        assertEquals(expected, niconicoVideoUrl("so12345"))
        assertEquals(expected, niconicoVideoUrl("https://www.nicovideo.jp/watch/so12345?from=share"))
        assertEquals(expected, niconicoVideoUrl("https://nico.ms/so12345"))
        assertEquals("https://www.nicovideo.jp/watch/1234567890", niconicoVideoUrl("1234567890"))
    }
    @Test fun parsesActualVideoIdentityAndThumbnailFromWatchResponse() {
        val response = org.json.JSONObject("""{"video":{"id":"so12345","title":"実際の動画 第1話","duration":1440,"thumbnail":{"url":"https://example.com/small.jpg","largeUrl":"https://example.com/large.jpg"}}}""")
        val source = parseNiconicoSource("1234567890", response)
        assertEquals("so12345", source.id)
        assertEquals("実際の動画 第1話", source.title)
        assertEquals("https://example.com/large.jpg", source.thumbnailUrl)
    }
    @Test fun parsesThePublicChannelWatchPayload() {
        val raw = checkNotNull(javaClass.getResourceAsStream("/niconico-channel.json")).bufferedReader().use { it.readText() }
        val source = parseNiconicoSource("so22048157", org.json.JSONObject(raw.removePrefix("\uFEFF")))
        assertEquals("のんのんびより", source.ownerName)
        assertEquals(2581583L, source.channelId)
        assertEquals("https://ch.nicovideo.jp/ch2581583", source.ownerUrl)
        assertTrue(source.isNiconicoChannel())
    }
    @Test fun readsOrdinaryUploaderNicknameFromTheWatchApi() {
        val source = parseNiconicoSource("sm22257969", org.json.JSONObject("""{"video":{"id":"sm22257969","title":"公開動画"},"owner":{"id":29780457,"nickname":"ぽちまる△"}}"""))
        assertEquals("ぽちまる△", source.ownerName)
        assertEquals("https://www.nicovideo.jp/user/29780457", source.ownerUrl)
        assertFalse(source.isNiconicoChannel())
    }
    @Test fun keepsChannelAndOrdinaryUploaderIdentitiesSeparate() {
        val video = org.json.JSONObject("""{"video":{"id":"so123","title":"動画"},"channel":{"id":"ch1234","name":"公式チャンネル"},"owner":{"id":"999","name":"投稿者"}}""")
        val channel = parseNiconicoSource("so123", video)
        assertTrue(channel.isNiconicoChannel())
        assertEquals("公式チャンネル", channel.ownerName)
        assertEquals("https://ch.nicovideo.jp/ch1234", channel.ownerUrl)
        video.remove("channel")
        val user = parseNiconicoSource("so123", video)
        assertFalse(user.isNiconicoChannel())
        assertEquals("投稿者", user.ownerName)
        assertEquals("https://www.nicovideo.jp/user/999", user.ownerUrl)
    }
    @Test fun identifiesTheViewerAndNeverUsesTheVideoOwnerAsTheAccount() {
        assertEquals("12345", niconicoViewerId(org.json.JSONObject("""{"viewer":{"isLoggedIn":true,"userId":12345},"owner":{"id":"99999"}}""")))
        assertNull(niconicoViewerId(org.json.JSONObject("""{"viewer":{"isLoggedIn":false,"userId":12345},"owner":{"id":"99999"}}""")))
        assertNull(niconicoViewerId(org.json.JSONObject("""{"owner":{"id":"99999"}}""")))
    }
    @Test fun doesNotInventLinksForUnrecognizedSources() {
        assertNull(niconicoVideoUrl("https://example.com/watch/so12345"))
        assertNull(niconicoVideoUrl("https://nicovideo.jp.example.com/watch/so12345"))
        assertNull(niconicoVideoUrl("unknown"))
    }
}
