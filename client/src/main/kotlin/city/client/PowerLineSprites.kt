package city.client

import city.core.BuildingDef
import city.core.City
import city.core.surface
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Point2D
import java.awt.geom.QuadCurve2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A small concrete distribution pole; cables remain geometry so they follow terrain and connections. */
internal object PowerLineSprites {
    private val pole =
        ImageIO
            .read(requireNotNull(javaClass.getResource("/city/client/power/concrete-pole.png")))
            .getSubimage(372, 26, 280, 1483)

    const val HEIGHT = 12.0
    private const val ATTACHMENT_HEIGHT = HEIGHT * 13.85 / 15
    private const val INSULATOR_OFFSET = HEIGHT * 1.025 / 15

    /** Transport poles occupy opposite corners, outside both straight corridors and junctions. */
    fun bases(
        city: City,
        index: Int,
        projection: CityProjection,
    ): List<Point2D.Double> {
        val tile = city.tiles[index]
        if (!tile.road && !tile.rail) {
            return listOf(Point2D.Double(projection.screenX(index % city.width, index / city.width).toDouble(), projection.centerY(index % city.width, index / city.width, city.surface(index)).toDouble()))
        }
        val polygon = projection.polygon(city, index)
        // Both points lie on the terrain's north/south triangle diagonal. Interpolate
        // the actual projected vertices, including their elevations and camera rotation.
        val north = Math.floorMod(projection.rotation, 4)
        val south = (north + 2) % 4
        return listOf(0.08, 0.92).map { fraction ->
            Point2D.Double(
                polygon.xpoints[north] * (1 - fraction) + polygon.xpoints[south] * fraction,
                polygon.ypoints[north] * (1 - fraction) + polygon.ypoints[south] * fraction,
            )
        }
    }

    /** One drop per neighboring building, even when two adjacent footprint cells touch the line. */
    fun suppliedBuildings(
        city: City,
        index: Int,
    ): List<Int> =
        city
            .neighbors(index)
            .filter { neighbor ->
                val tile = city.tiles[neighbor]
                tile.powered && (tile.building != null || tile.occupants > 0)
            }.distinctBy { neighbor ->
                val tile = city.tiles[neighbor]
                if (tile.building != null && tile.anchor in city.tiles.indices) tile.anchor else neighbor
            }

    fun draw(
        g: Graphics2D,
        city: City,
        index: Int,
        projection: CityProjection,
        catalog: Map<String, BuildingDef> = emptyMap(),
    ) {
        // Rasterize curves independently of the scene clip: Java2D can otherwise
        // round a clipped quadratic differently at a cache-chunk boundary.
        val zoom = projection.zoom
        val reach = ceil(64 * zoom + 4).toInt()
        val x = projection.screenX(index % city.width, index / city.width) - reach
        val y = projection.centerY(index % city.width, index / city.width, city.surface(index)) - reach
        val image = BufferedImage(2 * reach, 2 * reach, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.translate(-x, -y)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            drawVectors(graphics, city, index, projection, catalog)
        } finally {
            graphics.dispose()
        }
        g.drawImage(image, x, y, null)
    }

    private fun drawVectors(
        g: Graphics2D,
        city: City,
        index: Int,
        projection: CityProjection,
        catalog: Map<String, BuildingDef>,
    ) {
        val zoom = projection.zoom
        val supports = bases(city, index, projection)
        val graphics = g.create() as Graphics2D
        try {
            graphics.color = Color(0x45413b)
            graphics.stroke = BasicStroke((0.4 * zoom).toFloat().coerceAtLeast(0.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            fun span(
                a: Point2D.Double,
                b: Point2D.Double,
                half: Boolean,
            ) {
                for (offset in listOf(-INSULATOR_OFFSET, INSULATOR_OFFSET)) {
                    val ax = a.x + offset * zoom
                    val ay = a.y - ATTACHMENT_HEIGHT * zoom
                    val bx = b.x + offset * zoom
                    val by = b.y - ATTACHMENT_HEIGHT * zoom
                    val sag = 0.6 * zoom
                    if (half) {
                        graphics.draw(QuadCurve2D.Double(ax, ay, (3 * ax + bx) / 4, (3 * ay + by) / 4 + sag, (ax + bx) / 2, (ay + by) / 2 + sag))
                    } else {
                        graphics.draw(QuadCurve2D.Double(ax, ay, (ax + bx) / 2, (ay + by) / 2 + 2 * sag, bx, by))
                    }
                }
            }
            if (supports.size == 2) span(supports[0], supports[1], half = false)
            for (neighbor in city.neighbors(index)) {
                if (!city.tiles[neighbor].powerLine) continue
                // Canonical ordering makes both cells select exactly the same span,
                // even when several pairs are equidistant on flat terrain.
                val first = bases(city, minOf(index, neighbor), projection)
                val second = bases(city, maxOf(index, neighbor), projection)
                val pair = first.flatMap { a -> second.map { b -> a to b } }.minBy { (a, b) -> a.distanceSq(b) }
                if (index < neighbor) span(pair.first, pair.second, half = true) else span(pair.second, pair.first, half = true)
            }
            for (neighbor in suppliedBuildings(city, index)) {
                val tile = city.tiles[neighbor]
                val nx = projection.screenX(neighbor % city.width, neighbor / city.width).toDouble()
                val ny = projection.centerY(neighbor % city.width, neighbor / city.width, city.surface(neighbor)).toDouble()
                val support = supports.minBy { it.distanceSq(nx, ny) }
                val geometry = buildingGeometry(tile, catalog[tile.building], zoom)
                val height = minOf(6 * zoom, (geometry?.height ?: (6 * zoom).roundToInt()) * 0.65)
                val bx = nx + (support.x - nx) * 0.35
                val by = ny + (support.y - ny) * 0.35 - height
                val ay = support.y - ATTACHMENT_HEIGHT * zoom
                graphics.draw(QuadCurve2D.Double(support.x, ay, (support.x + bx) / 2, (ay + by) / 2 + zoom, bx, by))
            }
            val height = (HEIGHT * zoom).roundToInt().coerceAtLeast(1)
            val width = (height * pole.width.toDouble() / pole.height).roundToInt().coerceAtLeast(1)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            for (support in supports.sortedBy { it.y }) {
                graphics.drawImage(pole, support.x.roundToInt() - width / 2, support.y.roundToInt() - height, width, height, null)
            }
        } finally {
            graphics.dispose()
        }
    }
}
