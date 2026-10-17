package net.yexe.anicome

data class PanelPosition(val x: Float, val y: Float)
fun settledPanelPosition(x: Float, y: Float, snap: Boolean): PanelPosition {
    val safeX = if (x.isFinite()) x.coerceIn(0f, 1f) else 1f
    val safeY = if (y.isFinite()) y.coerceIn(0f, 1f) else .3f
    return PanelPosition(if (snap) { if (safeX < .5f) 0f else 1f } else safeX, safeY)
}
fun interface PanelAnimation { fun cancel() }
interface PanelAnimator {
    fun start(from: Float, to: Float, duration: Long, frame: (Float) -> Unit, finished: () -> Unit): PanelAnimation
}
class AndroidPanelAnimator : PanelAnimator {
    override fun start(from: Float, to: Float, duration: Long, frame: (Float) -> Unit, finished: () -> Unit): PanelAnimation {
        val animator = android.animation.ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { frame(it.animatedValue as Float) }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) = finished() })
        animator.start()
        return PanelAnimation { animator.cancel() }
    }
}
class PanelSnapMotion(private val animator: PanelAnimator) {
    private var generation = 0L
    private var animation: PanelAnimation? = null
    fun cancel() { generation++; val previous = animation; animation = null; previous?.cancel() }
    fun settle(x: Float, y: Float, snap: Boolean, reduced: Boolean, frame: (PanelPosition) -> Unit, save: (PanelPosition) -> Unit) {
        cancel()
        val current = settledPanelPosition(x, y, false)
        val target = settledPanelPosition(x, y, snap)
        if (reduced || kotlin.math.abs(current.x - target.x) < .001f) { frame(target); save(target); return }
        val ticket = generation
        val running = animator.start(current.x, target.x, 180L, { value ->
            if (ticket == generation && value.isFinite()) frame(PanelPosition(value.coerceIn(0f, 1f), target.y))
        }, {
            if (ticket == generation) { generation++; animation = null; frame(target); save(target) }
        })
        if (ticket == generation) animation = running else running.cancel()
    }
}
