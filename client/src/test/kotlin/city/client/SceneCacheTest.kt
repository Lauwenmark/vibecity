package city.client

import city.core.*
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.random.Random
import kotlin.test.*

class SceneCacheTest {
    private val catalog = Catalog().all().associateBy { it.id }

    private fun fixture(): City {
        val c = City.generate(size = 32)
        return c.copy(
            terrain = null,
            tiles =
                c.tiles.mapIndexed { i, t ->
                    when {
                        i % 43 == 0 -> t.copy(building = "metro", anchor = i, subway = true)
                        i % 41 == 0 -> t.copy(elevation = 12, building = "arcology", anchor = i)
                        i % 7 == 0 -> t.copy(zone = Zone.RESIDENTIAL_DENSE, occupants = 80, elevation = i % 13)
                        i % 11 == 0 -> t.copy(road = true, rail = true, powerLine = true)
                        i % 13 == 0 -> t.copy(pipe = true, subway = true, fire = 3)
                        else -> t.copy(powered = i % 2 == 0, watered = i % 3 == 0, pollution = i % 101, crime = i % 101, traffic = i % 101)
                    }
                },
        )
    }

    private fun canvas(
        c: City,
        inspect: (Int) -> Unit = {},
    ): CityCanvas =
        CityCanvas(inspect, {}).apply {
            catalog = this@SceneCacheTest.catalog
            city = c
            setSize(preferredSize)
        }

    private fun paint(
        canvas: CityCanvas,
        region: Rectangle,
    ): BufferedImage {
        val image = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.translate(-region.x, -region.y)
            g.clip = region
            // Compare static scenery independently of blinking overlays.
            canvas.utilityAlertsVisible = false
            canvas.paint(g)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun reference(canvas: CityCanvas): BufferedImage {
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = canvas.background
            g.fillRect(0, 0, image.width, image.height)
            CityRenderer(canvas.city!!, canvas.catalog, canvas.layer, canvas.zoom).render(g, Rectangle(0, 0, image.width, image.height))
        } finally {
            g.dispose()
        }
        return image
    }

    private fun assertPixels(
        expected: BufferedImage,
        actual: BufferedImage,
        x: Int = 0,
        y: Int = 0,
    ) {
        val e = expected.getRGB(x, y, actual.width, actual.height, null, 0, actual.width)
        val a = actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width)
        assertTrue(e.contentEquals(a), "Cached scene differs from complete painter at ($x, $y)")
    }

    @Test fun `hover reuses chunks and inspects only when entering another cell`() =
        SwingUtilities.invokeAndWait {
            var inspections = 0
            val c = City.generate()
            val canvas = canvas(c) { inspections++ }
            val region = Rectangle(canvas.width / 2 - 300, canvas.height / 2 - 200, 600, 400)
            val initial = paint(canvas, region)
            val rendered = canvas.renderedChunkCount
            val projection = CityProjection(c.width, c.height, 1.0)
            val i = c.index(32, 32)
            val sx = projection.screenX(32, 32)
            val sy = projection.screenY(32, 32, c.tiles[i].elevation)
            repeat(20) {
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0, 0, sx, sy, 0, false))
                paint(canvas, region)
            }
            assertEquals(1, inspections)
            assertEquals(rendered, canvas.renderedChunkCount, "Hover rebuilt the scene")
            canvas.selected = -1
            assertPixels(initial, paint(canvas, region))
            var sizeChanges = 0
            canvas.addPropertyChangeListener("preferredSize") { sizeChanges++ }
            canvas.city = c.copy(month = 1, tiles = c.tiles.map { it.copy(pollution = 80, powered = true) })
            paint(canvas, region)
            assertEquals(rendered, canvas.renderedChunkCount, "Invisible simulation fields evicted surface chunks")
            assertEquals(0, sizeChanges)
        }

    @Test fun `chunk borders and viewport culling preserve complete painter pixels`() =
        SwingUtilities.invokeAndWait {
            val canvas = canvas(fixture())
            for (zoom in listOf(.5, 1.35, 2.0)) {
                canvas.zoom = zoom
                canvas.setSize(canvas.preferredSize)
                for (layer in Layer.entries) {
                    canvas.layer = layer
                    val expected = reference(canvas)
                    for (region in listOf(Rectangle(0, 0, 420, 260), Rectangle(canvas.width / 2 - 210, canvas.height / 2 - 130, 420, 260))) {
                        assertPixels(expected, paint(canvas, region), region.x, region.y)
                    }
                }
            }
        }

    @Test fun `incoming edits invalidate geometry connections overlays and building colors`() =
        SwingUtilities.invokeAndWait {
            val old = fixture()
            val i = old.index(16, 16)
            val changed =
                old.copy(
                    tiles =
                        old.tiles.mapIndexed { n, t ->
                            if (n in listOf(i, i - 1, i + 1, i - old.width)) {
                                t.copy(
                                    elevation = 9,
                                    road = true,
                                    rail = true,
                                    water = false,
                                    trees = true,
                                    zone = Zone.COMMERCIAL_DENSE,
                                    occupants = 60,
                                    powerLine = true,
                                    pipe = true,
                                    subway = true,
                                    building = "wind",
                                    anchor = n,
                                    powered = !t.powered,
                                    watered = !t.watered,
                                    pollution = 100 - t.pollution,
                                    crime = 100 - t.crime,
                                    traffic = 100 - t.traffic,
                                    landValue = 100 - t.landValue,
                                    fire = 4,
                                )
                            } else {
                                t
                            }
                        },
                )
            for (layer in Layer.entries) {
                val canvas = canvas(old)
                canvas.layer = layer
                val region = Rectangle(canvas.width / 2 - 250, canvas.height / 2 - 200, 500, 400)
                paint(canvas, region)
                canvas.city = changed
                val expected = reference(canvas)
                assertPixels(expected, paint(canvas, region), region.x, region.y)
                canvas.catalog = catalog.mapValues { (_, def) -> def.copy(color = 0xff00ff) }
                assertPixels(reference(canvas), paint(canvas, region), region.x, region.y)
            }
        }

    @Test fun `picking matches reverse painter order without requiring a paint`() =
        SwingUtilities.invokeAndWait {
            val c = fixture()
            val canvas = canvas(c)
            val random = Random(2000)
            for (zoom in listOf(.5, 1.35, 2.0)) {
                canvas.zoom = zoom
                val projection = CityProjection(c.width, c.height, zoom)
                val oracle =
                    buildList {
                        for (d in 0 until c.width + c.height - 1) {
                            for (y in 0 until c.height) {
                                val x = d - y
                                if (x in 0 until c.width) {
                                    val i = c.index(x, y)
                                    add(i to projection.diamond(x, y, c.tiles[i].elevation))
                                }
                            }
                        }
                    }.asReversed()
                repeat(1500) {
                    val point = Point(random.nextInt(canvas.preferredSize.width), random.nextInt(canvas.preferredSize.height))
                    val expected = oracle.firstOrNull { it.second.contains(point) }?.first ?: -1
                    assertEquals(expected, canvas.pick(point), "Point $point at zoom $zoom")
                }
            }
            canvas.city = null
            assertEquals(-1, canvas.pick(Point(100, 100)))
        }

    @Test fun `large map navigation bounds the number of retained chunks`() =
        SwingUtilities.invokeAndWait {
            val canvas = canvas(City.generate(size = 256))
            for (y in 256..2304 step 256) {
                for (x in 256..4352 step 256) {
                    paint(canvas, Rectangle(x, y, 256, 256))
                    assertTrue(canvas.cachedChunkCount <= SceneCache.MAX_CHUNKS)
                }
            }
            assertTrue(canvas.renderedChunkCount > SceneCache.MAX_CHUNKS)
            val region = Rectangle(256, 256, 256, 256)
            assertPixels(paint(canvas(City.generate(size = 256)), region), paint(canvas, region))
        }
}
