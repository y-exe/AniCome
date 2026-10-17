package net.yexe.anicome

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import org.json.JSONObject
import java.util.Locale

val SupportedLanguages = listOf("system" to "端末の設定", "en" to "English", "zh" to "简体中文", "ja" to "日本語", "ko" to "한국어", "es" to "Español", "pt" to "Português", "fr" to "Français")
val LocalUiLanguage = staticCompositionLocalOf { "ja" }
object UiTranslations {
    private var catalog: TranslationCatalog? = null
    @Synchronized fun initialize(context: Context) {
        if (catalog != null) return
        val tables = listOf("en", "ko", "fr", "zh", "es", "pt").associateWith { language ->
            val data = context.assets.open("i18n/$language.json").bufferedReader().use { JSONObject(it.readText()) }
            data.keys().asSequence().associateWith { data.getString(it) }
        }
        catalog = TranslationCatalog(tables)
    }
    fun text(source: String, language: String, context: String? = null): String = catalog?.text(source, language, context) ?: source
}
class TranslationCatalog(private val translations: Map<String, Map<String, String>>) {
    private data class Template(val source: String, val regex: Regex, val tokens: List<String>, val translation: String)
    private val templates = translations.mapValues { compileTemplates(it.value) }
    private fun compileTemplates(table: Map<String, String>): List<Template> = table.entries.filter { it.key.contains("{0}") }.sortedByDescending { it.key.length }.map { (source, translation) ->
        val tokens = Regex("\\{([0-9]+)\\}").findAll(source).toList()
        var offset = 0
        val pattern = buildString {
            append('^')
            for (token in tokens) { append(Regex.escape(source.substring(offset, token.range.first))); append("(.+?)"); offset = token.range.last + 1 }
            append(Regex.escape(source.substring(offset))); append('$')
        }
        Template(source, Regex(pattern), tokens.map { it.value }, translation)
    }
    fun text(source: String, language: String, context: String? = null): String = translate(source, language, context, 0)
    private fun translate(source: String, language: String, context: String?, depth: Int): String {
        if (language == "ja") return source
        val table = translations[language] ?: return source
        if (context != null) table["$context:$source"]?.let { return it }
        table[source]?.let { return it }
        for (template in templates[language].orEmpty()) {
            val match = template.regex.matchEntire(source) ?: continue
            var result = template.translation
            template.tokens.forEachIndexed { index, token ->
                val captured = match.groupValues[index + 1]
                val error = template.source.contains("失敗") || template.source.contains("エラー")
                result = result.replace(token, if (error && depth < 3) translate(captured, language, null, depth + 1) else captured)
            }
            return result
        }
        return source
    }
}

@Composable fun uiText(source: String, context: String? = null): String = UiTranslations.text(source, LocalUiLanguage.current, context)
fun resolveUiLanguage(selection: String, system: String): String = if (selection == "system") system.takeIf { it in setOf("ja", "en", "ko", "fr", "zh", "es", "pt") } ?: "en" else selection.takeIf { it in setOf("ja", "en", "ko", "fr", "zh", "es", "pt") } ?: "ja"

fun Context.uiString(source: String): String {
    UiTranslations.initialize(this)
    val selection = (applicationContext as? AniComeApp)?.runtime?.language?.value ?: "system"
    return UiTranslations.text(source, resolveUiLanguage(selection, resources.configuration.locales[0].language))
}
fun searchSettingMatches(query: String, labels: List<String>): Boolean {
    fun normalized(text: String): String {
        val kana = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT).map { if (it in '\u30a1'..'\u30f6') (it.code - 0x60).toChar() else it }.joinToString("")
        return java.text.Normalizer.normalize(kana, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
    fun initials(text: String) = text.map { if (it in '\uac00'..'\ud7a3') (0x1100 + (it.code - 0xac00) / 588).toChar() else it }.joinToString("")
    fun similar(a: String, b: String): Boolean {
        val budget = if (a.length >= 5) 2 else 1
        if (a.length < 3 || kotlin.math.abs(a.length - b.length) > budget) return false
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ac ->
            val current = IntArray(b.length + 1); current[0] = i + 1
            b.forEachIndexed { j, bc -> current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (ac == bc) 0 else 1) }
            previous = current
        }
        return previous.last() <= budget
    }
    val aliases = SettingSearchReadings.filterKeys { key -> labels.any { it.contains(key, ignoreCase = true) } }.values.flatMap { it.split(' ') }
    val terms = (labels + aliases + labels.map(::initials)).map(::normalized)
    return normalized(query.trim()).split(Regex("\\s+")).filter(String::isNotBlank).all { token ->
        terms.any { text -> text.contains(token) || similar(token, text) || text.split(Regex("[・、 /]+" )).any { similar(token, it) } ||
            token.length >= 3 && text.length > token.length && (token.length - 1..token.length + 1).any { size ->
                size <= text.length && (0..text.length - size).any { offset -> similar(token, text.substring(offset, offset + size)) }
            }
        }
    }
}
