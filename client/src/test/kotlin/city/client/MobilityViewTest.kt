package city.client

import city.core.*
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.test.*

class MobilityViewTest {
    private fun city(): City {
        val tiles = MutableList(256) { Tile() }
        tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 12, powered = true)
        tiles[30] = Tile(zone = Zone.COMMERCIAL_DENSE, occupants = 60, powered = true)
        for (x in 1..14) tiles[32 + x] = Tile(road = true)
        return runBlocking { MobilitySimulation(Catalog()).simulate(City(width = 16, height = 16, tiles = tiles)) }
    }

    @Test fun `inspector selects individual need and clears invalidated plans`() {
        SwingUtilities.invokeAndWait {
            var selected: TravelPath? = null
            var position: Int? = null
            var hour = -1
            val panel =
                MobilityPanel { p, at, h ->
                    selected = p
                    position = at
                    hour = h
                }
            val c = city()
            panel.updateSnapshot(c, 17)
            assertEquals(6, panel.table.rowCount)
            val row = (0 until panel.table.rowCount).single { panel.table.getValueAt(it, 0) == "Courses" }
            panel.table.setRowSelectionInterval(row, row)
            assertNotNull(selected)
            assertEquals(30, selected!!.destination)
            assertNotNull(position)
            assertEquals(8, hour)

            fun children(root: Container): List<Component> = root.components.flatMap { listOf(it) + if (it is Container) children(it) else emptyList() }
            children(panel).filterIsInstance<JSpinner>().single().value = 12
            children(panel).filterIsInstance<JSlider>().single().value = 23 * 60
            assertEquals(17, position)
            assertEquals(23, hour)
            panel.updateSnapshot(c.copy(mobility = null))
            assertEquals(0, panel.table.rowCount)
            assertNull(selected)
            assertNull(position)
        }
    }

    @Test fun `route overlay changes pixels without rebuilding cached scenery and clears cleanly`() {
        SwingUtilities.invokeAndWait {
            val c = city()
            val canvas =
                CityCanvas({}, {}).apply {
                    city = c
                    setSize(preferredSize)
                }

            fun paint(): IntArray {
                val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                canvas.paint(g)
                g.dispose()
                return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            }
            val initial = paint()
            val rendered = canvas.renderedChunkCount
            canvas.itinerary =
                c.mobility!!
                    .homes
                    .single()
                    .paths
                    .first()
            canvas.residentPosition = 17
            assertFalse(initial.contentEquals(paint()))
            assertEquals(rendered, canvas.renderedChunkCount)
            canvas.itinerary = null
            canvas.residentPosition = null
            assertContentEquals(initial, paint())
            assertEquals(rendered, canvas.renderedChunkCount)
        }
    }

    @Test fun `hourly traffic colors change with observed hour`() {
        val c =
            city().let {
                it.copy(
                    mobility =
                        it.mobility!!.copy(
                            homes =
                                listOf(
                                    it.mobility!!
                                        .homes
                                        .single()
                                        .copy(loads = listOf(TrafficLoad(40, TravelMode.ROAD, List(24) { h -> if (h == 8) 120 else 0 }))),
                                ),
                        ),
                )
            }

        fun image(hour: Int): IntArray {
            val image = BufferedImage(700, 500, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            CityRenderer(c, emptyMap(), Layer.TRAFFIC, 1.0, hour).render(g, Rectangle(0, 0, 700, 500))
            g.dispose()
            return image.getRGB(0, 0, 700, 500, null, 0, 700)
        }
        assertFalse(image(8).contentEquals(image(0)))
    }

    @Test fun `inspector exposes weekly scored memory actual routes and return journeys`() {
        SwingUtilities.invokeAndWait {
            var path: TravelPath? = null
            val c = city()
            val panel = MobilityPanel { p, _, _ -> path = p }
            panel.updateSnapshot(c, 17)
            val food = (0 until panel.table.rowCount).single { panel.table.getValueAt(it, 0) == "Courses" }
            panel.table.setRowSelectionInterval(food, food)

            fun children(root: Container): List<Component> = root.components.flatMap { listOf(it) + if (it is Container) children(it) else emptyList() }
            val combos = children(panel).filterIsInstance<JComboBox<*>>()
            val view = combos.single { it.getItemAt(0) == "Plan du matin" }
            view.selectedIndex = 1
            assertNotNull(path)
            val weekday = combos.single { it.getItemAt(0) == "Lundi" }
            view.selectedIndex = 2
            weekday.selectedIndex = 6
            val memory = combos.single { (it.getItemAt(0) as? String)?.startsWith("Itinéraire") == true }
            assertTrue(memory.itemCount > 0)
            assertTrue(memory.getItemAt(0).toString().contains("score"))
            assertEquals(17, path!!.nodes.first() / 4)
            children(panel).filterIsInstance<JCheckBox>().single { it.text == "Retour" }.doClick()
            assertEquals(17, path!!.destination)
            assertTrue(
                children(panel).filterIsInstance<JLabel>().any {
                    it.text.contains(
                        c.households
                            .single()
                            .people
                            .first()
                            .name,
                    )
                },
            )
        }
    }
}
