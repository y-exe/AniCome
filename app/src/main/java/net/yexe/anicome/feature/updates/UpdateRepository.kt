package net.yexe.anicome

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppRelease(val tag: String, val name: String, val notes: String, val url: String, val digest: String, val page: String)
data class UpdateState(val checking: Boolean = false, val downloading: Boolean = false, val release: AppRelease? = null, val progress: Float = 0f, val ready: File? = null, val message: String = "")
fun releaseIsNewer(tag: String, current: String): Boolean {
    fun numbers(value: String) = value.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = numbers(tag); val b = numbers(current)
    for (i in 0 until maxOf(a.size, b.size)) { val compare = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 }); if (compare != 0) return compare > 0 }
    return false
}
class UpdateRepository(private val context: Context, private val api: ApiClient, private val preferences: AdvancedSettingsRepository) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val mutex = kotlinx.coroutines.sync.Mutex()
    suspend fun check() = mutex.withLock {
        if (BuildConfig.FDROID) return@withLock
        mutable.update { it.copy(checking = true, message = "") }
        try {
            val data = api.request("https://api.github.com/repos/y-exe/AniCome/releases/latest")
            if (data.optBoolean("draft") || data.optBoolean("prerelease")) return@withLock
            val tag = data.getString("tag_name")
            if (!releaseIsNewer(tag, BuildConfig.VERSION_NAME)) { mutable.value = UpdateState(message = "最新バージョンです"); return@withLock }
            val assets = data.getJSONArray("assets")
            val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk", true) }
            val asset = candidates.firstOrNull { it.optString("name").contains("universal", true) }
                ?: candidates.firstOrNull { it.optString("name").contains("arm64", true) && android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
                ?: candidates.firstOrNull { it.optString("name").contains("x86_64", true) && android.os.Build.SUPPORTED_ABIS.contains("x86_64") }
                ?: error("端末に対応するAPKが見つかりません")
            val url = asset.getString("browser_download_url")
            require(url.startsWith("https://github.com/y-exe/AniCome/releases/download/"))
            mutable.value = UpdateState(release = AppRelease(tag, data.optString("name").ifBlank { tag }, data.optString("body"), url, asset.optString("digest"), data.optString("html_url")))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ApiHttpException) { mutable.update { it.copy(message = if (error.status == 404) "公開リリースがありません" else if (error.status == 403) "更新の確認回数が上限に達しました。時間をおいて再試行してください" else "更新を確認できませんでした（HTTP ${error.status}）") } }
        catch (_: Exception) { mutable.update { it.copy(message = "更新を確認できませんでした。通信設定を確認してください") } }
        finally { mutable.update { it.copy(checking = false) } }
    }
    suspend fun download() = mutex.withLock {
        if (BuildConfig.FDROID) return@withLock
        val release = state.value.release ?: return@withLock
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(directory, "update.apk.part")
        val target = File(directory, "update.apk")
        mutable.update { it.copy(downloading = true, progress = 0f, ready = null, message = "") }
        try {
            api.download(release.url, temporary) { received, total -> mutable.update { it.copy(progress = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f) } }
            withContext(Dispatchers.IO) {
                if (release.digest.startsWith("sha256:")) {
                    val hash = MessageDigest.getInstance("SHA-256")
                    temporary.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
                    check(hash.digest().joinToString("") { "%02x".format(it) }.equals(release.digest.removePrefix("sha256:"), true)) { "更新ファイルの整合性が一致しません" }
                }
                validateUpdateApk(context, temporary)
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            mutable.update { it.copy(ready = target, progress = 1f, message = "更新の準備ができました") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "更新をダウンロードできませんでした") } }
        finally { temporary.delete(); mutable.update { it.copy(downloading = false) } }
    }
    fun installIntent(file: File): Intent {
        require(file.canonicalFile == File(context.cacheDir, "updates/update.apk").canonicalFile && file.isFile)
        return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun schedule(scope: CoroutineScope) {
        if (BuildConfig.FDROID) { WorkManager.getInstance(context).cancelUniqueWork("app-update-check"); return }
        scope.launch {
        val pending = File(context.cacheDir, "updates/update.apk")
        if (pending.isFile) withContext(Dispatchers.IO) { runCatching { validateUpdateApk(context, pending); mutable.update { it.copy(ready = pending, message = "更新の準備ができました") } } }
        preferences.state.filter { it.loaded }.map { Triple(it.autoUpdate, it.autoDownload, it.updateWifi) }.distinctUntilChanged().collect { (enabled, _, wifi) ->
            val manager = WorkManager.getInstance(context)
            if (!enabled) manager.cancelUniqueWork("app-update-check") else {
                val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS).setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build()
                manager.enqueueUniquePeriodicWork("app-update-check", ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }
    } }
    fun notifyAvailable() {
        val value = state.value
        if (value.release == null) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("updates", context.uiString("アップデート"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 700, Intent(context, MainActivity::class.java).putExtra("open_updates", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(700, Notification.Builder(context, "updates").setSmallIcon(R.drawable.ic_notification).setContentTitle("AniCome ${value.release.tag}")
            .setContentText(context.uiString(if (value.ready != null) "更新の準備ができました。タップしてインストール" else "新しいバージョンを利用できます")).setContentIntent(open).setAutoCancel(true).build())
    }
}

@Suppress("DEPRECATION")
fun validateUpdateApk(context: Context, file: File) {
    val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    val archive = checkNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)) { "APKを読み込めません" }
    require(archive.packageName == context.packageName) { "別のアプリのAPKです" }
    require((archive.applicationInfo?.minSdkVersion ?: 26) <= android.os.Build.VERSION.SDK_INT) { "この更新は端末のAndroidバージョンに対応していません" }
    java.util.zip.ZipFile(file).use { zip ->
        val nativeAbis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
        require(nativeAbis.isEmpty() || android.os.Build.SUPPORTED_ABIS.any { it in nativeAbis }) { "この更新は端末のCPUに対応していません" }
    }
    val newVersion = if (android.os.Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    val currentVersion = if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
    require(newVersion > currentVersion) { "インストール済みより新しいAPKではありません" }
    fun signatures(info: android.content.pm.PackageInfo) = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    val a = signatures(installed).orEmpty().map { it.toCharsString() }.toSet()
    val b = signatures(archive).orEmpty().map { it.toCharsString() }.toSet()
    require(a.isNotEmpty() && a == b) { "署名が異なるため更新できません" }
}
class UpdatesViewModel(private val repository: UpdateRepository) : ViewModel() {
    val state = repository.state
    fun check() { viewModelScope.launch { repository.check() } }
    fun download() { viewModelScope.launch { repository.download() } }
    fun installIntent(file: File) = repository.installIntent(file)
}
open class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = org.koin.core.context.GlobalContext.get()
        val preferences = koin.get<AdvancedSettingsRepository>().state.first { it.loaded }
        if (!preferences.autoUpdate) return Result.success()
        val repository = koin.get<UpdateRepository>()
        repository.check()
        if (preferences.autoDownload) repository.download()
        repository.notifyAvailable()
        return Result.success()
    }
}
