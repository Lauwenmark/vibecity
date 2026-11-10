package city.client

import city.core.*
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class MetroSpritesTest {
    private fun fixture(mask: Int): City {
        val city = City.generate(size = 16)
        val i = city.index(8, 8)
        val neighbors = intArrayOf(i - city.width, i + 1, i + city.width, i - 1)
        return city.copy(
            terrain = null,
            tiles =
                city.tiles.indices.map { n ->
                    Tile(elevation = 0, subway = n == i || neighbors.indices.any { mask and (1 shl it) != 0 && neighbors[it] == n })
                },
        )
    }

    @Test fun `all connection masks rotate and do not wrap at map boundaries`() {
        for (mask in 0..15) {
            val city = fixture(mask)
            for (rotation in 0..3) {
                val expected = (0..3).filter { mask and (1 shl it) != 0 }.sumOf { 1 shl ((it + rotation) % 4) }
                assertEquals(expected, MetroSprites.mask(city, city.index(8, 8), rotation))
            }
        }
        val city = fixture(15)
        val full = city.copy(tiles = city.tiles.map { it.copy(subway = true) })
        assertEquals(6, MetroSprites.mask(full, 0, 0))
        assertEquals(12, MetroSprites.mask(full, full.width - 1, 0))
        assertEquals(3, MetroSprites.mask(full, full.tiles.size - full.width, 0))
        assertEquals(9, MetroSprites.mask(full, full.tiles.lastIndex, 0))
    }

    @Test fun `packaged sprites have common dimensions and matching open rail endpoints`() {
        val images =
            (0..15).map { mask ->
                val resource = assertNotNull(javaClass.getResource("/city/client/metro/tunnel-${mask.toString().padStart(2, '0')}.png"))
                ImageIO.read(resource).also {
                    assertEquals(256, it.width)
                    assertEquals(128, it.height)
                    assertTrue(it.colorModel.hasAlpha())
                    assertEquals(0, it.getRGB(0, 0) ushr 24)
                }
            }
        // Sample inside each common connector, away from clipping on the exact boundary.
        for ((bit, point) in listOf(1 to (186 to 36), 2 to (186 to 92), 4 to (70 to 92), 8 to (70 to 36))) {
            val expected = images[bit].getRGB(point.first, point.second)
            for (mask in 0..15) if (mask and bit != 0) assertEquals(expected, images[mask].getRGB(point.first, point.second))
        }
    }

    @Test fun `render gallery of all connections rotations slopes and surface station`() {
        val gallery = BufferedImage(768, 640, BufferedImage.TYPE_INT_RGB)
        val g = gallery.createGraphics()
        g.color = Color(0x18232d)
        g.fillRect(0, 0, gallery.width, gallery.height)
        try {
            for (mask in 0..15) {
                val city = fixture(mask)
                val projection = CityProjection(16, 16, 4.0)
                val x = projection.screenX(8, 8)
                val y = projection.screenY(8, 8, 0)
                val cell = g.create(mask % 4 * 192, mask / 4 * 144, 192, 144) as java.awt.Graphics2D
                cell.translate(96 - x, 72 - y)
                CityRenderer(city, emptyMap(), Layer.WATER, 4.0).render(cell, Rectangle(x - 96, y - 72, 192, 144))
                cell.dispose()
                g.color = Color.WHITE
                g.drawString("mask $mask", mask % 4 * 192 + 8, mask / 4 * 144 + 16)
            }
            MetroSprites.station(g, 60, 610, 2.0)
            for (rotation in 0..3) {
                val city = fixture(7)
                val polygon = java.awt.Polygon(intArrayOf(0, 64, 0, -64), intArrayOf(-40, 0, 32, 0), 4)
                polygon.translate(220 + 150 * rotation, 610)
                MetroSprites.tunnel(g, polygon, MetroSprites.mask(city, city.index(8, 8), rotation))
            }
        } finally {
            g.dispose()
        }
        Files.createDirectories(Path.of("build/previews"))
        ImageIO.write(gallery, "png", Path.of("build/previews/metro-sprites.png").toFile())
    }
}
