package city.client

import city.core.City
import city.core.surface
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.QuadCurve2D
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** A small concrete distribution pole; cables remain geometry so they follow terrain and connections. */
internal object PowerLineSprites {
    private val pole =
        ImageIO
            .read(requireNotNull(javaClass.getResource("/city/client/power/concrete-pole.png")))
            .getSubimage(372, 26, 280, 1483)

    fun draw(
        g: Graphics2D,
        city: City,
        index: Int,
        projection: CityProjection,
    ) {
        val zoom = projection.zoom
        val x = projection.screenX(index % city.width, index / city.width).toDouble()
        val y = projection.centerY(index % city.width, index / city.width, city.surface(index)).toDouble()
        val graphics = g.create() as Graphics2D
        try {
            graphics.color = Color(0x45413b)
            graphics.stroke = BasicStroke((0.45 * zoom).toFloat().coerceAtLeast(0.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (neighbor in city.neighbors(index)) {
                if (!city.tiles[neighbor].powerLine) continue
                val nx = projection.screenX(neighbor % city.width, neighbor / city.width).toDouble()
                val ny = projection.centerY(neighbor % city.width, neighbor / city.width, city.surface(neighbor)).toDouble()
                for (offset in listOf(-1.025, 1.025)) {
                    val ax = x + offset * zoom
                    val ay = y - 13.85 * zoom
                    val bx = nx + offset * zoom
                    val by = ny - 13.85 * zoom
                    val sag = 0.8 * zoom
                    // Each cell owns half a quadratic span. Both halves meet with identical tangents,
                    // including on slopes, without drawing the same translucent edge twice.
                    graphics.draw(QuadCurve2D.Double(ax, ay, (3 * ax + bx) / 4, (3 * ay + by) / 4 + sag, (ax + bx) / 2, (ay + by) / 2 + sag))
                }
            }
            val height = (15 * zoom).roundToInt().coerceAtLeast(1)
            val width = (height * pole.width.toDouble() / pole.height).roundToInt().coerceAtLeast(1)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(pole, x.roundToInt() - width / 2, y.roundToInt() - height, width, height, null)
        } finally {
            graphics.dispose()
        }
    }
}
