package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class SaveReviewInteractionTest : InteractionFixture() {
    @Test fun duplicateWarningCanBeResolvedByExcludingOneEpisode() {
        var review by mutableStateOf(SaveReview(listOf(
            SaveEpisodeReview(Episode("作品 第2クール", 1), Episode("作品", 13), candidate = Candidate("so13", "作品 第13話")),
            SaveEpisodeReview(Episode("作品 第2クール", 2), Episode("作品", 13), candidate = Candidate("so13", "作品 第13話"))), 1, SaveSpace()))
        val view = fixture { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SaveReviewContent(review) { episode, selected -> review = review.copy(episodes = review.episodes.map { if (it.episode == episode) it.copy(selected = selected) else it }) }
        } }
        try {
            paparazzi.snapshot(view, "duplicate_review")
            assertTrue(texts(view).contains(DuplicateReviewMessage))
            click(view, "第13話")
            paparazzi.snapshot(view, "duplicate_resolved")
            assertEquals(1, review.selected.size)
            assertTrue(review.duplicateVideoIds.isEmpty())
            assertFalse(texts(view).contains(DuplicateReviewMessage))
        } finally { view.disposeComposition() }
    }
}
