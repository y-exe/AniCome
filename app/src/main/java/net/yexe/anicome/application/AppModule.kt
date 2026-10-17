package net.yexe.anicome

import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import java.io.File

val appModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single { SettingsRepository.create(androidContext(), get()) }
    single { AdvancedSettingsRepository(get(), get(), get()) }
    single { AppLockRepository(get(), get()) }
    single { CommentVisibilityRepository(get()) }
    single { BandaiTitleRepository() }
    single { PrimePlaybackRepository { android.os.SystemClock.elapsedRealtime() } }
    single { DanimePlaybackRepository { android.os.SystemClock.elapsedRealtime() } }
    single { NetflixEpisodeRepository(get(), Comments::search, archives = {
        withContext(Dispatchers.IO) {
            (get<CommentStore>().list() + get<AutoCommentCacheRepository>().store.ownList()).distinctBy { it.sourceId }
                .filter { "OFFICIAL" in it.sources.split(',') }
                .map { Candidate(it.sourceId, it.sourceTitle) }
        }
    }) }
    viewModel { BandaiTitleViewModel(get()) }
    viewModel { ServiceSelectionViewModel(get(), get()) }
    single { SyncAdjustmentRepository(get()) }
    single { TrafficBudgetRepository(get(), RoomTrafficIndex(get<LibraryDatabase>().traffic()), get()) }
    single { ApiClient.create(get(), get(), get()) }
    single { NiconicoProfileRepository(get(), get()) }
    single<DownloadQueue> { AndroidDownloadQueue(androidContext()) }
    single { ReservationScheduler(RoomReservationIndex(get<LibraryDatabase>().reservations()), get(), get(), estimateBytes = { spec ->
        estimatedCommentBytes(get<CommentStore>().ownList(), spec.amount.takeIf { it > 0 } ?: get<SettingsRepository>().load().getInt("commentAmount", 5), spec.sources.size)
    }, transferAllowance = { get<TrafficBudgetRepository>().allowance() }, transferUpdates = get<TrafficBudgetRepository>().state
        .map { Triple(it.loaded, it.reached, it.policy to it.resetsAt) }.distinctUntilChanged().map { Unit }) }
    single { DownloadRepository(androidContext(), get(), get(), get(), get()) }
    single { Room.databaseBuilder(androidContext(), LibraryDatabase::class.java, "library.db").addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6).build() }
    single { get<LibraryDatabase>().episodes() }
    single { CommentHistoryRepository(RoomCommentHistoryIndex(get<LibraryDatabase>().history()), get()) }
    single<SavedEpisodeIndex> { RoomEpisodeIndex(get()) }
    single { CommentStore(File(androidContext().filesDir, "comments"), capacity = { storageLimitBytes(get<SettingsRepository>().storageLimitMb) }, index = get(), onSaved = { before, after -> get<CommentHistoryRepository>().saved(before, after, "manual") }) }
    single { AutoCommentCacheRepository(androidContext(), get(), get(), get<LibraryDatabase>().cachedEpisodes(), get(), get()) }
    single { CommentRepository(get(), reuse = get<AutoCommentCacheRepository>().store, detailedLoad = { input, amount, progress -> Comments.load(input, amount, progress) }, onSearch = { query, count, episode -> get<CommentHistoryRepository>().search("VIDEO_SEARCH", query, count, episode) }) }
    single { PlaybackSourceRepository(get<CommentRepository>().forPlayback(get<AutoCommentCacheRepository>().store), get(), get(), detailedVideoLoad = { id, amount, progress -> Comments.load(id, amount, progress) }) }
    single { VideoReplacementRepository(get(), resolve = { item ->
        val text = get<SettingsRepository>().load().getString("selectedWork:${CommentStore.fileKey(item.episode.series)}", "")
        val work = runCatching { org.json.JSONObject(text!!).let { j -> AnimeInfo(j.getInt("id"), j.getString("title"), "", "", null, j.optInt("year").takeIf { it > 0 }, j.optInt("episodes").takeIf { it > 0 }, emptyList(), j.getString("provider")) } }.getOrNull()
        work?.let { get<EpisodeMetadataRepository>().resolve(it, item.episode.number) }
    }, changed = { get<AppCoordinator>().refreshLibrary(); get<AppCoordinator>().retryCurrent() }) }
    single { OfflineCommentRepository(get(), get<PlaybackSourceRepository>().forOfficial(get())) }
    single { ArchiveIntegrityRepository(get(), get(), cached = { id, amount -> get<CommentRepository>().cachedSource(id, amount) }, changed = { get<AppCoordinator>().refreshLibrary(); get<AppCoordinator>().retryCurrent() }) }
    single { ChannelCommentRepository(get()) }
    single { SaveReviewRepository(resolve = { work, number -> get<EpisodeMetadataRepository>().resolve(work, number) },
        candidates = { episode, subtitle ->
            val archived = get<CommentStore>().readForPlayback(episode)
            if (archived != null && "OFFICIAL" in archived.info.sources.split(",")) listOf(Comments.sourceMetadata(archived.info.sourceId))
            else get<CommentRepository>().searchEpisodeCandidates(episode.series, episode.number, subtitle)
        }, channelEpisode = { channel, number -> get<ChannelCommentRepository>().episode(channel, number) }, storage = {
            withContext(Dispatchers.IO) {
                val items = get<CommentStore>().ownList()
                val prefs = get<SettingsRepository>().load()
                SaveSpace(get<CommentStore>().storageSpace(), items.sumOf { it.bytes }, storageLimitBytes(prefs.getLong("storageLimitMb", 0L)),
                    items.map { it.bytes / it.amount.coerceAtLeast(1) / it.sources.split(",").size.coerceAtLeast(1) }.sorted().let { if (it.isEmpty()) 1_048_576L else it[it.size / 2] })
            }
        }) }
    single { SaveCommentRepository(get(), metadata = get(), channels = get(), settings = get(), reviews = get(), onSearch = { query, count -> get<CommentHistoryRepository>().search("WORK_SEARCH", query, count) }) }
    single { EpisodeMetadataRepository(get(), request = { url, body -> get<ApiClient>().request(url, body) }) }
    single { AppCoordinator(androidContext(), get(), get(), get(), get(), get(), get()) }
    single { ConvenienceRepository(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { CommentNotifications(androidContext(), get(), get(), get()) }
    single { FloatingPanelRepository(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { UpdateRepository(androidContext(), get(), get()) }
    single { StorageRepository(androidContext(), get()) }
    single { LicenseRepository(androidContext()) }
    single(createdAtStart = true) { PreviewCommentCache(File(androidContext().filesDir, "preview/so23335421.bin"), get(), { progress -> Comments.loadPreview(progress) }, get<AppCoordinator>().niconicoLinked) }
    single { CommentPreviewRepository(get()) { File(androidContext().filesDir, "preview/so23335421.bin").inputStream() } }
    single { SetupArtworkRepository() }
    single { ServiceArtworkRepository(get(), { url -> get<ApiClient>().requestText(url) }) }
    viewModel { ServiceArtworkViewModel(get()) }
    viewModel { CommentPreviewViewModel(get(), get()) }
    viewModel { ConvenienceViewModel(get()) }
    viewModel { AppViewModel(get()) }
    viewModel { SetupViewModel(get(), get(), get()) }
    single { NiconicoLoginRepository(get()) }
    viewModel { NiconicoLoginViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
    viewModel { AdvancedSettingsViewModel(get()) }
    viewModel { AppLockViewModel(get()) }
    viewModel { UpdatesViewModel(get()) }
    viewModel { StorageViewModel(get()) }
    viewModel { ArtworkViewModel() }
    viewModel { AnimeSearchViewModel { query -> trackedSearch(query, record = { value, count, _ -> get<CommentHistoryRepository>().search("WORK_SEARCH", value, count) }) { AnimeCatalog.search(query) } } }
    viewModel { CommentHistoryViewModel(get()) }
    viewModel { ArchiveIntegrityViewModel(get()) }
    viewModel { AutoCacheViewModel(get()) }
    viewModel { LibraryViewModel(get(), get()) }
    viewModel { ManualCommentViewModel(get(), get()) }
    viewModel { SaveCommentViewModel(get()) }
    viewModel { VideoReplacementViewModel(get()) }
    viewModel { FloatingPanelViewModel(get()) }
    viewModel { TrafficBudgetViewModel(get()) }
}
