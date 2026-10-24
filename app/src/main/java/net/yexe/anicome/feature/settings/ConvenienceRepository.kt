@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package net.yexe.anicome

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

data class ViewingHistory(val title: String = "", val subtitle: String = "", val mediaId: String = "", val position: Long = 0, val duration: Long = 0, val service: String = "", val updated: Long = 0)
data class ConvenienceOptions(val nextEpisodes: Int = 0, val prefetchWifi: Boolean = true, val prefetchCharging: Boolean = false,
    val rememberSelection: Boolean = true, val serviceProfiles: Boolean = true, val renderMode: String = "balanced",
    val homeSections: List<String> = listOf("resume", "watch", "fetch", "adjust"), val notificationActions: Set<String> = setOf("stop", "earlier", "later"), val history: ViewingHistory = ViewingHistory())
data class ConvenienceOperation(val busy: Boolean = false, val message: String = "", val error: String = "")
data class UndoRequest(val id: Long, val label: String)
fun protectedWorkKey(series: String) = "protectedWork:" + CommentStore.fileKey(series)
fun unprotectedEpisodes(episodes: List<Episode>, settings: SettingsSnapshot?) = episodes.filter { settings?.getBoolean(protectedWorkKey(it.series), false) != true }

fun prefetchWorkKey(series: String): String = animeWorkIdentity(series).let { "prefetchDisabled:" + CommentStore.fileKey("${it.base}:${it.season}:${it.cour}") }

fun missingOfflineSources(saved: SavedEpisode): Set<PlaybackSource> = OfflineSources - saved.sources.split(",").mapNotNull { name -> PlaybackSource.entries.find { it.name == name } }.toSet()

data class OfflineSourceDetail(val source: PlaybackSource, val count: Int, val sync: CommentSync)

fun presentedComments(comments: List<Comment>, slots: List<PlaybackCommentSlot>, hidden: Set<PlaybackSource>, offsets: Map<PlaybackSource, Long>, check: () -> Unit = {}): List<Comment> {
    if (comments.isEmpty() || slots.isEmpty() || (hidden.isEmpty() && offsets.values.all { it == 0L })) return comments
    return slots.filter { it.source !in hidden }.flatMap { slot ->
        val offset = offsets[slot.source] ?: 0L
        if (offset == 0L) slot.comments else slot.comments.mapIndexedNotNull { index, c -> if (index % 256 == 0) check(); (c.at + offset).takeIf { it >= 0 }?.let { c.copy(at = it) } }
    }.sortedBy { it.at }
}

class ConvenienceRepository(private val context: Context, private val settings: SettingsRepository, private val app: AppCoordinator,
    private val downloads: DownloadRepository, private val metadata: EpisodeMetadataRepository, private val scope: CoroutineScope, private val offline: OfflineCommentRepository? = null, private val visibility: CommentVisibilityRepository = CommentVisibilityRepository(scope), private val corrections: SyncAdjustmentRepository = SyncAdjustmentRepository(scope)) {
    private val prefs = MutableStateFlow(emptyPreferences())
    val options = prefs.map { p ->
        val s = SettingsSnapshot(p)
        val root = runCatching { JSONObject(s.getString("viewingHistory", "{}")!!) }.getOrDefault(JSONObject())
        ConvenienceOptions(s.getInt("nextEpisodes", 0).coerceIn(0, 5), s.getBoolean("prefetchWifi", true), s.getBoolean("prefetchCharging", false),
            s.getBoolean("rememberSelection", true), s.getBoolean("serviceProfiles", true), s.getString("renderMode", "balanced")!!,
            s.getString("homeSections", "resume,watch,fetch,adjust")!!.split(',').filter { it in setOf("resume", "watch", "fetch", "adjust") }.distinct(),
            s.getString("notificationActions", "stop,earlier,later")!!.split(',').filter { it in setOf("stop", "earlier", "later") }.toSet(),
            ViewingHistory(root.optString("title"), root.optString("subtitle"), root.optString("mediaId"), root.optLong("position"), root.optLong("duration"), root.optString("service"), root.optLong("updated")))
    }.stateIn(scope, SharingStarted.Eagerly, ConvenienceOptions())
    val revision = prefs.map { it.hashCode() }.stateIn(scope, SharingStarted.Eagerly, 0)
    val operation = MutableStateFlow(ConvenienceOperation())
    val undo = MutableStateFlow<UndoRequest?>(null)
    private val pending = linkedMapOf<Long, Job>()
    private var serial = 0L
    private var started = false
    private var profileService = ""
    private var profilesEnabled = false
    private var profileReady = false
    private var profileEpoch = Long.MIN_VALUE
    private val prefetched = mutableSetOf<String>()
    private val prefetchAttempts = mutableMapOf<String, Long>()
    fun start() {
        if (started) return
        started = true
        scope.launch { settings.data.collect { prefs.value = it; synchronizeProfile(it); Session.renderMode.value = SettingsSnapshot(it).getString("renderMode", "balanced") ?: "balanced" } }
        scope.launch { combine(Session.comments, Session.commentSlots, Session.hiddenSources, Session.sourceOffsets) { c, s, h, o -> PresentationInputs(c, s, h, o) }
            .mapLatest { input -> withContext(Dispatchers.Default) { presentedComments(input.comments, input.slots, input.hidden, input.offsets) { coroutineContext.ensureActive() } } }
            .collect { Session.presented.value = it } }
        scope.launch { Session.ngWords.collect { Session.effectiveNgWords.value = it } }
        scope.launch {
            while (isActive) {
                val playback = Session.manualPlayback.value ?: Session.playback.value
                if (playback.connected && playback.title.isNotBlank()) {
                    val position = playback.positionAt(SystemClock.elapsedRealtime()) ?: 0L
                    if (Session.manualPlayback.value == null) settings.putString("viewingHistory", JSONObject().put("title", playback.title).put("subtitle", playback.subtitle).put("mediaId", playback.mediaId)
                        .put("position", position).put("duration", playback.duration).put("service", app.activeService.value.name).put("updated", System.currentTimeMillis()).toString())
                    if (playback.duration > 0 && position * 10 >= playback.duration * 9) EpisodeMatcher.parse(playback.title, playback.subtitle)?.let { settings.putLong("watched:${CommentStore.fileKey(it.key)}", System.currentTimeMillis()) }
                    prefetch(playback)
                }
                delay(10_000)
            }
        }
        app.convenience = this
    }
    private fun synchronizeProfile(p: Preferences) {
        val snapshot = SettingsSnapshot(p)
        val enabled = snapshot.getBoolean("serviceProfiles", true)
        val service = snapshot.getString("activeService", app.activeService.value.name)!!
        val key = stringPreferencesKey("serviceProfile:$service")
        val current = CommentDisplayProfile.read(p)
        val saved = p[key]?.let(CommentDisplayProfile::parse)
        if (!profileReady && enabled && saved != null && (saved.position != current.position || saved.customY != current.customY)) {
            settings.edit { latest -> if (SettingsSnapshot(latest).getString("activeService", app.activeService.value.name) == service) saved.writePosition(latest) }
            return
        }
        profileReady = true
        val epoch = snapshot.getLong("serviceProfileEpoch", 0L)
        if (enabled && (!profilesEnabled || profileService != service || profileEpoch != epoch)) current.publishPosition()
        profileEpoch = epoch
        profilesEnabled = enabled; profileService = service
        if (enabled && saved != current) settings.edit { latest ->
            val live = SettingsSnapshot(latest)
            if (live.getBoolean("serviceProfiles", true)) latest[stringPreferencesKey("serviceProfile:${live.getString("activeService", app.activeService.value.name)}")] = CommentDisplayProfile.read(latest).json()
        }
    }
    fun selectService(service: StreamingService) {
        settings.edit { p ->
            val s = SettingsSnapshot(p)
            val old = s.getString("activeService", app.activeService.value.name)!!
            if (s.getBoolean("serviceProfiles", true) && old != service.name) {
                p[longPreferencesKey("serviceProfileEpoch")] = s.getLong("serviceProfileEpoch", 0L) + 1
                p[stringPreferencesKey("serviceProfile:$old")] = CommentDisplayProfile.read(p).json()
                (p[stringPreferencesKey("serviceProfile:${service.name}")]?.let(CommentDisplayProfile::parse) ?: defaultServiceProfile(service)).writePosition(p)
                p[stringPreferencesKey("serviceProfile:${service.name}")] = CommentDisplayProfile.read(p).json()
            }
            p[stringPreferencesKey("activeService")] = service.name
        }
    }
    fun resume(history: ViewingHistory): Boolean {
        val service = StreamingService.entries.firstOrNull { it.name == history.service } ?: return false
        val url = runCatching { Uri.parse(history.mediaId) }.getOrNull() ?: return false
        val host = url.host ?: return false
        if (url.scheme != "https" || !(host == service.domain || host.endsWith(".${service.domain}"))) return false
        return runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url).setPackage(service.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
    private suspend fun prefetch(playback: Playback) {
        val o = options.value
        if (o.nextEpisodes == 0 || !playback.playing || playback.advertising || !app.setupCompleted.value) return
        val ep = EpisodeMatcher.parse(playback.title, playback.subtitle) ?: return
        if (prefetchDisabled(ep.series)) return
        val prefetchKey = "${ep.key}:${o.nextEpisodes}:${o.prefetchWifi}:${o.prefetchCharging}"
        if (prefetchKey in prefetched || prefetchAttempts[prefetchKey]?.let { SystemClock.elapsedRealtime() - it < 300_000 } == true) return
        prefetchAttempts[prefetchKey] = SystemClock.elapsedRealtime()
        if (prefetchAttempts.size > 128) prefetchAttempts.remove(prefetchAttempts.keys.first())
        try {
            val wanted = animeWorkIdentity(ep.series)
            val remembered = SettingsSnapshot(settings.store.data.first()).getString("selectedWork:${CommentStore.fileKey(ep.series)}", "")!!
            val selected = runCatching { JSONObject(remembered).let { j -> AnimeInfo(j.getInt("id"), j.getString("title"), j.optString("image"), "", null, j.optInt("year").takeIf { it > 0 }, j.optInt("episodes").takeIf { it > 0 }, emptyList(), j.getString("provider"), j.optString("niconicoChannel").takeIf(String::isNotBlank)) } }.getOrNull()
            val works = if (selected != null) listOf(selected) else AnimeCatalog.search(ep.series).filter { animeWorkIdentity(it.title).let { it.base == wanted.base && it.season == wanted.season && it.cour == wanted.cour } }
            val work = works.singleOrNull() ?: return
            val total = work.episodes ?: return
            val local = if (wanted.cour > 1) (1..minOf(total, 100)).firstOrNull { metadata.resolve(work, it)?.episode?.number == ep.number } ?: return else ep.number
            for (n in (local + 1)..minOf(total, local + o.nextEpisodes)) {
                currentCoroutineContext().ensureActive()
                val next = Episode(work.title, n)
                if (app.repository.cached(next, app.commentAmount.value) == null) downloads.enqueuePrefetch(next, work, o.prefetchWifi, o.prefetchCharging)
            }
            prefetched.add(prefetchKey)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { prefetched.remove(prefetchKey) }
    }
    fun boolean(key: String, value: Boolean) {
        require(key in setOf("prefetchWifi", "prefetchCharging", "rememberSelection", "serviceProfiles"))
        if (key != "serviceProfiles") settings.putBoolean(key, value) else settings.edit { p ->
            val service = SettingsSnapshot(p).getString("activeService", app.activeService.value.name)!!
            p[stringPreferencesKey("activeService")] = service
            if (value) {
                p[longPreferencesKey("serviceProfileEpoch")] = SettingsSnapshot(p).getLong("serviceProfileEpoch", 0L) + 1
                val profileKey = stringPreferencesKey("serviceProfile:$service")
                (p[profileKey]?.let(CommentDisplayProfile::parse) ?: CommentDisplayProfile.read(p)).also { it.writePosition(p); p[profileKey] = it.json() }
            }
            p[booleanPreferencesKey(key)] = value
        }
    }
    fun protected(series: String) = SettingsSnapshot(prefs.value).getBoolean(protectedWorkKey(series), false)
    fun protect(series: String, value: Boolean) = settings.putBoolean(protectedWorkKey(series), value)
    fun prefetchDisabled(series: String) = SettingsSnapshot(prefs.value).getBoolean(prefetchWorkKey(series), false)
    fun disablePrefetch(series: String, value: Boolean) {
        settings.putBoolean(prefetchWorkKey(series), value)
        if (value) scope.launch { downloads.cancelPrefetch(series) }
        else { prefetched.clear(); prefetchAttempts.clear() }
    }
    fun nextEpisodes(value: Int) = settings.putInt("nextEpisodes", value.coerceIn(0, 5))
    fun renderMode(value: String) { require(value in setOf("quality", "balanced", "battery")); settings.putString("renderMode", value); Session.renderMode.value = value }
    fun homeSections(value: List<String>) = settings.putString("homeSections", value.filter { it in setOf("resume", "watch", "fetch", "adjust") }.distinct().joinToString(","))
    fun notificationAction(action: String, enabled: Boolean) { require(action in setOf("stop", "earlier", "later")); settings.putString("notificationActions", (options.value.notificationActions.let { if (enabled) it + action else it - action }).joinToString(",")) }
    val temporaryVisibility = visibility.state
    fun temporaryHide(mode: String) = visibility.hide(mode)
    fun source(source: PlaybackSource, enabled: Boolean) { Session.hiddenSources.update { if (enabled) it - source else it + source } }
    val correctionUndo = corrections.undo
    fun sourceOffset(source: PlaybackSource, delta: Long?) = corrections.source(source, delta)
    fun globalOffset(delta: Long?) = corrections.global(delta)
    fun previewGlobalOffset(value: Long) = corrections.previewGlobal(value)
    fun finishGlobalOffset() = corrections.finishGlobalPreview()
    fun undoCorrection(id: Long) = corrections.undo(id)
    fun alignMarker(candidate: SyncMarkerCandidate) = corrections.alignMarker(candidate, SystemClock.elapsedRealtime())
    fun rememberCandidate(episode: Episode, candidate: Candidate) {
        if (!options.value.rememberSelection) return
        settings.putString("selectedVideo:${CommentStore.fileKey(episode.key)}", JSONObject().put("id", candidate.id).put("title", candidate.title).put("channel", candidate.channelId ?: 0).toString())
    }
    suspend fun selectedCandidate(episode: Episode): Candidate? {
        if (!SettingsSnapshot(settings.store.data.first()).getBoolean("rememberSelection", true)) return null
        val text = SettingsSnapshot(settings.store.data.first()).getString("selectedVideo:${CommentStore.fileKey(episode.key)}", "")!!
        return runCatching { val j = JSONObject(text); Candidate(checkNotNull(niconicoVideoId(j.getString("id"))), j.getString("title"), channelId = j.optLong("channel").takeIf { it > 0 }) }.getOrNull()
    }
    fun forgetSelections() = scope.launch { settings.store.edit { p -> p.asMap().keys.filter { it.name.startsWith("selectedVideo:") }.forEach { @Suppress("UNCHECKED_CAST") p.remove(it as Preferences.Key<Any>) } } }
    fun ng(scopeName: String, text: String) {
        val p = Session.manualPlayback.value ?: Session.playback.value
        val ep = EpisodeMatcher.parse(p.title, p.subtitle)
        when (scopeName) {
            "global" -> app.setNgWords(text)
            "work" -> ep?.let { settings.putString("workNg:${CommentStore.fileKey(it.series)}", text.take(4000)) }
            "episode" -> ep?.let { settings.putString("episodeNg:${CommentStore.fileKey(it.key)}", text.take(4000)) }
        }
    }
    fun ngText(scopeName: String): String {
        val p = Session.manualPlayback.value ?: Session.playback.value
        val ep = EpisodeMatcher.parse(p.title, p.subtitle)
        return when (scopeName) {
            "work" -> ep?.let { SettingsSnapshot(prefs.value).getString("workNg:${CommentStore.fileKey(it.series)}", "") }.orEmpty()
            "episode" -> ep?.let { SettingsSnapshot(prefs.value).getString("episodeNg:${CommentStore.fileKey(it.key)}", "") }.orEmpty()
            else -> Session.ngWords.value
        }
    }
    fun episodeLabel(item: SavedEpisode) = SettingsSnapshot(prefs.value).getInt("episodeLabel:${CommentStore.fileKey(item.episode.key)}", item.videoEpisodeNumber())
    fun libraryTitle(item: SavedEpisode): String {
        val p = SettingsSnapshot(prefs.value)
        return p.getString("libraryTitle:${CommentStore.fileKey(item.libraryGroupKey(app.library.value))}", null)
            ?: p.getString("seriesLabel:${CommentStore.fileKey(item.episode.series)}", null) ?: item.libraryTitle()
    }
    fun watched(episode: Episode) = SettingsSnapshot(prefs.value).getLong("watched:${CommentStore.fileKey(episode.key)}", 0) > 0
    fun markWatched(episodes: List<Episode>, value: Boolean) { episodes.forEach { settings.putLong("watched:${CommentStore.fileKey(it.key)}", if (value) System.currentTimeMillis() else 0) } }
    private fun profile() = JSONObject().put("size", Session.size.value).put("speed", Session.speedScale.value).put("opacity", Session.opacity.value)
        .put("fps", Session.fps.value).put("font", Session.fontStyle.value).put("color", Session.colorStyle.value).put("position", Session.overlayPosition.value)
        .put("amount", app.commentAmount.value).put("countMode", app.commentCountMode.value).put("countLimit", app.commentCountLimit.value).put("renderMode", options.value.renderMode).toString()
    fun savePreset() = settings.putString("customPreset", profile())
    fun saveServiceProfile() = settings.putString("serviceProfile:${app.activeService.value.name}", profile())
    fun preset(name: String) {
        if (name == "custom") { applyProfile(SettingsSnapshot(prefs.value).getString("customPreset", "")!!); return }
        app.setCommentSize(if (name == "quiet") 14f else 17f)
        app.setOpacity(if (name == "quiet") .6f else 1f)
        app.setSpeedScale(if (name == "lively") 1f else .5f)
        renderMode(if (name == "art") "quality" else "balanced")
        app.setFontStyle("default"); app.setColorStyle("original")
    }
    private fun applyProfile(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        app.setCommentSize(j.optDouble("size", 17.0).toFloat()); app.setSpeedScale(j.optDouble("speed", .5).toFloat()); app.setOpacity(j.optDouble("opacity", 1.0).toFloat())
        app.setFps(j.optInt("fps", 60)); app.setFontStyle(j.optString("font", "default")); app.setColorStyle(j.optString("color", "original"))
        if (j.has("amount")) app.setCommentAmount(j.optInt("amount", 1))
        if (j.has("countMode")) app.setCommentCountMode(j.optBoolean("countMode"))
        if (j.has("countLimit")) app.setCommentCountLimit(j.optInt("countLimit", 5000))
        if (j.has("renderMode")) renderMode(j.optString("renderMode", "balanced").takeIf { it in setOf("quality", "balanced", "battery") } ?: "balanced")
        settings.putString("overlayPosition", j.optString("position", "center").takeIf { it in setOf("top", "center", "bottom", "custom") } ?: "center")
    }
    fun defer(label: String, action: suspend () -> Unit) {
        val id = ++serial
        undo.value = UndoRequest(id, label)
        pending[id] = scope.launch { delay(8000); try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "操作を完了できませんでした" } finally { pending.remove(id); if (undo.value?.id == id) undo.value = null } }
    }
    fun undo(id: Long) { pending.remove(id)?.cancel(); if (undo.value?.id == id) undo.value = null; Session.message.value = "操作を取り消しました" }
    fun retryFailed(works: List<WorkInfo>) { works.filter { it.state == WorkInfo.State.FAILED }.forEach(downloads::retry) }
    fun completeOffline(episodes: List<SavedEpisode>) { episodes.forEach { item ->
        val selected = runCatching { JSONObject(SettingsSnapshot(prefs.value).getString("selectedWork:${CommentStore.fileKey(item.episode.series)}", "")!!).let { j -> AnimeInfo(j.getInt("id"), j.getString("title"), j.optString("image"), "", null, j.optInt("year").takeIf { it > 0 }, j.optInt("episodes").takeIf { it > 0 }, emptyList(), j.getString("provider"), j.optString("niconicoChannel").takeIf(String::isNotBlank)) } }.getOrNull()
        val missing = missingOfflineSources(item)
        if (missing.isNotEmpty()) downloads.enqueue(item.episode, selected, channel = SettingsSnapshot(prefs.value).getLong("workChannel:${CommentStore.fileKey(item.episode.series)}", 0L).takeIf { it > 0L }, sources = missing)
    }; Session.message.value = if (episodes.any { missingOfflineSources(it).isNotEmpty() }) "不足している取得元を保存予約しました" else "すべての取得元が保存済みです" }
    suspend fun offlineDetails(episode: Episode): List<OfflineSourceDetail> = withContext(Dispatchers.IO) {
        val saved = app.repository.store.read(episode) ?: return@withContext emptyList()
        val slots = saved.slots.ifEmpty { listOf(PlaybackCommentSlot(saved.info.sourceId, saved.info.sourceTitle, PlaybackSource.OFFICIAL, saved.comments, true, saved.durationMs)) }
        val duration = slots.firstOrNull { it.source == PlaybackSource.OFFICIAL && it.durationMs > 0 }?.durationMs
            ?: slots.firstOrNull { it.source in setOf(PlaybackSource.DANIME, PlaybackSource.CHAPTER) && it.durationMs > 0 }?.durationMs ?: 0L
        val aligned = synchronizeCommentSlots(slots, duration, settings.load().getBoolean("autoCommentSync", true), false)
        OfflineSources.map { source -> OfflineSourceDetail(source, slots.filter { it.source == source }.sumOf { it.comments.size }, aligned.firstOrNull { it.source == source }?.sync ?: CommentSync.UNKNOWN) }
    }
    fun refresh(episodes: List<SavedEpisode>) = runOperation { for (item in episodes) {
        ensureActive()
        val before = item.count
        val fresh = offline?.save(item.episode, amount = item.amount, selected = item.sources.split(",").mapNotNull { name -> PlaybackSource.entries.find { it.name == name } }.toSet(), refresh = true) ?: app.repository.refresh(item)
        val slots = Session.commentSlots.value
        if (slots.any { it.source == PlaybackSource.OFFICIAL && niconicoVideoId(it.id) == niconicoVideoId(item.sourceId) }) {
            val updated = slots.map { slot -> if (slot.source == PlaybackSource.OFFICIAL && niconicoVideoId(slot.id) == niconicoVideoId(item.sourceId)) slot.copy(comments = fresh.comments, fromArchive = true) else slot }
            Session.commentSlots.value = updated
            Session.comments.value = updated.flatMap { it.comments }.sortedBy { it.at }
        }
        operation.value = ConvenienceOperation(busy = true, message = "${item.episode.label} · $before → ${fresh.info.count}")
    }; app.refreshLibrary(); if (episodes.any { Session.matchedKey == Session.playback.value.key && EpisodeMatcher.parse(Session.playback.value.title, Session.playback.value.subtitle)?.let(it::matchesPlaybackEpisode) == true }) app.retryCurrent(); "コメントを更新しました" }
    fun correct(item: SavedEpisode, series: String, number: Int) = runOperation {
        require(series.isNotBlank() && series.length <= 300 && number in 0..9999)
        settings.store.edit {
            it[stringPreferencesKey("libraryTitle:${CommentStore.fileKey(item.libraryGroupKey(app.library.value))}")] = series.trim()
            it[stringPreferencesKey("seriesLabel:${CommentStore.fileKey(item.episode.series)}")] = series.trim()
            it[intPreferencesKey("episodeLabel:${CommentStore.fileKey(item.episode.key)}")] = number
        }
        "表示名・話数を更新しました"
    }
    fun resetLabels(item: SavedEpisode) = runOperation {
        settings.store.edit {
            it.remove(stringPreferencesKey("libraryTitle:${CommentStore.fileKey(item.libraryGroupKey(app.library.value))}"))
            it.remove(stringPreferencesKey("libraryTitle:${CommentStore.fileKey(item.episode.series)}"))
            it.remove(stringPreferencesKey("seriesLabel:${CommentStore.fileKey(item.episode.series)}"))
            it.remove(intPreferencesKey("episodeLabel:${CommentStore.fileKey(item.episode.key)}"))
        }
        "元の表示に戻しました"
    }
    private fun runOperation(block: suspend CoroutineScope.() -> String) {
        if (operation.value.busy) return
        operation.value = ConvenienceOperation(true)
        scope.launch { try { operation.value = ConvenienceOperation(message = block()) } catch (e: CancellationException) { operation.value = ConvenienceOperation(); throw e } catch (e: Exception) { operation.value = ConvenienceOperation(error = e.message ?: "操作に失敗しました") } }
    }
    fun clearOperation() { if (!operation.value.busy) operation.value = ConvenienceOperation() }
    fun export(uri: Uri, includeComments: Boolean, episodes: List<SavedEpisode>? = null) = runOperation {
        withContext(Dispatchers.IO) {
            val p = settings.store.data.first()
            val data = JSONObject()
            p.asMap().forEach { (key, value) -> if (portableKey(key.name)) data.put(key.name, JSONObject().put("type", when (value) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is String -> "string"; else -> "skip" }).put("value", value)) }
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { output -> ZipOutputStream(output).use { zip ->
                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                entry("manifest.json", JSONObject().put("format", "AniCome").put("version", 1).put("settings", if (episodes == null) data else JSONObject()).toString())
                if (includeComments) for (saved in episodes ?: app.library.value) app.repository.store.exportJson(saved.episode)?.let { entry("comments/${CommentStore.fileKey(saved.episode.key)}.json", it) }
            } }
        }; "バックアップを保存しました"
    }
    fun import(uri: Uri) = runOperation {
        withContext(Dispatchers.IO) {
            val temporary = java.io.File(context.cacheDir, "restore-${java.util.UUID.randomUUID()}")
            check(temporary.mkdir())
            try {
            val archive = checkNotNull(context.contentResolver.openInputStream(uri)).use { input -> readBackupArchive(input, temporary) { coroutineContext.ensureActive() } }
            val data = archive.settings
            data.keys().forEach { key -> if (portableKey(key)) validatePortableSetting(key, data.getJSONObject(key)) }
            archive.comments.forEach { ensureActive(); app.repository.store.validateImport(it.readText(Charsets.UTF_8)) }
            for (file in archive.comments) { ensureActive(); app.repository.store.importJson(file.readText(Charsets.UTF_8)) }
            settings.store.edit { p -> data.keys().forEach { key -> if (portableKey(key)) {
                val item = data.getJSONObject(key)
                when (item.optString("type")) {
                    "boolean" -> p[booleanPreferencesKey(key)] = item.getBoolean("value")
                    "int" -> p[intPreferencesKey(key)] = item.getInt("value")
                    "long" -> p[longPreferencesKey(key)] = item.getLong("value")
                    "float" -> p[floatPreferencesKey(key)] = item.getDouble("value").toFloat()
                    "string" -> p[stringPreferencesKey(key)] = item.getString("value").take(16000)
                }
            } } }
            } finally { temporary.listFiles().orEmpty().forEach { it.delete() }; temporary.delete() }

        }
        app.reloadPortableSettings(); app.refreshLibrary(); "バックアップを読み込みました。既存のコメントは保持しました"
    }
}
internal fun portableKey(key: String) = key in setOf("overlayEnabled", "commentSize", "opacity", "speedScale", "fps", "colorStyle", "fontStyle", "ngWords", "commentAmount", "commentCountMode", "commentCountLimit", "wifiOnly", "batchLimit", "language", "themeMode", "storageLimitMb", "autoCacheHours", "autoCacheLimitMb", "trafficLimitMb", "trafficPeriod", "overlayPosition", "overlayCustomY", "reducedMotion", "autoCommentSync", "syncOnlyConfirmed", "nextEpisodes", "prefetchWifi", "prefetchCharging", "rememberSelection", "serviceProfiles", "renderMode", "homeSections", "notificationActions", "customPreset", "saveCommentSources", "panelCollapsed", "panelCompact", "panelSnap", "panelActions", "panelTitle", "panelCount", "panelTiming", "panelOpacity", "panelX", "panelY", "panelLandscapeX", "panelLandscapeY", "panelLandscapeCollapsed", "notificationProgress", "notificationCount", "notificationPlayback", "notificationCompletion") || key.startsWith("protectedWork:") || key.startsWith("workChannel:") || key.startsWith("prefetchDisabled:") || key.startsWith("episodeLabel:") || key.startsWith("libraryTitle:") || key.startsWith("seriesLabel:") || key.startsWith("selectedWork:") || key.startsWith("selectedVideo:") || key.startsWith("serviceProfile:") || key.startsWith("workNg:") || key.startsWith("episodeNg:") || key.startsWith("watched:") || PlaybackSource.entries.any { it.settingKey == key }

internal fun validatePortableSetting(key: String, item: JSONObject) {
    require(portableKey(key) && key.length <= 400)
    val expected = when {
        key in setOf("commentSize", "opacity", "speedScale", "overlayCustomY", "panelOpacity", "panelX", "panelY", "panelLandscapeX", "panelLandscapeY") -> "float"
        key in setOf("fps", "commentAmount", "commentCountLimit", "batchLimit", "nextEpisodes") || key.startsWith("episodeLabel:") -> "int"
        key in setOf("storageLimitMb", "autoCacheHours", "autoCacheLimitMb", "trafficLimitMb") || key.startsWith("workChannel:") || key.startsWith("watched:") -> "long"
        key in setOf("colorStyle", "fontStyle", "ngWords", "language", "themeMode", "trafficPeriod", "panelActions", "overlayPosition", "renderMode", "homeSections", "notificationActions", "customPreset", "saveCommentSources") || listOf("selectedWork:", "selectedVideo:", "serviceProfile:", "workNg:", "episodeNg:", "libraryTitle:", "seriesLabel:").any(key::startsWith) -> "string"
        else -> "boolean"
    }
    require(item.getString("type") == expected) { "バックアップの設定形式を確認してください" }
    when (expected) {
        "float" -> require(item.getDouble("value").isFinite())
        "int" -> require(item.getLong("value") in 0..9999)
        "long" -> require(item.getLong("value") in 0..Long.MAX_VALUE / 1048576)
        "boolean" -> item.getBoolean("value")
        "string" -> require(item.getString("value").length <= 16000)
    }
    if (key == "overlayCustomY") require(item.getDouble("value") in 0.0..1.0)
    if (key == "saveCommentSources") require(item.getString("value").split(',').filter(String::isNotBlank).all { name -> OfflineSources.any { it.name == name } })
    if (key == "panelActions") {
        val raw = item.getString("value")
        val keys = if (raw.isEmpty()) emptyList() else raw.split(',')
        require(keys.size <= 4 && keys.distinct().size == keys.size && keys.all { name -> PanelAction.entries.any { it.key == name } })
    }
    if (key.startsWith("libraryTitle:") || key.startsWith("seriesLabel:")) require(item.getString("value").isNotBlank() && item.getString("value").length <= 300)
    val allowed = when (key) {
        "language" -> setOf("system", "ja", "en", "ko", "fr", "zh", "es", "pt")
        "trafficPeriod" -> setOf("day", "month")
        "themeMode" -> setOf("system", "dark", "light")
        "overlayPosition" -> setOf("top", "center", "bottom", "custom")
        "renderMode" -> setOf("quality", "balanced", "battery")
        "fontStyle" -> setOf("default", "gothic", "mincho")
        "colorStyle" -> setOf("original", "white", "cyan", "yellow")
        else -> null
    }
    require(allowed == null || item.getString("value") in allowed)
}

private data class PresentationInputs(val comments: List<Comment>, val slots: List<PlaybackCommentSlot>, val hidden: Set<PlaybackSource>, val offsets: Map<PlaybackSource, Long>)

class ConvenienceViewModel(private val repository: ConvenienceRepository, private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private val mutableOfflineDetails = MutableStateFlow<List<OfflineSourceDetail>>(emptyList())
    val offlineDetails = mutableOfflineDetails.asStateFlow()
    private var offlineDetailsJob: Job? = null
    fun loadOfflineDetails(episode: Episode) { offlineDetailsJob?.cancel(); mutableOfflineDetails.value = emptyList(); offlineDetailsJob = viewModelScope.launch { val details = repository.offlineDetails(episode); ensureActive(); mutableOfflineDetails.value = details } }
    fun completeOffline(episodes: List<SavedEpisode>) = repository.completeOffline(episodes)
    fun protected(series: String) = repository.protected(series)
    fun protect(series: String, value: Boolean) = repository.protect(series, value)
    fun prefetchDisabled(series: String) = repository.prefetchDisabled(series)
    fun disablePrefetch(series: String, value: Boolean) = repository.disablePrefetch(series, value)
    val correctionUndo = repository.correctionUndo
    fun globalOffset(delta: Long?) = repository.globalOffset(delta)
    fun previewGlobalOffset(value: Long) = repository.previewGlobalOffset(value)
    fun finishGlobalOffset() = repository.finishGlobalOffset()
    fun undoCorrection(id: Long) = repository.undoCorrection(id)
    val temporaryVisibility = repository.temporaryVisibility
    val options = repository.options
    val revision = repository.revision
    val operation = repository.operation
    val undo = repository.undo
    private val markerContext = combine(Session.playback, Session.manualPlayback, Session.commentBinding, Session.manualCommentKey) { _, _, _, _ -> currentSyncMarkerContext() }.distinctUntilChanged()
    val markers = combine(Session.commentSlots, markerContext) { slots, _ -> slots to currentSyncMarkerContext() }
        .mapLatest { (slots, context) -> withContext(analysisDispatcher) { analyzeSyncMarkers(slots, context) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    fun boolean(key: String, value: Boolean) = repository.boolean(key, value)
    fun next(value: Int) = repository.nextEpisodes(value)
    fun preset(name: String) = repository.preset(name)
    fun hide(mode: String) = repository.temporaryHide(mode)
    fun source(source: PlaybackSource, enabled: Boolean) = repository.source(source, enabled)
    fun offset(source: PlaybackSource, delta: Long?) = repository.sourceOffset(source, delta)
    fun renderMode(value: String) = repository.renderMode(value)
    fun homeSections(value: List<String>) = repository.homeSections(value)
    fun notificationAction(value: String, enabled: Boolean) = repository.notificationAction(value, enabled)
    fun savePreset() = repository.savePreset()
    fun saveServiceProfile() = repository.saveServiceProfile()
    fun forgetSelections() = repository.forgetSelections()
    fun alignMarker(candidate: SyncMarkerCandidate) = repository.alignMarker(candidate)
    fun ng(scope: String, value: String) = repository.ng(scope, value)
    fun ngText(scope: String) = repository.ngText(scope)
    fun episodeLabel(item: SavedEpisode) = repository.episodeLabel(item)
    fun libraryTitle(item: SavedEpisode) = repository.libraryTitle(item)
    fun resetLabels(item: SavedEpisode) = repository.resetLabels(item)
    fun watched(episode: Episode) = repository.watched(episode)
    fun markWatched(episodes: List<Episode>, value: Boolean) = repository.markWatched(episodes, value)
    fun refresh(episodes: List<SavedEpisode>) = repository.refresh(episodes)
    fun correct(item: SavedEpisode, series: String, number: Int) = repository.correct(item, series, number)
    fun export(uri: Uri, include: Boolean, episodes: List<SavedEpisode>? = null) = repository.export(uri, include, episodes)
    fun import(uri: Uri) = repository.import(uri)
    fun resume(history: ViewingHistory) = repository.resume(history)
    fun undo(id: Long) = repository.undo(id)
}
