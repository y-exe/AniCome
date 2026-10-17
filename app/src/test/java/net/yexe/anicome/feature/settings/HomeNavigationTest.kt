package net.yexe.anicome

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.*
import org.junit.Assert.*
import org.junit.Test

class HomeNavigationTest : InteractionFixture() {
    @Test fun commentSwitchAndSettingsRowHaveIndependentActions() {
        val showing = mutableStateOf(false)
        var toggles = 0
        val destinations = mutableListOf<String>()
        val view = fixture { SettingsHomeList(false, 0, "", showing.value, { showing.value = !showing.value; toggles++ }) { destinations += it } }
        try {
            paparazzi.snapshot(view, "home_switch_before")
            val toggle = nodes(view).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            toggle.config[SemanticsActions.OnClick].action!!.invoke()
            paparazzi.snapshot(view, "home_switch_on")
            assertEquals(1, toggles)
            assertTrue(showing.value)
            assertTrue(destinations.isEmpty())
            val row = nodes(view).first { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsProperties.Role) != Role.Switch && it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "コメント表示" } }
            row.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(listOf("コメント表示"), destinations)
            assertEquals(1, toggles)
        } finally { view.disposeComposition() }
    }
    @Test fun hierarchyReturnsToItsGroupAndKeepsTheRequestedOrder() {
        assertEquals(listOf("通知", "ダウンロード", "コメント取得", "ネットワーク", "アップデート"), otherSettings(false, 0).map { it.page })
        otherSettings(false, 0).forEach { assertEquals("その他", settingsParent(it.page)) }
        assertEquals("アプリ詳細", settingsParent("OSSライセンス"))
        assertEquals("", settingsParent("その他"))
        assertEquals("", settingsParent("アプリ詳細"))
    }
    @Test fun nestedSettingsAndAboutReturnOneLevelAtATime() {
        val model = SettingsViewModel(AppCoordinator(), initialPage = "その他")
        val view = fixture {
            androidx.compose.runtime.CompositionLocalProvider(LocalAdvancedSettings provides AdvancedSettings(reducedMotion = true)) {
                SettingsScreen(initialPage = "その他", model = model, home = true) {}
            }
        }
        try {
            paparazzi.snapshot(view, "hierarchy_other")
            click(view, "ストレージ")
            assertEquals("ダウンロード", model.page.value)
            paparazzi.snapshot(view, "hierarchy_storage")
            assertTrue(texts(view).contains("保存容量の上限"))
            click(view, "戻る")
            assertEquals("その他", model.page.value)
            paparazzi.snapshot(view, "hierarchy_back_other")
            assertTrue(texts(view).contains("ニコニコ連携"))
            click(view, "戻る")
            assertEquals("", model.page.value)
            model.openPage("アプリ詳細")
            paparazzi.snapshot(view, "hierarchy_about")
            click(view, "OSSライセンス")
            assertEquals("OSSライセンス", model.page.value)
            paparazzi.snapshot(view, "hierarchy_oss")
            click(view, "戻る")
            assertEquals("アプリ詳細", model.page.value)
            paparazzi.snapshot(view, "hierarchy_back_about")
            assertTrue(texts(view).contains("GitHub"))
        } finally { view.disposeComposition() }
    }
    @Test fun sharedSettingsReturnToTheActualEntryPage() {
        val model = SettingsViewModel(AppCoordinator())
        assertEquals(StreamingService.NETFLIX, model.controller.activeService.value)
        model.openPage("コメント表示")
        model.openPage("取得元")
        model.openPage("コメント取得")
        model.backPage(); assertEquals("取得元", model.page.value)
        model.backPage(); assertEquals("コメント表示", model.page.value)
        model.backPage(); assertEquals("", model.page.value)
        model.openPage("その他"); model.openPage("コメント取得")
        model.backPage(); assertEquals("その他", model.page.value)
    }

    @Test fun bulkSelectionUsesTheActualRowsAndCanBeCleared() {
        val library = listOf("作品A", "作品B").mapIndexed { i, name -> SavedEpisode(Episode(name, 1), "so${i+1}", name, 0, 100, 1024) }
        val view = fixture { LibraryScreen(AppCoordinator(), library, Playback(), emptyList()) {} }
        var finished = false
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "作品を選択") }, 400)
                v.postDelayed({ click(view, "作品A") }, 900)
                v.postDelayed({ assertTrue(texts(view).toString(), texts(view).contains("1作品を選択")); click(view, "すべて選択") }, 1400)
                v.postDelayed({ assertTrue(texts(view).toString(), texts(view).contains("2作品を選択")); click(view, "選択を解除") }, 1900)
                v.postDelayed({ assertTrue(texts(view).toString(), texts(view).contains("0作品を選択")); finished = true }, 2400)
            }
        })
        try { paparazzi.gif(view, "bulk_selection", end = 2700, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition() }
    }
    @Test fun homeOfflineButtonOpensTheLibrary() {
        var calls = 0
        val view = fixture { SettingsScreen(home = true, onOffline = { calls++ }) {} }
        try {
            paparazzi.snapshot(view, "home_offline_entry")
            click(view, "事前ダウンロード")
            assertEquals(1, calls)
            assertFalse(texts(view).contains("色と書体"))
        } finally { view.disposeComposition() }
    }
}
