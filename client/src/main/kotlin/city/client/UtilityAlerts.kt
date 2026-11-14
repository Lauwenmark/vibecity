package city.client

import city.core.*
import java.awt.*

internal data class UtilityAlert(
    val cell: Int,
    val power: Boolean,
    val water: Boolean,
)

/** Aggregate the whole footprint: network capacity can leave only some cells supplied. */
internal fun collectUtilityAlerts(city: City): List<UtilityAlert> {
    val alerts = linkedMapOf<Int, UtilityAlert>()
    for ((i, tile) in city.tiles.withIndex()) {
        if (tile.building == null && tile.occupants <= 0) continue
        if (tile.powered && tile.watered) continue
        val anchor = if (tile.building != null) tile.anchor.takeIf { it in city.tiles.indices } ?: i else i
        val previous = alerts[anchor]
        alerts[anchor] = UtilityAlert(anchor, !tile.powered || previous?.power == true, !tile.watered || previous?.water == true)
    }
    return alerts.values.toList()
}

/** Screen-sized symbols, drawn separately from cached scenery and simulation time. */
internal fun paintUtilityAlerts(
    graphics: Graphics2D,
    city: City,
    catalog: Map<String, BuildingDef>,
    projection: CityProjection,
    alerts: List<UtilityAlert>,
    clip: Rectangle,
) {
    val g = graphics.create() as Graphics2D
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (alert in alerts) {
            val tile = city.tiles[alert.cell]
            val definition = tile.building?.let(catalog::get)
            val geometry = buildingGeometry(tile, definition, projection.zoom) ?: continue
            val x = projection.screenX(alert.cell % city.width, alert.cell / city.width)
            // Above the satisfaction face, keeping both overlays legible.
            val y = projection.centerY(alert.cell % city.width, alert.cell / city.width, city.surface(alert.cell)) - geometry.height - 39
            if (!clip.intersects(Rectangle(x - 26, y - 13, 52, 26))) continue
            if (alert.power) {
                val cx = x - if (alert.water) 13 else 0
                val bolt = Polygon(intArrayOf(cx + 2, cx - 7, cx - 1, cx - 4, cx + 8, cx + 2), intArrayOf(y - 11, y + 2, y + 2, y + 11, y - 3, y - 3), 6)
                g.color = Color(0xffd52a)
                g.fillPolygon(bolt)
                g.color = Color(0x463b13)
                g.drawPolygon(bolt)
            }
            if (alert.water) {
                val cx = x + if (alert.power) 13 else 0
                g.color = Color(0x192c3b)
                g.fillRoundRect(cx - 11, y - 11, 22, 22, 4, 4)
                g.color = Color(0xff4242)
                g.drawRoundRect(cx - 11, y - 11, 22, 22, 4, 4)
                g.color = Color(0xe3f4ff)
                g.drawLine(cx - 7, y, cx + 5, y)
                g.drawLine(cx + 5, y, cx + 5, y + 4)
                g.drawLine(cx - 7, y - 3, cx - 7, y + 3)
                g.drawLine(cx - 1, y, cx - 1, y - 6)
                g.drawLine(cx - 5, y - 6, cx + 3, y - 6)
                g.color = Color(0x69cfff)
                g.fillOval(cx + 3, y + 6, 4, 4)
            }
        }
    } finally {
        g.dispose()
    }
}
