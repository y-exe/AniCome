package net.yexe.anicome

import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ApiClientTest {
    @Test fun sendsSessionCookieOnlyToTrustedNiconicoAuthenticationHosts() = runBlocking {
        val fake = FakeService()
        val client = ApiClient(fake)
        client.request("https://www.nicovideo.jp/watch/so123", cookie = "user_session=example")
        assertEquals("user_session=example", fake.headers["Cookie"])
        assertEquals("*/*", fake.headers["Accept"])
        assertEquals("others", fake.headers["X-Client-Os-Type"])
        client.request("https://nvapi.nicovideo.jp/v1/comment/keys/thread?videoId=so123", cookie = "user_session=example")
        assertEquals("user_session=example", fake.headers["Cookie"])
        client.request("https://kitsu.io/api/edge/anime", cookie = "user_session=example")
        assertFalse(fake.headers.containsKey("Cookie"))
        assertEquals("application/vnd.api+json", fake.headers["Accept"])
        client.request("https://nvcomment.nicovideo.jp/v1/threads", "{}", "user_session=example")
        assertFalse(fake.headers.containsKey("Cookie"))
        assertTrue(fake.posted)
    }
    @Test fun rejectsNonHttpsBeforeCallingService() = runBlocking {
        val fake = FakeService()
        try { ApiClient(fake).request("http://www.nicovideo.jp/watch/so123"); fail() }
        catch (_: IllegalArgumentException) { assertTrue(fake.headers.isEmpty()) }
    }
    private class FakeService : JsonApiService {
        var headers = emptyMap<String, String>()
        var posted = false
        override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
            this.headers = headers
            return Response.success("{}".toResponseBody())
        }
        override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> {
            posted = true
            return get(url, headers)
        }
    }
}
