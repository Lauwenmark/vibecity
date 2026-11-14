package city.client

import city.core.*
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class UtilitySpritesTest {
    @Test fun `civic and power foundations follow the two to one footprint edges`() {
        for (id in BuildingSprites.allIds - "pump") {
            val sprite = ImageIO.read(assertNotNull(javaClass.getResource(BuildingSprites.resourcePath(id))))
            assertEquals(512, sprite.width)
            // A raised building may project above its plot, never below its two front edges.
            // Allow two source pixels of antialiasing (less than half a pixel at normal zoom).
            for (x in 0 until sprite.width) {
                val edge = sprite.height - 1 - kotlin.math.abs(x - (sprite.width - 1) / 2.0) / 2.0
                for (y in 0 until sprite.height) {
                    if ((sprite.getRGB(x, y) ushr 24) > 32) {
                        assertTrue(y <= edge + 2, "$id spills beyond its footprint at $x,$y (edge $edge)")
                    }
                }
            }
        }
    }

    @Test fun `utility footprints render identically in whole and clipped scenes at every rotation`() {
        val catalog = standardBuildings().associateBy { it.id }
        val gallery = BufferedImage(1200, 1300, BufferedImage.TYPE_INT_RGB)
        val gg = gallery.createGraphics()
        gg.color = Color(0x18232d)
        gg.fillRect(0, 0, gallery.width, gallery.height)
        for ((ordinal, id) in BuildingSprites.allIds.withIndex()) {
            val size = catalog.getValue(id).size
            val base = City.generate(size = 16)
            val anchor = base.index(6, 6)
            val city =
                base.copy(
                    terrain = null,
                    tiles =
                        base.tiles.indices.map { i ->
                            Tile(
                                elevation = 0,
                                building = if (i % 16 in 6 until 6 + size && i / 16 in 6 until 6 + size) id else null,
                                anchor = if (i % 16 in 6 until 6 + size && i / 16 in 6 until 6 + size) anchor else -1,
                            )
                        },
                )
            val resource = assertNotNull(javaClass.getResource(BuildingSprites.resourcePath(id)))
            val sprite = ImageIO.read(resource)
            assertTrue(sprite.colorModel.hasAlpha())
            assertEquals(0, sprite.getRGB(0, 0) ushr 24)
            for (rotation in 0..3) {
                val projection = CityProjection(16, 16, 2.0, rotation)
                val renderer = CityRenderer(city, catalog, Layer.SURFACE, 2.0, rotation = rotation)

                fun render(chunks: Boolean): BufferedImage {
                    val result = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
                    val g = result.createGraphics()
                    if (chunks) {
                        for (y in 0 until 800 step 100) {
                            for (x in 0 until 1200 step 100) {
                                val clip = Rectangle(x, y, 100, 100)
                                g.clip = clip
                                renderer.render(g, clip)
                            }
                        }
                    } else {
                        renderer.render(g, Rectangle(0, 0, 1200, 800))
                    }
                    g.dispose()
                    return result
                }
                val whole = render(false)
                val chunks = render(true)
                // Java2D bilinear interpolation can round by one at a clip boundary.
                for (py in 0 until 800) {
                    for (px in 0 until 1200) {
                        val a = whole.getRGB(px, py)
                        val b = chunks.getRGB(px, py)
                        for (shift in listOf(0, 8, 16)) {
                            assertTrue(
                                kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) <= 2,
                                "$id rotation $rotation at $px,$py: $a vs $b",
                            )
                        }
                    }
                }
                if (rotation == 0) {
                    val cx = projection.screenX(6, 6)
                    val cy = projection.screenY(6, 6, 0)
                    val x = ordinal % 4 * 300
                    val y = ordinal / 4 * 320
                    gg.drawImage(whole, x, y + 25, x + 300, y + 300, cx - 150, cy - 160, cx + 150, cy + 115, null)
                    gg.color = Color.WHITE
                    gg.drawString(id, x + 10, y + 20)
                }
            }
        }
        gg.dispose()
        Files.createDirectories(Path.of("build/previews"))
        ImageIO.write(gallery, "png", Path.of("build/previews/utility-sprites.png").toFile())
    }
}
