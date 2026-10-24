package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

@Composable internal fun SaveReviewDialog(state: SaveCommentState, save: () -> Unit, close: () -> Unit, select: (Episode, Boolean) -> Unit) {
    val review = state.review
    StudioDialog(onDismissRequest = { if (!state.saving) close() }, title = { AppText("保存内容を確認") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .6f).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.selected?.let { AppText(it.title, localize = false, style = MaterialTheme.typography.titleMedium); AppText(animeWorkSummaryText(it)) }
            if (state.reviewing) {
                StudioProgress({ if (state.reviewTotal > 0) state.reviewCurrent.toFloat() / state.reviewTotal else 0f })
                AppText("${state.reviewCurrent} / ${state.reviewTotal}話を確認中")
            }
            if (review != null) SaveReviewContent(review, state.saving, select)
            if (state.error.isNotBlank()) AppText(state.error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(save, enabled = review != null && review.selected.isNotEmpty() && review.duplicateVideoIds.isEmpty() && review.enoughSpace && !state.saving) { AppText(if (state.saving) "保存予約中" else "保存予約") } },
        dismissButton = { TextButton(close, enabled = !state.saving) { AppText("戻る") } })
}
@Composable internal fun SaveReviewContent(review: SaveReview, busy: Boolean = false, select: (Episode, Boolean) -> Unit) {
    val duplicateIds = review.duplicateVideoIds
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    AppText("${review.selected.size}話を選択", style = MaterialTheme.typography.titleMedium)
    AppText("保存しない話はチェックを外してください。特別編も個別に選べます。")
    if (duplicateIds.isNotEmpty()) SettingsCard { AppText(DuplicateReviewMessage, color = MaterialTheme.colorScheme.error) }
    SettingsCard {
        AppText("必要容量の目安  %.1f MB".format(review.estimatedBytes / 1048576.0))
        if (review.space.free != Long.MAX_VALUE) AppText("空き容量  %.1f MB".format(review.space.free / 1048576.0))
        if (review.space.limit != Long.MAX_VALUE) AppText("保存上限まで  %.1f MB".format((review.space.limit - review.space.used).coerceAtLeast(0L) / 1048576.0))
        AppText("取得量や取得元によって実際の容量は変わります。", style = MaterialTheme.typography.bodySmall)
        if (!review.enoughSpace) AppText("空き容量または保存容量の上限が不足しています。対象の話を減らすか、ストレージ設定を確認してください。", color = MaterialTheme.colorScheme.error)
    }
    ReferenceGroup { review.episodes.forEach { row ->
        StudioRow(modifier = Modifier.motionClickable(enabled = !busy) { select(row.episode, !row.selected) },
            headlineContent = { AppText("第${row.searchEpisode.number}話") },
            supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.episode.number != row.searchEpisode.number) AppText("作品内の第${row.episode.number}話 → 通算第${row.searchEpisode.number}話")
                if (row.subtitle.isNotBlank()) AppText(row.subtitle, localize = false)
                row.candidate?.let { AppText(it.title, localize = false); if (it.ownerName.isNotBlank()) AppText(it.ownerName, localize = false) }
                if (row.issue.isNotBlank()) AppText(row.issue, color = MaterialTheme.colorScheme.error)
                if (row.selected && niconicoVideoId(row.candidate?.id.orEmpty()) in duplicateIds) AppText("他の話と動画が重複しています", color = MaterialTheme.colorScheme.error)
                if (row.candidate == null) AppText("未確認の取得元は保存時に再検索します", style = MaterialTheme.typography.bodySmall)
            } },
            leadingContent = { EpisodeArtwork(row.candidate?.id.orEmpty(), row.candidate?.thumbnailUrl.orEmpty(), Modifier.size(64.dp, 36.dp), row.candidate?.title ?: row.episode.series) },
            trailingContent = { Checkbox(row.selected, { select(row.episode, it) }, enabled = !busy) })
    } }
}
}
