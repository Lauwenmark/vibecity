package city.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.*

class TemporalTrafficTest {
    private val catalog = Catalog()

    private fun network(detour: Boolean): City {
        val tiles = MutableList(256) { Tile(water = true) }
        tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 6, powered = true)
        tiles[30] = Tile(zone = Zone.COMMERCIAL_LIGHT, occupants = 6, powered = true)
        for (x in 1..14) tiles[32 + x] = Tile(water = true, road = true)
        if (detour) {
            for (x in 1..14) tiles[48 + x] = Tile(water = true, road = true)
            // A bypass must also have an unsaturated final approach to each destination.
            for (x in 2..13) tiles[16 + x] = Tile()
        }
        return City(width = 16, height = 16, tiles = tiles, trafficRules = TrafficRules(roadSlots = 1, roadEntriesPerMinute = 1, reconsiderMinutes = 2, reroutePercent = if (detour) 100 else 0))
    }

    private suspend fun execute(
        detour: Boolean,
        known: Boolean = false,
    ): Pair<City, DayExecutor> {
        val city = network(detour)
        val router = MobilityRouter(city, catalog, null)
        var id = 1L
        val homes =
            listOf(17, 30).map { cell ->
                val goal = if (cell == 17) 30 else 17
                val path = assertNotNull(router.route(cell * 4, goal * 4))
                assertTrue(path.nodes.any { it % 4 == 1 })
                val registry = HomeRoutes(listOf(path))
                val memories = mutableListOf(LearnedRoute(0, Need.WORK, false, 0, 80))
                if (known) {
                    for (index in path.nodes.indices) {
                        val node = path.nodes[index]
                        if (node % 4 != 1 || index == path.nodes.lastIndex) continue
                        val next = path.nodes[index + 1]
                        val avoid = setOf(next, next / 4 * 4)
                        val alternative = router.route(node, goal * 4, avoid) ?: continue
                        val complete = router.path(path.nodes.take(index) + alternative.nodes) ?: continue
                        val key = registry.intern(complete)
                        if (memories.none { it.path == key }) memories.add(LearnedRoute(0, Need.WORK, false, key, 20))
                    }
                }
                val people = List(6) { Citizen(id++, "Test $id", 30, memories.toList(), cell * 4) }
                val activities = listOf(PlannedActivity(Need.WORK, 480, 10, 0), PlannedActivity(Need.REST, 1320, 0))
                val home = MobilityHome(cell, people.size, listOf(NeedOptions(Need.WORK, listOf(0))), registry.paths, days = people.map { CitizenDay(it.id, it.position, activities) })
                PreparedHome(home, people, registry)
            }
        val executor = DayExecutor(city, router, homes, 0)
        executor.execute()
        return city to executor
    }

    @Test fun `hard road capacity causes upstream blocking and conserves midnight positions`() =
        runBlocking<Unit> {
            val (_, executor) = execute(false)
            assertTrue(executor.peaks().isNotEmpty())
            assertTrue(executor.peaks().values.all { it <= 1 })
            val actualDays = executor.homes().flatMap { it.days }
            for (minute in 480..1439) {
                val occupied =
                    actualDays
                        .map { it.positionAt(minute) }
                        .filter { it % 4 == 1 }
                        .groupingBy { it / 4 }
                        .eachCount()
                assertTrue(occupied.values.all { it <= 1 }, "Occupied road slots at minute $minute")
            }
            val byCell =
                executor
                    .homes()
                    .flatMap { it.loads }
                    .filter { it.mode == TravelMode.ROAD }
                    .groupBy { it.cell }
            for (cell in byCell.values) for (hour in 0..23) assertTrue(cell.sumOf { it.hours[hour] } <= 60)
            val legs = executor.homes().flatMap { it.days }.flatMap { it.legs }
            assertTrue(legs.any { it.waitMinutes > 0 })
            val stranded = legs.filter { it.arrival == null && it.trace.nodes.last() % 4 == 1 }
            assertTrue(stranded.isNotEmpty(), "The opposing queues must hold upstream road slots")
            for (home in executor.homes()) {
                for (load in home.loads.filter { it.mode == TravelMode.ROAD }) assertTrue(load.hours.all { it <= 60 })
                for (day in home.days) {
                    val person = executor.households().flatMap { it.people }.single { it.id == day.citizenId }
                    assertEquals(day.positionAt(1439), person.position)
                }
            }
            assertTrue(executor.households().flatMap { it.people }.any { it.position % 4 == 1 })
        }

    @Test fun `new successful detours are learned at low score and abandoned plans lose score`() =
        runBlocking<Unit> {
            val (_, executor) = execute(true)
            val homes = executor.homes()
            assertTrue(homes.flatMap { it.days }.flatMap { it.legs }.any { leg -> leg.arrival != null && leg.decisions.any { it.action == "Nouveau détour" } })
            assertTrue(executor.households().flatMap { it.people }.any { p -> p.routes.any { it.score == 20 } })
            assertTrue(executor.households().flatMap { it.people }.any { p -> p.routes.any { it.score < 80 && it.score != 20 } })
            assertTrue(executor.peaks().values.all { it <= 1 })
            val router = MobilityRouter(network(true), catalog, null)
            for (home in homes) {
                for (day in home.days) {
                    for (leg in day.legs) {
                        for (j in 1 until leg.trace.nodes.size) {
                            val duration = assertNotNull(router.duration(leg.trace.nodes[j - 1], leg.trace.nodes[j]))
                            assertTrue(leg.trace.minutes[j] - leg.trace.minutes[j - 1] >= duration, "A detour must consume its full edge travel time")
                        }
                    }
                }
            }
        }

    @Test fun `a blocked citizen checks known alternatives before routing anew`() =
        runBlocking<Unit> {
            val (_, executor) = execute(true, true)
            assertTrue(
                executor
                    .homes()
                    .flatMap { it.days }
                    .flatMap { it.legs }
                    .flatMap { it.decisions }
                    .any { it.action == "Itinéraire connu" },
            )
        }

    @Test fun `names identities and memories persist without recycling departed residents`() =
        runBlocking<Unit> {
            val base = network(true).copy(tiles = network(true).tiles.mapIndexed { i, t -> if (i == 17) t.copy(occupants = 2) else t })
            val engine = MobilitySimulation(catalog, 2)
            val first = engine.simulate(base)
            val people = first.households.single().people
            val less = first.copy(day = 1, tiles = first.tiles.mapIndexed { i, t -> if (i == 17) t.copy(occupants = 1) else t })
            val second = engine.simulate(less)
            assertEquals(
                people.first().id,
                second.households
                    .single()
                    .people
                    .single()
                    .id,
            )
            assertEquals(
                people.first().name,
                second.households
                    .single()
                    .people
                    .single()
                    .name,
            )
            val more = second.copy(day = 2, tiles = second.tiles.mapIndexed { i, t -> if (i == 17) t.copy(occupants = 2) else t })
            val third = engine.simulate(more)
            assertTrue(
                third.households
                    .single()
                    .people
                    .last()
                    .id > people.last().id,
            )
            assertTrue(
                third.households
                    .single()
                    .people
                    .first()
                    .routes
                    .isNotEmpty(),
            )
            val changed = Commands(catalog).apply(third, Request("BUILD", tool = "bulldoze", x = 6, y = 2))
            assertNull(changed.mobility)
            assertEquals(third.households, changed.households)
            val replanned = engine.simulate(changed.copy(day = 3))
            replanned.validateCitizens()
        }

    @Test fun `school and work take weekends off and the calendar advances daily`() =
        runBlocking<Unit> {
            val tiles = MutableList(256) { Tile() }
            tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1, powered = true)
            tiles[30] = Tile(building = "school", anchor = 30, powered = true)
            val child = Citizen(1, "Alice", 12, position = 68)
            val base = City(width = 16, height = 16, tiles = tiles, households = listOf(Household(17, listOf(child))), nextCitizenId = 2)
            val engine = MobilitySimulation(catalog)
            val friday = engine.simulate(base.copy(day = 4))
            assertTrue(
                friday.mobility!!
                    .homes
                    .single()
                    .days
                    .single()
                    .activities
                    .single { it.need == Need.EDUCATION }
                    .scheduled,
            )
            val saturday = engine.simulate(friday.copy(day = 5))
            assertFalse(
                saturday.mobility!!
                    .homes
                    .single()
                    .days
                    .single()
                    .activities
                    .single { it.need == Need.EDUCATION }
                    .scheduled,
            )
            val empty = City(width = 16, height = 16, tiles = List(256) { Tile() })
            var city = empty
            repeat(31) { city = Simulation(catalog).step(city) }
            assertEquals(31, city.day)
            assertEquals(1, city.history.size)
            city = Simulation(catalog).step(city)
            assertEquals(1, city.month)
            assertEquals(2, city.history.size)
            assertEquals("Jeudi", dayName(city.mobility!!.day))
        }

    @Test fun `weighted choice explores and monthly pruning retains only strong top routes`() {
        val strong = LearnedRoute(0, Need.WORK, false, 0, 80)
        val weak = strong.copy(path = 1, score = 20)
        val choices = (0L..999L).map { chooseRoute(listOf(strong, weak), it) }
        assertTrue(choices.count { it == strong } > choices.count { it == weak } * 5)
        assertTrue(choices.any { it == weak })
        val memories = listOf(2, 99, 98, 97, 96, 95, 94).mapIndexed { i, score -> strong.copy(path = i, score = score) }
        val pruned = Citizen(1, "Alice", 30, memories, 68).prune(TrafficRules())
        assertEquals(listOf(99, 98, 97, 96, 95), pruned.routes.map { it.score })
    }

    @Test fun `execution and learning remain byte identical across planning worker counts`() =
        runBlocking<Unit> {
            var serial = network(true)
            var parallel = serial
            repeat(8) { day ->
                serial = MobilitySimulation(catalog, 1).simulate(serial.copy(day = day))
                parallel = MobilitySimulation(catalog, 4).simulate(parallel.copy(day = day))
                assertEquals(wireJson.encodeToString(serial), wireJson.encodeToString(parallel))
                serial.validateCitizens()
            }
        }
}
