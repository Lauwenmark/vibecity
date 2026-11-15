package city.client

import city.core.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

class SatisfactionRenderTest {
    @Test fun `faces follow roofs and disappear without rebuilding scenery`() {
        SwingUtilities.invokeAndWait {
            val cells = listOf(85, 88, 91)
            val scores = listOf(90.0, 55.0, 20.0)
            val colors = listOf(0x88d679, 0xffd166, 0xef7770)
            val city = City(width = 16, height = 16, tiles = List(256) { if (it in cells) Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1) else Tile() }, households = cells.mapIndexed { index, cell -> Household(cell, listOf(Citizen(index + 1L, "Résident", 30, position = cell * TRAVEL_MODE_COUNT, wellbeing = Wellbeing(satisfaction = scores[index])))) })
            val canvas = CityCanvas({}, {})
            canvas.city = city

            fun render(): BufferedImage {
                canvas.setSize(canvas.preferredSize)
                val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                canvas.paint(g)
                g.dispose()
                return image
            }
            for (rotation in 0..3) {
                canvas.rotation = rotation
                canvas.zoom = if (rotation == 0) 2.0 else 1.0
                canvas.showSatisfaction = true
                val image = render()
                val chunks = canvas.renderedChunkCount
                val p = CityProjection(16, 16, canvas.zoom, rotation)
                val points =
                    cells.map { cell ->
                        p.screenX(cell % 16, cell / 16) to p.centerY(cell % 16, cell / 16, city.surface(cell)) - buildingGeometry(city.tiles[cell], null, canvas.zoom)!!.height - 19
                    }
                for ((index, point) in points.withIndex()) assertEquals(colors[index], image.getRGB(point.first, point.second) and 0xffffff)
                if (rotation == 0) {
                    Files.createDirectories(Path.of("build/previews"))
                    ImageIO.write(image, "png", Path.of("build/previews/satisfaction.png").toFile())
                }
                canvas.showSatisfaction = false
                val hidden = render()
                assertEquals(chunks, canvas.renderedChunkCount)
                for ((index, point) in points.withIndex()) assertNotEquals(colors[index], hidden.getRGB(point.first, point.second) and 0xffffff)
            }
        }
    }
}
