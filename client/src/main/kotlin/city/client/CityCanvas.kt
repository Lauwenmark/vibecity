package city.client

import city.core.*
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlin.math.*

enum class Layer(
    val label: String,
) {
    SURFACE("Surface"),
    ZONES("Zonage"),
    POWER("Électricité"),
    WATER("Eau / sous-sol"),
    POLLUTION("Pollution"),
    CRIME("Criminalité"),
    VALUE("Valeur foncière"),
    TRAFFIC("Trafic"),
}

/** Procedural isometric renderer; no proprietary game artwork is required. EDT-confined. */
class CityCanvas(
    private val inspect: (Int) -> Unit,
    private val build: (Int) -> Unit,
) : JPanel() {
    var city: City? = null
        set(value) {
            field = value
            updateSize()
            repaint()
        }
    var catalog: Map<String, BuildingDef> = emptyMap()
    var layer = Layer.SURFACE
        set(value) {
            field = value
            repaint()
        }
    var zoom = 1.0
        set(value) {
            field = value.coerceIn(.5, 2.0)
            updateSize()
            repaint()
        }
    var selected = -1
    var tool = "inspect"
    private var lastPaint = -1
    private val hitRegions = ArrayList<Pair<Int, Polygon>>()

    init {
        background = Color(0x101e29)
        toolTipText = ""
        val mouse =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    lastPaint = -1
                    act(e)
                }

                override fun mouseDragged(e: MouseEvent) {
                    act(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    lastPaint = -1
                }

                override fun mouseMoved(e: MouseEvent) {
                    selected = pick(e.point)
                    if (selected >= 0) inspect(selected)
                    repaint()
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    if (e.isControlDown) {
                        zoom += -e.preciseWheelRotation * .1
                        e.consume()
                    }
                }
            }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    private fun act(e: MouseEvent) {
        val i = pick(e.point)
        if (i < 0) return
        selected = i
        inspect(i)
        if (SwingUtilities.isLeftMouseButton(e) && tool != "inspect" && i != lastPaint) {
            lastPaint = i
            build(i)
        }
        repaint()
    }

    private fun pick(point: Point): Int = hitRegions.asReversed().firstOrNull { it.second.contains(point) }?.first ?: -1

    private fun updateSize() {
        val c = city ?: return
        preferredSize = Dimension(((c.width + c.height) * 16 * zoom + 140).toInt(), ((c.width + c.height) * 8 * zoom + 220).toInt())
        revalidate()
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val c = city ?: return null
        val i = pick(e.point)
        if (i < 0) return null
        val t = c.tiles[i]
        return "${i % c.width}, ${i / c.width} · ${t.building?.let { catalog[it]?.name } ?: if (t.zone != Zone.NONE) {
            t.zone.name
        } else if (t.water) {
            "Eau"
        } else {
            "Terrain"
        }} · ${t.occupants} occupants"
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val c = city ?: return
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val tw = 16 * zoom
        val th = 8 * zoom
        val originX = c.height * tw + 70
        val originY = 85.0
        hitRegions.clear()
        for (diagonal in 0 until c.width + c.height - 1) {
            for (y in max(0, diagonal - c.width + 1)..min(c.height - 1, diagonal)) {
                val x = diagonal - y
                val i = y * c.width + x
                val t = c.tiles[i]
                val sx = (originX + (x - y) * tw).toInt()
                val sy = (originY + (x + y) * th - t.elevation * 3 * zoom).toInt()
                val w = tw.roundToInt()
                val h = th.roundToInt()
                val diamond = Polygon(intArrayOf(sx, sx + w, sx, sx - w), intArrayOf(sy - h, sy, sy + h, sy), 4)
                hitRegions.add(i to diamond)
                if (x == c.width - 1 || y == c.height - 1) {
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
                    for (n in c.neighbors(i)) {
                        if (if (t.road) c.tiles[n].road else c.tiles[n].rail) {
                            val dx = n % c.width - x
                            val dy = n / c.width - y
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
                        val density = t.occupants.toDouble() / max(1, t.zone.capacity)
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
                if (i == selected) {
                    g.color = Color(0xffe1a0)
                    g.stroke = BasicStroke(2f)
                    g.drawPolygon(diamond)
                    g.stroke = BasicStroke(1f)
                }
            }
        }
        g.dispose()
    }

    private fun tileColor(t: Tile): Color {
        if (layer == Layer.POWER) return if (t.powered) Color(0xd9c968) else Color(0x3c4651)
        if (layer == Layer.WATER) return if (t.watered) Color(0x458baf) else Color(0x334554)
        if (layer in listOf(Layer.POLLUTION, Layer.CRIME, Layer.VALUE, Layer.TRAFFIC)) {
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
