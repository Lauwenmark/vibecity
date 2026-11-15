package city.core

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ImmigrationTest {
    private val catalog = Catalog()

    private fun fixture(connected: Boolean): City {
        val tiles = MutableList(256) { Tile() }
        tiles[17] = Tile(building = "wind", anchor = 17)
        tiles[18] = Tile(zone = Zone.RESIDENTIAL_LIGHT)
        tiles[34] = Tile(road = true)
        if (connected) tiles[32] = Tile(road = true)
        return City(width = 16, height = 16, month = 960, tiles = tiles, rules = Rules(disasters = false))
    }

    @Test fun `failed immigration creates neither citizens nor occupied places and retries next month`() =
        runBlocking {
            val simulation = Simulation(catalog)
            val rejected = simulation.step(fixture(false))
            assertTrue(rejected.tiles[18].powered)
            assertEquals(0, rejected.tiles[18].occupants)
            assertTrue(rejected.households.isEmpty())
            assertEquals(fixture(false).nextCitizenId, rejected.nextCitizenId)
            val accepted = simulation.step(fixture(true))
            assertEquals(0, accepted.tiles[18].occupants)
            val site = assertNotNull(accepted.tiles[18].construction)
            val finished = advanceHousingConstruction(accepted.copy(day = site.completionDay))
            val inhabited = simulation.step(finished.copy(day = dateDay(epoch.plusDays(site.completionDay.toLong()).withDayOfMonth(1).plusMonths(1))))
            assertTrue(inhabited.tiles[18].occupants > 0)
            assertEquals(
                inhabited.tiles[18].occupants,
                inhabited.households
                    .single()
                    .people.size,
            )
            val connected = rejected.copy(tiles = rejected.tiles.mapIndexed { i, t -> if (i == 32) t.copy(road = true) else t })
            val ordinaryDay = simulation.step(connected)
            assertTrue(ordinaryDay.households.isEmpty())
            val nextMonth = simulation.step(connected.copy(day = fixture(true).nextDay() + 31))
            assertEquals(0, nextMonth.tiles[18].occupants)
            assertNotNull(nextMonth.tiles[18].construction)
        }

    @Test fun `all four road borders allow entry but interior roads and border rails do not`() {
        for (entry in listOf(2, 242, 32, 47)) {
            val tiles = MutableList(256) { Tile() }
            tiles[entry] = Tile(road = true)
            assertTrue(MobilityRouter(City(width = 16, height = 16, tiles = tiles), catalog, null).exteriorRoadAccess()[17])
            tiles[entry] = Tile(rail = true, subway = true)
            tiles[17] = Tile(road = true)
            assertFalse(MobilityRouter(City(width = 16, height = 16, tiles = tiles), catalog, null).exteriorRoadAccess()[17])
        }
    }

    @Test fun `water fire and cliffs prevent access and a road bridge restores it`() {
        val tiles = MutableList(256) { Tile() }
        tiles[16] = Tile(road = true)
        for (i in (0 until 16).map { it * 16 + 1 }) tiles[i] = Tile(water = true)

        fun reachable() = MobilityRouter(City(width = 16, height = 16, tiles = tiles), catalog, null).exteriorRoadAccess()[18]
        assertFalse(reachable())
        tiles[17] = Tile(water = true, road = true)
        assertTrue(reachable())
        tiles[17] = tiles[17].copy(fire = 1)
        assertFalse(reachable())
        tiles[17] = Tile(elevation = 5)
        assertFalse(reachable())
    }
}
