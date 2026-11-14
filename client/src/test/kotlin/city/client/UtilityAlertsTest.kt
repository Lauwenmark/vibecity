package city.client

import city.core.*
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*

class UtilityAlertsTest {
    @Test fun `footprint shortages are combined and empty zones ignored`() {
        val city =
            City(
                width = 16,
                height = 16,
                tiles =
                    listOf(
                        Tile(building = "school", anchor = 0, powered = true, watered = false),
                        Tile(building = "school", anchor = 0, powered = false, watered = true),
                        Tile(zone = Zone.RESIDENTIAL_LIGHT),
                        Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1, powered = true, watered = true),
                    ) + List(252) { Tile() },
            )
        assertEquals(listOf(UtilityAlert(0, power = true, water = true)), collectUtilityAlerts(city))
    }

    @Test fun `paused alerts blink and supply changes repaint without rebuilding scenery`() {
        SwingUtilities.invokeAndWait {
            val city = City(width = 16, height = 16, tiles = List(256) { if (it == 136) Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 10) else Tile() })
            val canvas = CityCanvas({}, {})
            canvas.showSatisfaction = false
            canvas.animatedTraffic = false
            canvas.city = city.copy(rules = city.rules.copy(speed = 0))
            canvas.setSize(canvas.preferredSize)

            fun pixels(): List<Int> {
                val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                canvas.paint(g)
                g.dispose()
                return image.getRGB(0, 0, image.width, image.height, null, 0, image.width).toList()
            }
            val visible = pixels()
            val chunks = canvas.renderedChunkCount
            canvas.utilityAlertsVisible = false
            val hidden = pixels()
            assertNotEquals(visible, hidden)
            assertEquals(chunks, canvas.renderedChunkCount)
            canvas.utilityAlertsVisible = true
            canvas.city = canvas.city!!.copy(tiles = city.tiles.map { it.copy(powered = true, watered = true) })
            assertEquals(hidden, pixels())
            assertEquals(chunks, canvas.renderedChunkCount)
        }
    }
}
