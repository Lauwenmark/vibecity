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
    val rotation: Int = 0,
) {
    val originY = 85 + max(0.0, zoom - 2.0) * 128
    val tw = 16 * zoom
    val th = 8 * zoom
    val viewWidth = if (rotation % 2 == 0) width else height
    val viewHeight = if (rotation % 2 == 0) height else width
    val originX = viewHeight * tw + 70

    fun view(
        x: Int,
        y: Int,
    ): Point =
        when (rotation) {
            1 -> Point(height - 1 - y, x)
            2 -> Point(width - 1 - x, height - 1 - y)
            3 -> Point(y, width - 1 - x)
            else -> Point(x, y)
        }

    fun worldIndex(
        x: Int,
        y: Int,
    ): Int =
        when (rotation) {
            1 -> (height - 1 - x) * width + y
            2 -> (height - 1 - y) * width + width - 1 - x
            3 -> x * width + width - 1 - y
            else -> y * width + x
        }

    // Include maximum elevation, roofs, labels, strokes and ground side faces.
    val above = ceil(128 * zoom + 8).toInt()
    val below = ceil(32 * zoom + 8).toInt()
    val side = ceil(2 * tw + 4).toInt()

    fun screenX(
        x: Int,
        y: Int,
    ): Int = view(x, y).let { (originX + (it.x - it.y) * tw).toInt() }

    fun screenY(
        x: Int,
        y: Int,
        elevation: Int,
    ): Int = view(x, y).let { (originY + (it.x + it.y) * th - elevation * 3 * zoom).toInt() }

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
    ): Int = view(x, y).let { (originY + (it.x + it.y) * th - surface.centerHeight * 3 * zoom).toInt() }

    /** Every shared vertex projects to the same pixel from either adjacent tile. */
    fun polygon(
        city: City,
        index: Int,
    ): Polygon {
        val x = index % width
        val y = index / width
        val s = city.surface(index)
        if (city.terrain == null) return diamond(x, y, s.minimum)

        val at = view(x, y)
        val heights = intArrayOf(s.north, s.east, s.south, s.west)
        val xs = intArrayOf(at.x, at.x + 1, at.x + 1, at.x)
        val ys = intArrayOf(at.y, at.y, at.y + 1, at.y + 1)
        return Polygon(
            IntArray(4) { (originX + (xs[it] - ys[it]) * tw).toInt() },
            IntArray(4) { (originY + (xs[it] + ys[it]) * th - th - heights[Math.floorMod(it - rotation, 4)] * 3 * zoom).toInt() },
            4,
        )
    }

    /** Covers a changed cell and the connecting strokes drawn by its neighboring road/rail cells. */
    fun visualBounds(index: Int): Rectangle {
        val sx = screenX(index % width, index / width)
        val at = view(index % width, index / width)
        val baseline = originY + (at.x + at.y) * th
        return Rectangle(sx - side, floor(baseline - above).toInt(), 2 * side + 1, above + below + 2)
    }

    /** At most a handful of diagonals can cover a point, for terrain heights in 0..12. */
    fun pick(
        city: City,
        point: Point,
    ): Int {
        val first = max(0, floor((point.y - originY - th - 2) / th).toInt())
        val last = min(width + height - 2, ceil((point.y - originY + 36 * zoom + th + 2) / th).toInt())
        for (diagonal in last downTo first) {
            val startY = max(max(0, diagonal - viewWidth + 1), ceil((originX + diagonal * tw - point.x - tw - 2) / (2 * tw)).toInt())
            val endY = min(min(viewHeight - 1, diagonal), floor((originX + diagonal * tw - point.x + tw + 2) / (2 * tw)).toInt())
            for (y in endY downTo startY) {
                val x = diagonal - y
                val i = worldIndex(x, y)
                if (polygon(city, i).contains(point)) return i
            }
        }
        return -1
    }
}
