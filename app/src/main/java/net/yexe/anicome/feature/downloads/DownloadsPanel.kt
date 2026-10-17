@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package net.yexe.anicome

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay

@Composable fun DownloadsPanel(onSettings: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    val model: LibraryViewModel = org.koin.androidx.compose.koinViewModel()
    val library by model.library.collectAsStateWithLifecycle()
    val works by model.works.collectAsStateWithLifecycle()
    val playback by Session.playback.collectAsStateWithLifecycle()
    LibraryScreen(model.controller, library, playback, works, model::enqueue, model::cancelAll, model::retry, onBack = onBack, failureSettings = onSettings, pauseReservations = model::pause, resumeReservations = model::resume, reorderReservation = model::reorder, cancelWork = model::cancel)

}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LibraryScreen(app: AppCoordinator, library: List<SavedEpisode>, playback: Playback, works: List<WorkInfo>, retryDownload: (Episode) -> Unit = {}, cancelAll: () -> Unit = {}, retryReservation: ((WorkInfo) -> Unit)? = null, onBack: () -> Unit = {}, failureSettings: (String) -> Unit = {}, pauseReservations: ((Set<java.util.UUID>) -> Unit)? = null, resumeReservations: ((Set<java.util.UUID>) -> Unit)? = null, reorderReservation: ((java.util.UUID, String) -> Unit)? = null, cancelWork: (java.util.UUID) -> Unit) {
    val context = LocalContext.current
    val convenience = LocalConvenience.current
    val revision = convenience?.revision?.collectAsStateWithLifecycle()?.value ?: 0
    fun displayTitle(item: SavedEpisode) = convenience?.libraryTitle(item) ?: item.libraryTitle()
    var selectedItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var episodeMenu by remember { mutableStateOf<SavedEpisode?>(null) }
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f
    val limitMb by app.storageLimitMb.collectAsStateWithLifecycle()
    var selectedScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryTools by remember { mutableStateOf(false) }
    var inspecting by rememberSaveable { mutableStateOf(false) }
    if (inspecting) ArchiveIntegritySheet(settings = { page -> inspecting = false; failureSettings(page) }) { inspecting = false }
    var showingHistory by rememberSaveable { mutableStateOf(false) }
    var historyManual by remember { mutableStateOf<CommentHistoryEntry?>(null) }
    var historySearch by remember { mutableStateOf<String?>(null) }
    var showingCache by rememberSaveable { mutableStateOf(false) }
    if (showingCache) AutoCacheSheet { showingCache = false }
    var failureSeries by remember { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    if (showingHistory) CommentHistorySheet(redo = { entry ->
        showingHistory = false
        if (entry.kind == "WORK_SEARCH" || (!entry.isSearch && niconicoVideoId(entry.sourceId) == null)) { historySearch = entry.query.ifBlank { entry.series }; adding = true }
        else historyManual = entry
    }) { showingHistory = false }
    historyManual?.let { entry -> ManualCommentSheet(initialQuery = if (entry.isSearch) entry.query else "", initialEpisode = entry.target, initialVideoId = if (entry.isSearch) "" else entry.sourceId) { historyManual = null } }
    var queueScreen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    fun matchesLibraryQuery(item: SavedEpisode) = listOf(displayTitle(item), item.libraryTitle(), item.episode.series, item.sourceTitle).any { it.contains(query.trim(), true) }
    var sort by rememberSaveable { mutableStateOf("recent") }
    var sorting by remember { mutableStateOf(false) }
    var deletingSelection by rememberSaveable { mutableStateOf(false) }
    var cancellingAll by remember { mutableStateOf(false) }
    var orderingQueue by remember { mutableStateOf(false) }
    if (orderingQueue && reorderReservation != null) StudioSheet({ orderingQueue = false }) { ReservationOrderContent(works, reorderReservation) }
    var seriesMenu by remember { mutableStateOf<String?>(null) }
    var deleteSeries by remember { mutableStateOf<String?>(null) }
    if (deletingSelection) StudioDialog({ deletingSelection = false }, title = { AppText("選択した作品を削除？") }, text = { AppText("${selectedItems.size}作品の保存済みコメントを削除します。") },
        confirmButton = { TextButton({ app.delete(library.filter { it.libraryGroupKey(library) in selectedItems }.map { it.episode }); deletingSelection = false; selectedItems = emptySet(); selecting = false }) { AppText("削除") } },
        dismissButton = { TextButton({ deletingSelection = false }) { AppText("キャンセル") } })
    if (cancellingAll) StudioDialog(onDismissRequest = { cancellingAll = false }, title = { AppText("保存予約をすべて取り消す") }, text = { AppText("取得中・待機中・失敗した予約を取り消します。保存済みのコメントは残ります。") },
        confirmButton = { TextButton({ cancelAll(); cancellingAll = false }) { AppText("すべて取り消す") } }, dismissButton = { TextButton({ cancellingAll = false }) { AppText("キャンセル") } })
    val bar = LocalReferenceBar.current
    LaunchedEffect(selectedScreen, queueScreen) { bar?.state?.heightOffset = 0f; if (selectedScreen == null) bar?.state?.heightOffsetLimit = 0f }
    var delete by remember { mutableStateOf<SavedEpisode?>(null) }
    val groups = remember(library, sort, revision) { library.groupBy { it.libraryGroupKey(library) }.toList().let { groups -> when (sort) { "title" -> groups.sortedBy { displayTitle(it.second.first()) }; "count" -> groups.sortedByDescending { it.second.size }; "size" -> groups.sortedByDescending { it.second.sumOf { item -> item.bytes } }; "watched" -> groups.sortedByDescending { it.second.count { item -> convenience?.watched(item.episode) == true } }; else -> groups.sortedByDescending { it.second.maxOf { item -> item.savedAt } } } } }
    val visibleKeys = groups.filter { (_, episodes) -> episodes.any(::matchesLibraryQuery) }.map { it.first }.toSet()
    LaunchedEffect(groups.map { it.first }) { selectedItems = selectedItems.intersect(groups.map { it.first }.toSet()) }
    val saveLabel = uiText("事前ダウンロード")
    val pending = works.filter { it.state != WorkInfo.State.SUCCEEDED && it.state != WorkInfo.State.CANCELLED }
    val filteredPending = pending.filter { it.tags.firstOrNull { tag -> tag.startsWith("label:") }?.removePrefix("label:").orEmpty().contains(query.trim(), true) }
    BackHandler(selecting || selectedScreen != null || queueScreen) { if (selecting) { selecting = false; selectedItems = emptySet() } else if (queueScreen) { queueScreen = false; query = "" } else selectedScreen = null }
    val push = detailMotion(false)
    val pop = detailMotion(true)
    val forward = tabMotion(true)
    val backward = tabMotion(false)
    SharedTransitionLayout {
    val shared = this
    AnimatedContent(selectedScreen to queueScreen, transitionSpec = {
        if (initialState.first != targetState.first) { if (targetState.first == null) pop else push }
        else if (targetState.second) forward else backward
    }, label = "libraryScreen") { (selected, queue) ->
    CompositionLocalProvider(LocalArtworkTransition provides ArtworkTransition(shared, this)) {
    CollapsingPage(Modifier.fillMaxSize(), enabled = selected != null) {
    Scaffold(modifier = Modifier.weight(1f), floatingActionButton = { if (!queue && !selecting) ExtendedFloatingActionButton(onClick = { adding = true }, modifier = Modifier.clearAndSetSemantics { role = Role.Button; contentDescription = saveLabel; onClick { adding = true; true } }, icon = { AppIcon(Icons.Filled.Add, null) }, text = { AppText("事前ダウンロード", style = MaterialTheme.typography.labelLarge) }) }, topBar = {
        if (selected != null) StudioHeader(title = { AppText(library.firstOrNull { it.libraryGroupKey(library) == selected }?.let(::displayTitle) ?: selected!!, localize = false, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            StudioIconButton({ selectedScreen = null }) { AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
        }) else Column(Modifier.padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioIconButton({ if (selecting) { selecting = false; selectedItems = emptySet() } else if (queue) { queueScreen = false; query = "" } else onBack() }) { AppIcon(Icons.AutoMirrored.Filled.ArrowBack, if (queue) "戻る" else "ホームに戻る") }
                AppText("事前ダウンロード", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    StudioIconButton({ libraryTools = true }) { AppIcon(Icons.Filled.MoreVert, "保存データの操作") }
                    DropdownMenu(libraryTools, { libraryTools = false }) {
                        DropdownMenuItem(text = { AppText("検索・取得履歴") }, leadingIcon = { AppIcon(Icons.Filled.History, null) }, onClick = { libraryTools = false; showingHistory = true })
                        DropdownMenuItem(text = { AppText("保存データの検査と修復") }, leadingIcon = { AppIcon(Icons.Filled.Build, null) }, onClick = { libraryTools = false; inspecting = true })
                    }
                }
            }
            StudioSearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), placeholder = if (queue) "保存予約を検索" else "作品を検索", actions = { if (!queue) StudioIconButton({ sorting = true }) { AppIcon(Icons.AutoMirrored.Filled.Sort, "並び順") } })
            if (!queue && !selecting) OutlinedButton({ showingCache = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { AppIcon(Icons.Filled.History, null); Spacer(Modifier.width(8.dp)); AppText("自動キャッシュ保存", Modifier.weight(1f)); AppIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
            if (!queue) {
                val sortChoices = listOf("recent" to "新しく保存した順", "title" to "作品名順", "count" to "保存した話数順", "size" to "容量が大きい順", "watched" to "視聴済み順")
                if (sorting) StudioDialog({ sorting = false }, title = { AppText("並び順") }, text = {
                    ReferenceGroup { sortChoices.forEach { (value, label) -> PreferenceChoice(label, sort == value, { sort = value; sorting = false }) } }
                }, confirmButton = { TextButton({ sorting = false }) { AppText("閉じる") } })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(false to "保存済み", true to "保存予約").forEach { (value, label) ->
                    StudioTonalButton(onClick = { if (queue != value) query = ""; queueScreen = value }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = queue == value; role = Role.Tab }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (queue == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer, contentColor = if (queue == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                        AppText(label, maxLines = 1)
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (queue) {
                if (pending.isNotEmpty()) item {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pausable = pending.filter { reservationManaged(it) && !reservationPaused(it) && !it.state.isFinished }.map { it.id }.toSet()
                        val paused = pending.filter(::reservationPaused).map { it.id }.toSet()
                        if (pauseReservations != null && pausable.isNotEmpty()) OutlinedButton({ pauseReservations(pausable) }) { AppIcon(Icons.Filled.Pause, null); Spacer(Modifier.width(4.dp)); AppText("すべて一時停止") }
                        if (resumeReservations != null && paused.isNotEmpty()) OutlinedButton({ resumeReservations(paused) }) { AppIcon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); AppText("停止中の予約を再開") }
                        if (reorderReservation != null && pending.count { reservationWaiting(it) } > 1) OutlinedButton({ orderingQueue = true }) { AppIcon(Icons.AutoMirrored.Filled.Sort, null); Spacer(Modifier.width(4.dp)); AppText("保存順を変更") }
                        OutlinedButton({ cancellingAll = true }) { AppIcon(Icons.Filled.Close, null); Spacer(Modifier.width(8.dp)); AppText("すべて取り消す") }
                    }
                }
                if (filteredPending.isEmpty()) item { EmptyLibrary(if (pending.isEmpty()) "保存予約はありません" else "一致する予約がありません", if (pending.isEmpty()) "予約したコメントの取得状況をここで確認できます。" else "検索する作品名を変えてみてください。") }
                items(filteredPending.mapNotNull { reservationEpisode(it.tags)?.series }.distinct(), key = { "queue:$it" }) { title ->
                    val all = works.filter { reservationEpisode(it.tags)?.series == title && it.state != WorkInfo.State.CANCELLED }
                    ReservationGroup(title, all, library, { work -> if (retryReservation != null) retryReservation(work) else reservationEpisode(work.tags)?.let(retryDownload) }, cancelWork, failureSettings, { series -> failureSeries = series; adding = true }, pauseReservations, resumeReservations)
                }
            } else if (selected == null) {
                item {
                    if (groups.isNotEmpty()) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (!selecting) OutlinedButton({ selecting = true; selectedItems = emptySet() }, Modifier.fillMaxWidth()) { AppIcon(Icons.Filled.Checklist, null); Spacer(Modifier.width(8.dp)); AppText("作品を選択") }
                        else SettingsCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                AppText("${selectedItems.size}作品を選択", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                StudioIconButton({ selecting = false; selectedItems = emptySet() }) { AppIcon(Icons.Filled.Close, "選択を終了") }
                            }
                            AppText("一覧の作品をタップして選択してください", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ selectedItems = if (visibleKeys.all { it in selectedItems }) selectedItems - visibleKeys else selectedItems + visibleKeys }) { AppText(if (visibleKeys.all { it in selectedItems }) "選択を解除" else "すべて選択") }
                                FilledTonalButton({ deletingSelection = true }, enabled = selectedItems.isNotEmpty()) { AppIcon(Icons.Filled.Delete, null); Spacer(Modifier.width(8.dp)); AppText("削除") }
                            }
                        }
                    }
                    AppText("${groups.size}作品・${library.size}話", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                }
                if (pending.isNotEmpty()) item {
                    StudioRow(modifier = Modifier.motionClickable { queueScreen = true }, headlineContent = { AppText("保存予約 ${pending.size}件") }, leadingContent = { AppIcon(Icons.Filled.Schedule, null) }, trailingContent = { AppIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
                }
                if (groups.isEmpty()) item { EmptyLibrary("保存したコメントはありません", "作品を追加すると、オフラインでもコメントを表示できます。", { adding = true }) }
                if (groups.isNotEmpty() && groups.none { it.second.any(::matchesLibraryQuery) }) item { EmptyLibrary("一致する作品がありません", "検索する作品名を変えてみてください。") }
                items(groups.filter { it.second.any(::matchesLibraryQuery) }, key = { it.first }) { (title, episodes) ->
                    SeriesListCard(episodes.first(), episodes.size, title = displayTitle(episodes.first()), longClick = { seriesMenu = title }, selecting = selecting, checked = title in selectedItems,
                        select = { selectedItems = if (title in selectedItems) selectedItems - title else selectedItems + title }) { selectedScreen = title }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    AppText("使用容量  %.1f MB".format(library.sumOf { it.bytes } / 1048576.0) + if (limitMb == 0L) " · 上限なし" else " / ${limitMb} MB", Modifier.padding(horizontal = 16.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val episodes = library.filter { it.libraryGroupKey(library) == selected }.sortedBy { convenience?.episodeLabel(it) ?: it.videoEpisodeNumber() }
                library.firstOrNull { it.libraryGroupKey(library) == selected }?.let { hero -> item { SeriesArtworkHeader(hero, displayTitle(hero)) } }
                if (episodes.isEmpty()) item { EmptyLibrary("保存した話はありません", "この作品のコメントを追加できます。", { adding = true }) }
                items(episodes, key = { it.episode.key }) { saved ->
                    EpisodeDownloadRow(saved, actions = { episodeMenu = saved }, number = convenience?.episodeLabel(saved) ?: saved.videoEpisodeNumber()) { delete = saved }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    }
    }
    }
    }
    if (adding) AddDownloadSheet(historySearch ?: failureSeries ?: library.firstOrNull { it.libraryGroupKey(library) == selectedScreen }?.episode?.series ?: EpisodeMatcher.parse(playback.title, playback.subtitle)?.series.orEmpty(), openChannelHelp = failureSeries != null) { adding = false; failureSeries = null; historySearch = null }
    seriesMenu?.let { key ->
        val episodes = library.filter { it.libraryGroupKey(library) == key }
        if (episodes.isNotEmpty()) StudioSheet(onDismissRequest = { seriesMenu = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(displayTitle(episodes.first()), localize = false, style = MaterialTheme.typography.titleLarge)
                AppText("${episodes.size}話 保存済み", style = MaterialTheme.typography.bodyMedium)
                ReferenceGroup {
                MenuAction("コメントを追加", Icons.Filled.Add) { selectedScreen = key; adding = true; seriesMenu = null }
                val url = episodes.first().ownerUrl.ifBlank { niconicoVideoUrl(episodes.first().sourceId).orEmpty() }
                if (url.isNotBlank()) MenuAction("ニコニコで開く", Icons.Filled.OpenInNew) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); seriesMenu = null }
                MenuAction("作品名をコピー", Icons.Filled.ContentCopy) { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("", displayTitle(episodes.first()))); seriesMenu = null }
                if (convenience != null) {
                    MenuAction("不足している取得元を保存", Icons.Filled.Download) { convenience.completeOffline(episodes); seriesMenu = null }
                    MenuAction("コメントを更新", Icons.Filled.Refresh) { convenience.refresh(episodes); seriesMenu = null }
                    MenuAction("視聴済みにする", Icons.Filled.DoneAll) { convenience.markWatched(episodes.map { it.episode }, true); seriesMenu = null }
                    val protected = episodes.all { convenience.protected(it.episode.series) }
                    MenuAction(if (protected) "作品の保護を解除" else "作品を保護", Icons.Filled.Lock) { episodes.map { it.episode.series }.distinct().forEach { convenience.protect(it, !protected) }; seriesMenu = null }

                }
                }
                if (convenience != null) SettingsCard { BackupControls(convenience, episodes) }
                ReferenceGroup {
                MenuAction("すべての話を削除", Icons.Filled.Delete, destructive = true) { deleteSeries = key; seriesMenu = null }
                }
            }
        }
    }
    deleteSeries?.let { key ->
        val episodes = library.filter { it.libraryGroupKey(library) == key }
        StudioDialog(onDismissRequest = { deleteSeries = null }, title = { AppText("作品のコメントを削除？") }, text = { Column { AppText(episodes.firstOrNull()?.let(::displayTitle).orEmpty(), localize = false); AppText("全${episodes.size}話のコメントを削除します。") } },
            confirmButton = { TextButton({ app.delete(episodes.map { it.episode }); deleteSeries = null; if (selectedScreen == key) selectedScreen = null }) { AppText("削除") } }, dismissButton = { TextButton({ deleteSeries = null }) { AppText("キャンセル") } })
    }
    if (episodeMenu != null && convenience != null) StudioSheet({ episodeMenu = null }) { EpisodeActions(episodeMenu!!, convenience, { delete = episodeMenu }, { episodeMenu = null }) }
    delete?.let { saved ->
        StudioDialog(onDismissRequest = { delete = null }, title = { AppText("コメントを削除？") }, text = { AppText(saved.episode.label, localize = false) }, confirmButton = { StudioTextButton({ app.delete(saved.episode); delete = null }) { AppText("削除") } }, dismissButton = { StudioTextButton({ delete = null }) { AppText("キャンセル") } })
    }
}
@Composable internal fun EpisodeDownloadRow(saved: SavedEpisode, actions: (() -> Unit)? = null, number: Int = saved.videoEpisodeNumber(), remove: () -> Unit) {
    val context = LocalContext.current
    val sourceUrl = niconicoVideoUrl(saved.sourceId)
    ListItem(
        modifier = Modifier.fillMaxWidth().motionClickable(enabled = sourceUrl != null) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sourceUrl))) },
        headlineContent = { AppText("第${number}話", style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText(saved.sourceTitle, localize = false, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            AppText("${saved.count}件 · ${saved.amount}倍", style = MaterialTheme.typography.bodySmall)
            AppText(saved.sources.split(",").mapNotNull { name -> PlaybackSource.entries.find { it.name == name }?.label?.let { uiText(it) } }.joinToString(" · "), localize = false, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        } },
        leadingContent = { AnimeArtwork(null, Modifier.width(96.dp).height(54.dp), saved.sourceTitle, imageOverride = saved.thumbnailUrl) },
        trailingContent = { StudioIconButton(actions ?: remove) { AppIcon(if (actions == null) Icons.Filled.Delete else Icons.Filled.MoreVert, if (actions == null) "第${number}話を削除" else "話の操作") } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
@Composable private fun EmptyLibrary(title: String, detail: String, action: (() -> Unit)? = null) {
    Box(Modifier.padding(16.dp)) {
        StatusCard {
            AppText(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
            AppText(detail, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
            action?.let { StudioTextButton(it) { AppText("コメントを保存") } }
        }
    }
}
@Composable private fun AddDownloadSheet(initialSeries: String, openChannelHelp: Boolean = false, dismiss: () -> Unit) {
    val model: SaveCommentViewModel = org.koin.androidx.compose.koinViewModel()
    LaunchedEffect(Unit) { model.start(initialSeries) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.complete) { if (state.complete) dismiss() }
    StudioSheet(onDismissRequest = dismiss, dismissEnabled = !state.saving) { SaveCommentForm(state, model::query, model::choose, model::all, model::episode, model::retry, model::save, model::channelInput, model::connectChannel, model::source, model::cancelChannel, model::forgetChannel, model::confirmSave, model::closeReview, model::selectReview, openChannelHelp) }
}
@Composable internal fun SaveCommentForm(state: SaveCommentState, query: (String) -> Unit, choose: (AnimeInfo) -> Unit, all: (Boolean) -> Unit, episode: (String) -> Unit, retry: () -> Unit, save: () -> Unit, channelInput: (String) -> Unit = {}, connectChannel: () -> Unit = {}, source: (PlaybackSource) -> Unit = {}, cancelChannel: () -> Unit = {}, forgetChannel: () -> Unit = {}, confirmSave: () -> Unit = {}, closeReview: () -> Unit = {}, selectReview: (Episode, Boolean) -> Unit = { _, _ -> }, initialChannelHelp: Boolean = false) {
    if (state.reviewing || state.review != null) SaveReviewDialog(state, confirmSave, closeReview, selectReview)
    var channelHelp by rememberSaveable { mutableStateOf(initialChannelHelp) }
    LaunchedEffect(state.channel?.id) { if (state.channel != null) channelHelp = false }
    if (channelHelp) ChannelInputDialog(state, channelInput, connectChannel) { cancelChannel(); channelHelp = false }
    StudioFormLayout("コメントを保存", actions = {
        if (state.error.isNotBlank()) AppText(state.error, color = MaterialTheme.colorScheme.error)
        if (state.rememberedChannel != null) SettingsCard {
            AppText("保存したチャンネル指定を使用します")
            AppText("ch${state.rememberedChannel}", localize = false)
            TextButton(forgetChannel) { AppText("チャンネル指定を解除") }
        }
        if (state.sources.isEmpty()) AppText("取得元を1つ以上選んでください", color = MaterialTheme.colorScheme.error)
        Button(save, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.canSave) {
            if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else { AppIcon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); AppText("保存内容を確認") }
        }
    }) {
        OutlinedTextField(state.query, query, Modifier.fillMaxWidth(), label = { AppText("作品名") }, singleLine = true, readOnly = state.saving,
            visualTransformation = rememberRoundedInput(),
            leadingIcon = state.selected?.let { work -> { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { AnimeArtwork(work, Modifier.size(24.dp, 36.dp).clip(RoundedCornerShape(4.dp)), work.title) } } },
            supportingText = { AppText(if (state.selected != null) state.total?.let { "選択済み · 全${it}話" } ?: uiText("選択済み") else "作品名を入力して候補を選んでください") })
        state.channel?.let { AppText("指定チャンネル：ch${it.id}", style = MaterialTheme.typography.bodyMedium) }
        if (state.loading) { StudioProgress(); AppText("作品を検索しています") }
        if (state.failure) { AppText("検索できませんでした。通信を確認してください。"); OutlinedButton(retry) { AppText("再試行") } }
        if (state.searched && state.results.isEmpty()) AppText("候補がありません。別の作品名で検索してください。")
        state.results.take(6).forEach { work ->
            StudioRow(headlineContent = { AppText(work.title, localize = false) }, modifier = Modifier.motionClickable { choose(work) },
                supportingContent = { AppText(animeWorkSummaryText(work)) }, leadingContent = { AnimeArtwork(work, Modifier.size(48.dp, 64.dp), work.title) })
        }
        TextButton({ channelHelp = true }, enabled = !state.saving) { AppIcon(Icons.Filled.ErrorOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); AppText("うまく取得できない場合はこちら") }
        var choosingSources by rememberSaveable { mutableStateOf(false) }
        OutlinedCard(Modifier.fillMaxWidth()) {
            ListItem(headlineContent = { AppText("保存する取得元") }, supportingContent = { AppText("${state.sources.size}種類を選択", style = MaterialTheme.typography.bodyMedium) },
                trailingContent = { AppIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, modifier = Modifier.motionClickable(enabled = !state.saving) { choosingSources = true })
        }
        if (choosingSources) StudioDialog({ choosingSources = false }, title = { AppText("保存する取得元") }, text = {
            ReferenceGroup { OfflineSources.forEach { kind ->
                StudioRow(headlineContent = { AppText(kind.label) }, trailingContent = { Checkbox(kind in state.sources, { source(kind) }) }, modifier = Modifier.motionClickable { source(kind) })
            } }
        }, confirmButton = { TextButton({ choosingSources = false }) { AppText("完了") } })
        if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f) ReferenceGroup {
            PreferenceChoice("1話", !state.allEpisodes, { all(false) }, enabled = !state.saving)
            PreferenceChoice("まとめて", state.allEpisodes, { all(true) }, enabled = !state.saving)
        } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "1話", true to "まとめて").forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = state.allEpisodes == value, onClick = { all(value) }, shape = SegmentedButtonDefaults.itemShape(index, 2), enabled = !state.saving,
                    icon = { AppIcon(if (value) Icons.AutoMirrored.Filled.LibraryBooks else Icons.Filled.Description, null) }) { AppText(label) }
            }
        }
        if (state.allEpisodes) {
            AppText(state.channel?.numbers?.let { "第${it.first()}話から第${it.last()}話まで保存します" } ?: state.total?.let { "第1話から第${it}話まで保存します" } ?: uiText("総話数が未登録のため、1話ずつ保存してください"), style = MaterialTheme.typography.bodyLarge)
        } else {
            val number = state.episode.toIntOrNull()
            val invalid = state.selected != null && (number == null || number < 1 || state.channel?.numbers?.let { number !in it } ?: (state.total?.let { number > it } ?: false))
            StudioField(state.episode, episode, Modifier.fillMaxWidth(), label = { AppText("話数") }, enabled = !state.saving, isError = invalid,
                supportingText = if (invalid) { { AppText(state.channel?.numbers?.let { "指定チャンネルの話数から選んでください" } ?: state.total?.let { "1〜${it}の話数を入力してください" } ?: "1以上の話数を入力してください") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        }

    }
}

@Composable private fun SourceOwnerLink(url: String) {
    val context = LocalContext.current
    TextButton({ context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }, contentPadding = PaddingValues(0.dp)) {
        AppText(url, localize = false, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun SeriesArtworkHeader(saved: SavedEpisode, title: String = saved.libraryTitle()) {
    val artwork = rememberArtwork(saved.episode.series, saved.sourceTitle)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimeArtwork(artwork, Modifier.fillMaxWidth().aspectRatio(1.4f).sharedArtwork(saved.libraryKey()), title)
        if (saved.ownerUrl.isNotBlank()) SourceOwnerLink(saved.ownerUrl)
    }
}

@Composable private fun SeriesListCard(saved: SavedEpisode, count: Int, title: String = saved.libraryTitle(), longClick: () -> Unit = {}, selecting: Boolean = false, checked: Boolean = false, select: () -> Unit = {}, click: () -> Unit) {
    val artwork = rememberArtwork(saved.episode.series, saved.sourceTitle)
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).pressMotion(interactions).combinedClickable(interactionSource = interactions, indication = LocalIndication.current, role = if (selecting) Role.Checkbox else Role.Button, onClick = if (selecting) select else click, onLongClick = if (selecting) select else longClick).semantics { if (selecting) toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }, shape = RoundedCornerShape(24.dp)) {
        ListItem(trailingContent = if (selecting) { { Checkbox(checked, null) } } else null, headlineContent = { AppText(title, localize = false, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Column { if (saved.ownerUrl.isNotBlank()) SourceOwnerLink(saved.ownerUrl); AppText("${count}話 保存済み") } },
            leadingContent = { AnimeArtwork(artwork, Modifier.width(56.dp).height(80.dp).sharedArtwork(saved.libraryKey()), title) },
            colors = ListItemDefaults.colors(containerColor = if (selecting && checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer))
    }
}
