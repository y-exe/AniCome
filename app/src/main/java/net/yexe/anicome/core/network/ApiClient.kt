package net.yexe.anicome

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.TimeUnit

interface JsonApiService {
    @Streaming @GET suspend fun get(@Url url: String, @HeaderMap headers: Map<String, String>): Response<ResponseBody>
    @Streaming @POST suspend fun post(@Url url: String, @HeaderMap headers: Map<String, String>, @Body body: RequestBody): Response<ResponseBody>
}
class ApiHttpException(val status: Int) : java.io.IOException("取得失敗（HTTP $status）")
class ApiClient(private val service: JsonApiService, private val configuredService: (suspend () -> JsonApiService)? = null, initialClient: OkHttpClient = OkHttpClient(), private val traffic: TrafficBudgetRepository? = null) {
    @Volatile var httpClient: OkHttpClient = initialClient
        private set
    private suspend fun activeService() = configuredService?.invoke() ?: service
    suspend fun prepareNetwork() { activeService() }
    suspend fun request(url: String, body: String? = null, cookie: String? = null): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { JSONObject(requestText(url, body, cookie)) }
    suspend fun requestText(url: String, body: String? = null, cookie: String? = null): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val address = url.toHttpUrl()
        require(address.isHttps)
        traffic?.prepare()
        val headers = mutableMapOf("User-Agent" to "AniCome/${BuildConfig.VERSION_NAME} Android", "X-Frontend-Id" to "6", "X-Frontend-Version" to "0", "Accept" to "application/json")
        if (address.host in setOf("kitsu.io", "kitsu.app")) headers["Accept"] = "application/vnd.api+json"
        if (address.host == "www.nicovideo.jp") headers["Accept"] = "*/*"
        if (address.host == "snapshot.search.nicovideo.jp") headers["Accept"] = "*/*"
        if (address.host == "ch.nicovideo.jp" && address.queryParameter("rss") != null) {
            headers["User-Agent"] = "AniCome/${BuildConfig.VERSION_NAME}"
            headers["Accept"] = "application/rss+xml, application/xml"
        }
        if (address.host?.let { it == "nicovideo.jp" || it.endsWith(".nicovideo.jp") } == true) headers["X-Client-Os-Type"] = "others"
        if (cookie != null && address.host in setOf("www.nicovideo.jp", "nvapi.nicovideo.jp")) headers["Cookie"] = cookie
        val active = activeService()
        val response = if (body == null) active.get(address.toString(), headers) else active.post(address.toString(), headers, body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw ApiHttpException(response.code())
        }
        val context = kotlinx.coroutines.currentCoroutineContext()
        checkNotNull(response.body()).withTrafficBudget(traffic) { context.ensureActive() }.use { data ->
            require(data.contentLength() <= 64L * 1024 * 1024) { "応答データが大きすぎます" }
            data.charStream().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(16384)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val read = reader.read(buffer); if (read < 0) break
                    require(result.length + read <= 32 * 1024 * 1024) { "応答データが大きすぎます" }
                    result.append(buffer, 0, read)
                }
                result.toString()
            }
        }
    }
    suspend fun download(url: String, target: java.io.File, progress: (Long, Long) -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(URI(url).scheme == "https")
        val response = activeService().get(url, mapOf("Accept" to "application/octet-stream", "User-Agent" to "AniCome/${BuildConfig.VERSION_NAME} Android"))
        if (!response.isSuccessful) { response.errorBody()?.close(); throw ApiHttpException(response.code()) }
        checkNotNull(response.body()).use { body ->
            val length = body.contentLength()
            require(length <= 256L * 1024 * 1024) { "更新ファイルが大きすぎます" }
            body.byteStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536); var received = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    received += count; require(received <= 256L * 1024 * 1024)
                    output.write(buffer, 0, count); progress(received, length)
                }
                output.fd.sync()
            } }
        }
    }
    companion object {
        fun create(settings: SettingsRepository? = null, scope: kotlinx.coroutines.CoroutineScope? = null, traffic: TrafficBudgetRepository? = null): ApiClient {
            val base = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val request = if (chain.request().url.host !in setOf("www.nicovideo.jp", "nvapi.nicovideo.jp")) chain.request().newBuilder().removeHeader("Cookie").build() else chain.request()
                    chain.proceed(request)
                }.build()
            fun service(client: OkHttpClient) = Retrofit.Builder().baseUrl("https://www.nicovideo.jp/").client(client).build().create(JsonApiService::class.java)
            val mutex = kotlinx.coroutines.sync.Mutex()
            var key: List<Any>? = null
            var active = service(base)
            lateinit var result: ApiClient
            val factory: suspend () -> JsonApiService = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val options = settings?.load()?.let(AdvancedSettings::from) ?: AdvancedSettings()
                    mutex.withLock {
                        val newKey = listOf(options.proxyType, options.proxyHost, options.proxyPort, options.timeout, options.proxyCredentials)
                        if (key != newKey) {
                            result.httpClient = base.newBuilder().proxyConnection(options).connectTimeout(options.timeout.toLong(), TimeUnit.SECONDS)
                                .readTimeout(options.timeout.toLong(), TimeUnit.SECONDS).build()
                            active = service(result.httpClient); key = newKey
                        }
                        active
                    }
                }
            }
            result = ApiClient(active, factory, base, traffic)
            if (scope != null && settings != null) scope.launch { settings.data.catch { if (it is kotlinx.coroutines.CancellationException) throw it }.collect { try { factory() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { } } }
            return result
        }
    }
}
object ApiAccess {
    private val fallback by lazy { ApiClient.create() }
    var injected: ApiClient? = null
    val client get() = injected ?: fallback
}
