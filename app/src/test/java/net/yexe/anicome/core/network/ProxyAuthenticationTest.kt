package net.yexe.anicome

import okhttp3.*
import org.junit.Test
import org.junit.Assert.*
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyAuthenticationTest {
    @Test fun httpCredentialsOnlyAnswerProxyAuthenticationAndDoNotLoop() {
        val credentials = ProxyCredentials("example", "secret")
        val client = OkHttpClient.Builder().proxyConnection(AdvancedSettings(proxyType = "http", proxyHost = "127.0.0.1", proxyCredentials = credentials)).build()
        val response = Response.Builder().request(Request.Builder().url("https://video.invalid/").build()).protocol(Protocol.HTTP_1_1).code(407).message("Proxy auth").build()
        val request = client.proxyAuthenticator.authenticate(null, response)!!
        assertEquals(Credentials.basic("example", "secret"), request.header("Proxy-Authorization"))
        assertNull(request.header("Authorization"))
        assertNull(client.proxyAuthenticator.authenticate(null, response.newBuilder().request(request).build()))
        assertNull(OkHttpClient.Builder().proxyConnection(AdvancedSettings()).build().proxyAuthenticator.authenticate(null, response))
        assertFalse(credentials.toString().contains("secret"))
    }
    @Test fun socks5AuthenticatesAndResolvesTheTargetAtTheProxy() {
        val server = ServerSocket(0); server.soTimeout = 5000
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit<List<String>> {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream(); val output = socket.getOutputStream()
                fun read() = input.read().also { check(it >= 0) }
                fun bytes(count: Int) = ByteArray(count) { read().toByte() }
                assertEquals(5, read()); val methods = bytes(read()); assertTrue(methods.contains(2.toByte()))
                output.write(byteArrayOf(5, 2)); output.flush()
                assertEquals(1, read()); val user = bytes(read()).toString(Charsets.UTF_8); val password = bytes(read()).toString(Charsets.UTF_8)
                output.write(byteArrayOf(1, 0)); output.flush()
                assertEquals(5, read()); assertEquals(1, read()); assertEquals(0, read()); assertEquals(3, read())
                val destination = bytes(read()).toString(Charsets.US_ASCII); val port = read() * 256 + read()
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                val request = input.bufferedReader().let { reader -> generateSequence { reader.readLine() }.takeWhile(String::isNotEmpty).toList() }
                assertFalse(request.any { it.contains("secret") || it.contains("Proxy-Authorization", true) })
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray()); output.flush()
                listOf(user, password, destination, port.toString())
            }
        }
        try {
            val client = OkHttpClient.Builder().proxyConnection(AdvancedSettings(proxyType = "socks", proxyHost = "127.0.0.1", proxyPort = server.localPort, proxyCredentials = ProxyCredentials("example", "secret"))).callTimeout(5, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url("http://never-resolve.invalid:8080/").build()).execute().use { assertEquals("OK", it.body!!.string()) }
            assertEquals(listOf("example", "secret", "never-resolve.invalid", "8080"), accepted.get(5, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }
    @Test fun invalidCredentialsCannotBecomeHeadersOrSocksMessages() {
        listOf(ProxyCredentials("", "password"), ProxyCredentials("a:b", "x"), ProxyCredentials("u", "a\r\nb"), ProxyCredentials("u", "あ".repeat(100))).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }
}
