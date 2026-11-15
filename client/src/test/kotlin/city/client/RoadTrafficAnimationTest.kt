package city.client

import city.core.*
import java.awt.*
import java.awt.geom.Area
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

    @Test fun `municipal changes reuse segments and traffic changes reuse masks`() {
        val source = fixture(12)
        val view = RoadTrafficAnimation()
        view.update(source, 8, 1.0)
        val initial = view.segments.toList()
        val changedRules = source.copy(revision = 1, treasury = 12345, rules = Rules(taxRate = 12, speed = 0))
        view.update(changedRules, 8, 1.0)
        initial.zip(view.segments).forEach { (old, next) -> assertSame(old, next) }
        val busier = fixture(120).copy(revision = 2)
        view.update(busier, 8, 1.0)
        initial.zip(view.segments).forEach { (old, next) ->
            assertSame(old.visibleRoad, next.visibleRoad)
            assertSame(old.bounds, next.bounds)
            assertTrue(next.count > old.count)
            assertTrue(next.speed < old.speed)
        }
        val busy = view.segments.toList()
        view.update(busier, 0, 1.0)
        assertTrue(view.isEmpty)
        view.update(busier, 8, 1.0)
        busy.zip(view.segments).forEach { (old, next) -> assertSame(old, next) }
        val capacity = busier.copy(trafficRules = busier.trafficRules.copy(roadEntriesPerMinute = busier.trafficRules.roadEntriesPerMinute * 2))
        view.update(capacity, 8, 1.0)
        busy.zip(view.segments).forEach { (old, next) ->
            assertSame(old.visibleRoad, next.visibleRoad)
            assertTrue(next.speed > old.speed)
        }
        view.update(null, 8, 1.0)
        view.update(source, 8, 1.0)
        assertNotSame(initial.first().visibleRoad, view.segments.first().visibleRoad)
    }

    @Test fun `cached geometry matches fresh geometry after scenery and camera changes`() {
        val source = fixture()
        val view = RoadTrafficAnimation()

        data class Scene(
            val city: City = source,
            val zoom: Double = 1.0,
            val rotation: Int = 0,
            val layer: Layer = Layer.SURFACE,
            val catalog: Map<String, BuildingDef> = emptyMap(),
        )

        fun tileScene(change: (Tile) -> Tile): Scene = Scene(source.copy(tiles = source.tiles.mapIndexed { i, tile -> if (i == 7 * 16 + 9) change(tile) else tile }))
        val scenes =
            listOf(
                Scene(),
                Scene(source.copy(terrain = Terrain.flat(16, 16, 6))),
                tileScene { it.copy(zone = Zone.RESIDENTIAL_DENSE, occupants = 80) },
                tileScene { it.copy(zone = Zone.RESIDENTIAL_DENSE, occupants = 20) },
                tileScene { it.copy(building = "tower", anchor = 7 * 16 + 9) },
                Scene(zoom = 2.0),
                Scene(rotation = 1),
                Scene(layer = Layer.TRAFFIC),
                Scene(catalog = Catalog().all().associateBy { it.id }),
                Scene(source.copy(tiles = source.tiles.mapIndexed { i, tile -> if (i == 6 * 16 + 7) tile.copy(fire = 1) else tile })),
                Scene(source.copy(tiles = source.tiles.mapIndexed { i, tile -> if (i == 6 * 16 + 7) tile.copy(road = false) else tile })),
            )
        for (scene in scenes) {
            view.update(scene.city, 8, scene.zoom, scene.catalog, scene.rotation, scene.layer)
            val fresh = RoadTrafficAnimation()
            fresh.update(scene.city, 8, scene.zoom, scene.catalog, scene.rotation, scene.layer)
            assertEquals(fresh.segments.map { it.from to it.to }, view.segments.map { it.from to it.to })
            fresh.segments.zip(view.segments).forEach { (expected, actual) ->
                assertEquals(expected.bounds, actual.bounds)
                assertEquals(listOf(expected.ax, expected.ay, expected.bx, expected.by, expected.speed), listOf(actual.ax, actual.ay, actual.bx, actual.by, actual.speed))
                assertEquals(expected.count, actual.count)
                val difference = Area(expected.visibleRoad)
                difference.exclusiveOr(Area(actual.visibleRoad))
                assertTrue(difference.isEmpty)
            }
        }
    }

    @Test fun `construction retains decorative traffic until recalculation but demolished roads stay empty`() {
        val city = fixture()
        val view = RoadTrafficAnimation()
        view.update(city, 8, 1.0)
        val original = view.segments.map { it.from to it.to }
        val built = Commands(Catalog()).apply(city, Request("BUILD", tool = "park", x = 2, y = 2))
        assertNull(built.mobility)
        view.update(built, 8, 1.0)
        assertEquals(original, view.segments.map { it.from to it.to })
        val gap = 6 * 16 + 7
        val demolished = built.copy(revision = 2, tiles = built.tiles.mapIndexed { i, tile -> if (i == gap) Tile() else tile })
        view.update(demolished, 8, 1.0)
        assertFalse(view.isEmpty)
        assertTrue(view.segments.none { it.from == gap || it.to == gap })
        view.update(built.copy(revision = 3), 8, 1.0)
        assertTrue(view.segments.none { it.from == gap || it.to == gap })
        view.update(city.copy(revision = 4, mobility = city.mobility!!.copy(homes = emptyList())), 8, 1.0)
        assertTrue(view.isEmpty)
        view.update(city, 8, 1.0)
        view.update(null, 8, 1.0)
        view.update(built, 8, 1.0)
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

    @Test fun `sprite pixels hide traffic at every camera rotation and zoom`() {
        val loads = (0 until 256).map { TrafficLoad(it, TravelMode.ROAD, List(24) { 120 }) }
        val source =
            fixture().copy(
                tiles = List(256) { Tile(road = it != 7 * 16 + 9) },
                mobility = Mobility(0, listOf(MobilityHome(17, 1, emptyList(), loads = loads)), 0, 0),
            )
        val building = 7 * 16 + 9
        val city = source.copy(tiles = source.tiles.mapIndexed { i, t -> if (i == building) t.copy(building = "tower", anchor = building) else t })
        var coveredCars = 0
        for (rotation in 0..3) {
            for (zoom in listOf(1.0, 2.0)) {
                val projection = CityProjection(16, 16, zoom, rotation)
                val sprite = BufferedImage(1400, 1000, BufferedImage.TYPE_INT_ARGB)
                sprite.createGraphics().let { g ->
                    BuildingSprites.draw(g, city, building, 1, projection)
                    g.dispose()
                }

                fun cars(c: City): BufferedImage {
                    val view = RoadTrafficAnimation()
                    view.update(c, 8, zoom, rotation = rotation)
                    return BufferedImage(1400, 1000, BufferedImage.TYPE_INT_ARGB).also { image ->
                        val g = image.createGraphics()
                        repeat(12) { view.paint(g, Rectangle(0, 0, image.width, image.height), it * .3) }
                        g.dispose()
                    }
                }
                val unmasked = cars(source)
                val masked = cars(city)
                for (y in 0 until sprite.height) {
                    for (x in 0 until sprite.width) {
                        if ((sprite.getRGB(x, y) ushr 24) < 128) continue
                        if ((unmasked.getRGB(x, y) ushr 24) != 0) coveredCars++
                        assertEquals(0, masked.getRGB(x, y), "Car over sprite at $x,$y rotation=$rotation zoom=$zoom")
                    }
                }
            }
        }
        assertTrue(coveredCars > 0, "The sprite must overlap actual car pixels")
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
