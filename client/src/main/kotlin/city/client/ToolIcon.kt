package city.client

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Original 32-unit municipal pictograms; vector drawing preserves HiDPI sharpness. */
class ToolIcon(
    private val id: String,
    private val selected: Boolean = false,
) : Icon {
    override fun getIconWidth() = 32

    override fun getIconHeight() = 32

    override fun paintIcon(
        c: Component?,
        graphics: Graphics,
        x: Int,
        y: Int,
    ) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            Painter(g).draw(id, selected)
        } finally {
            g.dispose()
        }
    }

    private class Painter(
        val g: Graphics2D,
    ) {
        val ink = 0x101e29
        val cream = 0xe7d5a5
        val blue = 0x72bfd7
        val green = 0x78b880
        val gold = 0xe7b85e
        val red = 0xda7963
        val steel = 0xa9bec9

        fun line(
            color: Int,
            vararg points: Int,
        ) {
            g.color = Color(color)
            g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (i in 0 until points.size - 2 step 2) g.drawLine(points[i], points[i + 1], points[i + 2], points[i + 3])
        }

        fun rect(
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            color: Int,
        ) {
            g.color = Color(color)
            g.fillRect(x, y, w, h)
            g.color = Color(ink)
            g.stroke = BasicStroke(1f)
            g.drawRect(x, y, w, h)
        }

        fun poly(
            color: Int,
            vararg p: Int,
        ) {
            val xs = p.filterIndexed { i, _ -> i % 2 == 0 }.toIntArray()
            val ys = p.filterIndexed { i, _ -> i % 2 == 1 }.toIntArray()
            g.color = Color(color)
            g.fillPolygon(xs, ys, xs.size)
            g.color = Color(ink)
            g.stroke = BasicStroke(1f)
            g.drawPolygon(xs, ys, xs.size)
        }

        fun circle(
            x: Int,
            y: Int,
            size: Int,
            color: Int,
        ) {
            g.color = Color(color)
            g.fillOval(x, y, size, size)
            g.color = Color(ink)
            g.stroke = BasicStroke(1f)
            g.drawOval(x, y, size, size)
        }

        fun building(
            x: Int = 6,
            y: Int = 12,
            w: Int = 20,
            h: Int = 15,
            color: Int = cream,
        ) {
            rect(x, y, w, h, color)
            for (wx in x + 3 until x + w - 2 step 5) {
                for (wy in y + 3 until y + h - 2 step 5) rect(wx, wy, 2, 2, blue)
            }
        }

        fun tree(
            x: Int,
            y: Int,
        ) {
            rect(x + 4, y + 10, 3, 8, cream)
            poly(green, x + 5, y, x + 12, y + 12, x - 2, y + 12)
        }

        fun drop(
            x: Int = 16,
            y: Int = 5,
            color: Int = blue,
        ) {
            poly(color, x, y, x - 5, y + 8, x - 4, y + 12, x, y + 14, x + 4, y + 12, x + 5, y + 8)
            line(0xe3f5ed, x - 2, y + 8, x - 2, y + 10)
        }

        fun rail() {
            for (y in 8..26 step 5) line(cream, 7, y, 25, y)
            line(steel, 11, 6, 11, 28)
            line(steel, 21, 6, 21, 28)
        }

        fun arrow(up: Boolean) {
            val tip = if (up) 5 else 18
            val tail = if (up) 18 else 5
            line(cream, 23, tail, 23, tip)
            line(cream, 19, if (up) 9 else 14, 23, tip, 27, if (up) 9 else 14)
        }

        fun draw(
            id: String,
            selected: Boolean,
        ) {
            g.color = Color(if (selected) 0x496377 else 0x293e4b)
            g.fillRoundRect(0, 0, 32, 32, 6, 6)
            when {
                id.startsWith("zone:") -> {
                    zone(id)
                }

                else -> {
                    when (id) {
                        "inspect" -> {
                            rect(5, 14, 15, 12, green)
                            circle(10, 5, 14, blue)
                            circle(13, 8, 8, 0x375968)
                            line(cream, 22, 18, 28, 26)
                        }

                        "bulldoze" -> {
                            rect(5, 22, 20, 6, steel)
                            circle(6, 23, 4, ink)
                            circle(19, 23, 4, ink)
                            rect(7, 10, 10, 12, gold)
                            rect(9, 12, 6, 6, blue)
                            rect(17, 17, 8, 6, gold)
                            poly(gold, 27, 16, 29, 26, 24, 26, 24, 20)
                        }

                        "road" -> {
                            poly(steel, 10, 4, 22, 4, 29, 29, 3, 29)
                            poly(0x3d4851, 12, 4, 20, 4, 26, 29, 6, 29)
                            line(cream, 16, 7, 16, 12)
                            line(cream, 16, 19, 16, 25)
                        }

                        "rail" -> {
                            rail()
                        }

                        "power" -> {
                            line(steel, 8, 28, 16, 4, 24, 28)
                            line(steel, 8, 11, 24, 11)
                            line(steel, 6, 17, 26, 17)
                            line(steel, 12, 22, 20, 17, 12, 11)
                            poly(gold, 25, 2, 20, 10, 24, 10, 21, 16, 29, 7, 25, 7)
                        }

                        "pipe" -> {
                            poly(blue, 5, 7, 13, 7, 13, 19, 27, 19, 27, 27, 5, 27)
                            rect(3, 6, 12, 4, steel)
                            rect(24, 17, 4, 12, steel)
                            line(0xc8e9ee, 8, 12, 8, 23, 21, 23)
                        }

                        "subway" -> {
                            circle(4, 3, 24, steel)
                            rect(8, 14, 16, 15, ink)
                            rail()
                        }

                        "clear_underground" -> {
                            rect(3, 18, 26, 10, 0x8e735b)
                            line(green, 3, 17, 29, 17)
                            line(blue, 6, 23, 26, 23)
                            poly(red, 12, 13, 21, 4, 28, 11, 19, 20)
                            poly(cream, 12, 13, 16, 9, 23, 16, 19, 20)
                        }

                        "raise", "lower", "level" -> {
                            poly(0x9a8063, 3, 27, 3, 21, 10, 21, 15, 14, 21, 21, 29, 21, 29, 27)
                            line(green, 3, 21, 10, 21, 15, 14, 21, 21, 29, 21)
                            if (id == "level") {
                                line(cream, 5, 10, 27, 10)
                                line(gold, 16, 3, 16, 8)
                            } else {
                                arrow(id == "raise")
                            }
                        }

                        "water" -> {
                            poly(steel, 3, 8, 19, 8, 19, 20, 29, 20, 29, 28, 3, 28)
                            poly(blue, 3, 6, 21, 6, 21, 21, 29, 21, 29, 27, 14, 27, 14, 12, 3, 12)
                            line(0xd1efee, 17, 10, 17, 23, 24, 24)
                        }

                        "trees" -> {
                            tree(4, 8)
                            tree(17, 4)
                        }

                        "coal", "oil", "gas" -> {
                            building(4, 17, 24, 11, steel)
                            rect(5, 6, 5, 12, red)
                            rect(12, 9, 4, 8, cream)
                            when (id) {
                                "coal" -> {
                                    poly(0x5d646b, 19, 6, 25, 4, 29, 10, 24, 15, 18, 12)
                                }

                                "oil" -> {
                                    drop(24, 2, 0x536575)
                                }

                                else -> {
                                    drop(24, 2, gold)
                                    poly(red, 24, 8, 21, 14, 26, 14)
                                }
                            }
                        }

                        "nuclear" -> {
                            poly(cream, 9, 8, 23, 8, 21, 17, 27, 28, 5, 28, 11, 17)
                            circle(13, 17, 6, gold)
                            line(ink, 16, 18, 16, 22)
                            line(steel, 12, 5, 10, 2)
                            line(steel, 20, 5, 22, 2)
                        }

                        "wind" -> {
                            line(cream, 16, 13, 16, 28)
                            poly(cream, 16, 13, 13, 3, 17, 3)
                            poly(cream, 16, 13, 28, 15, 26, 19)
                            poly(cream, 16, 13, 9, 23, 6, 20)
                            circle(13, 10, 6, steel)
                        }

                        "solar" -> {
                            circle(22, 3, 7, gold)
                            poly(0x5687c0, 7, 10, 24, 10, 28, 24, 3, 24)
                            line(blue, 6, 17, 26, 17)
                            line(blue, 12, 11, 10, 23)
                            line(blue, 19, 11, 21, 23)
                            line(steel, 12, 27, 21, 27)
                        }

                        "fusion" -> {
                            circle(4, 4, 24, blue)
                            circle(9, 9, 14, ink)
                            circle(13, 13, 6, cream)
                            line(gold, 16, 2, 16, 8)
                            line(gold, 4, 24, 9, 21)
                            line(gold, 28, 24, 23, 21)
                        }

                        "hydro" -> {
                            rect(3, 8, 26, 21, blue)
                            poly(steel, 7, 5, 24, 8, 27, 26, 5, 24)
                            for (x in 10..22 step 6) line(blue, x, 16, x, 28)
                        }

                        "microwave" -> {
                            building(5, 23, 22, 5, steel)
                            line(cream, 16, 14, 16, 23)
                            poly(cream, 5, 6, 25, 19, 17, 21, 9, 16)
                            line(gold, 15, 13, 23, 5)
                            circle(23, 3, 4, blue)
                        }

                        "pump" -> {
                            rect(5, 19, 22, 9, steel)
                            rect(7, 12, 9, 11, blue)
                            line(cream, 5, 10, 18, 10)
                            drop(24, 3)
                        }

                        "tower" -> {
                            line(steel, 10, 15, 7, 29)
                            line(steel, 22, 15, 25, 29)
                            line(steel, 9, 24, 23, 24)
                            rect(6, 6, 20, 12, blue)
                            poly(cream, 5, 6, 16, 2, 27, 6)
                        }

                        "treatment" -> {
                            rect(3, 14, 11, 14, blue)
                            rect(18, 14, 11, 14, green)
                            line(cream, 5, 21, 12, 21)
                            line(cream, 20, 21, 27, 21)
                            line(steel, 8, 12, 8, 6, 23, 6, 23, 12)
                        }

                        "desalination" -> {
                            line(blue, 3, 9, 7, 6, 11, 9)
                            line(blue, 3, 16, 7, 13, 11, 16)
                            rect(13, 5, 5, 23, cream)
                            for (y in 9..24 step 5) line(steel, 14, y, 17, y)
                            drop(25, 11)
                        }

                        "police" -> {
                            poly(blue, 5, 6, 16, 3, 27, 6, 25, 20, 16, 29, 7, 20)
                            poly(gold, 16, 8, 18, 13, 24, 13, 19, 17, 21, 23, 16, 19, 11, 23, 13, 17, 8, 13, 14, 13)
                        }

                        "fire" -> {
                            circle(7, 8, 18, red)
                            rect(4, 21, 25, 5, red)
                            rect(14, 6, 4, 16, gold)
                        }

                        "hospital" -> {
                            building(4, 11, 24, 17, cream)
                            rect(11, 3, 10, 12, cream)
                            line(red, 16, 5, 16, 12)
                            line(red, 13, 8, 19, 8)
                        }

                        "school" -> {
                            rect(4, 5, 24, 18, cream)
                            rect(6, 7, 20, 14, 0x3b705d)
                            line(cream, 9, 12, 18, 12)
                            line(cream, 9, 16, 15, 16)
                            line(gold, 22, 15, 17, 27)
                        }

                        "college" -> {
                            rect(9, 14, 15, 10, blue)
                            poly(cream, 2, 11, 16, 4, 30, 11, 16, 18)
                            line(gold, 27, 13, 27, 24)
                        }

                        "library" -> {
                            rect(4, 5, 24, 23, cream)
                            for (x in 7..23 step 5) rect(x, 8, 3, 15, if (x % 2 == 0) blue else red)
                            line(ink, 5, 24, 27, 24)
                        }

                        "museum", "cityhall" -> {
                            poly(cream, 3, 11, 16, 3, 29, 11)
                            for (x in 7..25 step 6) rect(x, 13, 3, 12, cream)
                            rect(3, 26, 26, 3, cream)
                            if (id == "cityhall") {
                                circle(12, 6, 8, blue)
                                line(ink, 16, 8, 16, 10, 18, 10)
                            }
                        }

                        "park", "large_park" -> {
                            rect(3, 24, 26, 5, green)
                            tree(4, 4)
                            if (id == "large_park") {
                                tree(17, 7)
                            } else {
                                rect(18, 19, 11, 4, cream)
                                line(cream, 19, 23, 19, 27)
                                line(cream, 27, 23, 27, 27)
                            }
                        }

                        "zoo" -> {
                            circle(9, 16, 15, gold)
                            for ((x, y) in listOf(3 to 10, 10 to 4, 19 to 4, 25 to 11)) circle(x, y, 6, cream)
                        }

                        "stadium" -> {
                            g.color = Color(steel)
                            g.fillOval(2, 6, 28, 22)
                            g.color = Color(red)
                            g.fillOval(5, 9, 22, 16)
                            rect(9, 12, 14, 10, green)
                            line(cream, 16, 13, 16, 21)
                        }

                        "prison" -> {
                            building(3, 7, 26, 22, steel)
                            rect(7, 11, 18, 14, ink)
                            for (x in 9..23 step 5) line(steel, x, 12, x, 24)
                            rect(14, 17, 5, 6, gold)
                        }

                        "bus", "station" -> {
                            if (id == "station") {
                                poly(cream, 2, 10, 16, 2, 30, 10)
                                rail()
                            }
                            rect(7, 8, 18, 18, if (id == "bus") gold else steel)
                            rect(10, 11, 12, 7, blue)
                            circle(9, 21, 4, cream)
                            circle(20, 21, 4, cream)
                            if (id == "bus") {
                                rect(8, 27, 4, 3, ink)
                                rect(20, 27, 4, 3, ink)
                            }
                        }

                        "metro" -> {
                            line(cream, 5, 11, 5, 4, 10, 9, 15, 4, 15, 11)
                            line(steel, 5, 18, 12, 18, 12, 22, 19, 22, 19, 26, 27, 26)
                            line(blue, 19, 4, 27, 12, 22, 12)
                        }

                        "airport" -> {
                            poly(cream, 15, 3, 18, 3, 19, 13, 29, 20, 29, 23, 19, 19, 19, 26, 23, 28, 10, 28, 14, 25, 14, 19, 3, 23, 3, 20, 14, 13)
                        }

                        "seaport" -> {
                            circle(12, 3, 8, cream)
                            line(cream, 16, 11, 16, 28)
                            line(cream, 9, 15, 23, 15)
                            line(cream, 5, 20, 7, 25, 16, 29, 25, 25, 27, 20)
                            poly(cream, 3, 18, 10, 19, 5, 23)
                            poly(cream, 29, 18, 22, 19, 27, 23)
                        }

                        "mayor" -> {
                            building(6, 16, 20, 12)
                            poly(red, 3, 16, 16, 7, 29, 16)
                            line(cream, 20, 10, 20, 2)
                            poly(gold, 21, 2, 28, 4, 21, 6)
                        }

                        "arcology" -> {
                            poly(blue, 16, 3, 30, 28, 2, 28)
                            for (y in 14..26 step 4) line(cream, 14 - (y - 14) / 2, y, 18 + (y - 14) / 2, y)
                        }

                        "forest_arcology" -> {
                            building(9, 5, 14, 24, green)
                            for (y in 9..25 step 7) {
                                rect(5, y, 22, 3, green)
                                tree(if (y == 16) 17 else 2, y - 8)
                            }
                        }

                        "darco" -> {
                            building(5, 13, 22, 16, 0x7e809b)
                            building(9, 5, 14, 24, 0x9296b0)
                            rect(13, 18, 6, 11, ink)
                        }

                        "launch" -> {
                            poly(steel, 16, 2, 22, 14, 22, 25, 28, 29, 4, 29, 10, 25, 10, 14)
                            line(blue, 16, 9, 16, 25)
                            line(cream, 8, 27, 24, 27)
                        }

                        else -> {
                            building()
                            poly(gold, 4, 12, 16, 4, 28, 12)
                        }
                    }
                }
            }
        }

        fun zone(id: String) {
            val color =
                when {
                    "RESIDENTIAL" in id -> green
                    "COMMERCIAL" in id -> blue
                    else -> gold
                }
            rect(2, 25, 28, 5, color)
            if (id.endsWith("DENSE")) {
                if ("INDUSTRIAL" in id) {
                    for (x in 6..24 step 8) rect(x, 4, 4, 17, steel)
                    building(4, 18, 24, 10, color)
                } else {
                    building(3, 11, 10, 17, color)
                    building(14, 4, 14, 24, color)
                }
            } else {
                when {
                    "RESIDENTIAL" in id -> {
                        building(7, 15, 18, 13, color)
                        poly(cream, 4, 15, 16, 5, 28, 15)
                    }

                    "COMMERCIAL" in id -> {
                        building(5, 12, 22, 16, color)
                        for (x in 4..24 step 5) rect(x, 11, 5, 6, if (x % 2 == 0) cream else blue)
                    }

                    else -> {
                        building(4, 17, 24, 11, color)
                        rect(22, 6, 5, 12, steel)
                        poly(cream, 3, 17, 10, 10, 10, 17, 18, 10, 18, 17)
                    }
                }
            }
        }
    }
}
