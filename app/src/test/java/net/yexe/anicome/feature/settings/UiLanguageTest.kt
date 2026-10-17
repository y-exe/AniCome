package net.yexe.anicome

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class UiLanguageTest {
    @Test fun translatesTimingUndoValuesAsACompleteSentence() {
        val catalog = TranslationCatalog(tables())
        assertEquals("Before: +2.0 s", catalog.text("変更前：+2.0 秒", "en"))
        assertEquals("변경 전: +2.0초", catalog.text("変更前：+2.0 秒", "ko"))
        assertEquals("Avant : +2.0 s", catalog.text("変更前：+2.0 秒", "fr"))
        assertEquals("更改前：+2.0秒", catalog.text("変更前：+2.0 秒", "zh"))
    }
    private fun tables(): Map<String, Map<String, String>> = listOf("en", "ko", "fr", "zh", "es", "pt").associateWith { language ->
        val path = listOf(File("src/main/assets/i18n/$language.json"), File("app/src/main/assets/i18n/$language.json")).first { it.isFile }
        val json = JSONObject(path.readText())
        json.keys().asSequence().associateWith { json.getString(it) }
    }
    @Test fun allLanguagesHaveTheSameNonEmptyEntriesAndPlaceholders() {
        val tables = tables()
        val keys = tables.getValue("en").keys
        assertTrue(keys.size >= 280)
        tables.forEach { (language, entries) ->
            assertEquals(language, keys, entries.keys)
            entries.forEach { (source, translated) ->
                assertTrue("$language $source", translated.isNotBlank())
                val placeholders = Regex("\\{[0-9]+\\}")
                assertEquals(placeholders.findAll(source).map { it.value }.toSet(), placeholders.findAll(translated).map { it.value }.toSet())
            }
        }
    }
    @Test fun formatsCountsAndPreservesExternalTitles() {
        val catalog = TranslationCatalog(tables())
        assertEquals("3 titles · 9 episodes", catalog.text("3作品・9話", "en"))
        assertEquals("제3화", catalog.text("第3話", "ko"))
        assertEquals("Identifiant : 12345", catalog.text("ユーザーID: 12345", "fr"))
        assertEquals("已用容量：2.9 MB · 无上限", catalog.text("使用容量  2.9 MB · 上限なし", "zh"))
        assertEquals("元の作品タイトル", catalog.text("元の作品タイトル", "en"))
        assertEquals("3作品・9話", catalog.text("3作品・9話", "ja"))
    }
    @Test fun resolvesUnsupportedSystemLanguagesAndExplicitChoices() {
        assertEquals("en", resolveUiLanguage("system", "de"))
        assertEquals("ko", resolveUiLanguage("system", "ko"))
        assertEquals("fr", resolveUiLanguage("fr", "ja"))
        assertEquals("es", resolveUiLanguage("system", "es"))
        assertEquals("pt", resolveUiLanguage("pt", "ja"))
        assertEquals(listOf("system", "en", "zh", "ja", "ko", "es", "pt", "fr"), SupportedLanguages.map { it.first })
    }
    @Test fun translatesNestedErrorsWithoutChangingExternalTitles() {
        val catalog = TranslationCatalog(tables())
        assertEquals("Fetch failed: Request failed (HTTP 406)", catalog.text("取得失敗: 取得失敗（HTTP 406）", "en"))
        assertEquals("タイトル", catalog.text("タイトル", "en"))
        assertEquals("100 comments · 元の作品タイトル", catalog.text("100件 · 元の作品タイトル", "en"))
    }
    @Test fun translatesLocalAndOverallEpisodeNumbersAsAWholeSentence() {
        val catalog = TranslationCatalog(tables())
        assertEquals("Episode 1 within this title → overall episode 13", catalog.text("作品内の第1話 → 通算第13話", "en"))
        assertEquals("Épisode 1 de cette œuvre → épisode global 13", catalog.text("作品内の第1話 → 通算第13話", "fr"))
        assertEquals("작품 내 제1화 → 전체 제13화", catalog.text("作品内の第1話 → 通算第13話", "ko"))
        assertEquals("作品内第1集 → 累计第13集", catalog.text("作品内の第1話 → 通算第13話", "zh"))
    }
    @Test fun translatesCompleteEstimateSentencesIncludingTheUnits() {
        val catalog = TranslationCatalog(tables())
        assertEquals("Estimated time remaining: about 40 seconds", catalog.text("保存完了までの目安：約40秒", "en"))
        assertEquals("현재 댓글 출처의 남은 시간: 약 2분", catalog.text("現在の取得元の残り目安：約2分", "ko"))
        assertEquals("Durée estimée : environ 2 min", catalog.text("保存時間の目安：約2分", "fr"))
        assertEquals("预计保存耗时：约 10 秒", catalog.text("保存時間の目安：約10秒", "zh"))
    }

}
