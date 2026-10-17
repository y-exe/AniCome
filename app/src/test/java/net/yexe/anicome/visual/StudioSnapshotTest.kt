package net.yexe.anicome

import android.content.Context
import android.content.ContextWrapper
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class StudioSnapshotTest(private val variant: String) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "{0}") fun variants() = listOf("dark", "light", "compact", "largeText", "compactLargeText", "en", "ko", "fr", "zh", "en-compactLargeText", "ko-compactLargeText", "fr-compactLargeText", "zh-compactLargeText", "es", "pt", "es-compactLargeText", "pt-compactLargeText") }
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ja", softButtons = false,
        screenWidth = if (variant.contains("compact", ignoreCase = true)) 880 else 1080,
        screenHeight = if (variant.contains("compact", ignoreCase = true)) 1760 else 2340,
        fontScale = if (variant.endsWith("Text")) 1.5f else 1f), theme = "android:Theme.Material.NoActionBar")
    private val resultOwner = object : androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {}
        }
    }
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Session.playback.value = Playback(); Session.manualPlayback.value = null; Session.overlay.value = false; Session.offset.value = 0; Session.size.value = 17f; Session.fps.value = 60
    }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun syncFocusedTools() {
        Session.commentSlots.value = listOf(PlaybackCommentSlot("so1", "本編", PlaybackSource.OFFICIAL, listOf(Comment(1000, "コメント")), sync = CommentSync.NATIVE))
        Session.commentSyncReports.value = listOf(PlaybackSource.JIKKYO to CommentSync.UNKNOWN)
        capture("sync_focused_tools") { app -> PlaybackTools(convenienceModel(app), "sync") }
        Session.commentSlots.value = emptyList(); Session.commentSyncReports.value = emptyList()
    }

    @Test fun globalTimingAdjustment() = capture("global_timing_adjustment") { app -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { TimingControls(convenienceModel(app)) } }

    @Test fun floatingSyncAttention() = capture("floating_sync_attention") { FloatingPanelContent(FloatingPanelState(label = "コメント再生中", current = 240, total = 1000, overlay = true, fromArchive = true, syncReports = listOf(PlaybackSource.JIKKYO to CommentSync.UNKNOWN)), FloatingPanelOptions(compact = true), false, {}, {}, {}, {}, tools = {}, toggleComments = {}) }

    @Test fun syncStatusReady() = capture("sync_status_ready") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { CommentSyncStatus(listOf(PlaybackSource.OFFICIAL to CommentSync.NATIVE, PlaybackSource.DANIME to CommentSync.OFFSET, PlaybackSource.JIKKYO to CommentSync.CUT)) } }
    @Test fun syncStatusUncertain() = capture("sync_status_uncertain") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { CommentSyncStatus(listOf(PlaybackSource.JIKKYO to CommentSync.START_ONLY, PlaybackSource.LIVE_ARCHIVE to CommentSync.UNKNOWN, PlaybackSource.VIDEO to CommentSync.DISABLED), unavailable = true) } }

    @Test fun panelPlacementPortrait() = capture("panel_placement_portrait") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelPlacementEditor(FloatingPanelOptions(), { _, _ -> }, {}, initialLandscape = false) } }
    @Test fun panelPlacementLandscape() = capture("panel_placement_landscape") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelPlacementEditor(FloatingPanelOptions(landscapeX = 0f, landscapeY = 0f), { _, _ -> }, {}, initialLandscape = true) } }
    @Test fun temporaryHideActive() = capture("temporary_hide_active") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { TemporaryHideControls(TemporaryCommentVisibility(25, canHideEpisode = true), {}, full = true) } }
    @Test fun temporaryHideEmpty() = capture("temporary_hide_empty") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { TemporaryHideControls(TemporaryCommentVisibility(), {}, full = true) } }

    @Test fun panelActionsEditor() = capture("panel_actions_editor") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelActionEditor(DefaultPanelActions) { _, _ -> } } }
    @Test fun panelActionsEmpty() = capture("panel_actions_empty") { Column(Modifier.padding(16.dp)) { PanelActionEditor(emptyList()) { _, _ -> } } }
    @Test fun panelActionsCustom() = capture("panel_actions_custom") { FloatingPanelContent(FloatingPanelState(label = "コメント再生中", current = 240, total = 1000, overlay = true), FloatingPanelOptions(compact = true, actions = listOf(PanelAction.SYNC, PanelAction.OPEN, PanelAction.TOGGLE)), false, {}, {}, {}, {}, tools = {}, toggleComments = {}) }
    @Test fun trafficBudget() = capture("traffic_budget") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TrafficBudgetContent(TrafficBudgetState(true, TrafficPolicy(100, "month"), 100L * 1048576, 1793458800000), false, "", {}, {})
    } }
    @Test fun trafficBudgetEditor() = capture("traffic_budget_editor") { TrafficBudgetEditor(TrafficPolicy(100, "day"), false, "", {}, { _, _ -> }) }
    @Test fun trafficBudgetReservation() = capture("traffic_budget_reservation") {
        ReservationRow(androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.ENQUEUED, setOf("series:テスト作品", "episode:13"),
            progress = androidx.work.workDataOf("reservationManaged" to true, "reservationTrafficBlocked" to true, "reservationOrder" to 1)), {}, {}, pause = {})
    }
    @Test fun automaticCache() = capture("automatic_cache") { Column(Modifier.padding(16.dp)) {
        AutoCacheContent(listOf(SavedEpisode(Episode("ご注文はうさぎですか？", 1), "so23335421", "第1羽", 1000, 2000, 2500000, sources = "OFFICIAL,DANIME,LIVE_ARCHIVE")), 48, {}, {}, {})
    } }
    @Test fun automaticCacheEmpty() = capture("automatic_cache_empty") { Column(Modifier.padding(16.dp)) { AutoCacheContent(emptyList(), 48, {}, {}, {}) } }
    @Test fun saveReview() = capture("save_review") { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val row = SaveEpisodeReview(Episode("テスト作品 第2クール", 1), Episode("テスト作品", 13), "新しい旅", Candidate("so13", "テスト作品 第13話「新しい旅」", channelId = 10, ownerName = "公式チャンネル"))
        SaveReviewContent(SaveReview(listOf(row, row.copy(episode = row.episode.copy(number = 2), searchEpisode = row.searchEpisode.copy(number = 14), candidate = null, issue = "公式動画が見つかりませんでした", selected = false)), 1_048_576L, SaveSpace(free = 100_000_000L))) { _, _ -> }
    } }
    @Test fun wideSaveReviewDialog() = capture("wide_save_review_dialog") {
        val title = "ようこそ実力至上主義の教室へ 3rd Season"
        val rows = (1..13).map { SaveEpisodeReview(Episode(title, it), Episode(title, it), "各話のタイトル", Candidate("so$it", "$title 第${it}話", 2649237)) }
        val work = AnimeInfo(6888, title, "", "", null, 2024, 13, emptyList(), "syoboi")
        SaveReviewDialog(SaveCommentState(query = title, work = work, review = SaveReview(rows, 1_048_576, SaveSpace())), {}, {}, { _, _ -> })
    }
    @Test fun saveReviewLowSpace() = capture("save_review_low_space") { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SaveReviewContent(SaveReview(listOf(SaveEpisodeReview(Episode("作品", 1), Episode("作品", 1))), 20_000_000L, SaveSpace(free = 6_000_000L))) { _, _ -> }
    } }
    @Test fun videoReplacement() = capture("video_replacement") { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val chosen = Candidate("so13", "テスト作品 第13話", channelId = 10, ownerName = "公式チャンネル")
        VideoReplacementChoices(VideoReplacementState(target = Episode("テスト作品", 13), candidates = listOf(chosen, chosen.copy(id = "so1", title = "テスト作品 第1話")), selected = chosen)) {}
    } }
    @Test fun commentHistory() = capture("comment_history") {
        CommentHistoryContent(listOf(CommentHistoryEntry(1L, 1000L, "VIDEO_SEARCH", "テスト作品", 13, "テスト作品 新しい旅", count = 3), CommentHistoryEntry(2L, 1000L, "REPLACE", "テスト作品 第2クール", 1, sourceId = "so13", sourceTitle = "テスト作品 第13話", count = 100, previousId = "so1", previousCount = 50, sources = "OFFICIAL,DANIME"), CommentHistoryEntry(3L, 1000L, "USE", "テスト作品", 13, sourceId = "so13", sourceTitle = "テスト作品 第13話", count = 100, storage = "archive")))
    }
    @Test fun commentHistoryEmpty() = capture("comment_history_empty") { CommentHistoryContent(emptyList()) }
    @Test fun archiveIntegrity() = capture("archive_integrity") {
        val info = SavedEpisode(Episode("テスト作品", 1), "so1", "テスト作品 第1話", 1000L, 100, 1000L)
        ArchiveIntegrityContent(ArchiveIntegrityState(checked = true, checks = listOf(ArchiveCheck("one.json", info, "healthy"), ArchiveCheck("two.json", info.copy(episode = Episode("テスト作品", 2)), "partial", setOf(PlaybackSource.OFFICIAL), setOf(PlaybackSource.DANIME))), recoveryBytes = 100_000L), {}, {})
    }
    @Test fun archiveIntegrityUnknown() = capture("archive_integrity_unknown") { ArchiveIntegrityContent(ArchiveIntegrityState(checked = true, checks = listOf(ArchiveCheck("unknown.json", null, "corrupt"))), {}, {}) }
    @Test fun reservationPaused() = capture("reservation_paused") {
        val item = androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.ENQUEUED, setOf("series:テスト作品 第2クール", "episode:1"), progress = androidx.work.workDataOf("reservationManaged" to true, "reservationPaused" to true, "sourceNumber" to 13))
        ReservationRow(item, {}, {}, pause = {}, resume = {})
    }
    @Test fun reservationOrder() = capture("reservation_order") {
        val rows = (13..15).mapIndexed { i, n -> androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.ENQUEUED, setOf("series:テスト作品", "episode:$n"), progress = androidx.work.workDataOf("reservationManaged" to true, "reservationOrder" to i + 1, "reservationRank" to i.toLong(), "reservationPaused" to (i == 2))) }
        ReservationOrderContent(rows) { _, _ -> }
    }
    @Test fun reservationEstimate() = capture("reservation_estimate") { ReservationRow(estimateWork(60_000L, 10_000L), {}, {}, pause = {}) }
    @Test fun reservationPhaseEstimate() = capture("reservation_phase_estimate") { ReservationRow(estimateWork(0L, 10_000L), {}, {}, pause = {}) }
    @Test fun reservationEstimateUnknown() = capture("reservation_estimate_unknown") { ReservationRow(estimateWork(0L, 0L), {}, {}, pause = {}) }
    private fun estimateWork(duration: Long, phase: Long) = androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.RUNNING,
        setOf("series:テスト作品", "episode:13"), progress = androidx.work.workDataOf("reservationManaged" to true, "label" to "コメントを取得中", "current" to 4000, "target" to 20000,
            "reservationBytes" to 5_242_880L, "reservationDurationMs" to duration, "downloadElapsedMs" to 20_000L, "phaseRemainingMs" to phase))
    @Test fun proxyEditor() = capture("proxy_editor") { Column(Modifier.padding(24.dp)) { ProxyEditorContent("socks", "proxy.example.com", "1080", "", false, {}, {}, {}) } }
    @Test fun proxyError() = capture("proxy_error") { Column(Modifier.padding(24.dp)) { ProxyEditorContent("http", "", "0", "ホスト名と1〜65535のポート番号を確認してください", false, {}, {}, {}) } }
    @Test fun proxySaving() = capture("proxy_saving") { Column(Modifier.padding(24.dp)) { ProxyEditorContent("http", "proxy.example.com", "8080", "", true, {}, {}, {}) } }
    @Test fun storageUnlimited() = capture("storage_unlimited") { Column(Modifier.padding(24.dp)) { StorageLimitEditorContent(false, "", "", {}, {}) } }
    @Test fun storageLimitError() = capture("storage_limit_error") { Column(Modifier.padding(24.dp)) { StorageLimitEditorContent(true, "0", "1以上の有効な容量を入力してください", {}, {}) } }
    @Test fun ngDuplicate() = capture("ng_duplicate") { Column(Modifier.padding(24.dp)) { NgWordInputContent("広告", true, {}) } }
    @Test fun saveInvalidEpisode() = capture("save_invalid_episode") {
        val work = AnimeInfo(700, "保存フォームの表示例", "", "", null, 2026, 12, emptyList())
        SaveCommentForm(SaveCommentState(query = work.title, work = work, episode = "13", sources = emptySet()), {}, {}, {}, {}, {}, {})
    }
    @Test fun formFooter() = capture("form_footer") { StudioFormLayout("入力項目", actions = { Button({}, Modifier.fillMaxWidth()) { AppText("保存") } }) { repeat(20) { OutlinedTextField("", {}, Modifier.fillMaxWidth(), label = { AppText("作品名") }) } } }
    @Test fun contextMenuStyle() = capture("context_menu_style") {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppText("ご注文はうさぎですか？ 第1話", style = MaterialTheme.typography.titleLarge)
            ReferenceGroup { MenuAction("不足している取得元を保存", androidx.compose.material.icons.Icons.Filled.Download) {}; MenuAction("コメントを更新", androidx.compose.material.icons.Icons.Filled.Refresh) {}; MenuAction("作品名・話数を修正", androidx.compose.material.icons.Icons.Filled.Edit) {} }
            ReferenceGroup { MenuAction("削除", androidx.compose.material.icons.Icons.Filled.Delete, destructive = true) {} }
        }
    }
    @Test fun episodeMenu() = capture("episode_menu") { app ->
        EpisodeActions(SavedEpisode(Episode("ご注文はうさぎですか？", 1), "so23335421", "ご注文はうさぎですか？ 第1羽", 1000, 0, 1024), convenienceModel(app), {}, {})
    }
    @Test fun serviceProfilesEnabled() = capture("service_profiles_enabled") {
        Column(Modifier.padding(16.dp)) { ServiceProfileContent(true, StreamingService.ABEMA, {}, {}) }
    }
    @Test fun startup() = capture("startup") { StartupScreen {} }
    @Test fun floatingCollapsedLogo() = capture("floating_collapsed_logo") { FloatingPanelContent(FloatingPanelState(), FloatingPanelOptions(), true, {}, {}, {}, {}) }
    @Test fun setupPermission() = capture("setup_permission") { SetupScreen(false, false, {}, {}, {}, currentStep = 1) }
    @Test fun setupOverlayPermission() = capture("setup_overlay_permission") { SetupScreen(true, false, {}, {}, {}, currentStep = 2) }
    @Test fun setupAppGuide() = capture("setup_app_guide") { SetupScreen(true, true, {}, {}, {}, linked = true, currentStep = 4) }
    @Test fun setupSaveGuide() = capture("setup_save_guide") { SetupScreen(true, true, {}, {}, {}, linked = true, currentStep = 6, notificationsAllowed = false) }
    @Test fun setupLogin() = capture("setup_login") { SetupScreen(true, true, {}, {}, {}, currentStep = 3) }
    @Test fun setup() = capture("setup") { SetupScreen(false, false, {}, {}, {}) }
    @Test fun ngWords() = capture("ngwords") { NgWordsEditor("ネタバレ\n広告", {}) }
    @Test fun serviceSelection() = capture("services") { ServiceSelectionContent(StreamingService.DMM_TV, {}) }
    @Test fun storage() = capture("storage") { SettingsScreen("ダウンロード") {} }
    @Test fun oss() = capture("oss") { SettingsScreen("OSSライセンス") {} }
    @Test fun theme() = capture("theme") { SettingsScreen("テーマ") {} }
    @Test fun home() = capture("home") { app -> SettingsScreen(home = true) {} }
    @Test fun settings() = capture("settings") { SettingsScreen(onClose = {}) {} }
    @Test fun library() = capture("library") { app -> LibraryScreen(app, emptyList(), Playback(), emptyList()) {} }
    @Test fun controls() = capture("controls") { SettingsScreen("コメント表示") {} }
    @Test fun network() = capture("network") { SettingsScreen("ネットワーク") {} }
    @Test fun notifications() = capture("notifications") { SettingsScreen("通知") {} }
    @Test fun security() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val store = MemoryPreferences()
        kotlinx.coroutines.runBlocking { store.updateData { androidx.datastore.preferences.core.mutablePreferencesOf(androidx.datastore.preferences.core.stringPreferencesKey("lockMode") to "pin", androidx.datastore.preferences.core.booleanPreferencesKey("lockBiometric") to true) } }
        val model = AppLockViewModel(AppLockRepository(SettingsRepository(store, scope), scope))
        try { capture("security") { CompositionLocalProvider(LocalAppLock provides model) { SettingsScreen("セキュリティ") {} } } }
        finally { model.viewModelScope.cancel(); scope.cancel() }
    }
    @Test fun updates() = capture("updates") { SettingsScreen("アップデート") {} }
    @Test fun pinLock() = captureLock("pin")
    @Test fun passwordLock() = captureLock("password")
    @Test fun patternLock() = captureLock("pattern")
    private fun captureLock(mode: String) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val store = MemoryPreferences()
        kotlinx.coroutines.runBlocking { store.updateData { androidx.datastore.preferences.core.mutablePreferencesOf(androidx.datastore.preferences.core.stringPreferencesKey("lockMode") to mode, androidx.datastore.preferences.core.booleanPreferencesKey("lockBiometric") to true) } }
        val model = AppLockViewModel(AppLockRepository(SettingsRepository(store, scope), scope))
        try { capture("lock_$mode") { androidx.compose.runtime.CompositionLocalProvider(LocalBiometricAvailable provides true) { AppAccessGate(model) {} } } }
        finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun commentSyncControls() = capture("sync_controls") { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { CommentSyncSettings(AdvancedSettings()) { _, _ -> } } }
    @Test fun floatingSync() = capture("floating_sync") { FloatingPanelContent(FloatingPanelState(label = "コメント再生中", current = 240, total = 1000, overlay = true, syncReports = listOf(PlaybackSource.JIKKYO to CommentSync.CUT, PlaybackSource.LIVE_ARCHIVE to CommentSync.UNKNOWN)), FloatingPanelOptions(), false, {}, {}, {}, {}) }
    @Test fun commentSources() = capture("sources") { SettingsScreen("取得元") {} }
    @Test fun otherSettings() = capture("other_settings") { SettingsScreen("その他") {} }
    @Test fun about() = capture("about") { SettingsScreen("アプリ詳細") {} }
    @Test fun acquisition() = capture("acquisition") { app -> Column(Modifier.padding(16.dp)) { AcquisitionSettings(app) } }
    @Test fun loginHeader() = capture("login_header") { NiconicoLoginHeader {} }
    @Test fun previewCounts() = capture("preview_counts") { Column(Modifier.padding(16.dp)) { PreviewCommentCountPicker(5000) {} } }
    @Test fun languages() = capture("languages") { LanguagePicker("ja") {} }
    @Test fun logoAnimationFinal() = capture("logo_animation_final") {
        val context = LocalContext.current
        val composition = androidx.compose.runtime.remember {
            com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(context.assets.open("logo-animation.json").bufferedReader().use { it.readText() }, "test-logo").value
        }
        Assert.assertNotNull(composition)
        com.airbnb.lottie.compose.LottieAnimation(composition, progress = { 1f }, modifier = Modifier.size(180.dp))
    }
    @Test fun customCommentPosition() = capture("custom_comment_position") { CommentPositionContent("custom", {}, .231f, {}) }
    @Test fun niconicoFallback() = capture("niconico_fallback") { Column(Modifier.padding(24.dp)) { NiconicoBadge() } }
    @Test fun floatingPlaybackSettings() = capture("floating_playback_settings") { FloatingPlaybackSettingsContent(FloatingPanelState(overlay = true, offset = 1500), 17f, 1f, .5f, {}, {}, {}, { _, _ -> }, {}) }
    @Test fun floatingCompact() = capture("floating_compact") { FloatingPanelContent(FloatingPanelState(label = "コメント再生中", title = "テスト作品 第13話", current = 240, total = 1000, overlay = true, fromArchive = true), FloatingPanelOptions(compact = true), false, {}, {}, {}, {}, tools = {}, toggleComments = {}) }
    @Test fun floatingCompactLoading() = capture("floating_compact_loading") { FloatingPanelContent(FloatingPanelState(busy = true, label = "コメントを取得中", current = 320, total = 1000, fetching = true), FloatingPanelOptions(compact = true), false, {}, {}, {}, {}, tools = {}, toggleComments = {}) }
    @Test fun floatingPanel() = capture("floating_panel") { CompositionLocalProvider(LocalContentColor provides androidx.compose.ui.graphics.Color.Black) { FloatingPanelContent(FloatingPanelState(label = "コメント再生中", title = "テスト作品 第1話", current = 240, total = 1000, overlay = true), FloatingPanelOptions(), false, {}, {}, {}, {}) } }
    @Test fun floatingLoading() = capture("floating_loading") { FloatingPanelContent(FloatingPanelState(busy = true, label = "コメントを取得中", current = 320, total = 1000, fetching = true), FloatingPanelOptions(), false, {}, {}, {}, {}) }
    @Test fun floatingConfirmation() = capture("floating_confirmation") { FloatingPanelContent(FloatingPanelState(), FloatingPanelOptions(), false, {}, {}, {}, {}, confirmingClose = true) }
    @Test fun floatingCollapsed() = capture("floating_collapsed") { FloatingPanelContent(FloatingPanelState(busy = true), FloatingPanelOptions(), true, {}, {}, {}, {}) }
    @Test fun floatingSettings() = capture("floating_settings") { SettingsScreen("フローティング表示") {} }
    @Test fun saveSelection() = capture("save_selection") {
        val work = AnimeInfo(700, "保存フォームの表示例", "", "", null, 2026, 26, emptyList())
        SaveCommentForm(SaveCommentState(query = work.title, work = work), {}, {}, {}, {}, {}, {})
    }
    @Test fun saveAllEpisodes() = capture("save_all") {
        val work = AnimeInfo(700, "保存フォームの表示例", "", "", null, 2026, 26, emptyList())
        SaveCommentForm(SaveCommentState(query = work.title, work = work, allEpisodes = true), {}, {}, {}, {}, {}, {})
    }
    @Test fun offlineEpisode() = capture("offline_episode") {
        EpisodeDownloadRow(SavedEpisode(Episode("保存フォームの表示例", 13), "so123", "保存フォームの表示例 第13話", 0, 52000, 12000000,
            amount = 20, sources = OfflineSources.joinToString(",") { it.name }), actions = {}) {}
    }
    @Test fun failedReservation() = capture("failed_reservation") {
        val work = androidx.work.WorkInfo(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), androidx.work.WorkInfo.State.FAILED,
            setOf("series:無職転生 第2クール", "episode:2"), outputData = androidx.work.workDataOf("message" to "検索できませんでした。通信を確認してください。"))
        Column(Modifier.padding(top = 16.dp)) { ReservationRow(work, {}, {}) }
    }
    @Test fun downloadingReservation() = capture("downloading_reservation") {
        val work = androidx.work.WorkInfo(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"), androidx.work.WorkInfo.State.RUNNING,
            setOf("series:まちカドまぞく 2丁目", "episode:1"), progress = androidx.work.workDataOf("label" to "コメントを取得中", "current" to 300, "target" to 1000))
        Column(Modifier.padding(top = 16.dp)) { ReservationRow(work, {}, {}) }
    }
    @Test fun channelHelp() = capture("channel_help") {
        ChannelInputDialog(SaveCommentState(channelInput = "ch2650096"), {}, {}, {})
    }
    @Test fun continuousEpisodeRow() = capture("continuous_episode") {
        Column { EpisodeDownloadRow(SavedEpisode(Episode("無職転生 第2クール", 2), "so889951", "無職転生 第13話「すれ違い」", 0, 1000, 1024)) {} }
    }
    @Test fun timing() = capture("timing") { SettingsScreen("タイミング") {} }
    @Test fun active() {
        Session.overlay.value = true
        capture("active") { app -> HomeViewingCard(app, {}, {}) }
    }
    @Test fun saved() {
        val titles = listOf("保存した作品の表示例", "長い作品名の表示例・第2期", "作品名")
        @Suppress("UNCHECKED_CAST")
        val cache = AnimeCatalog.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(null) as MutableMap<String, AnimeInfo>
        titles.forEachIndexed { index, title -> cache[EpisodeMatcher.normalize(title)] = AnimeInfo(index + 2, title, "", "", null, 2026, 12, emptyList()) }
        val library = titles.flatMapIndexed { index, title -> (1..3).map { episode -> SavedEpisode(Episode(title, episode), "so1", title, index.toLong(), 1000, 1_000_000) } }
        capture("saved") { app -> LibraryScreen(app, library, Playback(), emptyList()) {} }
    }
    @Test fun playing() {
        val title = "テスト作品：長いタイトルが複数行になっても読みやすい表示"
        @Suppress("UNCHECKED_CAST")
        val cache = AnimeCatalog.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(null) as MutableMap<String, AnimeInfo>
        cache[EpisodeMatcher.normalize(title)] = AnimeInfo(1, title, "", "", null, 2026, 12, emptyList())
        Session.playback.value = Playback(connected = true, title = title, subtitle = "第3話  いつものアニメに、みんなの声を。", position = 120_000, duration = 720_000)
        capture("playing") { app -> HomeViewingCard(app, {}, {}) }
    }
    @androidx.compose.runtime.Composable private fun convenienceModel(app: AppCoordinator, start: Boolean = false): ConvenienceViewModel {
        val context = LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val model = androidx.compose.runtime.remember(app) {
            val settings = SettingsRepository(MemoryPreferences(), scope)
            ConvenienceViewModel(ConvenienceRepository(context, settings, app, DownloadRepository(context, settings), EpisodeMetadataRepository(), scope).also { if (start) it.start() }, UnconfinedTestDispatcher())
        }
        androidx.compose.runtime.DisposableEffect(model) { onDispose { model.viewModelScope.cancel() } }
        return model
    }
    @Test fun displayExtras() = capture("display_extras") { app ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { CommentDisplayExtras(convenienceModel(app)) }
    }
    @Test fun storageExtras() = capture("storage_extras") { app -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { StorageExtras(convenienceModel(app)) } }
    @Test fun homeLayout() = capture("home_layout") { app -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { HomeLayoutSettings(convenienceModel(app)) } }
    @Test fun reorderedHomeLayout() = capture("home_layout_reordered") { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { HomeSectionOrder(listOf("adjust", "watch", "fetch")) {} } }
    @Test fun selectionSettings() = capture("selection_settings") { app -> Column(Modifier.padding(16.dp)) { CommentSelectionSettings(convenienceModel(app)) } }
    @Test fun sourceTools() {
        val c = listOf(Comment(1000, "test"))
        Session.commentSlots.value = listOf(PlaybackCommentSlot("so1", "official", PlaybackSource.OFFICIAL, c, true, 1440000, CommentSync.NATIVE), PlaybackCommentSlot("jk1", "live", PlaybackSource.JIKKYO, c, false, 1740000, CommentSync.CUT, syncSpans = listOf(CommentTimeSpan(0, 600000, 0), CommentTimeSpan(900000, 1740000, 600000))))
        capture("source_tools") { app -> PlaybackTools(convenienceModel(app)) }
        Session.commentSlots.value = emptyList()
    }
    private fun capture(name: String, content: @androidx.compose.runtime.Composable (AppCoordinator) -> Unit) {
        val application = PreviewApplication()
        val app = application.runtime.apply { language.value = variant.substringBefore("-").takeIf { it in setOf("en", "ko", "fr", "zh", "es", "pt") } ?: "ja" }
        val context = object : ContextWrapper(paparazzi.context) { override fun getApplicationContext(): Context = application }
        paparazzi.snapshot("${name}_$variant") {
            CompositionLocalProvider(LocalContext provides context, LocalNiconicoLoginPreview provides true, androidx.activity.compose.LocalActivityResultRegistryOwner provides resultOwner) {
                AniComeTheme(if (variant == "light") "light" else "dark") {
                    Scaffold() { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) { content(app) }
                    }
                }
            }
        }
    }
}
