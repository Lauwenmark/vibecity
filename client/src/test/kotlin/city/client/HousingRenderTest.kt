package city.client

import city.core.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class HousingRenderTest {
    @Test fun `construction and empty finished housing remain visible at every rotation`() {
        val empty = Tile(zone = Zone.RESIDENTIAL_DENSE, housingCapacity = 0)
        val site = empty.copy(construction = HousingConstruction(40, 0, 120))
        val complete = empty.copy(housingCapacity = 40)
        assertNull(buildingGeometry(empty, null, 1.0))
        assertNotNull(buildingGeometry(site, null, 1.0))
        assertEquals(buildingGeometry(complete, null, 1.0), buildingGeometry(complete.copy(occupants = 1), null, 1.0))
        Files.createDirectories(Path.of("build/previews"))
        for (rotation in 0..3) {
            fun render(tile: Tile): BufferedImage {
                val city = City(width = 16, height = 16, tiles = List(256) { if (it == 119) tile else Tile() }, terrain = Terrain.flat(16, 16, 2))
                val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                CityRenderer(city, emptyMap(), Layer.SURFACE, 2.0, rotation = rotation).render(g, Rectangle(0, 0, image.width, image.height))
                g.dispose()
                return image
            }
            val ground = render(empty)
            val chantier = render(site)
            val finished = render(complete)

            fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            assertFalse(pixels(ground).contentEquals(pixels(chantier)))
            assertFalse(pixels(chantier).contentEquals(pixels(finished)))
            if (rotation == 0) ImageIO.write(chantier, "png", Path.of("build/previews/housing-construction.png").toFile())
        }
    }
}
