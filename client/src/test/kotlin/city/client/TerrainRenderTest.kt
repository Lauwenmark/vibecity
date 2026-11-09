package city.client

import city.core.*
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.random.Random
import kotlin.test.*

class TerrainRenderTest {
    private fun flat() = City(width = 16, height = 16, tiles = List(256) { Tile() }, terrain = Terrain.flat(16, 16))

    private fun view(city: City) =
        CityCanvas({}, {}).apply {
            catalog = Catalog().all().associateBy { it.id }
            this.city = city
            setSize(preferredSize)
        }

    private fun image(canvas: CityCanvas): BufferedImage {
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            canvas.paint(g)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    @Test fun `placement overlay covers every footprint cell at every rotation without rebuilding scenery`() =
        SwingUtilities.invokeAndWait {
            val city = flat()
            val canvas = view(city)
            val definition = canvas.catalog.values.first { it.size > 1 }
            val anchor = city.index(5, 5)
            val expected =
                (5 until 5 + definition.size).flatMap { y ->
                    (5 until 5 + definition.size).map { x -> city.index(x, y) }
                }
            canvas.selected = anchor
            for (rotation in 0..3) {
                canvas.rotation = rotation
                canvas.tool = "inspect"
                val before = image(canvas)
                val chunks = canvas.renderedChunkCount
                canvas.tool = definition.id
                assertEquals(expected, canvas.selectionCells(anchor))
                val after = image(canvas)
                val p = CityProjection(city.width, city.height, canvas.zoom, rotation)
                for (cell in expected) {
                    val x = p.screenX(cell % city.width, cell / city.width)
                    val y = p.centerY(cell % city.width, cell / city.width, city.surface(cell))
                    assertNotEquals(before.getRGB(x, y), after.getRGB(x, y))
                }
                assertEquals(chunks, canvas.renderedChunkCount)
                assertEquals(listOf(city.tiles.lastIndex), canvas.selectionCells(city.tiles.lastIndex))
            }
        }

    @Test fun `building front base rests on the centered ground footprint`() {
        val definition = BuildingDef("test", "Test", 0, 0, color = 0xb06030)
        for (rotation in 0..3) {
            for (zoom in listOf(1.0, 2.0, 4.0)) {
                for (elevation in listOf(0, 6, 12)) {
                    val index = 8 * 16 + 8
                    val tile = Tile(building = definition.id, anchor = index)
                    val city =
                        flat().copy(
                            terrain = Terrain.flat(16, 16, elevation),
                            tiles = List(256) { if (it == index) tile else Tile() },
                        )
                    val p = CityProjection(16, 16, zoom, rotation)
                    val geometry = buildingGeometry(tile, definition, zoom)!!
                    val image = BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    CityRenderer(city, mapOf(definition.id to definition), Layer.SURFACE, zoom, rotation = rotation)
                        .render(g, Rectangle(0, 0, image.width, image.height))
                    g.dispose()
                    val x = p.screenX(8, 8) + 2
                    val y = p.centerY(8, 8, city.surface(index)) + geometry.depth - 3
                    assertEquals(Color(definition.color).rgb, image.getRGB(x, y), "Base at $zoom, $rotation, $elevation")
                }
            }
        }
    }

    @Test fun `a changed corner invalidates the cache even if every tile minimum stays unchanged`() =
        SwingUtilities.invokeAndWait {
            val old = flat()
            val terrain = old.terrain!!
            val changed = old.withTerrain(terrain.copy(vertices = terrain.vertices.mapIndexed { i, z -> if (i == 8 * 17 + 8) z + 1 else z }))
            assertEquals(old.tiles, changed.tiles)
            val canvas = view(old)
            val before = image(canvas)
            val count = canvas.renderedChunkCount
            canvas.city = changed
            val after = image(canvas)
            assertTrue(canvas.renderedChunkCount > count)
            assertFalse(pixels(before).contentEquals(pixels(after)))
            assertTrue(pixels(image(view(changed))).contentEquals(pixels(after)))
            val warm = canvas.renderedChunkCount
            canvas.selected = changed.index(7, 7)
            image(canvas)
            assertEquals(warm, canvas.renderedChunkCount)
        }

    @Test fun `shared edges project identically and picking follows the slope polygons`() =
        SwingUtilities.invokeAndWait {
            val city = City.generate(size = 16)
            val random = Random(2026)
            val canvas = view(city)
            for (zoom in listOf(.5, .85, 1.35, 2.0)) {
                canvas.zoom = zoom
                val p = CityProjection(city.width, city.height, zoom)
                for (y in 0 until city.height) {
                    for (x in 0 until city.width - 1) {
                        val a = p.polygon(city, city.index(x, y))
                        val b = p.polygon(city, city.index(x + 1, y))
                        assertEquals(a.xpoints[1], b.xpoints[0])
                        assertEquals(a.ypoints[1], b.ypoints[0])
                        assertEquals(a.xpoints[2], b.xpoints[3])
                        assertEquals(a.ypoints[2], b.ypoints[3])
                    }
                }
                val oracle =
                    city.tiles.indices
                        .sortedWith(compareBy<Int> { it % city.width + it / city.width }.thenBy { it / city.width })
                        .map { it to p.polygon(city, it) }
                        .asReversed()
                repeat(1000) {
                    val point = Point(random.nextInt(canvas.preferredSize.width), random.nextInt(canvas.preferredSize.height))
                    assertEquals(oracle.firstOrNull { it.second.contains(point) }?.first ?: -1, canvas.pick(point))
                }
            }
        }

    @Test fun `slopes and inclined networks have no chunk seams on any layer`() =
        SwingUtilities.invokeAndWait {
            val terrain = Terrain(16, 16, List(289) { if (it % 17 <= 7) 2 else 3 })
            val city =
                flat().withTerrain(terrain).let { c ->
                    c.copy(
                        tiles =
                            c.tiles.mapIndexed { i, t ->
                                t.copy(road = i / 16 == 8, powerLine = i / 16 == 10, pipe = i / 16 == 12, water = i == 7 + 5 * 16, building = if (i == 7 + 5 * 16) "hydro" else null, anchor = if (i == 7 + 5 * 16) i else -1)
                            },
                    )
                }
            val canvas = view(city)
            for (rotation in 0..3) {
                canvas.rotation = rotation
                for (zoom in listOf(.5, 1.35, 4.0)) {
                    canvas.zoom = zoom
                    canvas.setSize(canvas.preferredSize)
                    for (layer in Layer.entries) {
                        canvas.layer = layer
                        val expected = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
                        val g = expected.createGraphics()
                        try {
                            g.color = canvas.background
                            g.fillRect(0, 0, canvas.width, canvas.height)
                            CityRenderer(city, canvas.catalog, layer, zoom, rotation = rotation).render(g, Rectangle(0, 0, canvas.width, canvas.height))
                        } finally {
                            g.dispose()
                        }
                        assertTrue(pixels(expected).contentEquals(pixels(image(canvas))), "Mismatch at $zoom, $layer, $rotation")
                    }
                }
            }
        }
}
