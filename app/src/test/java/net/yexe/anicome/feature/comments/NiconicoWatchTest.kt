package net.yexe.anicome

import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class NiconicoWatchTest {
    private fun watch() = JSONObject().put("comment", JSONObject().put("nvComment", JSONObject().put("threadKey", "sample")))
        .put("video", JSONObject().put("title", "Title &amp; <test> \"quoted\""))
    private fun payload(modern: Boolean) = JSONObject().put("data", JSONObject().put("response",
        if (modern) JSONObject().put("\$watchV4", JSONObject().put("data", watch())) else watch())).toString()
    private fun html(modern: Boolean) = "<html><meta content='${payload(modern).replace("&", "&amp;").replace("\"", "&quot;")}' name='server-response'></html>"

    @Test fun readsLegacyJsonAndModernHtmlWithoutLosingLiteralEntities() {
        for (value in listOf(payload(false), payload(true), html(false), html(true))) {
            val parsed = NiconicoWatch.parse(value)
            assertEquals("sample", parsed.getJSONObject("comment").getJSONObject("nvComment").getString("threadKey"))
            assertEquals(watch().getJSONObject("video").getString("title"), parsed.getJSONObject("video").getString("title"))
        }
    }

    @Test fun fallsBackOn400AndPreservesSessionOnBothRequests() = runBlocking {
        val urls = mutableListOf<String>()
        val api = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
                assertEquals("user_session=sample", headers["Cookie"])
                urls += url
                return if (url.contains("responseType")) Response.error(400, "{}".toResponseBody()) else Response.success(html(true).toResponseBody())
            }
            override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Unexpected POST")
        }
        assertTrue(NiconicoWatch.load("so123", "user_session=sample", ApiClient(api)).has("comment"))
        assertEquals(listOf("https://www.nicovideo.jp/watch/so123?responseType=json&nvpcClientVersion=1", "https://www.nicovideo.jp/watch/so123"), urls)
    }

    @Test fun requestsClientVersionAndReadsV4ChannelMetadata() = runBlocking {
        var calls = 0
        val modern = watch().put("video", JSONObject().put("id", "so123").put("title", "Episode 2").put("duration", 1418))
            .put("metadata", JSONObject().put("jsonLd", JSONObject().put("owner", JSONObject()
                .put("type", "channel").put("id", "ch2614174").put("name", "Channel"))))
        val api = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
                calls++
                assertEquals("https://www.nicovideo.jp/watch/so123?responseType=json&nvpcClientVersion=1", url)
                return Response.success(JSONObject().put("data", JSONObject().put("response", JSONObject()
                    .put("\$watchV4", JSONObject().put("data", modern)))).toString().toResponseBody())
            }
            override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Unexpected POST")
        }
        val source = parseNiconicoSource("so123", NiconicoWatch.load("so123", null, ApiClient(api)))
        assertEquals(1, calls)
        assertEquals(2614174L, source.channelId)
        assertEquals("Channel", source.ownerName)
        assertEquals("https://ch.nicovideo.jp/ch2614174", source.ownerUrl)
        assertEquals(1418L, source.duration)
    }

    @Test fun readsV4UserAndKeepsV3ChannelMetadata() {
        val data = watch().put("metadata", JSONObject().put("jsonLd", JSONObject().put("owner", JSONObject()
            .put("type", "user").put("id", "1234").put("name", "Uploader"))))
        val parsed = NiconicoWatch.parse(JSONObject().put("data", JSONObject().put("response", JSONObject()
            .put("\$watchV4", JSONObject().put("data", data)))).toString())
        val user = parseNiconicoSource("sm123", parsed)
        assertEquals(1234L, user.userId)
        assertEquals("Uploader", user.ownerName)
        assertNull(user.channelId)
        val legacy = watch().put("channel", JSONObject().put("id", "ch567").put("name", "Legacy"))
        val old = NiconicoWatch.parse(JSONObject().put("data", JSONObject().put("response", legacy)).toString())
        assertEquals(567L, parseNiconicoSource("so123", old).channelId)
    }

    @Test fun doesNotRetryAuthenticationOrRateLimitErrors() = runBlocking {
        for (status in listOf(401, 403, 429)) {
            var calls = 0
            val api = object : JsonApiService {
                override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> { calls++; return Response.error(status, "{}".toResponseBody()) }
                override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Unexpected POST")
            }
            try { NiconicoWatch.load("so123", null, ApiClient(api)); fail("Expected HTTP error") }
            catch (error: ApiHttpException) { assertEquals(status, error.status) }
            assertEquals(1, calls)
        }
    }
}
