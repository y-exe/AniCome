package net.yexe.anicome

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class FetchCompletion(val title: String, val count: Int)

data class FetchProgress(val label: String = "待機中", val current: Int = 0, val target: Int = 0)

class AppCoordinator(
    private val context: Context? = null,
    private val settings: SettingsRepository? = null,
    private val injectedRepository: CommentRepository? = null,
    private val dao: SavedEpisodeDao? = null,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    val playbackSources: PlaybackSourceRepository? = null,
    private val history: CommentHistoryRepository? = null
) {
    private val appContext get() = requireNotNull(context)
    var convenience: ConvenienceRepository? = null
    val ready = MutableStateFlow(false)
    val startupError = MutableStateFlow("")
    lateinit var repository: CommentRepository
        private set
    val library = MutableStateFlow<List<SavedEpisode>>(emptyList())
    val status = MutableStateFlow("DMM TVの作品情報を待っています")
    val busy = MutableStateFlow(false)
    val fetchProgress = MutableStateFlow(FetchProgress())
    val completedFetches = MutableSharedFlow<FetchCompletion>(extraBufferCapacity = 4)
    val auto = MutableStateFlow(true)
    val commentAmount = MutableStateFlow(5)
    val niconicoLinked = MutableStateFlow(false)
    val watchDownloads = MutableStateFlow(false)
    val wifiOnly = MutableStateFlow(true)
    val batchLimit = MutableStateFlow(12)
    val language = MutableStateFlow("system")
    val themeMode = MutableStateFlow("dark")
    val activeService = MutableStateFlow(StreamingService.NETFLIX)
    val storageLimitMb = MutableStateFlow(0L)
    val setupCompleted = MutableStateFlow(false)
    private val retry = MutableStateFlow(0)
    private var preferences = SettingsSnapshot(androidx.datastore.preferences.core.emptyPreferences())
    private var initialization: Job? = null
    fun start() {
        if (initialization?.isActive == true || ready.value) return
        initialization = scope.launch {
            startupError.value = ""
            try { initialize() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { startupError.value = "設定と保存データを読み込めませんでした。空き容量を確認して再試行してください。" }
        }
    }
    private suspend fun initialize() {
        preferences = settings?.load() ?: preferences
        ApiAccess.client.prepareNetwork()
        settings?.let { source -> scope.launch { source.data.collect { val value = SettingsSnapshot(it); Session.customPosition.value = value.getFloat("overlayCustomY", 1f).coerceIn(0f,1f); Session.overlayPosition.value = value.getString("overlayPosition", "center") ?: "center" } } }
        settings?.let { source -> scope.launch {
            source.saveFailed.collect { failed -> if (failed) Session.message.value = "設定を保存できません。端末の空き容量を確認してください。再試行しています。" }
        } }
        repository = checkNotNull(injectedRepository)
        withContext(Dispatchers.IO) { repository.store.reconcileIndex() }
        dao?.let { source -> scope.launch { source.observe().collect { library.value = it.map(SavedEpisodeEntity::toEpisode) } } }
        storageLimitMb.value = preferences.getLong("storageLimitMb", 0L).coerceAtLeast(0L)
        setupCompleted.value = preferences.getBoolean("setupCompleted", false)
        Session.size.value = preferences.getFloat("commentSize", 14f).coerceIn(5f, 25f)
        Session.overlayRequested.value = preferences.getBoolean("overlayEnabled", true)
        auto.value = preferences.getBoolean("auto", true)
        commentAmount.value = preferences.getInt("commentAmount", 5).coerceIn(1, 20)
        niconicoLinked.value = NiconicoLogin.isLinked()
        NiconicoLogin.publishProfile()
        if (niconicoLinked.value) completeInitialLogin()
        Session.commentAmount.value = commentAmount.value
        watchDownloads.value = preferences.getBoolean("watchDownloads", false)
        wifiOnly.value = preferences.getBoolean("wifiOnly", true)
        batchLimit.value = preferences.getInt("batchLimit", 12).coerceIn(1, 50)
        language.value = preferences.getString("language", "system")?.takeIf { value -> SupportedLanguages.any { it.first == value } } ?: "system"
        themeMode.value = preferences.getString("themeMode", "dark") ?: "dark"
        activeService.value = preferences.getString("activeService", StreamingService.NETFLIX.name)
            ?.let { name -> StreamingService.displayOrder.firstOrNull { it.name == name } } ?: StreamingService.NETFLIX
        if (preferences.getString("activeService", null) in setOf(StreamingService.HULU.name, StreamingService.FOD.name)) settings?.putString("activeService", activeService.value.name)
        Session.opacity.value = preferences.getFloat("opacity", .75f).coerceIn(.2f, 1f)
        Session.speedScale.value = preferences.getFloat("speedScale", .7f).coerceIn(.25f, 1.5f)
        Session.displayDensity.value = 1f
        Session.fps.value = preferences.getInt("fps", 60).coerceIn(15, 144)
        Session.colorStyle.value = preferences.getString("colorStyle", "original") ?: "original"
        Session.fontStyle.value = preferences.getString("fontStyle", "default") ?: "default"
        Session.ngWords.value = preferences.getString("ngWords", "") ?: ""
        ready.value = true
        scope.launch { refreshLibrary() }
        scope.launch {
            val automaticTriggers = combine(Session.playback.map { Triple(it.key, it.connected, it.advertising) to it.commentLookupDuration }.distinctUntilChanged(), auto, commentAmount, retry, setupCompleted) { _, enabled, _, _, completed -> enabled && completed }.combine(playbackSources?.targets ?: flowOf(emptySet<PlaybackSource>())) { enabled, _ -> enabled }
            combine(automaticTriggers, Session.manualPlayback, Session.adPause) { enabled, manual, adPause -> Triple(enabled, manual, adPause) }
                .collectLatest { (enabled, manual, adPause) ->
                    busy.value = false
                    fetchProgress.value = FetchProgress()
                    if (manual != null) { status.value = "手動再生モード"; return@collectLatest }
                    val playback = Session.playback.value
                    val commentDuration = playback.commentLookupDuration
                    if (adPause || playback.advertising) { status.value = "広告中。コメント表示を一時停止しています"; return@collectLatest }
                    if (Session.commentBinding.value == CommentBinding.MANUAL && Session.manualCommentKey.value == playback.key) {
                        status.value = "手動で選択したコメントを使用中"
                        return@collectLatest
                    }
                    val sourcePolicy = playbackSources?.targets?.first()
                    val allowedSources = sourcePolicy?.first
                    fun retainedSlots() = if (Session.matchedKey == playback.key) Session.commentSlots.value.filter { slot -> allowedSources?.contains(slot.source) != false && (sourcePolicy?.second != true || sourcePolicy.third != true || slot.source == PlaybackSource.OFFICIAL || (slot.sync.confirmed && slot.sync != CommentSync.DISABLED)) } else emptyList()
                    prepareAutomaticComments(playback, enabled, allowedSources)
                    if (!enabled) { status.value = "自動取得はオフです"; return@collectLatest }
                    val artworkQuery = runCatching {
                        EpisodeMatcher.parse(playback.title, playback.subtitle)?.series
                    }.getOrNull() ?: playback.searchTitle.ifBlank { playback.title }
                    scope.launch { AnimeCatalog.find(artworkQuery) }
                    if (!playback.connected || playback.title.isBlank()) { status.value = "${activeService.value.label}の作品情報を待っています"; return@collectLatest }
                    val episode = EpisodeMatcher.parse(playback.title, playback.subtitle)
                    if (episode == null) { status.value = "作品名・話数を特定できません。${activeService.value.label}からの情報を待っています"; return@collectLatest }
                    busy.value = true
                    try {
                        val amount = commentAmount.value
                        val connected = online()
                        val cached = if (!connected && playbackSources != null) playbackSources.cached(episode, commentDuration) else if (!connected) repository.cached(episode, amount)?.let { PlaybackComments(listOf(PlaybackCommentSlot(it.info.sourceId, it.info.sourceTitle, PlaybackSource.OFFICIAL, it.comments, true))) } else null
                        val obtained = if (cached != null) cached else {
                            if (!connected) {
                                if (withContext(Dispatchers.IO) { repository.store.readForPlayback(episode) } != null) {
                                    status.value = "保存済みですが、表示できる取得元がありません。取得元と同期設定を確認してください"
                                } else {
                                    DownloadWorker.enqueue(appContext, episode, "playback")
                                    status.value = "未保存です。オンラインになったら自動取得します"
                                }
                                return@collectLatest
                            }
                            status.value = if (amount > Comments.availableAmount(amount)) {
                                "${episode.label} を1倍で自動取得中…（高倍率にはニコニコログイン連携が必要です）"
                            } else "${episode.label} のコメントを${amount}倍で自動取得中…"
                            convenience?.selectedCandidate(episode)?.takeUnless { library.value.any { it.matchesPlaybackEpisode(episode) && it.sources.contains(',') } }?.let { remembered -> playbackSources?.manual(episode, remembered, amount) { label, current, target -> fetchProgress.value = FetchProgress(label, current, target) } }
                                ?: playbackSources?.obtain(episode, commentDuration / 1000, playback.searchTitle, amount, Diagnostics::trace,
                                progress = { label, current, target -> fetchProgress.value = FetchProgress(label, current, target) }, onPartial = { update ->
                                    if (Session.playback.value.key == playback.key && !(Session.commentBinding.value == CommentBinding.MANUAL && Session.manualCommentKey.value == playback.key)) {
                                        val partial = continuePlaybackComments(retainedSlots(), update, Session.commentSyncReports.value)
                                        if (Session.matchedKey != playback.key) Session.offset.value = 0
                                        Session.matchedKey = playback.key; if (partial.title.isNotBlank()) Session.matchedTitle = partial.title
                                        Session.commentSyncReports.value = partial.syncReports
                                        if (partial.comments.isNotEmpty()) {
                                            Session.commentsFromArchive.value = partial.fromArchive
                                            Session.commentSlots.value = partial.slots
                                            Session.comments.value = partial.comments
                                            Session.commentSources.value = partial.slots.map { it.source }.distinct()
                                        }
                                        Session.commentBinding.value = CommentBinding.AUTO
                                    }
                                })
                                ?: repository.obtain(episode, duration = commentDuration / 1000, searchTitle = playback.searchTitle, amount = amount, trace = Diagnostics::trace,
                                    progress = { label, current, target -> fetchProgress.value = FetchProgress(label, current, target) }).let { PlaybackComments(listOf(PlaybackCommentSlot(it.info.sourceId, it.info.sourceTitle, PlaybackSource.OFFICIAL, it.comments, it.fromArchive))) }
                        }
                        ensureActive()
                        val saved = continuePlaybackComments(retainedSlots(), obtained, Session.commentSyncReports.value)
                        if (Session.playback.value.key == playback.key && !(Session.commentBinding.value == CommentBinding.MANUAL && Session.manualCommentKey.value == playback.key)) {
                            if (Session.matchedKey != playback.key) Session.offset.value = 0
                            Session.matchedKey = playback.key
                            Session.matchedTitle = saved.title
                            Session.commentSyncReports.value = saved.syncReports
                            Session.commentsFromArchive.value = saved.fromArchive
                            Session.commentSlots.value = saved.slots
                            Session.comments.value = saved.comments
                            Session.commentSources.value = saved.slots.map { it.source }.distinct()
                            Session.commentBinding.value = CommentBinding.AUTO
                            Session.manualCommentKey.value = ""
                            if (obtained.slots.all { it.fromArchive }) recordArchiveUse(episode, obtained)
                            if (!saved.fromArchive) completedFetches.emit(FetchCompletion(saved.title, saved.comments.size))
                            status.value = if (saved.fromArchive) "保存済みコメントを使用" else "自動選択・オフライン保存済み"
                        }
                        refreshLibrary()
                    } catch (e: CancellationException) { throw e }
                    catch (e: MatchUnavailable) { status.value = e.message.orEmpty() }
                    catch (e: Exception) {
                        status.value = "取得失敗: ${e.message?.take(220) ?: e.javaClass.simpleName}"
                        if (e is java.io.IOException && e !is TrafficLimitReached) DownloadWorker.enqueue(appContext, episode, "playback")
                    } finally { busy.value = false }
                }
        }
        appContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var validated = false
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (connected && !validated) retry.update { it + 1 }
                validated = connected
            }
            override fun onLost(network: Network) { validated = false }
        })
    }
    fun recordArchiveUse(episode: Episode, comments: PlaybackComments): Job? {
        val repository = history ?: return null
        val entry = archivedUseHistory(episode, comments) ?: return null
        return scope.launch(Dispatchers.IO) { repository.used(entry) }
    }
    fun online(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    fun retryCurrent() {
        playbackSources?.clearMissing()
        if (this::repository.isInitialized) repository.clearMissing()
        Session.commentBinding.value = CommentBinding.AUTO
        Session.manualCommentKey.value = ""
        retry.update { it + 1 }
    }
    fun setAuto(value: Boolean) {
        settings?.putBoolean("auto", value)
        auto.value = value
        if (!value) WorkManager.getInstance(appContext).cancelAllWorkByTag("playback")
    }
    fun setCommentAmount(value: Int) {
        val amount = value.coerceIn(1, 20)
        settings?.putInt("commentAmount", amount)
        Session.commentAmount.value = amount
        commentAmount.value = amount
    }
    fun refreshNiconicoLogin() {
        niconicoLinked.value = NiconicoLogin.isLinked()
        NiconicoLogin.publishProfile()
        if (niconicoLinked.value) completeInitialLogin()
    }
    fun completeInitialLogin() {
        if (!niconicoLinked.value) return
        settings?.putBoolean("setupCompleted", true)
        setupCompleted.value = true
    }
    fun setStorageLimitMb(value: Long) {
        require(value in 0..(Long.MAX_VALUE / 1048576))
        settings?.putLong("storageLimitMb", value)
        storageLimitMb.value = value
    }
    fun setCommentSize(value: Float) {
        Session.size.value = kotlin.math.round(value).coerceIn(5f, 25f)
        settings?.putFloat("commentSize", Session.size.value)
    }
    fun unlinkNiconico() {
        NiconicoLogin.unlink()
        niconicoLinked.value = false
    }
    fun setWatchDownloads(value: Boolean) {
        settings?.putBoolean("watchDownloads", value)
        watchDownloads.value = value
        if (!value) WorkManager.getInstance(appContext).cancelAllWorkByTag("notification")
    }
    fun setWifiOnly(value: Boolean) {
        settings?.putBoolean("wifiOnly", value)
        wifiOnly.value = value
    }
    fun setBatchLimit(value: Int) { batchLimit.value = value.coerceIn(1, 50); settings?.putInt("batchLimit", batchLimit.value) }
    fun setLanguage(value: String) {
        require(SupportedLanguages.any { it.first == value })
        language.value = value
        settings?.putString("language", value)
    }
    fun setThemeMode(value: String) { themeMode.value = value.takeIf { it in setOf("system", "light", "dark") } ?: "system"; settings?.putString("themeMode", themeMode.value) }
    fun setActiveService(service: StreamingService) {
        if (convenience != null) convenience!!.selectService(service) else settings?.putString("activeService", service.name)
        activeService.value = service
        Session.playback.value = Playback()
        status.value = "${service.label}の作品情報を待っています"
    }
    fun beginManualPlayback(series: String, episode: Int, positionSeconds: Long, durationSeconds: Long, playing: Boolean) {
        val safeEpisode = episode.coerceIn(1, 9999)
        val duration = (durationSeconds.coerceAtLeast(0) * 1000)
        val position = (positionSeconds.coerceAtLeast(0) * 1000).let { if (duration > 0) it.coerceAtMost(duration) else it }
        val title = "$series 第${safeEpisode}話"
        Session.manualPlayback.value = Playback(
            connected = true, title = title, subtitle = "手動再生", searchTitle = series,
            mediaId = "manual:${Episode(series, safeEpisode).key}", position = position,
            updatedAt = SystemClock.elapsedRealtime(), speed = if (playing) 1f else 0f,
            playing = playing, duration = duration
        )
        Session.adPause.value = false
        Session.commentSyncReports.value = emptyList()
        Session.commentsFromArchive.value = false
        Session.comments.value = emptyList()
        Session.matchedKey = ""
        Session.commentBinding.value = CommentBinding.MANUAL
        Session.manualCommentKey.value = Session.manualPlayback.value!!.key
        status.value = "手動再生モード。コメントを選択してください"
    }
    fun updateManualPlayback(positionSeconds: Long, durationSeconds: Long, playing: Boolean) {
        val old = Session.manualPlayback.value ?: return
        val duration = durationSeconds.coerceAtLeast(0) * 1000
        val position = (positionSeconds.coerceAtLeast(0) * 1000).let { if (duration > 0) it.coerceAtMost(duration) else it }
        Session.manualPlayback.value = old.copy(position = position, duration = duration, playing = playing, speed = if (playing) 1f else 0f, updatedAt = SystemClock.elapsedRealtime())
    }
    fun endManualPlayback() {
        Session.manualPlayback.value = null
        Session.commentSyncReports.value = emptyList()
        Session.commentsFromArchive.value = false
        Session.comments.value = emptyList()
        Session.matchedKey = ""
        Session.manualCommentKey.value = ""
        Session.commentBinding.value = CommentBinding.AUTO
        retry.update { it + 1 }
    }
    fun setOpacity(value: Float) { Session.opacity.value = value.coerceIn(.2f, 1f); settings?.putFloat("opacity", Session.opacity.value) }
    fun setSpeedScale(value: Float) { Session.speedScale.value = value.coerceIn(.25f, 1.5f); settings?.putFloat("speedScale", Session.speedScale.value) }
    fun setDisplayDensity(value: Float) { Session.displayDensity.value = value.coerceIn(.25f, 1f); settings?.putFloat("displayDensity", Session.displayDensity.value) }
    fun setFps(value: Int) { Session.fps.value = value.coerceIn(15, 144); settings?.putInt("fps", Session.fps.value) }
    fun setColorStyle(value: String) { Session.colorStyle.value = value; settings?.putString("colorStyle", value) }
    fun setFontStyle(value: String) { Session.fontStyle.value = value; settings?.putString("fontStyle", value) }
    fun setCommentDisplayEnabled(value: Boolean) { Session.overlayRequested.value = value; settings?.putBoolean("overlayEnabled", value) }
    fun setNgWords(value: String) { Session.ngWords.value = value.take(1000); settings?.putString("ngWords", Session.ngWords.value) }
    private var metadataJob: Job? = null
    private val metadataAttempts = mutableMapOf<String, Long>()
    suspend fun refreshLibrary() {
        library.value = withContext(Dispatchers.IO) { repository.store.list() }
        withContext(Dispatchers.Main.immediate) { refreshSavedSources() }
    }
    private fun refreshSavedSources() {
        if (metadataJob?.isActive == true) return
        val missing = library.value.filter { it.ownerName.isBlank() || it.ownerUrl.isBlank() || it.thumbnailUrl.isBlank() }.groupBy { niconicoVideoId(it.sourceId) ?: it.sourceId }
        if (missing.isEmpty()) return
        metadataJob = scope.launch {
            val permits = kotlinx.coroutines.sync.Semaphore(2)
            coroutineScope {
                missing.forEach { (id, episodes) -> launch {
                    permits.acquire()
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (metadataAttempts[id]?.let { now - it < 30 * 60 * 1000 } == true) return@launch
                        metadataAttempts[id] = now
                        val source = Comments.sourceMetadata(id)
                        for (saved in episodes) {
                            val updated = withContext(Dispatchers.IO) { repository.store.updateSource(saved.episode, saved.sourceId, source) }
                            if (updated != null) library.update { items -> items.map { if (it.episode.key == saved.episode.key && it.sourceId == saved.sourceId) updated else it } }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { }
                    finally { permits.release() }
                } }
            }
        }
    }
    fun delete(episodes: List<Episode>) {
        deleteNow(episodes)
    }
    private fun deleteNow(episodes: List<Episode>) = scope.launch {
        try {
            val deletable = unprotectedEpisodes(episodes, settings?.load())
            for (episode in deletable) {
                withContext(Dispatchers.IO) { WorkManager.getInstance(appContext).cancelUniqueWork(DownloadWorker.name(episode)).result.get() }
                repository.delete(episode)
            }
            refreshLibrary()
            Session.message.value = if (deletable.size < episodes.size) "保護された作品は残しました" else "作品のコメントを削除しました"
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "削除できませんでした" }
    }
    fun delete(episode: Episode) = delete(listOf(episode))
    suspend fun reloadPortableSettings() {
        val p = settings?.load() ?: return
        setCommentSize(p.getFloat("commentSize", 14f)); setOpacity(p.getFloat("opacity", .75f)); setSpeedScale(p.getFloat("speedScale", .7f)); setFps(p.getInt("fps", 60))
        setColorStyle(p.getString("colorStyle", "original")!!); setFontStyle(p.getString("fontStyle", "default")!!); setNgWords(p.getString("ngWords", "")!!)
        setCommentAmount(p.getInt("commentAmount", 5)); setWifiOnly(p.getBoolean("wifiOnly", true)); setBatchLimit(p.getInt("batchLimit", 12)); setLanguage(p.getString("language", "system")!!)
        setThemeMode(p.getString("themeMode", "dark")!!); setStorageLimitMb(p.getLong("storageLimitMb", 0L).coerceAtLeast(0))
    }
}
