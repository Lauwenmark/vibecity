package city.client

import city.core.*
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

class RoadTrafficAnimationTest {
    private fun fixture(
        volume: Int = 120,
        mode: TravelMode = TravelMode.ROAD,
    ): City {
        val tiles = MutableList(256) { Tile() }
        for (x in 2..12) tiles[6 * 16 + x] = Tile(road = true)
        val loads = (2..12).map { TrafficLoad(6 * 16 + it, mode, List(24) { hour -> if (hour == 8) volume else 0 }) }
        return City(width = 16, height = 16, tiles = tiles, terrain = Terrain.flat(16, 16, 2), mobility = Mobility(0, listOf(MobilityHome(17, 1, emptyList(), loads = loads)), 0, 0))
    }

    @Test fun `car density follows road load and selected hour with bounded visual congestion`() {
        val view = RoadTrafficAnimation()
        view.update(fixture(12), 8, 1.0)
        val quiet = view.segments.first()
        assertEquals(1, quiet.count)
        view.update(fixture(120), 8, 1.0)
        val busy = view.segments.first()
        assertTrue(busy.count > quiet.count)
        assertTrue(busy.speed < quiet.speed)
        view.update(fixture(12000), 8, 1.0)
        assertTrue(view.segments.all { it.count == 6 && it.speed > 0 })
        view.update(fixture(), 0, 1.0)
        assertTrue(view.isEmpty)
        view.update(fixture(), -1, 1.0)
        assertFalse(view.isEmpty)
        view.update(fixture(mode = TravelMode.RAIL), -1, 1.0)
        assertTrue(view.isEmpty)
        view.update(fixture().copy(mobility = null), -1, 1.0)
        assertTrue(view.isEmpty)
    }

    @Test fun `cars never cross missing roads or fire and respect terrain projection`() {
        val source = fixture()
        val gap = 6 * 16 + 7
        val view = RoadTrafficAnimation()
        view.update(source.copy(tiles = source.tiles.mapIndexed { i, t -> if (i == gap) t.copy(road = false) else t }), -1, 1.0)
        assertTrue(view.segments.none { it.from == gap || it.to == gap })
        view.update(source.copy(tiles = source.tiles.mapIndexed { i, t -> if (i == gap) t.copy(fire = 1) else t }), -1, 1.0)
        assertTrue(view.segments.none { it.from == gap || it.to == gap })
        view.update(source, -1, 1.0)
        val low = view.segments.first()
        view.update(source.copy(terrain = Terrain.flat(16, 16, 6)), -1, 1.0)
        assertEquals(low.ay - 12, view.segments.first().ay)
    }

    @Test fun `animated cars move while scenery stays cached and pause freezes the image`() {
        SwingUtilities.invokeAndWait {
            val canvas =
                CityCanvas({}, {}).apply {
                    city = fixture()
                    zoom = 2.0
                    setSize(preferredSize)
                }

            fun paint(): BufferedImage {
                val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                canvas.paint(g)
                g.dispose()
                return image
            }

            fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val initial = paint()
            val cached = canvas.renderedChunkCount
            repeat(8) { canvas.advanceTrafficAnimation(.25) }
            val moved = paint()
            assertFalse(pixels(initial).contentEquals(pixels(moved)))
            assertEquals(cached, canvas.renderedChunkCount)
            assertFalse(canvas.trafficAnimationRunning) // Detached canvas has no timer.
            canvas.city = canvas.city!!.copy(rules = Rules(speed = 0))
            canvas.advanceTrafficAnimation(.25)
            assertContentEquals(pixels(moved), pixels(paint()))
            canvas.city = canvas.city!!.copy(rules = Rules(speed = 1))
            canvas.animatedTraffic = false
            val hidden = paint()
            canvas.advanceTrafficAnimation(.25)
            assertContentEquals(pixels(hidden), pixels(paint()))
            assertFalse(pixels(moved).contentEquals(pixels(hidden)))
            assertEquals(cached, canvas.renderedChunkCount)
            canvas.animatedTraffic = true
            canvas.trafficHour = 0
            assertContentEquals(pixels(hidden), pixels(paint()))
            Files.createDirectories(Path.of("build/previews"))
            val comparison = BufferedImage(initial.width, initial.height * 2, BufferedImage.TYPE_INT_RGB)
            val g = comparison.createGraphics()
            g.drawImage(initial, 0, 0, null)
            g.drawImage(moved, 0, initial.height, null)
            g.dispose()
            ImageIO.write(comparison, "png", Path.of("build/previews/traffic-animation.png").toFile())
        }
    }

    @Test fun `building silhouettes occlude traffic instead of letting cars float over roofs`() {
        val source = fixture()
        val building = 7 * 16 + 9
        val city = source.copy(tiles = source.tiles.mapIndexed { i, t -> if (i == building) t.copy(zone = Zone.RESIDENTIAL_DENSE, occupants = 80) else t })
        val view = RoadTrafficAnimation()
        view.update(city, 8, 1.0)
        val projection = CityProjection(16, 16, 1.0)
        val sx = projection.screenX(9, 7)
        val sy = projection.centerY(9, 7, city.surface(building))
        val image = BufferedImage(700, 500, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        view.paint(g, Rectangle(0, 0, 700, 500), .5)
        g.dispose()
        // Interior of the foreground building must remain completely transparent in the car layer.
        for (y in sy - 45 until sy - 10) for (x in sx - 5..sx + 5) assertEquals(0, image.getRGB(x, y))
    }
}
