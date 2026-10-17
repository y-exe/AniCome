package net.yexe.anicome

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMetadataTest {
    @Test fun resolvesTheActualDanimePlaybackNotificationWithoutSessionMetadata() {
        val title = "凶乱令嬢ニア・リストン 病弱令嬢に転生した神殺しの武人の華麗なる無双録 #1 闇より浮かびて"
        val fields = PlaybackMetadata.withNotification(listOf("TITLE" to null), listOf("NOTIFICATION_TITLE" to title))
        val resolved = PlaybackMetadata.resolve(fields)
        assertEquals("凶乱令嬢ニア・リストン 病弱令嬢に転生した神殺しの武人の華麗なる無双録", resolved.first)
        assertEquals("第1話", resolved.second)
        assertEquals(Episode(resolved.first, 1), EpisodeMatcher.parse(resolved.first, resolved.second))
        assertEquals(title, PlaybackMetadata.searchTitle(fields))
    }

    @Test fun sessionMetadataWinsOverADelayedNotificationFromThePreviousEpisode() {
        val session = listOf("TITLE" to "作品 第2話")
        val fields = PlaybackMetadata.withNotification(session, listOf("NOTIFICATION_TITLE" to "作品 第1話"))
        assertEquals(session, fields)
    }

    @Test fun updatesTheEpisodeWhenTheNotificationChanges() {
        val fields = PlaybackMetadata.withNotification(emptyList(), listOf("NOTIFICATION_TITLE" to "作品 #2 次の話"))
        assertEquals("作品" to "第2話", PlaybackMetadata.resolve(fields))
    }

    @Test fun removalDoesNotKeepThePreviousNotificationTitle() {
        assertEquals("" to "", PlaybackMetadata.resolve(PlaybackMetadata.withNotification(emptyList(), emptyList())))
    }

    @Test fun usesDisplayTitleWhenTitleIsMissing() {
        val fields = listOf("TITLE" to null, "DISPLAY_TITLE" to "作品 第2話")
        assertEquals("作品 第2話", PlaybackMetadata.searchTitle(fields))
    }

    @Test fun usesDisplayTitleWhenTitleIsWhitespace() {
        val fields = listOf("TITLE" to "  ", "DISPLAY_TITLE" to " 作品 第2話 ")
        assertEquals("作品 第2話", PlaybackMetadata.searchTitle(fields))
    }

    @Test fun preservesTheOriginalEpisodeTitleForSearch() {
        val fields = listOf("TITLE" to "Step9 ゴリラのみずき", "ARTIST" to "スロウスタート")
        assertEquals("Step9 ゴリラのみずき", PlaybackMetadata.searchTitle(fields))
    }

    @Test fun usesResolvedSeriesWhenTitleFieldsAreMissing() {
        val fields = listOf("TITLE" to null, "DISPLAY_TITLE" to null, "ARTIST" to "作品", "ALBUM" to "第2話")
        assertEquals("作品", PlaybackMetadata.searchTitle(fields))
    }

    @Test fun doesNotInventATitleWhenThePlayerProvidesNone() {
        val fields = listOf("TITLE" to null, "DISPLAY_TITLE" to null)
        assertEquals("", PlaybackMetadata.searchTitle(fields))
        assertEquals("" to "", PlaybackMetadata.resolve(fields))
    }
}
