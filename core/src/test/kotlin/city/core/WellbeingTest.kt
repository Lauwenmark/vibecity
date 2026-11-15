package city.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WellbeingTest {
    private val catalog = Catalog()
    private val homeCell = 17

    private fun fixture(score: Double = 75.0): City {
        val tiles = MutableList(256) { Tile(powered = true, watered = true, landValue = 60) }
        tiles[homeCell] = tiles[homeCell].copy(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1)
        tiles[18] = tiles[18].copy(zone = Zone.COMMERCIAL_LIGHT, occupants = 1)
        val person = Citizen(1, "Camille", 70, position = homeCell * TRAVEL_MODE_COUNT, wellbeing = Wellbeing(satisfaction = score))
        val household = Household(homeCell, listOf(person))
        val day = CitizenDay(1, homeCell * TRAVEL_MODE_COUNT, listOf(PlannedActivity(Need.REST, 1320, 0)))
        return City(width = 16, height = 16, tiles = tiles, day = 1, nextCitizenId = 2, households = listOf(household), mobility = Mobility(0, listOf(MobilityHome(homeCell, 1, emptyList(), days = listOf(day))), 1, 0))
    }

    private fun ideal(city: City): City {
        val tiles = city.tiles.toMutableList()
        for ((cell, id) in listOf(1 to "fire", 16 to "hospital", 33 to "large_park")) tiles[cell] = tiles[cell].copy(building = id, anchor = cell)
        return city.copy(tiles = tiles)
    }

    @Test fun `ideal life recovers slowly to one hundred`() {
        var city = ideal(fixture(50.0))
        val first =
            updateWellbeing(city, catalog)
                .households
                .single()
                .people
                .single()
                .wellbeing
        assertEquals(50.35, first.satisfaction, .0001)
        assertTrue(first.factors.all { it == 0.0 })
        repeat(160) { city = updateWellbeing(city, catalog) }
        assertEquals(100.0, city.households.single().satisfaction, .0001)
    }

    @Test fun `persistent modest nuisance converges instead of emptying the city`() {
        var city = ideal(fixture(90.0))
        city = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i == homeCell) t.copy(pollution = 20) else t })
        repeat(400) { city = updateWellbeing(city, catalog) }
        assertEquals(95.6, city.households.single().satisfaction, .01)
        assertEquals(0.0, city.households.single().moveProbability())
    }

    @Test fun `essential shortages erode score and persisted history raises departure chance`() {
        var city = fixture().let { c -> c.copy(tiles = c.tiles.map { it.copy(powered = false, watered = false, pollution = 100, crime = 100) }) }
        repeat(60) { city = updateWellbeing(city, catalog) }
        val home = city.households.single()
        assertTrue(home.satisfaction < 10)
        assertTrue(home.moveProbability() > .03)
        assertEquals(city, wireJson.decodeFromString<City>(wireJson.encodeToString(city)))
        city.validateCitizens()
    }

    @Test fun `late long trip and missed food affect opinion and destination crime`() {
        val city = ideal(fixture())
        val path = TravelPath(listOf(17 * TRAVEL_MODE_COUNT, 18 * TRAVEL_MODE_COUNT), listOf(0, 5))
        val trace = TravelPath(listOf(17 * TRAVEL_MODE_COUNT, 18 * TRAVEL_MODE_COUNT), listOf(0, 100))
        val day = CitizenDay(1, 17 * TRAVEL_MODE_COUNT, listOf(PlannedActivity(Need.LEISURE, 60, 45, 0), PlannedActivity(Need.FOOD, 1200, 30)), listOf(ExecutedLeg(Need.LEISURE, false, 0, 90, 190, trace)))
        val changed = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i == 18) t.copy(crime = 100) else t }, mobility = city.mobility!!.copy(homes = listOf(MobilityHome(17, 1, emptyList(), paths = listOf(path), days = listOf(day)))))
        val opinion =
            updateWellbeing(changed, catalog)
                .households
                .single()
                .people
                .single()
                .wellbeing
        assertEquals(1, opinion.unmet)
        assertEquals(125.0, opinion.averageDelay)
        assertEquals(100, opinion.travelMinutes)
        assertTrue(opinion.factors[OpinionFactor.TRAVEL.ordinal] > 0)
        assertTrue(opinion.factors[OpinionFactor.CRIME.ordinal] > 0)
        assertEquals(18.0, opinion.factors[OpinionFactor.NEEDS.ordinal])
    }

    @Test fun `work destinations preserve contracts but income does not require a job`() {
        val city = fixture()
        val people = listOf(Citizen(1, "A", 30, position = 17 * TRAVEL_MODE_COUNT), Citizen(2, "B", 40, position = 17 * TRAVEL_MODE_COUNT, wellbeing = Wellbeing(workplace = 18, employedDays = 30)))
        val path = TravelPath(listOf(17 * TRAVEL_MODE_COUNT, 18 * TRAVEL_MODE_COUNT), listOf(0, 5))
        val days = people.map { CitizenDay(it.id, 68, emptyList(), listOf(ExecutedLeg(Need.WORK, false, 0, 0, 5, path))) }
        val result = updateWellbeing(city.copy(nextCitizenId = 3, households = listOf(Household(17, people)), mobility = city.mobility!!.copy(homes = listOf(MobilityHome(17, 2, emptyList(), paths = listOf(path), days = days)))), catalog)
        val opinions =
            result.households
                .single()
                .people
                .map { it.wellbeing }
        assertNull(opinions[0].workplace)
        assertEquals(18, opinions[1].workplace)
        assertEquals(31, opinions[1].employedDays)
        assertEquals(opinions[0].income, opinions[1].income)
        assertEquals(0.0, opinions[0].factors[OpinionFactor.EMPLOYMENT.ordinal])
        assertEquals(50.0, opinions[0].income)
    }

    private fun readyToMove(): City {
        val c = fixture(0.0)
        val person =
            c.households
                .single()
                .people
                .single()
                .copy(wellbeing = Wellbeing(satisfaction = 0.0, unhappyDays = 90))
        val seed = (0L..10000).first { noise(it xor 1, 17, 1) / 2147483648.0 < .08 }
        return c.copy(seed = seed, households = listOf(Household(17, listOf(person))), stats = Statistics(population = 1))
    }

    @Test fun `unhappy household leaves when no better vacant home exists`() {
        val result = migrateUnhappyHouseholds(readyToMove(), catalog)
        assertTrue(result.households.isEmpty())
        assertEquals(0, result.tiles[17].occupants)
        assertEquals(0, result.stats.population)
        assertNull(result.mobility)
    }

    @Test fun `relocation preserves citizen identity and opinion and clears old routes`() {
        var city = readyToMove()
        city =
            city.copy(
                tiles =
                    city.tiles.mapIndexed { i, t ->
                        when (i) {
                            17 -> t.copy(pollution = 100)
                            20 -> t.copy(zone = Zone.RESIDENTIAL_LIGHT, housingCapacity = 12)
                            21 -> t.copy(road = true)
                            else -> t
                        }
                    },
            )
        val result = migrateUnhappyHouseholds(city, catalog)
        val household = result.households.single()
        assertEquals(20, household.cell)
        assertEquals(1L, household.people.single().id)
        assertEquals(0.0, household.satisfaction)
        assertEquals(20 * TRAVEL_MODE_COUNT, household.people.single().position)
        assertEquals(1, result.stats.population)
        assertEquals(0, result.tiles[17].occupants)
        assertEquals(1, result.tiles[20].occupants)
    }

    @Test fun `attachment reduces moves and people away from home are not teleported`() {
        val city = readyToMove()
        val home = city.households.single()
        val attached = home.copy(people = home.people.map { it.copy(wellbeing = it.wellbeing.copy(residenceDays = 730)) })
        assertEquals(home.moveProbability() / 2, attached.moveProbability())
        val away = city.copy(households = listOf(home.copy(people = home.people.map { it.copy(position = 18 * TRAVEL_MODE_COUNT) })))
        assertEquals(away, migrateUnhappyHouseholds(away, catalog))
    }

    @Test fun `legacy citizen defaults and invalid opinion validation`() {
        val person = wireJson.decodeFromString<Citizen>("""{"id":1,"name":"A","age":30,"position":68}""")
        assertEquals(75.0, person.wellbeing.satisfaction)
        assertFailsWith<IllegalArgumentException> { fixture().copy(households = listOf(Household(17, listOf(person.copy(wellbeing = Wellbeing(satisfaction = -1.0)))))).validateCitizens() }
    }

    @Test fun `arcology residents survive loss of power and departures do not respawn`() =
        runBlocking {
            val small = Catalog(listOf(BuildingDef("homes", "Homes", 0, 0, residents = 2)))
            val tiles = MutableList(256) { Tile() }.also { it[17] = Tile(building = "homes", anchor = 17) }
            val city = City(width = 16, height = 16, tiles = tiles, day = 1)
            val occupied = MobilitySimulation(small, 1).simulate(city)
            assertEquals(
                2,
                occupied.households
                    .single()
                    .people.size,
            )
            val empty = city.copy(tiles = tiles.mapIndexed { i, t -> if (i == 17) t.copy(residentialOccupancy = 0) else t })
            assertTrue(MobilitySimulation(small, 1).simulate(empty).households.isEmpty())
        }

    @Test fun `network deltas preserve opinions and residential occupancy`() {
        val previous = fixture().withSharedTerrain()
        val current = updateWellbeing(previous, catalog).copy(revision = 1, tiles = previous.tiles.mapIndexed { i, tile -> if (i == 30) tile.copy(building = "arcology", anchor = 30, residentialOccupancy = 0) else tile })
        val encoded = wireJson.encodeToString(Response("STATE", city = current).compactState(previous))
        val restored = wireJson.decodeFromString<Response>(encoded).restoreState(previous).city
        assertEquals(current, restored)
    }

    @Test fun `daily updates preserve mobility invariants and worker determinism`() =
        runBlocking {
            var serial = fixture().copy(mobility = null)
            var parallel = serial
            repeat(35) {
                serial = Simulation(catalog, workers = 1).step(serial)
                parallel = Simulation(catalog, workers = 4).step(parallel)
                assertEquals(serial, parallel)
                serial.mobility!!.validate(serial, catalog)
                serial.validateCitizens()
                assertEquals(serial.households.sumOf { it.people.size }, serial.stats.population)
            }
        }
}
