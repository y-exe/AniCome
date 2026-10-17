package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class PosterGridTest {
    @Test fun adjacentRowsTravelInOppositeDirections() {
        val grid = PosterGrid(420, 900, 104, 156, 6, 12)
        assertTrue(grid.x(4, 0, .11f) < grid.x(4, 0, .10f))
        assertTrue(grid.x(4, 1, .11f) > grid.x(4, 1, .10f))
    }
    @Test fun fullCycleReturnsEachImageToItsOriginalPosition() {
        val grid = PosterGrid(900, 420, 104, 156, 6, 7)
        repeat(grid.rows) { row -> repeat(grid.columns) { column ->
            assertEquals(grid.x(column, row, 0f), grid.x(column, row, 1f))
        } }
    }
    @Test fun everyPhaseCoversRotatedViewportOnPhonesAndTablets() {
        for ((width, height) in listOf(420 to 900, 900 to 420, 1600 to 2560, 2560 to 1600)) {
            for (count in listOf(1, 4, 12, 24)) {
                val grid = PosterGrid(width, height, 104, 156, 6, count)
                val left = (width - grid.planeWidth) / 2f
                val top = (height - grid.planeHeight) / 2f
                assertTrue(grid.y(0) <= top)
                assertTrue(grid.y(grid.rows - 1) + grid.tileHeight >= top + grid.planeHeight)
                repeat(201) { frame ->
                    repeat(2) { row ->
                        val xs = (0 until grid.columns).map { grid.x(it, row, frame / 200f) }.sorted()
                        assertTrue(xs.first() <= left + 1)
                        assertTrue(xs.last() + grid.tileWidth >= left + grid.planeWidth)
                        xs.zipWithNext().forEach { (a, b) -> assertTrue(b - a - grid.tileWidth <= 7) }
                    }
                }
            }
        }
    }
}
