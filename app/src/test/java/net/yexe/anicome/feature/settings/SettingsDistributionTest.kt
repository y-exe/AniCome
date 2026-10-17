package net.yexe.anicome

import org.junit.Test
import org.junit.Assert.*

class SettingsDistributionTest : InteractionFixture() {
    @Test fun homeContainsGroupedSettingsWithoutTheRemovedCategory() {
        val view = fixture { SettingsScreen(onClose = {}) {} }
        try {
            paparazzi.snapshot(view, "settings_distribution")
            val labels = texts(view)
            assertFalse(labels.contains("便利な機能"))
            assertFalse(labels.contains("外観"))
            assertFalse(labels.contains("アップデート"))
            assertFalse(labels.contains("色と書体"))
            assertTrue(labels.contains("その他"))
            assertTrue(labels.contains("アプリ詳細"))
            assertFalse(labels.contains("ニコニコ連携"))
        } finally { view.disposeComposition() }
    }
}
