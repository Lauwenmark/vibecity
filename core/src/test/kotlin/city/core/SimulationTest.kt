package city.core

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SimulationTest {
    private val catalog = Catalog()
    private val commands = Commands(catalog)

    private fun flat() = City(width = 16, height = 16, tiles = List(256) { Tile() })

    private fun place(
        city: City,
        tool: String,
        x: Int,
        y: Int,
    ) = commands.apply(city, Request("BUILD", tool = tool, x = x, y = y))

    @Test fun `terrain is reproducible and has river and hills`() {
        assertEquals(City.generate(42), City.generate(42))
        assertNotEquals(City.generate(42).tiles, City.generate(43).tiles)
        assertTrue(City.generate().tiles.any { it.water })
        assertTrue(
            City
                .generate()
                .tiles
                .map { it.elevation }
                .distinct()
                .size > 2,
        )
    }

    @Test fun `failed construction leaves whole transaction intact`() {
        val city = flat().copy(treasury = 1)
        assertFailsWith<IllegalArgumentException> { place(city, "coal", 0, 0) }
        assertEquals(1, city.treasury)
        assertTrue(city.tiles.all { it.building == null })
        assertFailsWith<IllegalArgumentException> { place(flat(), "coal", 15, 15) }
    }

    @Test fun `footprint costs once and demolition from any cell removes entire structure`() {
        val city = place(flat(), "coal", 2, 2)
        assertEquals(96000, city.treasury)
        assertEquals(16, city.tiles.count { it.building == "coal" })
        assertFailsWith<IllegalArgumentException> { place(city, "park", 3, 3) }
        val cleared = place(city, "bulldoze", 4, 4)
        assertEquals(0, cleared.tiles.count { it.building != null })
        assertEquals(95968, cleared.treasury)
    }

    @Test fun `historical inventions and uneven ground are validated`() {
        assertFailsWith<IllegalArgumentException> { place(flat(), "nuclear", 0, 0) }
        val uneven = flat().copy(tiles = flat().tiles.mapIndexed { i, t -> if (i == 1) t.copy(elevation = 3) else t })
        assertFailsWith<IllegalArgumentException> { place(uneven, "coal", 0, 0) }
        assertEquals(16, place(flat().copy(month = 660), "nuclear", 0, 0).tiles.count { it.building != null })
    }

    @Test fun `unpowered development does not grow`() =
        runBlocking {
            var city = place(flat(), "zone:RESIDENTIAL_LIGHT", 5, 5)
            city = place(city, "road", 5, 6)
            repeat(12) { city = Simulation(catalog).step(city) }
            assertEquals(0, city.stats.population)
        }

    @Test fun `power must be connected and finite per component`() =
        runBlocking {
            var city = place(flat().copy(month = 960), "wind", 0, 0)
            for (x in 1..15) city = place(city, "zone:RESIDENTIAL_LIGHT", x, 0)
            for (x in 0..15) city = place(city, "zone:RESIDENTIAL_LIGHT", x, 1)
            city = place(city, "zone:RESIDENTIAL_LIGHT", 10, 10)
            val result = Simulation(catalog).step(city)
            assertEquals(30, result.tiles.count { it.powered })
            assertFalse(result.tiles[170].powered)
        }

    @Test fun `water requires electricity and pipe connectivity`() =
        runBlocking {
            var city = place(flat(), "pump", 0, 0)
            city = place(city, "pipe", 1, 0)
            var result = Simulation(catalog).step(city)
            assertFalse(result.tiles[1].watered)
            city = place(city, "coal", 0, 1)
            result = Simulation(catalog).step(city)
            assertTrue(result.tiles[1].watered)
            assertFalse(result.tiles[15].watered)
        }

    @Test fun `parallel scheduling does not change simulation results`() =
        runBlocking {
            var serial = demoCity()
            var parallel = serial
            repeat(8) {
                serial = Simulation(catalog, workers = 1).step(serial)
                parallel = Simulation(catalog, workers = 8).step(parallel)
            }
            assertEquals(serial, parallel)
            assertTrue(serial.stats.population > 0)
            assertTrue(serial.stats.jobs > 0)
            assertTrue(serial.tiles.any { it.zone.residential && it.watered })
            assertEquals(1, serial.history.size)
            assertEquals(8, serial.day)
        }

    @Test fun `budget and bond transactions conserve money`() =
        runBlocking {
            val initial = commands.apply(flat(), Request("BOND"))
            assertEquals(110000, initial.treasury)
            assertEquals(10000, initial.debt)
            val next = Simulation(catalog).step(initial)
            assertEquals(initial.treasury + next.stats.income - next.stats.expenses, next.treasury)
            val repaid = commands.apply(next, Request("REPAY"))
            assertEquals(0, repaid.debt)
            assertEquals(next.treasury - 10000, repaid.treasury)
        }

    @Test fun `tile and global plugins can alter behavior and persist state`() =
        runBlocking {
            val tileRule =
                object : TileRule {
                    override val id = "test.tile"

                    override fun apply(
                        context: TileContext,
                        proposed: Tile,
                    ) = proposed.copy(landValue = 99)
                }
            val system =
                object : CityRule {
                    override val id = "test.economy"

                    override suspend fun evolve(
                        city: City,
                        catalog: Catalog,
                    ) = city.copy(treasury = city.treasury + 100, pluginData = mapOf("test.turn" to city.month.toString()))
                }
            val result = Simulation(catalog, listOf(tileRule), systems = listOf(system)).step(flat())
            assertTrue(result.tiles.all { it.landValue == 99 })
            assertEquals(100100, result.treasury)
            assertEquals("0", result.pluginData["test.turn"])
        }

    @Test fun `fires destroy entire building footprint`() =
        runBlocking {
            val city = place(flat(), "coal", 0, 0)
            val burning = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i == 0) t.copy(fire = 6) else t })
            val result = Simulation(catalog).step(burning)
            assertTrue(result.tiles.none { it.building != null || it.anchor >= 0 })
        }

    @Test fun `services require power and funding`() =
        runBlocking {
            var city = place(flat(), "park", 5, 5)
            var result = Simulation(catalog).step(city)
            assertEquals(55, result.tiles[85].landValue)
            city = place(city, "coal", 1, 4)
            result = Simulation(catalog).step(city)
            // Replace polluting generator to isolate park benefit.
            val clean = Catalog(catalog.all().map { if (it.id == "coal") it.copy(pollution = 0) else it })
            result = Simulation(clean).step(city)
            assertTrue(result.tiles[85].landValue > 55)
            result = Simulation(clean).step(city.copy(rules = city.rules.copy(serviceFunding = 0)))
            assertEquals(55, result.tiles[85].landValue)
        }

    @Test fun `frozen catalog rejects runtime reconfiguration`() {
        val catalog = Catalog()
        catalog.freeze()
        assertFailsWith<IllegalStateException> { catalog.register(catalog["park"].copy(cost = 0)) }
    }
}
