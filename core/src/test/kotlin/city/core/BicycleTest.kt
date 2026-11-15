package city.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.*

class BicycleTest {
    private val home = 17
    private val destination = 27

    private fun fixture(
        crime: Int = 0,
        seed: Long = 2000,
    ): City =
        City(
            width = 16,
            height = 16,
            seed = seed,
            day = 0,
            tiles =
                List(256) { i ->
                    when (i) {
                        home -> Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 2, powered = true)
                        destination -> Tile(building = "racks", anchor = i, crime = crime, powered = true)
                        else -> Tile()
                    }
                },
        )

    private fun catalog(capacity: Int) = Catalog(listOf(BuildingDef("racks", "Parking", 0, 0, service = "park", bicycleParking = capacity)))

    private suspend fun outing(
        city: City,
        capacity: Int,
        count: Int = 1,
        departure: Int = 480,
        stay: Int = 480,
    ): DayExecutor {
        val router = MobilityRouter(city, catalog(capacity), null)
        val out = assertNotNull(router.bicycleRoute(home * TRAVEL_MODE_COUNT, destination))
        val back = assertNotNull(router.route(destination * TRAVEL_MODE_COUNT, home * TRAVEL_MODE_COUNT))
        val registry = HomeRoutes(listOf(out, back))
        val people = List(count) { Citizen(it + 1L, "Cycliste", 30, position = home * TRAVEL_MODE_COUNT, bicycle = BicycleState(cell = home)) }
        val days = people.map { CitizenDay(it.id, it.position, listOf(PlannedActivity(Need.LEISURE, departure, stay, 0, 1))) }
        val prepared = PreparedHome(MobilityHome(home, count, emptyList(), registry.paths, days = days), people, registry)
        return DayExecutor(city, router, listOf(prepared), 0).also { it.execute() }
    }

    @Test fun `cycling is faster than walking but is never freely available to ordinary routing`() =
        runBlocking {
            val city = fixture()
            val router = MobilityRouter(city, catalog(0), null)
            val walk = assertNotNull(router.route(home * TRAVEL_MODE_COUNT, destination * TRAVEL_MODE_COUNT))
            val bike = assertNotNull(router.bicycleRoute(home * TRAVEL_MODE_COUNT, destination))
            assertEquals(50, walk.duration)
            assertEquals(22, bike.duration)
            assertTrue(walk.nodes.none { it % TRAVEL_MODE_COUNT == TravelMode.BICYCLE.ordinal })
            assertTrue(router.valid(bike))
            val water = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i % 16 == 6) t.copy(water = true) else t })
            assertNull(MobilityRouter(water, catalog(0), null).bicycleRoute(home * TRAVEL_MODE_COUNT, destination))
            val bridge = water.copy(tiles = water.tiles.mapIndexed { i, t -> if (i == 22) t.copy(road = true) else t })
            assertNotNull(MobilityRouter(bridge, catalog(0), null).bicycleRoute(home * TRAVEL_MODE_COUNT, destination))
        }

    @Test fun `distance parking and theft history influence cycling preference`() {
        val person = Citizen(1, "Cycliste", 30, position = home * TRAVEL_MODE_COUNT, bicycle = BicycleState(cell = home))

        fun chance(
            steps: Int,
            parking: Boolean = false,
            p: Citizen = person,
        ) = bicycleChoicePercent(p, 10, steps, steps * 2 + 2, steps * 5, parking)
        assertEquals(0.0, chance(1))
        assertTrue(chance(10) > chance(3))
        assertTrue(chance(10) > chance(40))
        assertTrue(chance(10, true) > chance(10))
        val robbed = person.copy(bicycle = person.bicycle!!.copy(lastTheftDay = 10, lastTheftCell = destination))
        assertTrue(chance(10, p = robbed) < chance(10))
        assertEquals(0.0, robbed.bicycle!!.reluctance(120))
        assertEquals(0.0, robbed.bicycle!!.unhappiness(26))
        assertEquals(0.0, chance(10, p = person.copy(bicycle = BicycleState())))
    }

    @Test fun `parking has finite capacity and survives overnight with bicycle location`() =
        runBlocking {
            val city = fixture()
            val executor = outing(city, 1, count = 2, departure = 1000, stay = 600)
            val people = executor.households().single().people
            assertEquals(1, people.count { it.bicycle!!.protectedParking })
            assertTrue(people.all { it.position == destination * TRAVEL_MODE_COUNT && it.bicycle!!.cell == destination && it.bicycle!!.checkedUntil == 1440 })
            val saved = city.copy(day = 1, households = executor.households(), nextCitizenId = 3)
            val restored = wireJson.decodeFromString<City>(wireJson.encodeToString(saved))
            restored.validateCitizens()
            val router = MobilityRouter(restored, catalog(1), null)
            val prepared =
                restored.households.map { h ->
                    PreparedHome(MobilityHome(h.cell, h.people.size, emptyList(), h.paths, days = h.people.map { CitizenDay(it.id, it.position, emptyList()) }), h.people, HomeRoutes(h.paths))
                }
            val next = DayExecutor(restored, router, prepared, 1).also { it.execute() }
            assertTrue(
                next
                    .households()
                    .single()
                    .people
                    .all { it.position == home * TRAVEL_MODE_COUNT && it.bicycle!!.cell == home && it.bicycle!!.parkedSince == null },
            )
            assertTrue(
                next
                    .homes()
                    .single()
                    .days
                    .all {
                        it.legs
                            .single()
                            .trace.nodes
                            .any { n -> n % TRAVEL_MODE_COUNT == TravelMode.BICYCLE.ordinal }
                    },
            )
        }

    @Test fun `theft removes the bicycle lowers happiness and forces a non cycling return`() =
        runBlocking {
            val returnMinute = 480 + 22 + 480
            val chance = bicycleTheftProbability(100, 480, false)
            val seed = (0L..100000L).first { noise(it xor 1L, returnMinute, 1973) / Int.MAX_VALUE.toDouble() in bicycleTheftProbability(100, 480, true)..<chance }
            val unprotected = outing(fixture(100, seed), 0)
            val person =
                unprotected
                    .households()
                    .single()
                    .people
                    .single()
            assertNull(person.bicycle!!.cell)
            assertEquals(1, person.bicycle!!.thefts)
            assertEquals(destination, person.bicycle!!.lastTheftCell)
            assertEquals(67.0, person.wellbeing.satisfaction)
            assertEquals(home * TRAVEL_MODE_COUNT, person.position)
            val legs =
                unprotected
                    .homes()
                    .single()
                    .days
                    .single()
                    .legs
            assertTrue(
                legs
                    .first()
                    .trace.nodes
                    .any { it % TRAVEL_MODE_COUNT == TravelMode.BICYCLE.ordinal },
            )
            assertTrue(
                legs
                    .last()
                    .trace.nodes
                    .none { it % TRAVEL_MODE_COUNT == TravelMode.BICYCLE.ordinal },
            )
            assertEquals(
                home,
                outing(fixture(100, seed), 1)
                    .households()
                    .single()
                    .people
                    .single()
                    .bicycle!!
                    .cell,
            )
            assertEquals(0.0, bicycleTheftProbability(0, 480, false))
            assertTrue(bicycleTheftProbability(100, 480, false) > bicycleTheftProbability(100, 10, false))
        }

    @Test fun `replacement requires savings and returning home and cannot duplicate a parked bike`() {
        val city = fixture().copy(day = 10)
        val person = Citizen(1, "Cycliste", 30, position = home * TRAVEL_MODE_COUNT, wellbeing = Wellbeing(income = 100.0, costs = 50.0), bicycle = BicycleState(savings = 115.0, updatedDay = 9, lastTheftDay = 9, lastTheftCell = destination))
        val bought = person.prepareBicycle(city, home)
        assertEquals(home, bought.bicycle!!.cell)
        assertEquals(0.0, bought.bicycle!!.savings)
        assertTrue(bought.bicycle!!.reluctance(10) > 0)
        assertEquals(bought, bought.prepareBicycle(city, home))
        assertNull(
            person
                .copy(position = destination * TRAVEL_MODE_COUNT)
                .prepareBicycle(city, home)
                .bicycle!!
                .cell,
        )
        assertNull(
            person
                .copy(bicycle = person.bicycle!!.copy(savings = 0.0))
                .prepareBicycle(city, home)
                .bicycle!!
                .cell,
        )
        assertEquals(
            destination,
            person
                .copy(bicycle = person.bicycle!!.copy(cell = destination))
                .prepareBicycle(city, home)
                .bicycle!!
                .cell,
        )
    }

    @Test fun `daily simulation chooses more bicycles with parking and remains deterministic across workers`() =
        runBlocking {
            val people = List(40) { Citizen(it + 1L, "Cycliste", 30, position = home * TRAVEL_MODE_COUNT, bicycle = BicycleState(cell = home)) }
            val city = fixture().copy(tiles = fixture().tiles.mapIndexed { i, t -> if (i == home) t.copy(occupants = people.size) else t }, households = listOf(Household(home, people)), nextCitizenId = 41)
            val bare = MobilitySimulation(catalog(0), 1).simulate(city)
            val racks = MobilitySimulation(catalog(100), 1).simulate(city)

            fun cyclists(result: City) =
                result.mobility!!
                    .homes
                    .single()
                    .days
                    .count { d -> d.legs.any { !it.returning && it.trace.nodes.any { n -> n % TRAVEL_MODE_COUNT == TravelMode.BICYCLE.ordinal } } }
            assertTrue(cyclists(bare) > 0)
            assertTrue(cyclists(racks) > cyclists(bare))
            assertEquals(racks, MobilitySimulation(catalog(100), 4).simulate(city))
            racks.validateCitizens()
            assertTrue(
                racks.households
                    .single()
                    .people
                    .all { it.position == home * TRAVEL_MODE_COUNT && it.bicycle!!.cell == home },
            )
            val withoutBikes = city.copy(households = listOf(Household(home, people.map { it.copy(bicycle = BicycleState()) })))
            assertEquals(0, cyclists(MobilitySimulation(catalog(100), 1).simulate(withoutBikes)))
        }

    @Test fun `a cyclist already in motion resumes at the saved cell and keeps the same bicycle`() =
        runBlocking {
            val city = fixture().copy(day = 1)
            val at = 22 * TRAVEL_MODE_COUNT + TravelMode.BICYCLE.ordinal
            val person = Citizen(1, "Cycliste", 30, position = at, bicycle = BicycleState(cell = 22))
            val restored = wireJson.decodeFromString<Citizen>(wireJson.encodeToString(person))
            val prepared = PreparedHome(MobilityHome(home, 1, emptyList(), days = listOf(CitizenDay(1, at, emptyList()))), listOf(restored), HomeRoutes(emptyList()))
            val executor = DayExecutor(city, MobilityRouter(city, catalog(0), null), listOf(prepared), 1).also { it.execute() }
            val leg =
                executor
                    .homes()
                    .single()
                    .days
                    .single()
                    .legs
                    .single()
            assertEquals(at, leg.trace.nodes.first())
            assertEquals(11, leg.arrival)
            assertEquals(
                home,
                executor
                    .households()
                    .single()
                    .people
                    .single()
                    .bicycle!!
                    .cell,
            )
        }
}
