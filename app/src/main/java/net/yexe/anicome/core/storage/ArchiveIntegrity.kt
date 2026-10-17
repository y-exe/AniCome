package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BrokenArchiveSource(val source: PlaybackSource, val id: String, val title: String, val duration: Long = 0L, val amount: Int = 1)
data class ArchiveCheck(val fileName: String, val info: SavedEpisode?, val status: String, val valid: Set<PlaybackSource> = emptySet(), val damaged: Set<PlaybackSource> = emptySet(), val originalId: String = info?.sourceId.orEmpty(), val originalTitle: String = info?.sourceTitle.orEmpty(), val broken: List<BrokenArchiveSource> = emptyList()) {
    val healthy get() = status == "healthy"
    val repairable get() = info != null && status != "unsupported" && !healthy
}
fun archiveStatusLabel(status: String) = when (status) { "healthy" -> "正常"; "missing" -> "ファイルがありません"; "partial" -> "一部の取得元に問題があります"; "unsupported" -> "自動修復に対応していない形式です"; else -> "ファイルを読み取れません" }
fun recoverySearchEpisode(info: SavedEpisode): Episode {
    val number = EpisodeMatcher.extract(info.sourceTitle).episode?.number ?: return info.episode
    if (number == info.episode.number) return info.episode
    val identity = animeWorkIdentity(info.episode.series)
    val series = if (identity.cour > 1) identity.searchTitle + if (identity.explicitSeason && identity.season > 1) " 第${identity.season}期" else "" else info.episode.series
    return Episode(series, number)
}
class ArchiveIntegrityRepository(private val store: CommentStore, private val offline: OfflineCommentRepository,
    private val metadata: suspend (String) -> Candidate = Comments::sourceMetadata,
    private val load: suspend (String, Int) -> List<Comment> = Comments::load,
    private val cached: suspend (String, Int) -> SavedComments? = { _, _ -> null }, private val changed: suspend () -> Unit = {}) {
    suspend fun recoveryBytes() = withContext(Dispatchers.IO) { store.recoveryBytes() }
    suspend fun inspect() = withContext(Dispatchers.IO) { store.inspectArchives { coroutineContext.ensureActive() } }
    suspend fun repair(check: ArchiveCheck) = withContext(Dispatchers.IO) {
        require(check.repairable)
        val info = checkNotNull(check.info)
        val latest = store.inspectEpisode(info.episode)
        if (latest.healthy) return@withContext
        val wanted = latest.damaged.ifEmpty { info.sources.split(",").mapNotNull { name -> PlaybackSource.entries.find { it.name == name } }.toSet().ifEmpty { setOf(PlaybackSource.OFFICIAL) } }
        val known = latest.broken.ifEmpty {
            val kind = if (PlaybackSource.OFFICIAL in wanted) PlaybackSource.OFFICIAL else wanted.firstOrNull()
            if (kind != null && latest.originalId.isNotBlank()) listOf(BrokenArchiveSource(kind, latest.originalId, latest.originalTitle, amount = info.amount)) else emptyList()
        }
        store.salvageArchive(latest)
        val verified = mutableSetOf<String>()
        try {
            val restored = mutableSetOf<PlaybackSource>()
            for (source in known) {
                if (niconicoVideoId(source.id) == null || source.source !in setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME, PlaybackSource.COMMENT_ONLY, PlaybackSource.VIDEO)) continue
                val amount = maxOf(info.amount, source.amount)
                val archived = cached(source.id, amount)?.takeIf { saved -> saved.info.amount >= amount && niconicoVideoId(saved.info.sourceId) == niconicoVideoId(source.id) && saved.comments.size in 1..200000 && saved.comments.all { it.at in 0..86_400_000L && it.body.isNotBlank() && it.body.length <= 16000 } && source.source.name in saved.info.sources.split(",") }
                val slot = if (archived != null) PlaybackCommentSlot(source.id, archived.info.sourceTitle, source.source, archived.comments, true, archived.durationMs, amount = archived.info.amount)
                else {
                    check(Comments.availableAmount(amount) >= amount) { "元の取得量で修復するにはニコニコへのログインが必要です" }
                    val actual = metadata(source.id)
                    require(when (source.source) { PlaybackSource.OFFICIAL -> actual.isDownloadOfficial(); PlaybackSource.DANIME -> actual.channelId == DANIME_CHANNEL; PlaybackSource.COMMENT_ONLY -> actual.isCommentOnly(); else -> actual.userId != null })
                    PlaybackCommentSlot(source.id, actual.title, source.source, load(source.id, amount), false, actual.duration * 1000L, amount = amount)
                }
                store.saveSources(info.episode, listOf(slot), confirmedIds = setOf(source.id)); restored += source.source; verified += source.id
            }
            val remaining = wanted - restored
            if (remaining.isNotEmpty()) offline.save(info.episode, searchEpisode = recoverySearchEpisode(info.copy(sourceTitle = latest.originalTitle)), selected = remaining, amount = info.amount, refresh = true, onFetched = { verified += it })
        } finally {
            store.finishRecovery(info.episode, wanted, latest.originalId, latest.originalTitle, known, verified)
            changed()
        }
        kotlin.check(store.inspectEpisode(info.episode).healthy) { "取得できない取得元が残っています。再試行するか、チャンネル指定を確認してください。" }
    }
}
data class ArchiveIntegrityState(val checking: Boolean = false, val repairing: String? = null, val checks: List<ArchiveCheck> = emptyList(), val error: String = "", val checked: Boolean = false, val recoveryBytes: Long = 0L, val failure: AcquisitionFailure? = null)
class ArchiveIntegrityViewModel(private val repository: ArchiveIntegrityRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ArchiveIntegrityState())
    val state = mutable.asStateFlow()
    fun inspect() {
        if (mutable.value.checking || mutable.value.repairing != null) return
        mutable.update { it.copy(checking = true, error = "", failure = null) }
        viewModelScope.launch {
            try { val checks = repository.inspect(); val bytes = repository.recoveryBytes(); mutable.update { it.copy(checking = false, checks = checks, checked = true, recoveryBytes = bytes) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(checking = false, error = "保存データを検査できませんでした", failure = AcquisitionFailure.STORAGE) } }
        }
    }
    fun repair(check: ArchiveCheck) {
        if (mutable.value.checking || mutable.value.repairing != null || !check.repairable) return
        mutable.update { it.copy(repairing = check.fileName, error = "", failure = null) }
        viewModelScope.launch {
            try { repository.repair(check); val checks = repository.inspect(); val bytes = repository.recoveryBytes(); mutable.update { it.copy(repairing = null, checks = checks, recoveryBytes = bytes) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val checks = try { repository.inspect() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { mutable.value.checks }
                val bytes = try { repository.recoveryBytes() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { mutable.value.recoveryBytes }
                mutable.update { it.copy(repairing = null, checks = checks, recoveryBytes = bytes, error = e.message ?: "修復できませんでした", failure = acquisitionFailure(e)) }
            }
        }
    }
}
