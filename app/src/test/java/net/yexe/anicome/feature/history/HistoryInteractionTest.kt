package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class HistoryInteractionTest : InteractionFixture() {
    @Test fun aSavedEpisodeCanBeFetchedAgainAndRemovingHistoryUsesTheSeparateAction() {
        val item = CommentHistoryEntry(id = 1L, time = 1000L, kind = "REPLACE", series = "作品 第2クール", episode = 2, sourceId = "so13", sourceTitle = "作品 第13話", count = 100, previousId = "so1", previousCount = 50, sources = "OFFICIAL,DANIME")
        var replay: CommentHistoryEntry? = null; var deleted = false; var closed = false
        val view = fixture { CommentHistoryDetail(item, { replay = it }, { deleted = true }, { closed = true }) }
        try {
            paparazzi.snapshot(view, "history_details")
            assertTrue(texts(view).contains("so1 → so13"))
            click(view, "もう一度取得"); assertEquals(item, replay); assertTrue(closed); assertFalse(deleted)
            click(view, "履歴から削除"); assertTrue(deleted)
        } finally { view.disposeComposition() }
    }
    @Test fun searchHistoryKeepsTheQueryAndEpisodeForRepeatingTheSearch() {
        val item = CommentHistoryEntry(id = 2L, time = 1000L, kind = "VIDEO_SEARCH", series = "作品", episode = 13, query = "作品 新しい旅", count = 3)
        var replay: CommentHistoryEntry? = null
        val view = fixture { CommentHistoryDetail(item, { replay = it }, {}, {}) }
        try { paparazzi.snapshot(view, "history_search"); click(view, "もう一度検索"); assertEquals(Episode("作品", 13), replay!!.target); assertEquals("作品 新しい旅", replay!!.query) }
        finally { view.disposeComposition() }
    }
    @Test fun cachedUseDoesNotClaimThatZeroNewCommentsWereDownloaded() {
        val item = CommentHistoryEntry(id = 3L, time = 1000L, kind = "USE", series = "作品", episode = 13, sourceId = "so13", count = 100, storage = "archive")
        val view = fixture { CommentHistoryDetail(item, null, {}, {}) }
        try { paparazzi.snapshot(view, "history_cached_use"); assertTrue(texts(view).contains("100件")); assertFalse(texts(view).contains("0 → 100件")) }
        finally { view.disposeComposition() }
    }

    @Test fun repairAuthenticationErrorsOfferTheNiconicoSettings() {
        var page = ""
        val view = fixture { ArchiveIntegrityContent(ArchiveIntegrityState(error = "元の取得量で修復するにはニコニコへのログインが必要です", failure = AcquisitionFailure.ACCESS), {}, {}, { page = it }) }
        try { paparazzi.snapshot(view, "repair_login"); click(view, "設定を確認"); assertEquals("コメント取得", page) }
        finally { view.disposeComposition() }
    }

}
