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
    private var satisfactionScores: List<Pair<Int, Double>> = emptyList()
    var showSatisfaction = true
        set(value) {
            field = value
            repaint()
        }

    private var sceneRenderer: CityRenderer? = null
    internal val renderedChunkCount: Long get() = cache.renderedChunks
    internal val cachedChunkCount: Int get() = cache.size
    private var projection: CityProjection? = null
    private val roadTraffic = RoadTrafficAnimation()
    private var animationSeconds = 0.0
    private var previousFrame = 0L
    private val animationTimer =
        Timer(50) {
            val now = System.nanoTime()
            if (previousFrame != 0L) advanceTrafficAnimation((now - previousFrame) / 1_000_000_000.0)
            previousFrame = now
        }.apply { isCoalesce = true }
    internal val trafficAnimationRunning: Boolean get() = animationTimer.isRunning
    var animatedTraffic = true
        set(value) {
            field = value
            syncAnimationTimer()
            repaint()
        }

    private fun refreshTraffic() {
        roadTraffic.update(city, trafficHour, zoom, catalog, rotation)
        syncAnimationTimer()
    }

    private fun syncAnimationTimer() {
        val active =
            isShowing && animatedTraffic && city != null && city!!.rules.speed > 0 &&
                (layer == Layer.SURFACE || layer == Layer.TRAFFIC) && !roadTraffic.isEmpty
        if (active && !animationTimer.isRunning) {
            previousFrame = System.nanoTime()
            animationTimer.start()
        } else if (!active) {
            animationTimer.stop()
            previousFrame = 0L
        }
    }

    internal fun advanceTrafficAnimation(seconds: Double) {
        if (!animatedTraffic || city?.rules?.speed == 0 || (layer != Layer.SURFACE && layer != Layer.TRAFFIC)) return
        animationSeconds += seconds.coerceIn(0.0, .25)
        // Repaint the visible viewport from cached scenery; no scene invalidation per frame.
        repaint(visibleRect)
    }

    override fun addNotify() {
        super.addNotify()
        syncAnimationTimer()
    }

    override fun removeNotify() {
        animationTimer.stop()
        previousFrame = 0L
        super.removeNotify()
    }

    var city: City? = null
        set(value) {
            val previous = field
            if (previous === value) return
            field = value
            if (previous?.households !== value?.households) {
                satisfactionScores =
                    value
                        ?.households
                        .orEmpty()
                        .filter { it.people.isNotEmpty() }
                        .map { it.cell to it.satisfaction }
                if (showSatisfaction) repaint()
            }
            sceneRenderer = null
            refreshTraffic()
            if (previous?.mobility != value?.mobility && animatedTraffic) repaint()
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
            sceneRenderer = null
            refreshTraffic()
            cache.clear()
            repaint()
        }
    var layer = Layer.SURFACE
        set(value) {
            if (field == value) return
            field = value
            sceneRenderer = null
            syncAnimationTimer()
            cache.clear()
            repaint()
        }
    var zoom = 1.0
        set(value) {
            val bounded = value.coerceIn(.5, 16.0)
            if (field == bounded) return
            field = bounded
            sceneRenderer = null
            refreshTraffic()
            updateSize()
            cache.clear()
            repaint()
        }
    var rotation = 0
        set(value) {
            val normalized = Math.floorMod(value, 4)
            if (field == normalized) return
            val center = pick(Point(visibleRect.centerX.toInt(), visibleRect.centerY.toInt()))
            field = normalized
            sceneRenderer = null
            updateSize()
            refreshTraffic()
            cache.clear()
            repaint()
            if (center >= 0) focusCell(center)
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
            sceneRenderer = null
            refreshTraffic()
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
        set(value) {
            if (field == value) return
            val old = selectionBounds(selected)
            field = value
            old?.let { repaint(it) }
            selectionBounds(selected)?.let { repaint(it) }
        }
    private var lastPaint = -1

    init {
        background = Color(0x101e29)
        toolTipText = ""
        addHierarchyListener { if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) syncAnimationTimer() }
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
                    val viewport = parent as? JViewport
                    val oldZoom = zoom
                    val oldOriginY = projection?.originY ?: 85.0
                    val oldPosition = viewport?.viewPosition ?: Point()
                    zoom += -e.preciseWheelRotation * .15
                    if (viewport != null) {
                        val ratio = zoom / oldZoom
                        setSize(preferredSize)
                        viewport.viewPosition =
                            Point(
                                ((e.x - 70) * ratio + 70 - (e.x - oldPosition.x)).toInt().coerceIn(0, (width - viewport.width).coerceAtLeast(0)),
                                ((e.y - oldOriginY) * ratio + (projection?.originY ?: 85.0) - (e.y - oldPosition.y)).toInt().coerceIn(0, (height - viewport.height).coerceAtLeast(0)),
                            )
                    }
                    e.consume()
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
        val p = projection ?: return null
        return selectionCells(index).map { p.polygon(c, it).bounds }.reduce { bounds, next -> bounds.apply { add(next) } }.apply { grow(3, 3) }
    }

    /** Match the world-space footprint used by BUILD, clipped without wrapping at map edges. */
    internal fun selectionCells(index: Int): List<Int> {
        val c = city ?: return emptyList()
        if (index !in c.tiles.indices) return emptyList()
        val size = catalog[tool]?.size ?: 1
        val x = index % c.width
        val y = index / c.width
        return (y until minOf(y + size, c.height)).flatMap { row ->
            (x until minOf(x + size, c.width)).map { column -> c.index(column, row) }
        }
    }

    private fun updateSize() {
        val c = city
        projection = c?.let { CityProjection(it.width, it.height, zoom, rotation) }
        val size = if (c == null) Dimension(0, 0) else Dimension(((c.width + c.height) * 16 * zoom + 140).toInt(), ((c.width + c.height) * 8 * zoom + 220 + (projection!!.originY - 85)).toInt())
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
            val renderer = sceneRenderer ?: CityRenderer(c, catalog, layer, zoom, trafficHour, rotation).also { sceneRenderer = it }
            cache.paint(g, clip, background, renderer)
            if (animatedTraffic && (layer == Layer.SURFACE || layer == Layer.TRAFFIC)) roadTraffic.paint(g, clip, animationSeconds)
            if (showSatisfaction && layer == Layer.SURFACE) paintSatisfaction(g, c, clip)
            paintItinerary(g, c)
            if (selected in c.tiles.indices) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(0xffe1a0)
                g.stroke = BasicStroke(2f)
                for (cell in selectionCells(selected)) {
                    val polygon = projection!!.polygon(c, cell)
                    if (catalog.containsKey(tool)) {
                        g.color = Color(255, 225, 160, 65)
                        g.fillPolygon(polygon)
                    }
                    g.color = Color(0xffe1a0)
                    g.drawPolygon(polygon)
                }
            }
        } finally {
            g.dispose()
        }
    }

    /** Screen-sized faces remain readable at every zoom, without invalidating scenery chunks. */
    private fun paintSatisfaction(
        g: Graphics2D,
        c: City,
        clip: Rectangle,
    ) {
        val p = projection ?: return
        for ((cell, score) in satisfactionScores) {
            val tile = c.tiles[cell]
            val geometry = buildingGeometry(tile, tile.building?.let { catalog[it] }, zoom) ?: continue
            val x = p.screenX(cell % c.width, cell / c.width)
            val y = p.centerY(cell % c.width, cell / c.width, c.surface(cell)) - geometry.height - 13
            if (!clip.intersects(Rectangle(x - 10, y - 10, 20, 20))) continue
            g.color =
                when {
                    score >= 70 -> Color(0x88d679)
                    score >= 40 -> Color(0xffd166)
                    else -> Color(0xef7770)
                }
            g.fillOval(x - 9, y - 9, 18, 18)
            g.color = Color(0x243743)
            g.drawOval(x - 9, y - 9, 18, 18)
            g.fillOval(x - 4, y - 3, 2, 2)
            g.fillOval(x + 2, y - 3, 2, 2)
            when {
                score >= 70 -> g.drawArc(x - 4, y - 2, 8, 7, 180, 180)
                score >= 40 -> g.drawLine(x - 4, y + 4, x + 4, y + 4)
                else -> g.drawArc(x - 4, y + 3, 8, 7, 0, 180)
            }
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
        if (layer == Layer.WATER) return a.pipe == b.pipe && a.subway == b.subway && a.building == b.building
        if (layer == Layer.ZONES) return true
        return a.trees == b.trees && a.building == b.building && a.anchor == b.anchor &&
            a.occupants == b.occupants && a.zone == b.zone && a.powerLine == b.powerLine && a.water == b.water
    }
}
