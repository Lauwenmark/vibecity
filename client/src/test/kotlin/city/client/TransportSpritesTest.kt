package city.client

import city.core.*
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class TransportSpritesTest {
    private fun fixture(mask: Int): City {
        val center = 8 * 16 + 8
        val neighbors = intArrayOf(center - 16, center + 1, center + 16, center - 1)
        return City(
            width = 16,
            height = 16,
            terrain = Terrain.flat(16, 16, 0),
            tiles = List(256) { i -> Tile(road = i == center || neighbors.indices.any { mask and (1 shl it) != 0 && neighbors[it] == i }) },
        )
    }

    @Test fun `connections rotate without wrapping and respect network type and slopes`() {
        for (mask in 0..15) {
            val city = fixture(mask)
            for (rotation in 0..3) {
                val expected = (0..3).filter { mask and (1 shl it) != 0 }.sumOf { 1 shl ((it + rotation) % 4) }
                assertEquals(expected, TransportSprites.mask(city, city.index(8, 8), rotation, rail = false))
                assertEquals(0, TransportSprites.mask(city, city.index(8, 8), rotation, rail = true))
                val rail = city.copy(tiles = city.tiles.map { it.copy(road = false, rail = it.road) })
                assertEquals(expected, TransportSprites.mask(rail, rail.index(8, 8), rotation, rail = true))
            }
        }
        val full = fixture(15).copy(tiles = List(256) { Tile(road = true, rail = true) })
        for (rail in listOf(false, true)) {
            assertEquals(6, TransportSprites.mask(full, 0, 0, rail))
            assertEquals(12, TransportSprites.mask(full, 15, 0, rail))
            assertEquals(3, TransportSprites.mask(full, 240, 0, rail))
            assertEquals(9, TransportSprites.mask(full, 255, 0, rail))
        }
        val slope = full.withTerrain(Terrain(16, 16, List(289) { if (it % 17 == 9 && it / 17 == 8) 1 else 0 }))
        for (rail in listOf(false, true)) {
            val index = slope.index(8, 8)
            assertFalse(slope.transportConnects(index, index + 1))
            assertEquals(0, TransportSprites.mask(slope, index, 0, rail) and 2)
        }
    }

    @Test fun `all sprites fit the exact diamond and share their open connectors`() {
        for (kind in listOf("road", "rail", "rail-overlay")) {
            val images =
                (0..15).map { mask ->
                    ImageIO.read(assertNotNull(javaClass.getResource("/city/client/transport/$kind-${mask.toString().padStart(2, '0')}.png")))
                }
            for (image in images) {
                assertEquals(256, image.width)
                assertEquals(128, image.height)
                assertTrue(image.colorModel.hasAlpha())
                for (y in 0 until 128) {
                    for (x in 0 until 256) {
                        if (kotlin.math.abs(x - 128) / 2.0 + kotlin.math.abs(y - 64) > 65) {
                            assertEquals(0, image.getRGB(x, y) ushr 24, "$kind outside 2:1 diamond at $x,$y")
                        }
                    }
                }
            }
            // Entire central port strips, not just a rail's centre pixel.
            for (side in 0..3) {
                val bit = 1 shl side
                for (depth in listOf(.04, .08, .12)) {
                    for (offset in -18..18) {
                        var u = .5 + offset / 100.0
                        var v = depth
                        repeat(side) {
                            val previous = u
                            u = 1 - v
                            v = previous
                        }
                        val x = (128 + 128 * u - 128 * v).toInt()
                        val y = (64 * u + 64 * v).toInt()
                        val expected = images[bit].getRGB(x, y)
                        for (mask in 0..15) if (mask and bit != 0) assertEquals(expected, images[mask].getRGB(x, y), "$kind mask $mask port $bit at $x,$y")
                    }
                }
            }
        }
    }

    @Test fun `surface networks render on slopes and crossings at every camera rotation`() {
        val flat = fixture(15)
        val terrain = Terrain(16, 16, List(289) { if (it % 17 <= 8) 0 else 1 })
        val gallery = BufferedImage(1024, 768, BufferedImage.TYPE_INT_RGB)
        val gg = gallery.createGraphics()
        gg.color = Color(0x243b31)
        gg.fillRect(0, 0, gallery.width, gallery.height)
        try {
            for (mode in 0..2) {
                val city =
                    if (mode == 2) {
                        flat.copy(
                            tiles = flat.tiles.mapIndexed { i, t -> t.copy(road = t.road && i % 16 == 8, rail = t.road && i / 16 == 8) },
                        )
                    } else {
                        flat.withTerrain(terrain).let { c ->
                            c.copy(tiles = c.tiles.map { it.copy(road = mode == 0 && it.road, rail = mode == 1 && it.road) })
                        }
                    }
                for (rotation in 0..3) {
                    val projection = CityProjection(16, 16, 4.0, rotation)
                    val cx = projection.screenX(8, 8)
                    val cy = projection.centerY(8, 8, city.surface(city.index(8, 8)))
                    val renderer = CityRenderer(city, emptyMap(), Layer.SURFACE, 4.0, rotation = rotation)
                    val bounds = Rectangle(cx - 128, cy - 128, 256, 256)
                    val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    g.translate(-bounds.x, -bounds.y)
                    renderer.render(g, bounds)
                    g.dispose()
                    val baseline = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
                    val bg = baseline.createGraphics()
                    bg.translate(-bounds.x, -bounds.y)
                    CityRenderer(city.copy(tiles = city.tiles.map { it.copy(road = false, rail = false) }), emptyMap(), Layer.SURFACE, 4.0, rotation = rotation).render(bg, bounds)
                    bg.dispose()
                    assertTrue((0 until 256).sumOf { y -> (0 until 256).count { x -> image.getRGB(x, y) != baseline.getRGB(x, y) } } > 200, "Missing transport rendering at $mode,$rotation")
                    val clipped = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
                    val cg = clipped.createGraphics()
                    cg.translate(-bounds.x, -bounds.y)
                    for (y in 0 until 256 step 64) {
                        for (x in 0 until 256 step 64) {
                            val clip = Rectangle(bounds.x + x, bounds.y + y, 64, 64)
                            cg.clip = clip
                            renderer.render(cg, clip)
                        }
                    }
                    cg.dispose()
                    for (y in 0 until 256) {
                        for (x in 0 until 256) {
                            val a = image.getRGB(x, y)
                            val b = clipped.getRGB(x, y)
                            for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) <= 2, "Clipped network at $mode,$rotation,$x,$y")
                        }
                    }
                    gg.drawImage(image, rotation * 256, mode * 256, null)
                }
            }
        } finally {
            gg.dispose()
        }
        Files.createDirectories(Path.of("build/previews"))
        ImageIO.write(gallery, "png", Path.of("build/previews/transport-sprites.png").toFile())
    }

    @Test fun `rail crossing overlays the road without covering it in ballast`() {
        val base = fixture(15)
        val city = base.copy(tiles = base.tiles.mapIndexed { i, t -> t.copy(road = t.road && i % 16 == 8, rail = t.road && i / 16 == 8) })
        val polygon = java.awt.Polygon(intArrayOf(128, 256, 128, 0), intArrayOf(0, 64, 128, 64), 4)

        fun tile(source: City): BufferedImage {
            val image = BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            TransportSprites.draw(g, polygon, source, source.index(8, 8), 0)
            g.dispose()
            return image
        }
        val road = tile(city.copy(tiles = city.tiles.map { it.copy(rail = false) }))
        val both = tile(city)
        var unchanged = 0
        var changed = 0
        for (y in 0 until 128) {
            for (x in 0 until 256) {
                if ((road.getRGB(x, y) ushr 24) == 255) {
                    if (road.getRGB(x, y) == both.getRGB(x, y)) unchanged++ else changed++
                }
            }
        }
        assertTrue(unchanged > 1000, "Road surface should remain visible at the crossing")
        assertTrue(changed > 100, "Crossing rails should remain visible over the road")
    }
}
