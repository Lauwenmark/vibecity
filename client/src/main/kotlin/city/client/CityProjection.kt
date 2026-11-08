package city.client

import city.core.*
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import kotlin.math.*

/** Shared integer geometry for drawing and picking, independent of the last paint or viewport. */
internal class CityProjection(
    private val width: Int,
    private val height: Int,
    val zoom: Double,
) {
    val tw = 16 * zoom
    val th = 8 * zoom
    val originX = height * tw + 70

    // Include maximum elevation, roofs, labels, strokes and ground side faces.
    val above = ceil(128 * zoom + 8).toInt()
    val below = ceil(32 * zoom + 8).toInt()
    val side = ceil(2 * tw + 4).toInt()

    fun screenX(
        x: Int,
        y: Int,
    ): Int = (originX + (x - y) * tw).toInt()

    fun screenY(
        x: Int,
        y: Int,
        elevation: Int,
    ): Int = (85 + (x + y) * th - elevation * 3 * zoom).toInt()

    fun diamond(
        x: Int,
        y: Int,
        elevation: Int,
    ): Polygon {
        val sx = screenX(x, y)
        val sy = screenY(x, y, elevation)
        val w = tw.roundToInt()
        val h = th.roundToInt()
        return Polygon(intArrayOf(sx, sx + w, sx, sx - w), intArrayOf(sy - h, sy, sy + h, sy), 4)
    }

    fun centerY(
        x: Int,
        y: Int,
        surface: Surface,
    ): Int = (85 + (x + y) * th - surface.centerHeight * 3 * zoom).toInt()

    /** Every shared vertex projects to the same pixel from either adjacent tile. */
    fun polygon(
        city: City,
        index: Int,
    ): Polygon {
        val x = index % width
        val y = index / width
        val s = city.surface(index)
        if (city.terrain == null) return diamond(x, y, s.minimum)

        fun vx(
            px: Int,
            py: Int,
        ) = (originX + (px - py) * tw).toInt()

        fun vy(
            px: Int,
            py: Int,
            z: Int,
        ) = (85 + (px + py) * th - th - z * 3 * zoom).toInt()
        return Polygon(
            intArrayOf(vx(x, y), vx(x + 1, y), vx(x + 1, y + 1), vx(x, y + 1)),
            intArrayOf(vy(x, y, s.north), vy(x + 1, y, s.east), vy(x + 1, y + 1, s.south), vy(x, y + 1, s.west)),
            4,
        )
    }

    /** Covers a changed cell and the connecting strokes drawn by its neighboring road/rail cells. */
    fun visualBounds(index: Int): Rectangle {
        val sx = screenX(index % width, index / width)
        val baseline = 85 + (index % width + index / width) * th
        return Rectangle(sx - side, floor(baseline - above).toInt(), 2 * side + 1, above + below + 2)
    }

    /** At most a handful of diagonals can cover a point, for terrain heights in 0..12. */
    fun pick(
        city: City,
        point: Point,
    ): Int {
        val first = max(0, floor((point.y - 85 - th - 2) / th).toInt())
        val last = min(width + height - 2, ceil((point.y - 85 + 36 * zoom + th + 2) / th).toInt())
        for (diagonal in last downTo first) {
            val startY = max(max(0, diagonal - width + 1), ceil((originX + diagonal * tw - point.x - tw - 2) / (2 * tw)).toInt())
            val endY = min(min(height - 1, diagonal), floor((originX + diagonal * tw - point.x + tw + 2) / (2 * tw)).toInt())
            for (y in endY downTo startY) {
                val x = diagonal - y
                val i = y * width + x
                if (polygon(city, i).contains(point)) return i
            }
        }
        return -1
    }
}
