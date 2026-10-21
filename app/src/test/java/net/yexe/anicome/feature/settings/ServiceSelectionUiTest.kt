package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class ServiceSelectionUiTest : InteractionFixture() {
    @Test fun groupedSelectionAndHelp() {
        val view = fixture { ServiceSelectionList(StreamingService.NETFLIX, {}, {}) }
        try {
            paparazzi.snapshot(view, "service_selection_groups")
            val labels = texts(view)
            assertTrue(labels.contains("通常の取得方法"))
            assertTrue(labels.contains("ユーザー補助で取得"))
            assertTrue(StreamingService.HULU in StreamingService.accessibilityServices)
            assertFalse(labels.contains("FOD"))
        } finally { view.disposeComposition() }
        val help = fixture { AccessibilityServiceHelp {} }
        try { paparazzi.snapshot(help, "service_accessibility_help") }
        finally { help.disposeComposition() }
    }
}
