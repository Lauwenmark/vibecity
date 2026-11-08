package city.client

import city.core.*
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

class RenderTest {
    @Test fun `renders evolved city and all diagnostic layers headlessly`() =
        runBlocking {
            var city = demoCity()
            repeat(12) { city = Simulation(Catalog()).step(city) }
            SwingUtilities.invokeAndWait {
                val canvas = CityCanvas({}, {})
                canvas.catalog = Catalog().all().associateBy { it.id }
                canvas.zoom = .7
                canvas.city = city
                canvas.setSize(canvas.preferredSize)
                Files.createDirectories(Path.of("build/previews"))
                for (layer in Layer.entries) {
                    canvas.layer = layer
                    val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
                    val graphics = image.createGraphics()
                    canvas.paint(graphics)
                    graphics.dispose()
                    assertNotEquals(image.getRGB(0, 0), image.getRGB(image.width / 2, image.height / 2))
                    if (layer == Layer.SURFACE) ImageIO.write(image, "png", Path.of("build/previews/city.png").toFile())
                }
            }
        }
}
