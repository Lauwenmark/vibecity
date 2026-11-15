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
    @Test fun `connected poles preserve clipped rendering on slopes at every rotation`() {
        val base = City.generate(size = 16)
        val city =
            base.copy(
                terrain = null,
                tiles =
                    base.tiles.indices.map { i ->
                        val x = i % 16
                        val y = i / 16
                        Tile(elevation = x / 3, powerLine = (x in 4..11 && y == 8) || (x == 8 && y in 5..11), road = y == 9)
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
                            assertTrue(abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) <= 2, "Seam at $x,$y, zoom $zoom, rotation $rotation")
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
