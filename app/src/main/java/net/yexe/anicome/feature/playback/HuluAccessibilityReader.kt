package net.yexe.anicome

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

@Suppress("DEPRECATION")
internal object HuluAccessibilityReader {
    private val prefix = "${StreamingService.HULU.packageName}:id/"
    @Volatile var stage = "idle"
        private set
    fun read(root: AccessibilityNodeInfo): HuluScreenPlayback? {
        if (root.packageName?.toString() != StreamingService.HULU.packageName) return null
        return root.withNodes("mini_player_layer") { layers ->
            layers.firstNotNullOfOrNull { layer ->
                layer.withNodes("view_player_controller") controls@ { controllers ->
                    val player = controllers.firstOrNull { it.isVisibleToUser && boundsVisible(it) } ?: return@controls null
                    val series = layer.text("text_title") ?: return@controls null
                    val subtitle = layer.text("sub_text_title") ?: return@controls null
                    val detail = episodeDetail(layer, subtitle) ?: matchingBackgroundDetail(root, series, subtitle) ?: return@controls null
                    HuluScreenParser.parse(HuluScreenSnapshot(series, subtitle, detail,
                        player.text("text_current_time").orEmpty(), player.text("text_duration").orEmpty()))
                }
            }
        }
    }
    private fun episodeDetail(layer: AccessibilityNodeInfo, subtitle: String): String? = layer.withNodes("text_list_title") { titles ->
        titles.filter { it.text?.toString()?.trim() == subtitle }.firstNotNullOfOrNull { title ->
            var parent = title.parent
            var result: String? = null
            var depth = 0
            while (parent != null && depth++ < 3 && result == null) {
                val row = parent
                result = row.withNodes("text_list_title") { if (it.size == 1) row.text("text_list_tips") else null }
                val next = if (result == null) parent.parent else null
                parent.recycle()
                parent = next
            }
            parent?.recycle()
            result
        }
    }
    private fun matchingBackgroundDetail(root: AccessibilityNodeInfo, series: String, subtitle: String): String? {
        if (root.text("series_title") != series) return null
        return root.withNodes("viewing_episode_root") { rows ->
            rows.firstNotNullOfOrNull { row -> row.text("sub_title")?.takeIf { row.text("title") == subtitle } }
        }
    }
    private fun boundsVisible(node: AccessibilityNodeInfo) = Rect().let { node.getBoundsInScreen(it); it.width() > 0 && it.height() > 0 }
    private fun AccessibilityNodeInfo.text(id: String): String? = withNodes(id) { nodes -> nodes.firstNotNullOfOrNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) } }
    private inline fun <T> AccessibilityNodeInfo.withNodes(id: String, block: (List<AccessibilityNodeInfo>) -> T): T {
        stage = "$id:find"
        val nodes = findAccessibilityNodeInfosByViewId(prefix + id)
        return try { block(nodes.filter { stage = "$id:refresh"; it.refresh() }) } finally { nodes.forEach { it.recycle() } }
    }
}
