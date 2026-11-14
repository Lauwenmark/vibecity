package city.client

import city.core.*
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.random.Random
import kotlin.test.*

class CameraTest {
    @Test fun `right drag pans continuously clamps to edges and does not activate tools`() =
        SwingUtilities.invokeAndWait {
            val canvas = CityCanvas({ fail("Panning must not inspect") }, { fail("Panning must not build") })
            canvas.city = City.generate(size = 16)
            canvas.tool = "road"
            canvas.setSize(canvas.preferredSize)
            val viewport = JViewport()
            viewport.setSize(300, 200)
            viewport.view = canvas
            viewport.viewPosition = Point(200, 150)

            fun mouse(
                id: Int,
                x: Int,
                y: Int,
                button: Int = MouseEvent.NOBUTTON,
            ) {
                val point = SwingUtilities.convertPoint(viewport, Point(x, y), canvas)
                canvas.dispatchEvent(MouseEvent(canvas, id, 0L, MouseEvent.BUTTON3_DOWN_MASK, point.x, point.y, 1, false, button))
            }

            mouse(MouseEvent.MOUSE_PRESSED, 100, 100, MouseEvent.BUTTON3)
            mouse(MouseEvent.MOUSE_DRAGGED, 130, 120)
            assertEquals(Point(170, 130), viewport.viewPosition)
            mouse(MouseEvent.MOUSE_DRAGGED, 140, 125)
            assertEquals(Point(160, 125), viewport.viewPosition)
            mouse(MouseEvent.MOUSE_DRAGGED, 2000, 2000)
            assertEquals(Point(0, 0), viewport.viewPosition)
            mouse(MouseEvent.MOUSE_DRAGGED, -2000, -2000)
            assertEquals(Point(canvas.width - viewport.width, canvas.height - viewport.height), viewport.viewPosition)
            mouse(MouseEvent.MOUSE_RELEASED, 100, 100, MouseEvent.BUTTON3)
            viewport.viewPosition = Point(200, 150)
            mouse(MouseEvent.MOUSE_PRESSED, 100, 100, MouseEvent.BUTTON3)
            mouse(MouseEvent.MOUSE_DRAGGED, 110, 110)
            assertEquals(Point(190, 140), viewport.viewPosition)
            mouse(MouseEvent.MOUSE_RELEASED, 110, 110, MouseEvent.BUTTON3)
        }

    @Test fun `rotated rectangular terrain picking matches reverse drawing order at every zoom`() {
        val city = City(width = 16, height = 24, tiles = List(384) { Tile() }, terrain = Terrain(16, 24, List(17 * 25) { 2 + it % 17 / 4 }))
        val random = Random(42)
        for (rotation in 0..3) {
            for (zoom in listOf(.5, 1.0, 2.0, 2.5, 3.0, 3.5, 4.0, 8.0, 12.0, 16.0)) {
                val p = CityProjection(city.width, city.height, zoom, rotation)
                val order =
                    city.tiles.indices
                        .sortedWith(compareBy<Int> { p.view(it % city.width, it / city.width).let { v -> v.x + v.y } }.thenBy { p.view(it % city.width, it / city.width).y })
                        .reversed()
                for (i in city.tiles.indices) {
                    val v = p.view(i % city.width, i / city.width)
                    assertEquals(i, p.worldIndex(v.x, v.y))
                }
                repeat(300) {
                    val point = Point(random.nextInt(((city.width + city.height) * p.tw + 140).toInt()), random.nextInt(((city.width + city.height) * p.th + p.originY + 220).toInt()))
                    assertEquals(order.firstOrNull { p.polygon(city, it).contains(point) } ?: -1, p.pick(city, point), "rotation=$rotation zoom=$zoom point=$point")
                }
            }
        }
    }

    @Test fun `wheel without modifiers zooms and rotation wraps in both directions`() =
        SwingUtilities.invokeAndWait {
            val canvas = CityCanvas({}, {})
            canvas.city = City.generate(size = 16)
            val event = MouseWheelEvent(canvas, MouseWheelEvent.MOUSE_WHEEL, 0L, 0, 300, 200, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)
            canvas.dispatchEvent(event)
            assertTrue(event.isConsumed)
            assertEquals(1.15, canvas.zoom, .001)
            for (level in listOf(2.5, 3.0, 3.5, 4.0, 8.0, 12.0, 16.0)) {
                canvas.zoom = level
                assertEquals(level, canvas.zoom)
            }
            canvas.zoom = 32.0
            assertEquals(16.0, canvas.zoom)
            canvas.rotation = -1
            assertEquals(3, canvas.rotation)
            canvas.rotation += 1
            assertEquals(0, canvas.rotation)
        }
}
