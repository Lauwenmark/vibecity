package city.client

import city.core.*
import java.awt.*
import java.awt.event.*
import javax.swing.*

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

/** Swing view with an independently painted hover overlay. All state and caches are EDT-confined. */
class CityCanvas(
    private val inspect: (Int) -> Unit,
    private val build: (Int) -> Unit,
    private val inspectResident: (Int) -> Unit = {},
) : JPanel() {
    private val cache = SceneCache()
    internal val renderedChunkCount: Long get() = cache.renderedChunks
    internal val cachedChunkCount: Int get() = cache.size
    private var projection: CityProjection? = null
    var city: City? = null
        set(value) {
            val previous = field
            if (previous === value) return
            field = value
            if (value == null || previous == null || value.width != previous.width || value.height != previous.height) {
                selected = -1
                updateSize()
                cache.clear()
                repaint()
                return
            }
            if (layer == Layer.TRAFFIC && trafficHour >= 0 && previous.mobility != value.mobility) {
                cache.clear()
                repaint()
            }
            if (itinerary != null || residentPosition != null) repaint()
            val changedTerrain = previous.terrain != value.terrain
            var dirty: Rectangle? = null
            for (i in value.tiles.indices) {
                if (!sameAppearance(previous.tiles[i], value.tiles[i]) || (changedTerrain && previous.surface(i) != value.surface(i))) {
                    val bounds = projection!!.visualBounds(i)
                    cache.invalidate(bounds)
                    if (dirty == null) dirty = bounds else dirty.add(bounds)
                }
            }
            dirty?.let { repaint(it) }
        }
    var catalog: Map<String, BuildingDef> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            cache.clear()
            repaint()
        }
    var layer = Layer.SURFACE
        set(value) {
            if (field == value) return
            field = value
            cache.clear()
            repaint()
        }
    var zoom = 1.0
        set(value) {
            val bounded = value.coerceIn(.5, 2.0)
            if (field == bounded) return
            field = bounded
            updateSize()
            cache.clear()
            repaint()
        }
    var selected = -1
        set(value) {
            if (field == value) return
            val old = selectionBounds(field)
            field = value
            old?.let { repaint(it) }
            selectionBounds(value)?.let { repaint(it) }
        }
    var trafficHour: Int = -1
        set(value) {
            if (field == value) return
            field = value
            cache.clear()
            repaint()
        }
    var itinerary: TravelPath? = null
        set(value) {
            field = value
            repaint()
        }
    var residentPosition: Int? = null
        set(value) {
            field = value
            repaint()
        }
    var tool = "inspect"
    private var lastPaint = -1

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
                    select(pick(e.point))
                }

                override fun mouseExited(e: MouseEvent) {
                    selected = -1
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

    private fun select(index: Int) {
        if (selected == index) return
        selected = index
        if (index >= 0) inspect(index)
    }

    private fun act(e: MouseEvent) {
        val i = pick(e.point)
        if (i < 0) return
        select(i)
        if (SwingUtilities.isLeftMouseButton(e) && tool == "inspect" && e.clickCount == 2) inspectResident(i)
        if (SwingUtilities.isLeftMouseButton(e) && tool != "inspect" && i != lastPaint) {
            lastPaint = i
            build(i)
        }
    }

    internal fun pick(point: Point): Int = city?.let { projection?.pick(it, point) } ?: -1

    fun focusCell(index: Int) {
        selectionBounds(index)?.apply { grow(100, 100) }?.let(::scrollRectToVisible)
    }

    private fun selectionBounds(index: Int): Rectangle? {
        val c = city ?: return null
        if (index !in c.tiles.indices) return null
        return projection?.polygon(c, index)?.bounds?.apply { grow(3, 3) }
    }

    private fun updateSize() {
        val c = city
        projection = c?.let { CityProjection(it.width, it.height, zoom) }
        val size = if (c == null) Dimension(0, 0) else Dimension(((c.width + c.height) * 16 * zoom + 140).toInt(), ((c.width + c.height) * 8 * zoom + 220).toInt())
        if (preferredSize != size) {
            preferredSize = size
            revalidate()
        }
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val c = city ?: return null
        val i = pick(e.point)
        if (i < 0) return null
        val t = c.tiles[i]
        val name =
            t.building?.let { catalog[it]?.name } ?: if (t.zone != Zone.NONE) {
                t.zone.name
            } else if (t.water) {
                "Eau"
            } else {
                "Terrain"
            }
        val s = c.surface(i)
        return "${i % c.width}, ${i / c.width} · $name · altitude ${s.minimum}–${s.maximum} · ${t.occupants} occupants"
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val c = city ?: return
        val g = graphics.create() as Graphics2D
        try {
            val clip = (g.clipBounds ?: Rectangle(0, 0, width, height)).intersection(Rectangle(0, 0, width, height))
            cache.paint(g, clip, background, CityRenderer(c, catalog, layer, zoom, trafficHour))
            paintItinerary(g, c)
            if (selected in c.tiles.indices) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(0xffe1a0)
                g.stroke = BasicStroke(2f)
                g.drawPolygon(projection!!.polygon(c, selected))
            }
        } finally {
            g.dispose()
        }
    }

    private fun paintItinerary(
        g: Graphics2D,
        c: City,
    ) {
        val p = projection ?: return

        fun point(cell: Int): Point = Point(p.screenX(cell % c.width, cell / c.width), p.centerY(cell % c.width, cell / c.width, c.surface(cell)))
        val nodes = itinerary?.nodes.orEmpty()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (i in 1 until nodes.size) {
            if (nodes[i] / 4 !in c.tiles.indices || nodes[i - 1] / 4 !in c.tiles.indices) continue
            val a = point(nodes[i - 1] / 4)
            val b = point(nodes[i] / 4)
            g.color =
                when (TravelMode.entries[nodes[i] % 4]) {
                    TravelMode.WALK -> Color(0xffdf70)
                    TravelMode.ROAD -> Color(0xff8b42)
                    TravelMode.RAIL -> Color(0x59caff)
                    TravelMode.METRO -> Color(0xc993ff)
                }
            g.drawLine(a.x, a.y, b.x, b.y)
        }
        for ((index, node) in nodes.withIndex()) {
            if ((index != 0 && index != nodes.lastIndex) || node / 4 !in c.tiles.indices) continue
            val at = point(node / 4)
            g.color = Color(0xffdf70)
            g.drawOval(at.x - 5, at.y - 5, 10, 10)
            g.drawString(if (index == 0) "Domicile" else "Destination", at.x + 7, at.y - 7)
        }
        residentPosition?.takeIf { it in c.tiles.indices }?.let {
            val at = point(it)
            g.color = Color.BLACK
            g.fillOval(at.x - 6, at.y - 6, 12, 12)
            g.color = Color.WHITE
            g.fillOval(at.x - 4, at.y - 4, 8, 8)
        }
    }

    /** Simulation-only fields must not evict surface chunks every month. */
    private fun sameAppearance(
        a: Tile,
        b: Tile,
    ): Boolean {
        if (a.elevation != b.elevation || a.road != b.road || a.rail != b.rail || a.fire != b.fire) return false
        val sameColor =
            when (layer) {
                Layer.POWER -> a.powered == b.powered
                Layer.WATER -> a.watered == b.watered
                Layer.POLLUTION -> a.pollution == b.pollution
                Layer.CRIME -> a.crime == b.crime
                Layer.VALUE -> a.landValue == b.landValue
                Layer.TRAFFIC -> a.traffic == b.traffic
                else -> a.water == b.water && a.zone == b.zone
            }
        if (!sameColor) return false
        if (layer == Layer.WATER) return a.pipe == b.pipe && a.subway == b.subway
        if (layer == Layer.ZONES) return true
        return a.trees == b.trees && a.building == b.building && a.anchor == b.anchor &&
            a.occupants == b.occupants && a.zone == b.zone && a.powerLine == b.powerLine && a.water == b.water
    }
}
