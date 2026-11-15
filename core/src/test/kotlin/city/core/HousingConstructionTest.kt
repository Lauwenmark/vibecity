package city.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class HousingConstructionTest {
    private val catalog = Catalog()

    private fun fixture(): City {
        val tiles = MutableList(256) { Tile() }
        for (i in 32..47) tiles[i] = Tile(road = true)
        tiles[18] = Tile(zone = Zone.RESIDENTIAL_DENSE, powered = true, watered = true, housingCapacity = 0)
        return City(width = 16, height = 16, tiles = tiles, day = 100)
    }

    private fun propose(
        city: City,
        amount: Int = 8,
    ): City = developHousing(city, city.tiles.mapIndexed { i, t -> if (i == 18) t.copy(occupants = t.occupants + amount) else t }, catalog)

    @Test fun `new demand starts one empty site and completion does not create people`() {
        val started = propose(fixture())
        assertEquals(0, started.tiles[18].occupants)
        assertTrue(started.households.isEmpty())
        val site = assertNotNull(started.tiles[18].construction)
        assertEquals(36, site.completionDay - site.startedDay)
        assertEquals(site, propose(started).tiles[18].construction)
        assertNotNull(advanceHousingConstruction(started.copy(day = site.completionDay - 1)).tiles[18].construction)
        val complete = advanceHousingConstruction(started.copy(day = site.completionDay))
        assertNull(complete.tiles[18].construction)
        assertEquals(12, complete.tiles[18].habitableHousingCapacity)
        assertEquals(0, complete.tiles[18].occupants)
        assertTrue(complete.households.isEmpty())
        val noDemand = developHousing(complete, complete.tiles, catalog)
        assertEquals(0, noDemand.tiles[18].occupants)
        assertEquals(8, propose(complete).tiles[18].occupants)
        assertEquals(2 * housingConstructionDays(12), housingConstructionDays(24))
    }

    private fun occupied(): City {
        val city = fixture()
        val people = (1L..12L).map { Citizen(it, "Resident $it", 30, position = 18 * TRAVEL_MODE_COUNT) }
        return city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i == 18) t.copy(occupants = 12, housingCapacity = 12) else t }, households = listOf(Household(18, people)), nextCitizenId = 13)
    }

    @Test fun `failed relocation changes neither people nor building and creates no backlog`() {
        val city = occupied()
        val result = propose(city)
        assertEquals(city.households, result.households)
        assertEquals(12, result.tiles[18].occupants)
        assertEquals(12, result.tiles[18].housingCapacity)
        assertTrue(result.tiles[18].constructionBlocked)
        assertNull(result.tiles[18].construction)
        assertEquals(13, result.nextCitizenId)
    }

    @Test fun `relocation reserves several homes atomically and preserves citizen identities`() {
        val city = occupied().let { c -> c.copy(tiles = c.tiles.mapIndexed { i, t -> if (i in 19..20) Tile(zone = Zone.RESIDENTIAL_LIGHT, housingCapacity = 6, powered = true, watered = true) else t }) }
        val result = propose(city)
        assertEquals(24, result.tiles[18].construction?.capacity)
        assertEquals(0, result.tiles[18].occupants)
        assertEquals(listOf(6, 6), result.households.map { it.people.size })
        assertEquals(
            (1L..12L).toSet(),
            result.households
                .flatMap { it.people }
                .map { it.id }
                .toSet(),
        )
        assertTrue(result.households.all { h -> h.people.all { it.position == h.cell * TRAVEL_MODE_COUNT && it.routes.isEmpty() } })
        result.validateCitizens()
        val insufficient = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i == 20) t.copy(housingCapacity = 5) else t })
        val rejected = propose(insufficient)
        assertEquals(insufficient.households, rejected.households)
        assertEquals(0, rejected.tiles[19].occupants)
        assertNull(rejected.tiles[18].construction)
    }

    @Test fun `sites and unbuilt ground cannot host evacuees`() {
        for (destination in listOf(Tile(zone = Zone.RESIDENTIAL_DENSE, powered = true), Tile(zone = Zone.RESIDENTIAL_DENSE, powered = true, housingCapacity = 80, construction = HousingConstruction(80, 90, 330)))) {
            val city = occupied().let { c -> c.copy(tiles = c.tiles.mapIndexed { i, t -> if (i == 19) destination else t }) }
            assertNull(propose(city).tiles[18].construction)
        }
    }

    @Test fun `save roundtrip and demolition preserve or remove construction state`() {
        val city = propose(fixture())
        val restored = Json.decodeFromString<City>(Json.encodeToString(city))
        assertEquals(city, restored)
        val demolished = Commands(catalog).apply(restored, Request(type = "BUILD", tool = "bulldoze", x = 2, y = 1))
        assertNull(demolished.tiles[18].construction)
        assertEquals(0, demolished.tiles[18].completedHousingCapacity)
    }

    @Test fun `legacy capacity freezes before vacancy and persists without occupants`() {
        val city = fixture().let { c -> c.copy(tiles = c.tiles.mapIndexed { i, t -> if (i == 18) t.copy(housingCapacity = null, occupants = 9) else t }) }
        val migrated = advanceHousingConstruction(city)
        assertEquals(12, migrated.tiles[18].housingCapacity)
        assertEquals(12, migrated.tiles[18].copy(occupants = 0).habitableHousingCapacity)
    }

    @Test fun `pending sites do not prevent arrivals into completed vacant homes`() {
        val city =
            fixture().let { c ->
                c.copy(
                    tiles =
                        c.tiles.mapIndexed { i, t ->
                            when (i) {
                                18 -> t.copy(housingCapacity = 12)
                                19 -> t.copy(zone = Zone.RESIDENTIAL_DENSE, housingCapacity = 0, construction = HousingConstruction(80, 90, 330))
                                else -> t
                            }
                        },
                )
            }
        val result = propose(city)
        assertEquals(8, result.tiles[18].occupants)
        assertNull(result.tiles[18].construction)
        assertEquals(city.tiles[19].construction, result.tiles[19].construction)
    }

    @Test fun `families cannot be split to force an enlargement`() {
        val city =
            occupied().let { c ->
                val people =
                    c.households.single().people.map { p ->
                        p.initialized(c).let { initialized ->
                            if (p.id <= 2) initialized.copy(life = initialized.life!!.copy(partner = if (p.id == 1L) 2 else 1)) else initialized
                        }
                    }
                c.copy(
                    households = listOf(Household(18, people)),
                    tiles =
                        c.tiles.mapIndexed { i, t ->
                            if (i in 19..30) Tile(zone = Zone.RESIDENTIAL_LIGHT, housingCapacity = 1, powered = true, watered = true) else t
                        },
                )
            }
        val rejected = propose(city)
        assertEquals(city.households, rejected.households)
        assertNull(rejected.tiles[18].construction)
        assertTrue(rejected.tiles[18].constructionBlocked)
    }

    @Test fun `network delta restores start progress and completion exactly`() {
        var previous = fixture().copy(terrain = Terrain.flat(16, 16, 2))
        val started = propose(previous).copy(revision = 1)
        val completed = advanceHousingConstruction(started.copy(day = started.tiles[18].construction!!.completionDay, revision = 2))
        for (current in listOf(started, completed)) {
            val compact = Response("STATE", city = current).compactState(previous)
            val decoded = wireJson.decodeFromString<Response>(wireJson.encodeToString(compact)).restoreState(previous)
            assertEquals(current, decoded.city)
            previous = current
        }
    }

    @Test fun `worker counts give identical construction decisions`() =
        runBlocking {
            val city = demoCity()
            assertEquals(Simulation(catalog, workers = 1).step(city), Simulation(catalog, workers = 4).step(city))
        }
}
