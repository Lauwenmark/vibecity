package city.client

import city.core.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.system.measureNanoTime
import kotlin.test.Test

class PowerLinePerformanceTest {
    @Test fun `measure repeated scenery rendering with a dense power network`() {
        val base = City.generate(size = 32)
        val catalog = standardBuildings().associateBy { it.id }
        val city =
            base.copy(
                terrain = null,
                tiles =
                    base.tiles.indices.map { i ->
                        val x = i % 32
                        val y = i / 32
                        when {
                            x in 12..15 && y in 12..15 -> Tile(building = "zoo", anchor = base.index(12, 12), powered = true)
                            y % 4 == 0 -> Tile(road = true, powerLine = true)
                            y % 4 == 1 -> Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 10, powered = true)
                            else -> Tile(trees = x % 3 == 0)
                        }
                    },
            )
        val renderer = CityRenderer(city, catalog, Layer.SURFACE, 1.5)
        val image = BufferedImage(1800, 1000, BufferedImage.TYPE_INT_RGB)
        val times =
            (0..3).map {
                val g = image.createGraphics()
                val nanos = measureNanoTime { renderer.render(g, Rectangle(0, 0, image.width, image.height)) }
                g.dispose()
                nanos / 1_000_000
            }
        println("Dense network render milliseconds: $times")
    }
}
