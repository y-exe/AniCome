package net.yexe.anicome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun fits(width: Int, height: Int) = left >= 0 && top >= 0 && right <= width && bottom <= height && this.width > 0 && this.height > 0
    fun widescreen(): VideoBounds {
        val w = minOf(width, height * 16f / 9f)
        val h = w * 9f / 16f
        val x = left + (width - w) / 2
        val y = top + (height - h) / 2
        return VideoBounds(x, y, x + w, y + h)
    }
}

data class PlayerViewport(val service: StreamingService, val displayWidth: Int, val displayHeight: Int,
    val bounds: VideoBounds, val source: String, val observedAt: Long)

class PlayerViewportRepository(private val now: () -> Long) {
    private val mutable = MutableStateFlow<PlayerViewport?>(null)
    val state = mutable.asStateFlow()
    fun observe(service: StreamingService, width: Int, height: Int, bounds: VideoBounds, source: String) {
        if (width <= height || !bounds.fits(width, height)) { clear(); return }
        mutable.value = PlayerViewport(service, width, height, bounds.widescreen(), source, now())
    }
    fun resolve(service: StreamingService, width: Int, height: Int, cutoutLeft: Int = 0, cutoutRight: Int = 0): VideoBounds? {
        mutable.value?.takeIf {
            it.service == service && it.displayWidth == width && it.displayHeight == height && width > height && now() - it.observedAt in 0..4000
        }?.bounds?.let { return it }
        if (service != StreamingService.U_NEXT || width <= height || height <= 0) return null
        val left = cutoutLeft.coerceAtLeast(0)
        val right = width - cutoutRight.coerceAtLeast(0)
        if (left >= right) return null
        return VideoBounds(left.toFloat(), 0f, right.toFloat(), height.toFloat()).widescreen()
    }
    fun clear() { mutable.value = null }
}
