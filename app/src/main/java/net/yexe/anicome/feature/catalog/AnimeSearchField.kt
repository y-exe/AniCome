package net.yexe.anicome

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable fun AnimeSearchField(value: String, change: (String) -> Unit, selected: String? = null, choose: (AnimeInfo) -> Unit = { change(it.title) }) {
    val model: AnimeSearchViewModel = org.koin.androidx.compose.koinViewModel(key = "anime-search-${androidx.compose.runtime.currentCompositeKeyHashCode}")
    val state by model.state.collectAsStateWithLifecycle()
    val results = state.results
    val loading = state.loading
    val completed = state.completed
    val failure = state.failure
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(value, retry, selected) { model.search(value, selected) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioField(value, change, Modifier.fillMaxWidth(), label = { AppText("作品名") }, supportingText = { AppText(if (selected == value) "選択済み" else "作品名を入力して候補を選んでください") }, singleLine = true)
        AnimatedVisibility(loading || failure || completed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth()) {
                    if (loading) { StudioProgress(); AppText("作品を検索しています", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                    if (failure) {
                        AppText("検索できませんでした。通信を確認してください。", Modifier.padding(16.dp))
                        StudioTextButton({ retry++ }, Modifier.padding(horizontal = 8.dp)) { AppIcon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); AppText("再試行") }
                    }
                    if (completed && results.isEmpty()) AppText("候補がありません。別の作品名で検索してください。", Modifier.padding(16.dp))
                    results.take(6).forEach { candidate ->
                        StudioRow(modifier = Modifier.motionClickable { choose(candidate) }, headlineContent = { AppText(candidate.title) }, supportingContent = { AppText(animeWorkSummaryText(candidate)) }, leadingContent = { AnimeArtwork(candidate, Modifier.size(48.dp, 64.dp), candidate.title) })
                    }
                }
            }
        }
    }
}
