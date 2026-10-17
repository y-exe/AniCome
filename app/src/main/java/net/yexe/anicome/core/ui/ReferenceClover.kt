package net.yexe.anicome

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.*

val ReferenceClover: Shape = object : Shape {
    private val polygon = run {
        val vertices = ArrayList<Float>()
        val rounds = ArrayList<CornerRounding>()
        for (repeat in 0 until 8) {
            val angle = repeat * PI / 4
            listOf(0f to -.464f, .258f to -.601f).forEachIndexed { index, (x, y) ->
                vertices += (.5 + x * cos(angle) - y * sin(angle)).toFloat()
                vertices += (.5 + x * sin(angle) + y * cos(angle)).toFloat()
                rounds += CornerRounding(if (index == 0) 0f else .209f)
            }
        }
        RoundedPolygon(vertices.toFloatArray(), perVertexRounding = rounds, centerX = .5f, centerY = .5f).normalized()
    }
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        polygon.cubics.forEachIndexed { index, c ->
            if (index == 0) path.moveTo(c.anchor0X * size.width, c.anchor0Y * size.height)
            path.cubicTo(c.control0X * size.width, c.control0Y * size.height, c.control1X * size.width, c.control1Y * size.height, c.anchor1X * size.width, c.anchor1Y * size.height)
        }
        path.close()
        return Outline.Generic(path)
    }
}
