package city.client

import city.core.*
import java.awt.*
import java.awt.image.BufferedImage
import kotlin.math.*

/** Pure scene drawing: only cells whose conservative visual bounds meet the clip are visited. */
internal class CityRenderer(
    private val city: City,
    private val catalog: Map<String, BuildingDef>,
    private val layer: Layer,
    private val zoom: Double,
    private val trafficHour: Int = -1,
    private val rotation: Int = 0,
) {
    private val hourlyTraffic =
        if (layer == Layer.TRAFFIC && trafficHour in 0..23) {
            IntArray(city.tiles.size).also { values ->
                city.mobility?.aggregateLoads()?.forEach { load ->
                    values[load.cell] = max(values[load.cell], (load.hours[trafficHour].toLong() * 100 / load.hourlyCapacity(city)).coerceAtMost(100).toInt())
                }
            }
        } else {
            null
        }

    private data class Occluder(
        val front: Point,
        val index: Int,
        val bounds: Rectangle,
        val shape: Shape?,
    )

    fun render(
        g: Graphics2D,
        clip: Rectangle,
    ) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val projection = CityProjection(city.width, city.height, zoom, rotation)
        val tw = projection.tw
        val th = projection.th
        val w = tw.roundToInt()
        val h = th.roundToInt()
        val poles = mutableListOf<Int>()
        val occluders = mutableListOf<Occluder>()
        val first = max(0, floor((clip.y - projection.originY - projection.below) / th).toInt())
        val last = min(city.width + city.height - 2, ceil((clip.maxY - projection.originY + projection.above) / th).toInt())
        for (diagonal in first..last) {
            val startY = max(max(0, diagonal - projection.viewWidth + 1), ceil((projection.originX + diagonal * tw - clip.maxX - projection.side) / (2 * tw)).toInt())
            val endY = min(min(projection.viewHeight - 1, diagonal), floor((projection.originX + diagonal * tw - clip.x + projection.side) / (2 * tw)).toInt())
            for (vy in startY..endY) {
                val vx = diagonal - vy
                val i = projection.worldIndex(vx, vy)
                val x = i % city.width
                val y = i / city.width
                val t = city.tiles[i]
                val sx = projection.screenX(x, y)
                val surface = city.surface(i)
                val sy = projection.centerY(x, y, surface)
                val diamond = projection.polygon(city, i)
                if (layer == Layer.SURFACE) {
                    if (t.powerLine) poles.add(i)
                    if (t.building in BuildingSprites.allIds) {
                        BuildingSprites.placement(city, i, catalog[t.building]?.size ?: 1, projection)?.let { bounds ->
                            occluders.add(Occluder(Point(vx, vy), i, bounds, null))
                        }
                    } else {
                        buildingGeometry(t, catalog[t.building], zoom)?.let { (bw, bh, bz) ->
                            val bottom = sy + bh
                            val shape = Polygon(intArrayOf(sx - bw, sx, sx + bw, sx + bw, sx, sx - bw), intArrayOf(bottom - bh - bz, bottom - 2 * bh - bz, bottom - bh - bz, bottom - bh, bottom, bottom - bh), 6)
                            occluders.add(Occluder(Point(vx, vy), i, shape.bounds, shape))
                        }
                    }
                    if (t.trees) {
                        val crown = Polygon(intArrayOf(sx - (6 * zoom).toInt(), sx, sx + (6 * zoom).toInt()), intArrayOf(sy - 3, sy - (19 * zoom).toInt(), sy - 3), 3)
                        val trunk = Rectangle(sx - 1, sy - 6, 2, 7)
                        occluders.add(Occluder(Point(vx, vy), i, crown.bounds, crown))
                        occluders.add(Occluder(Point(vx, vy), i, trunk, trunk))
                    }
                }
                if (vx == projection.viewWidth - 1 || vy == projection.viewHeight - 1) {
                    g.color = Color(0x344b3e)
                    val bottom = (projection.originY + (vx + vy) * th + 12 * zoom).toInt()
                    if (vx == projection.viewWidth - 1) g.fillPolygon(intArrayOf(diamond.xpoints[1], diamond.xpoints[2], diamond.xpoints[2], diamond.xpoints[1]), intArrayOf(diamond.ypoints[1], diamond.ypoints[2], bottom + h, bottom), 4)
                    if (vy == projection.viewHeight - 1) g.fillPolygon(intArrayOf(diamond.xpoints[2], diamond.xpoints[3], diamond.xpoints[3], diamond.xpoints[2]), intArrayOf(diamond.ypoints[2], diamond.ypoints[3], bottom, bottom + h), 4)
                }
                g.color = tileColor(t, i)
                g.fillPolygon(diamond)
                if (!surface.flat && (layer == Layer.SURFACE || layer == Layer.ZONES)) {
                    // Two triangles share the north/south diagonal, matching the surface geometry.
                    fun shade(
                        a: Int,
                        b: Int,
                        c: Int,
                        gradient: Int,
                    ) {
                        g.color = if (gradient >= 0) Color(255, 255, 255, (gradient * 18).coerceAtMost(60)) else Color(0, 0, 0, (-gradient * 18).coerceAtMost(60))
                        g.fillPolygon(intArrayOf(diamond.xpoints[a], diamond.xpoints[b], diamond.xpoints[c]), intArrayOf(diamond.ypoints[a], diamond.ypoints[b], diamond.ypoints[c]), 3)
                    }
                    shade(0, 1, 2, surface.east - surface.north)
                    shade(0, 2, 3, surface.north - surface.west)
                }
                g.color = Color(255, 255, 255, 17)
                g.drawPolygon(diamond)
                if (layer == Layer.WATER && (t.building != null || t.occupants > 0)) {
                    // Preserve the water-supply color and draw networks above the footprint.
                    g.color = Color(0xffad52)
                    g.stroke = BasicStroke((1.25 * zoom).toFloat().coerceAtLeast(1f))
                    g.drawPolygon(diamond)
                    g.stroke = BasicStroke(1f)
                }
                if (t.water && layer == Layer.SURFACE) {
                    g.color = Color(0x71a8b8)
                    g.drawLine(sx - w / 2, sy, sx, sy + h / 3)
                }
                if (t.road || t.rail) {
                    if (layer == Layer.SURFACE) {
                        TransportSprites.draw(g, diamond, city, i, rotation)
                    } else {
                        g.color = if (t.road) Color(0x47535a) else Color(0x938d80)
                        g.stroke = BasicStroke((if (t.road) 9 else 3) * zoom.toFloat())
                        for (n in city.neighbors(i)) {
                            if ((if (t.road) city.tiles[n].road else city.tiles[n].rail) && city.transportConnects(i, n)) {
                                val edge = edgePoint(diamond, n % city.width - x, n / city.width - y)
                                g.drawLine(sx, sy, edge.x, edge.y)
                            }
                        }
                        if (t.road) g.fillOval(sx - 4, sy - 3, 8, 6) else g.fillOval(sx - 3, sy - 2, 6, 4)
                        g.stroke = BasicStroke(1f)
                    }
                }
                if (layer == Layer.WATER) {
                    if (t.subway) MetroSprites.tunnel(g, diamond, MetroSprites.mask(city, i, rotation))
                    // Water remains a separate overlay, including on shared metro cells.
                    if (t.pipe) {
                        g.color = Color(0x63dcf2)
                        g.stroke = BasicStroke(3 * zoom.toFloat())
                        for (n in city.neighbors(i)) {
                            if (city.tiles[n].pipe || city.tiles[n].building != null) {
                                val edge = edgePoint(diamond, n % city.width - x, n / city.width - y)
                                g.drawLine(sx, sy, edge.x, edge.y)
                            }
                        }
                        g.fillOval(sx - 2, sy - 2, 4, 4)
                        g.stroke = BasicStroke(1f)
                    }
                    if (t.building == "metro") {
                        val radius = (6 * zoom).roundToInt().coerceAtLeast(4)
                        g.color = Color(0x493362)
                        g.fillOval(sx - radius, sy - radius, radius * 2, radius * 2)
                        g.color = Color(0xc1a1df)
                        g.drawOval(sx - radius, sy - radius, radius * 2, radius * 2)
                        g.font = Font("Dialog", Font.BOLD, (9 * zoom).roundToInt().coerceAtLeast(6))
                        g.color = Color.WHITE
                        val metrics = g.fontMetrics
                        g.drawString("M", sx - metrics.stringWidth("M") / 2, sy + (metrics.ascent - metrics.descent) / 2)
                    }
                } else if (layer != Layer.ZONES) {
                    if (t.trees) tree(g, sx, sy, zoom)
                    if (t.building == "metro" && layer == Layer.SURFACE) {
                        MetroSprites.station(g, sx, sy, zoom)
                    } else if (layer == Layer.SURFACE && t.building in BuildingSprites.allIds) {
                        BuildingSprites.draw(g, city, i, catalog[t.building]?.size ?: 1, projection)
                    } else if (t.building != null) {
                        val def = catalog[t.building]
                        val color = Color(def?.color ?: 0xaaaaaa)
                        val geometry = buildingGeometry(t, def, zoom)!!
                        block(g, sx, sy + geometry.depth, geometry.width, geometry.depth, geometry.height, color)
                        if (t.anchor == i) {
                            g.color = Color(0xeff4ef)
                            g.font = Font("Dialog", Font.BOLD, (10 * zoom).toInt().coerceAtLeast(8))
                            val label =
                                when (t.building) {
                                    "coal", "oil", "gas", "nuclear", "wind", "solar", "hydro", "fusion", "microwave" -> "ϟ"
                                    "pump", "tower" -> "≈"
                                    "hospital" -> "+"
                                    "police" -> "P"
                                    "fire" -> "F"
                                    else -> def?.name?.take(1) ?: "?"
                                }
                            g.drawString(label, sx - 3, sy + geometry.depth - geometry.height)
                        }
                    } else if (t.construction != null) {
                        val geometry = buildingGeometry(t, null, zoom)!!
                        // Visible foundations and an open scaffold, drawn in the native isometric toolkit.
                        block(g, sx, sy + geometry.depth, geometry.width, geometry.depth, (3 * zoom).toInt(), Color(0x9c8c70))
                        g.color = Color(0xe2b84b)
                        val corners =
                            listOf(
                                Point(sx - geometry.width, sy),
                                Point(sx, sy - geometry.depth),
                                Point(sx + geometry.width, sy),
                                Point(sx, sy + geometry.depth),
                            )
                        for (corner in corners) g.drawLine(corner.x, corner.y, corner.x, corner.y - geometry.height)
                        for (level in 0..3) {
                            val elevation = geometry.height * level / 3
                            g.drawPolygon(corners.map { it.x }.toIntArray(), corners.map { it.y - elevation }.toIntArray(), 4)
                        }
                        for (side in listOf(0, 2)) {
                            g.drawLine(corners[side].x, corners[side].y, sx, sy + geometry.depth - geometry.height)
                        }
                    } else if (t.occupants > 0 || t.completedHousingCapacity > 0) {
                        val geometry = buildingGeometry(t, null, zoom)!!
                        block(
                            g,
                            sx,
                            sy + geometry.depth,
                            geometry.width,
                            geometry.depth,
                            geometry.height,
                            when {
                                t.zone.residential -> Color(0xc6d1b3)
                                t.zone.commercial -> Color(0x80b6d0)
                                else -> Color(0xc7b68b)
                            },
                        )
                    }
                    if (t.powerLine && layer != Layer.SURFACE) {
                        g.color = Color(0xe3d785)
                        g.drawLine(sx, sy, sx, sy - (15 * zoom).toInt())
                        g.drawLine(sx - (4 * zoom).toInt(), sy - (12 * zoom).toInt(), sx + (4 * zoom).toInt(), sy - (12 * zoom).toInt())
                        for (n in city.neighbors(i)) {
                            if (city.tiles[n].powerLine) {
                                val edge = edgePoint(diamond, n % city.width - x, n / city.width - y)
                                g.drawLine(sx, sy - (12 * zoom).toInt(), edge.x, edge.y - (12 * zoom).toInt())
                            }
                        }
                    }
                }
                if (t.fire > 0) {
                    g.color = Color(0xff743e)
                    g.fillOval(sx - 4, sy - 18, 8, 14)
                }
            }
        }
        // Multi-cell artwork is painted at its front corner. A pole beside either front
        // edge can precede that corner in diagonal order despite standing in front of it.
        // Composite poles after scenery, masking only objects whose footprint is in front.
        for (index in poles) {
            val at = projection.view(index % city.width, index / city.width)
            val bounds = PowerLineSprites.bounds(city, index, projection)
            if (!bounds.intersects(clip)) continue
            // One small raster per pole tile. Erase foreground pixels directly rather
            // than tracing sprite silhouettes and subtracting thousands of vector paths.
            val overlay = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
            val copy = overlay.createGraphics()
            try {
                copy.translate(-bounds.x, -bounds.y)
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                PowerLineSprites.drawVectors(copy, city, index, projection, catalog)
                copy.composite = AlphaComposite.DstOut
                copy.color = Color.BLACK
                for (occluder in occluders) {
                    val front = occluder.front
                    if (at.x <= front.x && at.y <= front.y && at != front && occluder.bounds.intersects(bounds)) {
                        if (occluder.shape != null) {
                            copy.fill(occluder.shape)
                        } else {
                            BuildingSprites.draw(copy, city, occluder.index, catalog[city.tiles[occluder.index].building]?.size ?: 1, projection)
                        }
                    }
                }
            } finally {
                copy.dispose()
            }
            g.drawImage(overlay, bounds.x, bounds.y, null)
        }
    }

    private fun edgePoint(
        polygon: Polygon,
        dx: Int,
        dy: Int,
    ): Point {
        val a =
            when {
                dx > 0 -> 1
                dx < 0 -> 3
                dy > 0 -> 2
                else -> 0
            }
        val rotated = (a + rotation) % 4
        val b = (rotated + 1) % 4
        return Point((polygon.xpoints[rotated] + polygon.xpoints[b]) / 2, (polygon.ypoints[rotated] + polygon.ypoints[b]) / 2)
    }

    private fun tileColor(
        t: Tile,
        index: Int,
    ): Color {
        if (layer == Layer.POWER) return if (t.powered) Color(0xd9c968) else Color(0x3c4651)
        if (layer == Layer.WATER) return if (t.watered) Color(0x458baf) else Color(0x334554)
        if (layer == Layer.POLLUTION || layer == Layer.CRIME || layer == Layer.VALUE || layer == Layer.TRAFFIC) {
            val v =
                when (layer) {
                    Layer.POLLUTION -> t.pollution
                    Layer.CRIME -> t.crime
                    Layer.VALUE -> 100 - t.landValue
                    else -> hourlyTraffic?.get(index) ?: t.traffic
                }.coerceIn(0, 100)
            return Color(65 + v * 150 / 100, 145 - v * 80 / 100, 102 - v * 40 / 100)
        }
        if (t.water) return Color(0x326b88)
        if (t.zone != Zone.NONE) {
            return when {
                t.zone.residential -> Color(0x54875b)
                t.zone.commercial -> Color(0x477f9e)
                else -> Color(0xa39651)
            }
        }
        return Color(60 + t.elevation * 4, 100 + t.elevation * 5, 72 + t.elevation * 3)
    }

    private fun block(
        g: Graphics2D,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        z: Int,
        color: Color,
    ) {
        g.color = color.darker()
        g.fillPolygon(intArrayOf(x - w, x, x, x - w), intArrayOf(y - h, y, y - z, y - h - z), 4)
        g.color = color
        g.fillPolygon(intArrayOf(x, x + w, x + w, x), intArrayOf(y, y - h, y - h - z, y - z), 4)
        g.color = color.brighter()
        g.fillPolygon(intArrayOf(x - w, x, x + w, x), intArrayOf(y - h - z, y - 2 * h - z, y - h - z, y - z), 4)
        if (z > 12) {
            g.color = Color(0xdae4ce)
            for (d in 6 until z - 3 step 7) {
                g.fillRect(x + 2, y - d - 4, 2, 2)
                g.fillRect(x - w + 3, y - d - h / 2, 2, 2)
            }
        }
    }

    private fun tree(
        g: Graphics2D,
        x: Int,
        y: Int,
        z: Double,
    ) {
        g.color = Color(0x544d38)
        g.fillRect(x - 1, y - 6, 2, 7)
        g.color = Color(0x294e39)
        g.fillPolygon(intArrayOf(x - (6 * z).toInt(), x, x + (6 * z).toInt()), intArrayOf(y - 3, y - (19 * z).toInt(), y - 3), 3)
        g.color = Color(0x4b8052)
        g.fillPolygon(intArrayOf(x - (5 * z).toInt(), x, x), intArrayOf(y - 6, y - (18 * z).toInt(), y - 6), 3)
    }
}

/** Shared dimensions for painted buildings and vehicle occlusion. */
internal data class BuildingGeometry(
    val width: Int,
    val depth: Int,
    val height: Int,
)

internal fun buildingGeometry(
    tile: Tile,
    definition: BuildingDef?,
    zoom: Double,
): BuildingGeometry? {
    val w = (16 * zoom).roundToInt()
    val h = (8 * zoom).roundToInt()
    if (tile.building == "metro") return BuildingGeometry(w, h, (26 * zoom).roundToInt())
    if (tile.building != null) {
        val height =
            when {
                (definition?.residents ?: 0) > 0 -> 55
                (definition?.power ?: 0) > 0 -> 27
                else -> 16
            }
        return BuildingGeometry(w - 2, h - 1, (height * zoom).toInt())
    }
    val volume = tile.construction?.capacity ?: if (tile.zone.residential) tile.completedHousingCapacity else tile.occupants
    if (volume <= 0) return null
    val density = (volume.toDouble() / max(1, tile.zone.capacity)).coerceIn(0.0, 1.0)
    val height = ((if (tile.zone.name.endsWith("DENSE")) 10 + density * 40 else 5 + density * 10) * zoom).toInt()
    return BuildingGeometry((w * .65).toInt(), (h * .65).toInt(), height)
}
