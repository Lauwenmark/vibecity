package city.client

import city.core.*
import java.awt.*
import kotlin.math.*

/** Pure scene drawing: only cells whose conservative visual bounds meet the clip are visited. */
internal class CityRenderer(
    private val city: City,
    private val catalog: Map<String, BuildingDef>,
    private val layer: Layer,
    private val zoom: Double,
) {
    fun render(
        g: Graphics2D,
        clip: Rectangle,
    ) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val projection = CityProjection(city.width, city.height, zoom)
        val tw = projection.tw
        val th = projection.th
        val w = tw.roundToInt()
        val h = th.roundToInt()
        val first = max(0, floor((clip.y - 85 - projection.below) / th).toInt())
        val last = min(city.width + city.height - 2, ceil((clip.maxY - 85 + projection.above) / th).toInt())
        for (diagonal in first..last) {
            val startY = max(max(0, diagonal - city.width + 1), ceil((projection.originX + diagonal * tw - clip.maxX - projection.side) / (2 * tw)).toInt())
            val endY = min(min(city.height - 1, diagonal), floor((projection.originX + diagonal * tw - clip.x + projection.side) / (2 * tw)).toInt())
            for (y in startY..endY) {
                val x = diagonal - y
                val i = y * city.width + x
                val t = city.tiles[i]
                val sx = projection.screenX(x, y)
                val sy = projection.screenY(x, y, t.elevation)
                val diamond = projection.diamond(x, y, t.elevation)
                if (x == city.width - 1 || y == city.height - 1) {
                    val depth = (12 + t.elevation * 3) * zoom
                    g.color = Color(0x344b3e)
                    g.fillPolygon(intArrayOf(sx - w, sx, sx + w, sx + w, sx, sx - w), intArrayOf(sy, sy + h, sy, sy + depth.toInt(), sy + h + depth.toInt(), sy + depth.toInt()), 6)
                }
                g.color = tileColor(t)
                g.fillPolygon(diamond)
                g.color = Color(255, 255, 255, 17)
                g.drawPolygon(diamond)
                if (t.water && layer == Layer.SURFACE) {
                    g.color = Color(0x71a8b8)
                    g.drawLine(sx - w / 2, sy, sx, sy + h / 3)
                }
                if (t.road || t.rail) {
                    g.color = if (t.road) Color(0x47535a) else Color(0x938d80)
                    g.stroke = BasicStroke((if (t.road) 6 else 3) * zoom.toFloat())
                    for (n in city.neighbors(i)) {
                        if (if (t.road) city.tiles[n].road else city.tiles[n].rail) {
                            val dx = n % city.width - x
                            val dy = n / city.width - y
                            g.drawLine(sx, sy, sx + ((dx - dy) * tw).toInt(), sy + ((dx + dy) * th).toInt())
                        }
                    }
                    g.fillOval(sx - 3, sy - 2, 6, 4)
                    g.stroke = BasicStroke(1f)
                }
                if (layer == Layer.WATER) {
                    if (t.pipe || t.subway) {
                        g.color = if (t.pipe) Color(0x63dcf2) else Color(0xc1a1df)
                        g.stroke = BasicStroke(3 * zoom.toFloat())
                        g.drawLine(sx - w, sy, sx + w, sy)
                        g.stroke = BasicStroke(1f)
                    }
                } else if (layer != Layer.ZONES) {
                    if (t.trees) tree(g, sx, sy, zoom)
                    if (t.building != null) {
                        val def = catalog[t.building]
                        val color = Color(def?.color ?: 0xaaaaaa)
                        val height =
                            when {
                                def?.residents != null && def.residents > 0 -> 55
                                def?.power != null && def.power > 0 -> 27
                                else -> 16
                            }
                        block(g, sx, sy, w - 2, h - 1, (height * zoom).toInt(), color)
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
                            g.drawString(label, sx - 3, sy - (height * zoom).toInt())
                        }
                    } else if (t.occupants > 0) {
                        val density = (t.occupants.toDouble() / max(1, t.zone.capacity)).coerceIn(0.0, 1.0)
                        val height = ((if (t.zone.name.endsWith("DENSE")) 10 + density * 40 else 5 + density * 10) * zoom).toInt()
                        block(
                            g,
                            sx,
                            sy,
                            (w * .65).toInt(),
                            (h * .65).toInt(),
                            height,
                            when {
                                t.zone.residential -> Color(0xc6d1b3)
                                t.zone.commercial -> Color(0x80b6d0)
                                else -> Color(0xc7b68b)
                            },
                        )
                    }
                    if (t.powerLine) {
                        g.color = Color(0xe3d785)
                        g.drawLine(sx, sy, sx, sy - (15 * zoom).toInt())
                        g.drawLine(sx - w, sy - (12 * zoom).toInt(), sx + w, sy - (12 * zoom).toInt())
                    }
                }
                if (t.fire > 0) {
                    g.color = Color(0xff743e)
                    g.fillOval(sx - 4, sy - 18, 8, 14)
                }
            }
        }
    }

    private fun tileColor(t: Tile): Color {
        if (layer == Layer.POWER) return if (t.powered) Color(0xd9c968) else Color(0x3c4651)
        if (layer == Layer.WATER) return if (t.watered) Color(0x458baf) else Color(0x334554)
        if (layer == Layer.POLLUTION || layer == Layer.CRIME || layer == Layer.VALUE || layer == Layer.TRAFFIC) {
            val v =
                when (layer) {
                    Layer.POLLUTION -> t.pollution
                    Layer.CRIME -> t.crime
                    Layer.VALUE -> 100 - t.landValue
                    else -> t.traffic
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
