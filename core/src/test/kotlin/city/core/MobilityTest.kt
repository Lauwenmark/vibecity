package city.core

import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class MobilityTest {
    private val catalog = Catalog()
    private val engine = MobilitySimulation(catalog)

    private fun fixture(count: Int = 100): City {
        val tiles = MutableList(256) { Tile() }
        tiles[17] = Tile(zone = Zone.RESIDENTIAL_DENSE, occupants = count, powered = true)
        tiles[30] = Tile(zone = Zone.COMMERCIAL_DENSE, occupants = 60, powered = true)
        for (x in 1..14) tiles[32 + x] = Tile(road = true)
        tiles[62] = Tile(building = "park", anchor = 62, powered = true)
        return City(width = 16, height = 16, tiles = tiles, terrain = Terrain.flat(16, 16, 2))
    }

    @Test fun `every resident has stable individual schedules and shared paths`() =
        runBlocking<Unit> {
            val city = engine.simulate(fixture())
            val m = assertNotNull(city.mobility)
            val h = m.homes.single()
            assertEquals(100, h.residents)
            assertTrue(
                m.homes
                    .single()
                    .paths.size < h.residents,
            )
            val people = (0 until h.residents).map { Resident(h.cell, it, city.seed) }
            assertEquals(100, people.map { it.id }.distinct().size)
            assertTrue(people.map { m.itinerary(it, h).first().departure }.distinct().size > 1)
            for (person in people) {
                val trips = m.itinerary(person, h)
                assertEquals(Need.entries.toSet(), trips.map { it.need }.toSet())
                assertEquals(
                    city.households
                        .single()
                        .people[person.slot]
                        .age in 18..64,
                    trips.single { it.need == Need.WORK }.scheduled,
                )
                assertEquals(
                    city.households
                        .single()
                        .people[person.slot]
                        .let { it.age in 3..17 || (it.age >= 18 && it.id % 7 == 0L) },
                    trips.single { it.need == Need.EDUCATION }.scheduled,
                )
                val legs = h.days[person.slot].legs
                assertTrue(legs.zipWithNext().all { (a, b) -> a.arrival != null && a.arrival <= b.departure })
                assertEquals(
                    city.households
                        .single()
                        .people[person.slot]
                        .position / TRAVEL_MODE_COUNT,
                    m.position(person, h, 1439),
                )
            }
            assertEquals(city, engine.simulate(fixture()))
            m.validate(city, catalog)
        }

    @Test fun `road traffic comes from actual outbound and return visits`() =
        runBlocking<Unit> {
            val city = engine.simulate(fixture())
            val m = city.mobility!!
            assertTrue(m.aggregateLoads().any { it.mode == TravelMode.ROAD })
            assertTrue(m.aggregateLoads().all { it.hours.take(6).all { count -> count == 0 } })
            assertTrue(m.aggregateLoads().any { it.hours.slice(7..10).sum() > 0 })
            assertTrue(m.aggregateLoads().any { it.hours.slice(16..21).sum() > 0 })
            val expected = mutableMapOf<Int, IntArray>()
            val h = m.homes.single()
            for (day in h.days) {
                for (leg in day.legs) {
                    leg.trace.nodes.forEachIndexed { j, node ->
                        if (j > 0 && node % TRAVEL_MODE_COUNT != 0) {
                            val counts = expected.getOrPut(node) { IntArray(24) }
                            counts[(leg.departure + leg.trace.minutes[j]) / 60]++
                        }
                    }
                    if (leg.arrival != null) assertEquals(leg.trace.nodes.last(), day.positionAt(leg.arrival))
                }
            }
            for (load in m.aggregateLoads()) assertEquals(expected[load.cell * TRAVEL_MODE_COUNT + load.mode.ordinal]!!.toList(), load.hours)
            assertTrue(city.tiles.any { it.traffic > 0 })
        }

    @Test fun `water barrier prevents imaginary routes and causes unmet needs`() =
        runBlocking<Unit> {
            val source = fixture()
            val separated = source.copy(tiles = source.tiles.mapIndexed { i, t -> if (i % 16 == 8) Tile(water = true) else t })
            val city = engine.simulate(separated)
            val m = city.mobility!!
            assertTrue(
                m.homes
                    .single()
                    .paths
                    .isEmpty(),
            )
            assertTrue(m.aggregateLoads().isEmpty())
            assertTrue(m.unmetNeeds > 0)
            assertTrue(m.itinerary(Resident(17, 0, city.seed), m.homes.single()).any { it.status == "Aucune destination accessible" })
            val bridge = separated.copy(tiles = separated.tiles.mapIndexed { i, t -> if (i == 40) t.copy(road = true) else t })
            assertTrue(
                engine
                    .simulate(bridge)
                    .mobility!!
                    .homes
                    .single()
                    .paths
                    .isNotEmpty(),
            )
        }

    @Test fun `rail and metro require powered funded stations at both ends`() =
        runBlocking<Unit> {
            fun transit(
                mode: TravelMode,
                stations: Boolean,
                powered: Boolean = true,
            ): City {
                val tiles = MutableList(256) { Tile(water = true) }
                tiles[17] = Tile(zone = Zone.RESIDENTIAL_DENSE, occupants = 100, powered = true)
                tiles[30] = Tile(zone = Zone.COMMERCIAL_DENSE, occupants = 60, powered = true)
                val building = if (mode == TravelMode.RAIL) "station" else "metro"
                tiles[33] = Tile(building = if (stations) building else null, anchor = if (stations) 33 else -1, powered = powered)
                tiles[46] = Tile(building = if (stations) building else null, anchor = if (stations) 46 else -1, powered = powered)
                for (x in 1..14) tiles[48 + x] = Tile(water = true, rail = mode == TravelMode.RAIL, subway = mode == TravelMode.METRO)
                return City(width = 16, height = 16, tiles = tiles)
            }
            for (mode in listOf(TravelMode.RAIL, TravelMode.METRO)) {
                val connected = engine.simulate(transit(mode, true))
                assertTrue(
                    connected.mobility!!
                        .homes
                        .single()
                        .paths
                        .any { path -> path.nodes.any { it % TRAVEL_MODE_COUNT == mode.ordinal } },
                )
                assertTrue(connected.mobility!!.aggregateLoads().any { it.mode == mode })
                assertTrue(
                    engine
                        .simulate(transit(mode, false))
                        .mobility!!
                        .homes
                        .single()
                        .paths
                        .isEmpty(),
                )
                assertTrue(
                    engine
                        .simulate(transit(mode, true, false))
                        .mobility!!
                        .homes
                        .single()
                        .paths
                        .isEmpty(),
                )
                val unfunded = transit(mode, true).let { it.copy(rules = it.rules.copy(serviceFunding = 0)) }
                assertTrue(
                    engine
                        .simulate(unfunded)
                        .mobility!!
                        .homes
                        .single()
                        .paths
                        .isEmpty(),
                )
            }
        }

    @Test fun `congestion changes perceived travel cost and can divert to walking`() =
        runBlocking<Unit> {
            val free = engine.simulate(fixture())
            val previous =
                free.mobility!!.copy(
                    homes =
                        listOf(
                            free.mobility!!
                                .homes
                                .single()
                                .copy(loads = (33..46).map { TrafficLoad(it, TravelMode.ROAD, List(24) { 1200 }) }),
                        ),
                )
            val busy = engine.simulate(fixture(), previous)
            val freePath =
                free.mobility!!
                    .homes
                    .single()
                    .paths
                    .first()
            val busyPath =
                busy.mobility!!
                    .homes
                    .single()
                    .paths
                    .first()
            assertTrue(busyPath.duration > freePath.duration)
            assertTrue(freePath.nodes.any { it % TRAVEL_MODE_COUNT == TravelMode.ROAD.ordinal })
            assertTrue(busyPath.nodes.none { it % TRAVEL_MODE_COUNT == TravelMode.ROAD.ordinal })
        }

    @Test fun `plans survive serialization and old snapshots default to no mobility`() =
        runBlocking<Unit> {
            val city = engine.simulate(fixture())
            assertEquals(city, wireJson.decodeFromString<City>(wireJson.encodeToString(city)))
            val old = wireJson.encodeToString(fixture()).replace(",\"mobility\":null", "")
            assertNull(wireJson.decodeFromString<City>(old).mobility)
            val m = city.mobility!!
            assertFailsWith<IllegalArgumentException> { m.copy(homes = listOf(m.homes.single().copy(paths = listOf(TravelPath(listOf(-1), listOf(0)))))).validate(city, catalog) }
            assertFailsWith<IllegalArgumentException> { m.copy(homes = listOf(m.homes.single().copy(loads = listOf(TrafficLoad(33, TravelMode.WALK, List(24) { 0 }))))).validate(city, catalog) }
        }

    @Test fun `construction invalidates paths while pause preserves them`() =
        runBlocking<Unit> {
            val city = engine.simulate(fixture())
            val commands = Commands(catalog)
            assertEquals(city.mobility, commands.apply(city, Request("SPEED", value = 0)).mobility)
            val changed = commands.apply(city, Request("BUILD", tool = "bulldoze", x = 8, y = 2))
            assertNull(changed.mobility)
            assertTrue(changed.tiles.all { it.traffic == 0 })
        }

    @Test fun `arcology residents are all represented without duplicating routes`() =
        runBlocking<Unit> {
            val source = fixture()
            val city = source.copy(tiles = source.tiles.mapIndexed { i, t -> if (i == 17) Tile(building = "arcology", anchor = i, powered = true) else t })
            val result = engine.simulate(city)
            assertEquals(
                10000,
                result.mobility!!
                    .homes
                    .single()
                    .residents,
            )
            assertTrue(result.mobility!!.completedNeeds + result.mobility!!.unmetNeeds >= 30000)
            assertEquals(
                10000,
                result.households
                    .single()
                    .people.size,
            )
            assertTrue(
                result.households
                    .single()
                    .paths.size < 10000,
            )
            result.validateCitizens()
        }
}
