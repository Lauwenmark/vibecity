package city.client

import city.core.*
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

class PowerLineSpritesTest {
    @Test fun `transport supports stay in opposite corners at every rotation and elevation`() {
        val base = City.generate(size = 16)
        val index = base.index(8, 8)
        for ((road, rail) in listOf(true to false, false to true, true to true)) {
            val city = base.copy(tiles = base.tiles.mapIndexed { i, t -> if (i == index) t.copy(road = road, rail = rail, powerLine = true) else t })
            for (rotation in 0..3) {
                val projection = CityProjection(16, 16, 1.35, rotation)
                val points = PowerLineSprites.bases(city, index, projection)
                val polygon = projection.polygon(city, index)
                assertEquals(2, points.size)
                for ((i, fraction) in listOf(0.08, 0.92).withIndex()) {
                    val n = rotation
                    val s = (n + 2) % 4
                    assertEquals(polygon.xpoints[n] * (1 - fraction) + polygon.xpoints[s] * fraction, points[i].x, 0.0001)
                    assertEquals(polygon.ypoints[n] * (1 - fraction) + polygon.ypoints[s] * fraction, points[i].y, 0.0001)
                }
                assertTrue(points[0].distance(points[1]) > 12 * projection.zoom)
            }
        }
        assertEquals(1, PowerLineSprites.bases(base.copy(tiles = base.tiles.map { Tile() }), index, CityProjection(16, 16, 1.0)).size)
    }

    @Test fun `service drops target only adjacent supplied buildings and occupied zones`() {
        val base = City.generate(size = 16)
        val index = base.index(8, 8)
        val city =
            base.copy(
                tiles =
                    base.tiles.indices.map { i ->
                        when (i) {
                            index -> Tile(powerLine = true)
                            index - 1 -> Tile(building = "school", anchor = index - 1, powered = true)
                            index + 1 -> Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 10, powered = true)
                            index - 16 -> Tile(building = "school", anchor = index - 16, powered = false)
                            index + 16 -> Tile(zone = Zone.RESIDENTIAL_LIGHT, powered = true)
                            else -> Tile()
                        }
                    },
            )
        assertEquals(setOf(index - 1, index + 1), PowerLineSprites.suppliedBuildings(city, index).toSet())
    }

    @Test fun `poles beside the front edges of a zoo remain in front of its sprite`() {
        val catalog = standardBuildings().associateBy { it.id }
        val size = catalog.getValue("zoo").size
        val base = City.generate(size = 16)
        for (rotation in 0..3) {
            val projection = CityProjection(16, 16, 2.0, rotation)
            val footprint = (6 until 6 + size).flatMap { y -> (6 until 6 + size).map { x -> base.index(x, y) } }
            val views = footprint.map { projection.view(it % 16, it / 16) }
            val pole = projection.worldIndex(views.maxOf { it.x } + 1, views.minOf { it.y } + 1)
            val city =
                base.copy(
                    terrain = null,
                    tiles =
                        base.tiles.indices.map { i ->
                            Tile(elevation = 0, building = if (i in footprint) "zoo" else null, anchor = if (i in footprint) base.index(6, 6) else -1, powerLine = i == pole)
                        },
                )

            fun render(withPole: Boolean): BufferedImage {
                val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                val scene = if (withPole) city else city.copy(tiles = city.tiles.map { it.copy(powerLine = false) })
                CityRenderer(scene, catalog, Layer.SURFACE, 2.0, rotation = rotation).render(g, Rectangle(0, 0, 1200, 800))
                if (!withPole) PowerLineSprites.draw(g, city, pole, projection)
                g.dispose()
                return image
            }
            val actual = render(true)
            val expected = render(false)
            assertContentEquals(expected.getRGB(0, 0, 1200, 800, null, 0, 1200), actual.getRGB(0, 0, 1200, 800, null, 0, 1200))
            if (rotation == 0) {
                Files.createDirectories(Path.of("build/previews"))
                ImageIO.write(actual, "png", Path.of("build/previews/power-zoo.png").toFile())
            }
        }
    }

    @Test fun `connected poles preserve clipped rendering on slopes at every rotation`() {
        val base = City.generate(size = 16)
        val city =
            base.copy(
                terrain = null,
                tiles =
                    base.tiles.indices.map { i ->
                        val x = i % 16
                        val y = i / 16
                        Tile(
                            elevation = x / 3,
                            powerLine = (x in 4..11 && y == 8) || (x == 8 && y in 5..11),
                            road = y == 8,
                            rail = x == 8,
                            zone = if (y == 7 && x in 4..11 && x != 8) Zone.RESIDENTIAL_LIGHT else Zone.NONE,
                            occupants = if (y == 7 && x in 4..11 && x != 8) 10 else 0,
                            powered = x % 2 == 0,
                        )
                    },
            )
        for (zoom in listOf(0.5, 1.0, 2.0)) {
            for (rotation in 0..3) {
                val renderer = CityRenderer(city, emptyMap(), Layer.SURFACE, zoom, rotation = rotation)

                fun render(chunkSize: Int): BufferedImage {
                    val image = BufferedImage(1200, 700, BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    g.color = Color(0x26342c)
                    g.fillRect(0, 0, image.width, image.height)
                    for (y in 0 until image.height step chunkSize) {
                        for (x in 0 until image.width step chunkSize) {
                            val clip = Rectangle(x, y, chunkSize, chunkSize)
                            g.clip = clip
                            renderer.render(g, clip)
                        }
                    }
                    g.dispose()
                    return image
                }
                val whole = render(1200)
                val chunks = render(97)
                for (y in 0 until whole.height) {
                    for (x in 0 until whole.width) {
                        val a = whole.getRGB(x, y)
                        val b = chunks.getRGB(x, y)
                        for (shift in listOf(0, 8, 16)) {
                            if (abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) > 2) {
                                Files.createDirectories(Path.of("build/previews"))
                                ImageIO.write(whole, "png", Path.of("build/previews/power-whole.png").toFile())
                                ImageIO.write(chunks, "png", Path.of("build/previews/power-chunks.png").toFile())
                                fail("Seam at $x,$y, zoom $zoom, rotation $rotation: $a vs $b")
                            }
                        }
                    }
                }
                if (zoom == 2.0 && rotation == 0) {
                    Files.createDirectories(Path.of("build/previews"))
                    ImageIO.write(whole, "png", Path.of("build/previews/power-lines.png").toFile())
                }
            }
        }
    }
}
