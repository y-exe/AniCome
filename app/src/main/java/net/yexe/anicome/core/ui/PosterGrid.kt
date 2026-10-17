package net.yexe.anicome

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

 
internal class PosterGrid(width: Int, height: Int, val tileWidth: Int, val tileHeight: Int, gap: Int, itemCount: Int) {
    private val angle = Math.toRadians(12.0)
    val planeWidth = ceil(width * cos(angle) + height * sin(angle)).toInt()
    val planeHeight = ceil(height * cos(angle) + width * sin(angle)).toInt()
    val stepX = tileWidth + gap
    val stepY = tileHeight + gap
    val columns = ceil((planeWidth.toDouble() / stepX + 2) / itemCount).toInt() * itemCount
    val rows = ceil(planeHeight.toDouble() / stepY).toInt() + 2
    private val left = (width - planeWidth) / 2f
    private val top = (height - planeHeight) / 2f - stepY
    val period = columns * stepX

    fun x(column: Int, row: Int, phase: Float): Int {
        val direction = if (row % 2 == 0) -1 else 1
        val position = column * stepX + direction * phase.toDouble() * period
        val wrapped = ((position % period) + period) % period
        return (left - stepX + wrapped).roundToInt()
    }
    fun y(row: Int) = (top + row * stepY).roundToInt()
}
