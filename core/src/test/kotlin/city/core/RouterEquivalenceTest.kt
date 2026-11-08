package city.core

import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.test.*

class RouterEquivalenceTest {
    private fun fixture(seed: Int): City {
        val random = Random(seed)
        return City.generate(seed.toLong(), 16).let { city ->
            city.copy(
                tiles =
                    city.tiles.mapIndexed { index, tile ->
                        tile.copy(
                            road = random.nextBoolean(),
                            rail = random.nextBoolean(),
                            subway = random.nextBoolean(),
                            fire = if (index % 29 == 0) 1 else 0,
                            powered = index % 7 != 0,
                            building =
                                when (index % 31) {
                                    1 -> "station"
                                    2 -> "metro"
                                    3 -> "park"
                                    else -> null
                                },
                            anchor = index,
                            zone = if (index % 11 == 0) Zone.COMMERCIAL_LIGHT else Zone.NONE,
                            occupants = if (index % 11 == 0) 6 else 0,
                        )
                    },
            )
        }
    }

    @Test fun `cached adjacency and reused searches match original multimodal router`() =
        runBlocking {
            for (seed in 0..3) {
                val city = fixture(seed).let { if (seed % 2 == 0) it else it.copy(rules = it.rules.copy(serviceFunding = 0)) }
                val previous = Mobility(0, listOf(MobilityHome(17, 1, emptyList(), loads = city.tiles.indices.flatMap { cell -> listOf(TravelMode.ROAD, TravelMode.RAIL, TravelMode.METRO).map { mode -> TrafficLoad(cell, mode, List(24) { 1000 }) } })), 0, 0)
                val reference = ReferenceMobilityRouter(city, Catalog(), previous)
                val router = MobilityRouter(city, Catalog(), previous)
                // Check all four modes and all potential adjacent/transfer edges, including slopes and fire.
                for (node in 0 until city.tiles.size * 4) {
                    val expected = reference.edges(node).toMap()
                    for (cell in city.neighbors(node / 4) + node / 4) {
                        for (mode in 0..3) assertEquals(expected[cell * 4 + mode], router.duration(node, cell * 4 + mode))
                    }
                }
                val random = Random(seed)
                repeat(80) {
                    val start = random.nextInt(city.tiles.size * 4)
                    val goal = random.nextInt(city.tiles.size * 4)
                    val blocked = List(16) { random.nextInt(city.tiles.size * 4) }.toSet()
                    val expected = reference.route(start, goal, blocked)
                    assertEquals(expected, router.route(start, goal, blocked))
                    if (expected != null) {
                        assertTrue(router.valid(expected))
                        assertEquals(expected, router.path(expected.nodes))
                    }
                }
                for (home in 0 until 16) assertEquals(reference.from(home), router.from(home))
            }
        }

    @Test fun `simultaneous callers and cancellation cannot corrupt reused workspaces`() =
        runBlocking {
            val city = fixture(42)
            val router = MobilityRouter(city, Catalog(), null)
            val reference = ReferenceMobilityRouter(city, Catalog(), null)
            val expected = (0 until 40).map { reference.from(it) }
            coroutineScope {
                (0 until 80).map { n -> async(Dispatchers.Default) { assertEquals(expected[n % 40], router.from(n % 40)) } }.awaitAll()
            }
            val cancelled =
                launch(start = CoroutineStart.LAZY) {
                    currentCoroutineContext().cancel()
                    router.from(17)
                }
            cancelled.start()
            cancelled.join()
            assertTrue(cancelled.isCancelled)
            repeat(10) { assertEquals(expected[it], router.from(it)) }
        }
}
