package net.yexe.anicome

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

@Suppress("DEPRECATION")
internal object PlayerViewportReader {
    fun read(root: AccessibilityNodeInfo, width: Int, height: Int): Pair<VideoBounds, String>? {
        if (width <= height) return null
        val surfaces = mutableListOf<VideoBounds>()
        var content: VideoBounds? = null
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 500 || depth > 30) return
            val surface = node.className?.toString() in setOf("android.view.SurfaceView", "android.view.TextureView")
            val container = node.viewIdResourceName == "android:id/content"
            if ((surface || container) && node.refresh() && node.isVisibleToUser) {
                val rect = Rect().also(node::getBoundsInScreen)
                val bounds = VideoBounds(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
                if (bounds.fits(width, height) && bounds.width * bounds.height >= width.toFloat() * height * .25f) {
                    if (surface) surfaces += bounds else content = bounds
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> try { visit(child, depth + 1) } finally { child.recycle() } }
                if (visited >= 500) break
            }
        }
        visit(root, 0)
        return surfaces.maxByOrNull { it.width * it.height }?.let { it to "surface" } ?: content?.let { it to "content" }
    }
}
