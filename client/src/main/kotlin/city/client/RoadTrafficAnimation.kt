package city.client

import city.core.*
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import kotlin.math.*

/** Decorative traffic derived only from ROAD counts. Geometry is cached independently of traffic volumes and municipal rules. */
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
    ) {
        val cars by lazy {
            List(count) { car ->
                val direction = if (car % 2 == 0) 1 else -1
                CarArtwork((bx - ax) * direction, (by - ay) * direction, (from + car) % 6)
            }
        }
    }

    internal var segments: List<Segment> = emptyList()
        private set
    private var zoom = 1.0
    private var source: City? = null
    private var geometryRotation = 0
    private var geometryLayer = Layer.SURFACE
    private var geometryCatalog: Map<String, BuildingDef> = emptyMap()

    // Only the current scenery is retained; entries are created for active road edges.
    private val geometry = mutableMapOf<Long, Segment?>()
    private var buildingIndex: Map<Pair<Int, Int>, List<Shape>>? = null
    private var roadLoads: List<TrafficLoad> = emptyList()
    val isEmpty: Boolean get() = segments.isEmpty()

    fun update(
        city: City?,
        hour: Int,
        zoom: Double,
        catalog: Map<String, BuildingDef> = emptyMap(),
        rotation: Int = 0,
        layer: Layer = Layer.SURFACE,
    ) {
        val previous = source
        if (city == null || previous == null || !sameScenery(previous, city) || this.zoom != zoom ||
            geometryRotation != rotation || geometryLayer != layer || geometryCatalog != catalog
        ) {
            geometry.clear()
            buildingIndex = null
        }
        this.zoom = zoom
        geometryRotation = rotation
        geometryLayer = layer
        geometryCatalog = catalog
        if (city == null || previous?.seed != city.seed || previous.width != city.width || previous.height != city.height || city.revision < previous.revision) {
            roadLoads = emptyList()
        }
        if (city == null) {
            source = null
            segments = emptyList()
            return
        }
        // Only decorative counts survive invalidation; planned/actual citizen paths remain invalid.
        val mobility = city.mobility
        if (mobility != null && (previous?.mobility !== mobility || roadLoads.isEmpty())) {
            roadLoads = mobility.aggregateLoads().filter { it.mode == TravelMode.ROAD }
        }
        roadLoads = roadLoads.filter { city.tiles[it.cell].road && city.tiles[it.cell].fire == 0 }
        source = city
        val volumes = IntArray(city.tiles.size)
        for (load in roadLoads) {
            volumes[load.cell] = if (hour in 0..23) load.hours[hour] else load.hours.max()
        }
        if (volumes.none { it > 0 }) {
            segments = emptyList()
            return
        }
        val projection = CityProjection(city.width, city.height, zoom, rotation)
        val buildings by lazy {
            buildingIndex ?: indexBuildings(city, catalog, projection, zoom, layer).also { buildingIndex = it }
        }
        segments =
            buildList {
                city.tiles.forEachIndexed { i, tile ->
                    if (!tile.road || tile.fire > 0 || volumes[i] == 0) return@forEachIndexed
                    for (n in city.neighbors(i)) {
                        if (n <= i || !city.tiles[n].road || city.tiles[n].fire > 0 || !city.transportConnects(i, n)) continue
                        val volume = min(volumes[i], volumes[n])
                        if (volume == 0) continue
                        val ratio = volume.toDouble() / (city.trafficRules.roadEntriesPerMinute * 60)
                        val count = ceil(ratio * 4).toInt().coerceIn(1, 6)
                        val speed = .65 / (1 + 3 * ratio.coerceAtMost(3.0))
                        val key = i.toLong() * city.tiles.size + n
                        if (key !in geometry) {
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
                            geometry[key] = if (visible.isEmpty) null else Segment(i, n, ax, ay, bx, by, count, speed, bounds, visible)
                        }
                        geometry[key]?.let { cached ->
                            val segment = if (cached.count == count && cached.speed == speed) cached else cached.copy(count = count, speed = speed)
                            geometry[key] = segment
                            add(segment)
                        }
                    }
                }
            }
    }

    /** Compare only inputs used by projection, connectivity and building silhouettes. */
    private fun sameScenery(
        previous: City,
        city: City,
    ): Boolean =
        previous.width == city.width && previous.height == city.height && previous.seed == city.seed &&
            previous.terrain == city.terrain &&
            (
                previous.tiles === city.tiles ||
                    city.tiles.indices.all { i ->
                        val a = previous.tiles[i]
                        val b = city.tiles[i]
                        a.road == b.road && (a.fire > 0) == (b.fire > 0) && a.elevation == b.elevation &&
                            a.building == b.building && a.anchor == b.anchor && a.zone == b.zone && a.occupants == b.occupants
                    }
            )

    private fun indexBuildings(
        city: City,
        catalog: Map<String, BuildingDef>,
        projection: CityProjection,
        zoom: Double,
        layer: Layer,
    ): Map<Pair<Int, Int>, List<Shape>> {
        // Spatially indexed building silhouettes prevent cars being painted over foreground roofs.
        val buildings = mutableMapOf<Pair<Int, Int>, MutableList<Shape>>()
        city.tiles.forEachIndexed { i, tile ->
            val shape =
                if (layer == Layer.SURFACE && tile.building in BuildingSprites.allIds) {
                    BuildingSprites.silhouette(city, i, catalog[tile.building]?.size ?: 1, projection) ?: return@forEachIndexed
                } else {
                    val sx = projection.screenX(i % city.width, i / city.width)
                    val geometry = buildingGeometry(tile, catalog[tile.building], zoom) ?: return@forEachIndexed
                    val sy = projection.centerY(i % city.width, i / city.width, city.surface(i)) + geometry.depth
                    val (w, h, z) = geometry
                    Polygon(intArrayOf(sx - w, sx, sx + w, sx + w, sx, sx - w), intArrayOf(sy - h - z, sy - 2 * h - z, sy - h - z, sy - h, sy, sy - h), 6)
                }
            val bounds = shape.bounds
            for (y in Math.floorDiv(bounds.y, 64)..Math.floorDiv(bounds.y + bounds.height, 64)) {
                for (x in Math.floorDiv(bounds.x, 64)..Math.floorDiv(bounds.x + bounds.width, 64)) buildings.getOrPut(x to y) { mutableListOf() }.add(shape)
            }
        }
        return buildings
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
                        scale(zoom, zoom)
                    }
                segment.cars[car].paint(g)
            }
        }
        g.transform = oldTransform
        g.clip = oldClip
    }
}
