package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun VideoReplacementDialog(item: SavedEpisode, dismiss: () -> Unit) {
    val model: VideoReplacementViewModel = org.koin.androidx.compose.koinViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(item.episode.key) { model.start(item) }
    LaunchedEffect(state.completed) { if (state.completed) dismiss() }
    DisposableEffect(model) { onDispose { model.close() } }
    StudioDialog(onDismissRequest = { if (!state.saving) dismiss() }, title = { AppText("公式動画を差し替え") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .6f).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("他の取得元のコメントは残して、公式動画だけを変更します。")
            state.target?.let { AppText(it.label, localize = false) }
            StudioField(state.query, model::edit, Modifier.fillMaxWidth(), label = { AppText("検索語") }, enabled = !state.saving && !state.searching)
            OutlinedButton({ model.query() }, Modifier.fillMaxWidth(), enabled = state.query.isNotBlank() && !state.searching && !state.saving) { AppText("再検索") }
            if (state.searching || state.saving) StudioProgress()
            if (state.error.isNotBlank()) AppText(state.error, color = MaterialTheme.colorScheme.error)
            if (!state.searching && state.candidates.isEmpty()) AppText("公式動画が見つかりませんでした")
            VideoReplacementChoices(state, model::choose)
            if (state.selected != null) SettingsCard {
                AppText("差し替え先", style = MaterialTheme.typography.titleMedium)
                AppText(state.selected!!.title, localize = false)
                if (state.selected!!.ownerName.isNotBlank()) AppText(state.selected!!.ownerName, localize = false)
            }
        }
    }, confirmButton = { TextButton(model::save, enabled = state.selected != null && !state.saving && !state.searching) { AppText("差し替える") } },
        dismissButton = { TextButton(dismiss, enabled = !state.saving) { AppText("キャンセル") } })
}
@Composable internal fun VideoReplacementChoices(state: VideoReplacementState, choose: (Candidate) -> Unit) {
    ReferenceGroup { state.candidates.forEach { candidate ->
        val matches = state.target?.let { savedEpisodeMatches(it, candidate, state.subtitle) } == true
        StudioRow(modifier = Modifier.motionClickable(enabled = matches && !state.saving) { choose(candidate) },
            headlineContent = { AppText(candidate.title, localize = false) },
            supportingContent = { Column { if (candidate.ownerName.isNotBlank()) AppText(candidate.ownerName, localize = false); if (!matches) AppText("期・話数が一致しません", color = MaterialTheme.colorScheme.error) } },
            leadingContent = { AnimeArtwork(null, Modifier.size(64.dp, 36.dp), candidate.title, imageOverride = candidate.thumbnailUrl) },
            trailingContent = { RadioButton(state.selected?.id == candidate.id, { choose(candidate) }, enabled = matches && !state.saving) })
    } }
}
