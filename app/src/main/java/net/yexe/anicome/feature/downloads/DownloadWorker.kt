package net.yexe.anicome

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

open class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val downloads = org.koin.core.context.GlobalContext.get().get<DownloadRepository>()
        val series = inputData.getString("series") ?: return Result.failure()
        val number = inputData.getInt("number", -1)
        val origin = inputData.getString("origin") ?: "manual"
        try { if (!downloads.canRun(id, name(Episode(series, number), origin))) return Result.success() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { return Result.retry() }
        val result = performDownload()
        try { downloads.workerFinished(id, result.state, result.output) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Diagnostics.trace("保存予約の完了状態を再確認します") }
        return result.result
    }
    private suspend fun performDownload(): ReservationOutcome {
        val series = inputData.getString("series") ?: return ReservationOutcome.failure()
        val number = inputData.getInt("number", -1)
        if (number !in 0..9999 || series.isBlank()) return ReservationOutcome.failure()
        val app = applicationContext.aniComeApp
        if (!app.ready.value) return ReservationOutcome.retry()
        val notifications = org.koin.core.context.GlobalContext.get().get<CommentNotifications>()
        val notificationId = 1000 + (id.hashCode() and 0x3fffffff)
        val origin = inputData.getString("origin")
        if ((origin == "notification" && !app.watchDownloads.value) || (origin == "playback" && !app.auto.value)) return ReservationOutcome.success()
        if (origin == "prefetch" && skipPrefetch(origin, series, org.koin.core.context.GlobalContext.get().get<SettingsRepository>().load())) return ReservationOutcome.success()
        if (!app.setupCompleted.value) return ReservationOutcome.retry()
        return try {
            val started = android.os.SystemClock.elapsedRealtime()
            val amount = inputData.getInt("amount", 0).takeIf { it >= 1 } ?: app.commentAmount.value
            val maximum = inputData.getInt("maxComments", app.effectiveCommentLimit).coerceAtLeast(0)
            val estimator = DownloadProgressEstimator()
            val downloadedNew = java.util.concurrent.atomic.AtomicBoolean(false)
            var lastProgress = 0L
            val catalogId = inputData.getInt("catalogId", 0)
            val provider = inputData.getString("catalogProvider")
            val catalog = if (catalogId > 0 && provider in setOf("jikan", "kitsu", "anilist", "bangumi-data")) AnimeInfo(catalogId, series, "", "", null,
                inputData.getInt("catalogYear", 0).takeIf { it > 0 }, inputData.getInt("catalogEpisodes", 0).takeIf { it > 0 }, emptyList(), provider!!, inputData.getString("catalogChannel")?.takeIf(String::isNotBlank)) else null
            setProgress(workDataOf("label" to "各話のタイトルを取得中", "downloadElapsedMs" to 0L))
            var channel = inputData.getLong("channelId", 0L).takeIf { it > 0 }
            val reviewedId = inputData.getString("reviewVideo")?.takeIf { niconicoVideoId(it) != null }
            val metadata = if (inputData.getBoolean("reviewed", false)) ResolvedEpisodeMetadata(inputData.getString("reviewSubtitle").orEmpty(), Episode(inputData.getString("reviewSeries") ?: series, inputData.getInt("reviewNumber", number)))
                else if (channel == null && number > 0) org.koin.core.context.GlobalContext.get().get<EpisodeMetadataRepository>().resolve(
                catalog ?: AnimeInfo(0, series, "", "", null, null, null, emptyList(), "syoboi"), number) else null
            val subtitle = metadata?.title.orEmpty()
            val displayEpisode = Episode(series, metadata?.episode?.number ?: number)
            val selected = inputData.getStringArray("sources")?.mapNotNull { runCatching { PlaybackSource.valueOf(it) }.getOrNull() }?.toSet() ?: OfflineSources
            val koin = org.koin.core.context.GlobalContext.get()
            val archive = if (reviewedId != null) koin.get<CommentRepository>().cached(Episode(series, number), amount, maximum) else null
            val reuseReview = canReuseReviewedArchive(archive, reviewedId, metadata?.episode ?: Episode(series, number), subtitle)
            val candidate = if (reuseReview) null else reviewedId?.let { Comments.sourceMetadata(it) } ?: channel?.let { koin.get<ChannelCommentRepository>().episode(it, number) }
            if (reuseReview) channel = null
            val savingRepository = if (automatic(origin)) OfflineCommentRepository(koin.get<CommentRepository>().forPlayback(koin.get<AutoCommentCacheRepository>().store), koin.get()) else koin.get<OfflineCommentRepository>()
            val saved = savingRepository.save(Episode(series, number), searchEpisode = metadata?.episode ?: Episode(series, number), candidate = candidate, amount = amount, maxComments = maximum, subtitle = subtitle, channel = channel, selected = selected, onFetched = { if (it.isNotEmpty()) downloadedNew.set(true) }, progress = { label, current, target ->
                val now = android.os.SystemClock.elapsedRealtime()
                val remaining = estimator.remaining(label, current, target, now)
                if (now - lastProgress >= 500 || current == target) {
                    lastProgress = now
                    setProgressAsync(workDataOf("current" to current, "target" to target, "label" to label, "episodeTitle" to subtitle, "missingEpisodeTitle" to (channel == null && subtitle.isBlank()), "sourceNumber" to (metadata?.episode?.number ?: number), "downloadElapsedMs" to (now - started).coerceAtLeast(0), "phaseRemainingMs" to (remaining ?: 0L)))
                    notifications.download(notificationId, displayEpisode.label, current, target)
                }
            })
            app.refreshLibrary()
            notifications.downloaded(notificationId, displayEpisode.label, saved.info.count)
            EpisodeMatcher.parse(Session.playback.value.title, Session.playback.value.subtitle)?.let { if (saved.info.matchesPlaybackEpisode(it)) app.retryCurrent() }
            ReservationOutcome.success(workDataOf("message" to "保存済み：${saved.info.count}件", "sources" to saved.info.sources, "missingSources" to (selected - saved.slots.map { it.source }.toSet()).map { it.name }.toTypedArray(), "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0), "bytes" to saved.info.bytes, "amount" to amount, "downloadedNew" to downloadedNew.get()))
        } catch (e: CancellationException) { notifications.cancel(notificationId); throw e }
        catch (e: MatchUnavailable) { notifications.cancel(notificationId); ReservationOutcome.failure(acquisitionFailureOutput(e, inputData.getInt("catalogYear", 0).takeIf { it > 0 })) }
        catch (e: Exception) {
            notifications.cancel(notificationId)
            if (e is java.io.IOException && acquisitionFailure(e) in setOf(AcquisitionFailure.NETWORK, AcquisitionFailure.RATE_LIMIT) && runAttemptCount < 3) ReservationOutcome.retry()
            else ReservationOutcome.failure(acquisitionFailureOutput(e, inputData.getInt("catalogYear", 0).takeIf { it > 0 }))
        }
    }
    companion object {
        const val TAG = "comment-downloads"
        fun skipPrefetch(origin: String?, series: String, prefs: SettingsSnapshot) = origin == "prefetch" && prefs.getBoolean(prefetchWorkKey(series), false)
        fun automatic(origin: String?) = origin in setOf("playback", "notification", "prefetch")
        fun name(episode: Episode, origin: String = "manual") = (if (automatic(origin)) "cache-comments-" else "comments-") + CommentStore.fileKey(episode.key)
        fun enqueue(context: Context, episode: Episode, origin: String = "manual", catalog: AnimeInfo? = null, channel: Long? = null, wifiOverride: Boolean? = null, charging: Boolean = false, sources: Set<PlaybackSource> = OfflineSources, reviewed: SaveEpisodeReview? = null) {
            val wifi = wifiOverride ?: (context.aniComeApp.wifiOnly.value && origin != "playback")
            org.koin.core.context.GlobalContext.get().get<DownloadRepository>().submit(ReservationSpec(episode.copy(series = episode.series.take(300)), origin, catalog, channel, wifi, charging, sources, reviewed, amount = context.aniComeApp.commentAmount.value, maxComments = context.aniComeApp.effectiveCommentLimit))
        }
        fun enqueueLegacy(context: Context, spec: ReservationSpec) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(spec.input()).setConstraints(Constraints.Builder().setRequiresCharging(spec.charging).setRequiredNetworkType(if (spec.wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).apply { spec.tags().forEach(::addTag) }.build()
            WorkManager.getInstance(context).enqueueUniqueWork(spec.name, ExistingWorkPolicy.KEEP, request)
        }
    }
}
