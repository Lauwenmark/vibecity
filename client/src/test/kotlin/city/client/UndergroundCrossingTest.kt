package city.client

import city.core.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.*

class UndergroundCrossingTest {
    @Test fun `both construction orders preserve and display crossing networks`() {
        for (tools in listOf(listOf("pipe", "subway"), listOf("subway", "pipe"))) {
            val initial = City.generate(size = 16)
            var city = initial.copy(terrain = null, tiles = initial.tiles.map { Tile(elevation = 0) })
            val commands = Commands(Catalog())
            for (tool in tools) {
                for (offset in -1..1) {
                    city =
                        commands.apply(
                            city,
                            Request(type = "BUILD", tool = tool, x = 8 + if (tool == "pipe") offset else 0, y = 8 + if (tool == "subway") offset else 0),
                        )
                }
            }
            assertTrue(city.tiles[city.index(8, 8)].pipe)
            assertTrue(city.tiles[city.index(8, 8)].subway)
            for (rotation in 0..3) {
                val projection = CityProjection(city.width, city.height, 2.0, rotation)
                val bounds = projection.diamond(8, 8, 0).bounds
                val image = BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                try {
                    CityRenderer(city, emptyMap(), Layer.WATER, 2.0, rotation = rotation).render(g, Rectangle(0, 0, image.width, image.height))
                } finally {
                    g.dispose()
                }
                val pixels = image.getRGB(bounds.x, bounds.y, bounds.width, bounds.height, null, 0, bounds.width)
                assertTrue(pixels.any { it and 0xffffff == 0x63dcf2 }, "Water pipe must remain visible")
                assertTrue(pixels.any { it and 0xffffff == 0xc1a1df }, "Subway must remain visible at crossing")
            }
        }
    }
}
