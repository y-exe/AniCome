package net.yexe.anicome

import androidx.datastore.preferences.core.stringPreferencesKey

enum class PanelAction(val key: String, val title: String) {
    TOGGLE("toggle", "コメント表示を切り替え"), TOOLS("tools", "再生の操作"), NG("ng", "NGワード"), OPEN("open", "アプリを開く"), SYNC("sync", "同期の詳細"), HIDE("hide", "一時的に隠す")
}
val DefaultPanelActions = listOf(PanelAction.TOGGLE, PanelAction.TOOLS, PanelAction.NG, PanelAction.OPEN)
fun panelActions(value: String?): List<PanelAction> {
    if (value == null) return DefaultPanelActions
    if (value.isBlank()) return emptyList()
    return value.split(',').mapNotNull { key -> PanelAction.entries.firstOrNull { it.key == key } }.distinct().take(4).ifEmpty { DefaultPanelActions }
}
fun changePanelActions(current: List<PanelAction>, action: PanelAction, operation: String): List<PanelAction> {
    val ordered = current.distinct().take(4)
    val index = ordered.indexOf(action)
    return when (operation) {
        "show" -> if (index < 0 && ordered.size < 4) ordered + action else ordered
        "hide" -> ordered - action
        "up", "down" -> {
            if (index < 0) ordered else ordered.toMutableList().apply {
                val target = (index + if (operation == "up") -1 else 1).coerceIn(0, lastIndex)
                removeAt(index); add(target, action)
            }
        }
        "reset" -> DefaultPanelActions
        else -> ordered
    }
}
internal fun SettingsRepository.panelAction(action: PanelAction, operation: String) = edit { p ->
    val key = stringPreferencesKey("panelActions")
    p[key] = changePanelActions(panelActions(p[key]), action, operation).joinToString(",") { it.key }
}
