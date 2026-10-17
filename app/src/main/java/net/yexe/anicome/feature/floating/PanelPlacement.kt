package net.yexe.anicome

import androidx.datastore.preferences.core.*

enum class PanelCorner(val title: String, val position: PanelPosition) {
    TOP_LEFT("左上", PanelPosition(0f, 0f)), TOP_RIGHT("右上", PanelPosition(1f, 0f)),
    BOTTOM_LEFT("左下", PanelPosition(0f, 1f)), BOTTOM_RIGHT("右下", PanelPosition(1f, 1f))
}
fun subtitleSafeCorners(subtitles: String): List<PanelCorner> = when (subtitles) {
    "bottom" -> listOf(PanelCorner.TOP_LEFT, PanelCorner.TOP_RIGHT)
    "top" -> listOf(PanelCorner.BOTTOM_LEFT, PanelCorner.BOTTOM_RIGHT)
    else -> PanelCorner.entries
}
data class PanelPlacementRequest(val portrait: PanelPosition? = null, val landscape: PanelPosition? = null, val collapsed: Boolean? = null) {
    fun forOrientation(landscape: Boolean) = if (landscape) this.landscape else portrait
}
internal fun SettingsRepository.applyPanelPlacement(request: PanelPlacementRequest, revision: String = java.util.UUID.randomUUID().toString()) = edit { p ->
    request.portrait?.let { value -> val safe = settledPanelPosition(value.x, value.y, false); p[floatPreferencesKey("panelX")] = safe.x; p[floatPreferencesKey("panelY")] = safe.y; p[stringPreferencesKey("panelPositionRevision")] = revision }
    request.landscape?.let { value -> val safe = settledPanelPosition(value.x, value.y, false); p[floatPreferencesKey("panelLandscapeX")] = safe.x; p[floatPreferencesKey("panelLandscapeY")] = safe.y; p[stringPreferencesKey("panelLandscapePositionRevision")] = revision }
    request.collapsed?.let { p[booleanPreferencesKey("panelCollapsed")] = it; p[booleanPreferencesKey("panelLandscapeCollapsed")] = it }
}
val DefaultPanelPlacement = PanelPlacementRequest(PanelPosition(1f, .3f), PanelPosition(1f, .3f), true)

class PanelPositionWrites {
    private val pending = mutableMapOf<Boolean, MutableList<String>>()
    fun sent(revision: String, landscape: Boolean) { pending.getOrPut(landscape) { mutableListOf() }.add(revision) }
    fun observed(revision: String, landscape: Boolean): Boolean {
        val revisions = pending.getOrPut(landscape) { mutableListOf() }
        val index = revisions.indexOf(revision)
        if (index < 0) { revisions.clear(); return false }
        revisions.subList(0, index + 1).clear()
        return true
    }
}
