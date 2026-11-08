package city.client

import city.core.*
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import kotlin.math.*

/** Decorative traffic derived only from ROAD counts. Geometry is rebuilt on snapshots/zoom, not frames. */
internal class RoadTrafficAnimation {
    internal data class Segment(
        val from: Int,
        val to: Int,
        val ax: Double,
        val ay: Double,
        val bx: Double,
        val by: Double,
        val count: Int,
        val speed: Double,
        val bounds: Rectangle,
        val visibleRoad: Shape,
    )

    internal var segments: List<Segment> = emptyList()
        private set
    private var zoom = 1.0
    val isEmpty: Boolean get() = segments.isEmpty()

    fun update(
        city: City?,
        hour: Int,
        zoom: Double,
        catalog: Map<String, BuildingDef> = emptyMap(),
    ) {
        this.zoom = zoom
        val mobility = city?.mobility
        if (city == null || mobility == null) {
            segments = emptyList()
            return
        }
        val volumes = IntArray(city.tiles.size)
        for (load in mobility.aggregateLoads()) {
            if (load.mode == TravelMode.ROAD) volumes[load.cell] = if (hour in 0..23) load.hours[hour] else load.hours.max()
        }
        if (volumes.none { it > 0 }) {
            segments = emptyList()
            return
        }
        val projection = CityProjection(city.width, city.height, zoom)
        // Spatially indexed building silhouettes prevent cars being painted over foreground roofs.
        val buildings = mutableMapOf<Pair<Int, Int>, MutableList<Shape>>()
        city.tiles.forEachIndexed { i, tile ->
            val sx = projection.screenX(i % city.width, i / city.width)
            val sy = projection.centerY(i % city.width, i / city.width, city.surface(i))
            val geometry = buildingGeometry(tile, catalog[tile.building], zoom) ?: return@forEachIndexed
            val (w, h, z) = geometry
            val shape = Polygon(intArrayOf(sx - w, sx, sx + w, sx + w, sx, sx - w), intArrayOf(sy - h - z, sy - 2 * h - z, sy - h - z, sy - h, sy, sy - h), 6)
            val bounds = shape.bounds
            for (y in Math.floorDiv(bounds.y, 64)..Math.floorDiv(bounds.y + bounds.height, 64)) {
                for (x in Math.floorDiv(bounds.x, 64)..Math.floorDiv(bounds.x + bounds.width, 64)) buildings.getOrPut(x to y) { mutableListOf() }.add(shape)
            }
        }
        segments =
            buildList {
                city.tiles.forEachIndexed { i, tile ->
                    if (!tile.road || tile.fire > 0 || volumes[i] == 0) return@forEachIndexed
                    for (n in city.neighbors(i)) {
                        if (n <= i || !city.tiles[n].road || city.tiles[n].fire > 0 || !city.transportConnects(i, n)) continue
                        val volume = min(volumes[i], volumes[n])
                        if (volume == 0) continue
                        val ratio = volume.toDouble() / TravelMode.ROAD.capacity
                        val ax = projection.screenX(i % city.width, i / city.width).toDouble()
                        val ay = projection.centerY(i % city.width, i / city.width, city.surface(i)).toDouble()
                        val bx = projection.screenX(n % city.width, n / city.width).toDouble()
                        val by = projection.centerY(n % city.width, n / city.width, city.surface(n)).toDouble()
                        val bounds = Rectangle(floor(min(ax, bx)).toInt(), floor(min(ay, by)).toInt(), ceil(abs(bx - ax)).toInt() + 1, ceil(abs(by - ay)).toInt() + 1).apply { grow(ceil(6 * zoom).toInt(), ceil(6 * zoom).toInt()) }
                        val visible = Area(bounds)
                        val occluders = mutableSetOf<Shape>()
                        for (y in Math.floorDiv(bounds.y, 64)..Math.floorDiv(bounds.y + bounds.height, 64)) {
                            for (x in Math.floorDiv(bounds.x, 64)..Math.floorDiv(bounds.x + bounds.width, 64)) occluders.addAll(buildings[x to y].orEmpty())
                        }
                        for (shape in occluders) if (shape.intersects(bounds)) visible.subtract(Area(shape))
                        if (!visible.isEmpty) add(Segment(i, n, ax, ay, bx, by, ceil(ratio * 4).toInt().coerceIn(1, 6), .65 / (1 + 3 * ratio.coerceAtMost(3.0)), bounds, visible))
                    }
                }
            }
    }

    fun paint(
        g: Graphics2D,
        clip: Rectangle,
        seconds: Double,
    ) {
        val oldTransform = g.transform
        val oldClip = g.clip
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (segment in segments) {
            if (!segment.bounds.intersects(clip)) continue
            g.transform = oldTransform
            g.clip = oldClip
            g.clip(segment.visibleRoad)
            val dx = segment.bx - segment.ax
            val dy = segment.by - segment.ay
            val length = hypot(dx, dy)
            val nx = -dy / length
            val ny = dx / length
            for (car in 0 until segment.count) {
                val reverse = car % 2 == 1
                val direction = if (reverse) -1 else 1
                val phase = ((seconds * segment.speed + car.toDouble() / segment.count + (segment.from % 17) / 17.0) % 1.0)
                val progress = if (reverse) 1 - phase else phase
                val x = segment.ax + dx * progress + nx * 1.65 * zoom * direction
                val y = segment.ay + dy * progress + ny * 1.65 * zoom * direction
                g.transform =
                    AffineTransform(oldTransform).apply {
                        translate(x, y)
                        rotate(atan2(dy, dx) + if (reverse) Math.PI else 0.0)
                        scale(zoom, zoom)
                    }
                // Narrow cars fit the two lanes; roof, windscreen and lamps remain readable at normal zoom.
                g.color = Color(0x17202a)
                g.fillRoundRect(-3, -2, 6, 4, 2, 2)
                g.color = COLORS[(segment.from + car) % COLORS.size]
                g.fillRoundRect(-3, -1, 6, 2, 1, 1)
                g.color = Color(0xa8d5df)
                g.fillRect(0, -1, 1, 2)
                g.color = Color(0xffefb5)
                g.fillRect(2, -1, 1, 1)
            }
        }
        g.transform = oldTransform
        g.clip = oldClip
    }

    companion object {
        private val COLORS = listOf(0xe8d9af, 0xd96e50, 0x73a8cf, 0xe2bb56, 0xa9bc9c, 0xd8dde0).map(::Color)
    }
}
