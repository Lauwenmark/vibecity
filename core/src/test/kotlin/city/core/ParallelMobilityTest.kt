package city.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ParallelMobilityTest {
    private val catalog = Catalog()

    private fun fixture(): City {
        val tiles = MutableList(256) { Tile() }
        for (i in 17..23) tiles[i] = Tile(zone = Zone.RESIDENTIAL_DENSE, occupants = i * 3, powered = true)
        tiles[30] = Tile(zone = Zone.COMMERCIAL_DENSE, occupants = 60, powered = true)
        for (x in 1..14) tiles[32 + x] = Tile(road = true)
        return City(width = 16, height = 16, tiles = tiles)
    }

    @Test fun `serial and parallel snapshots match including next month congestion`() =
        runBlocking<Unit> {
            var serial = fixture()
            var parallel = serial
            repeat(3) {
                serial = MobilitySimulation(catalog, 1).simulate(serial.copy(month = it))
                parallel = MobilitySimulation(catalog, 4).simulate(parallel.copy(month = it))
                assertEquals(serial, parallel)
                assertEquals(wireJson.encodeToString(serial), wireJson.encodeToString(parallel))
                parallel.mobility!!.validate(parallel, catalog)
            }
        }

    @Test fun `each home owns paths and counts and aggregation sums shared roads`() =
        runBlocking<Unit> {
            val source = fixture()
            val city = MobilitySimulation(catalog, 4).simulate(source)
            val mobility = city.mobility!!
            for (home in mobility.homes) {
                val household = city.households.single { it.cell == home.cell }
                assertEquals(household.paths, home.paths)
                assertEquals(household.people.map { it.id }, home.days.map { it.citizenId })
                assertTrue(household.people.flatMap { it.routes }.all { it.path in home.paths.indices })
                assertTrue(home.options.flatMap { it.paths }.all { it in home.paths.indices })
            }
            assertTrue(mobility.homes.count { h -> h.loads.any { it.cell == 40 } } > 1)
            for (total in mobility.aggregateLoads()) {
                for (hour in 0..23) {
                    assertEquals(mobility.homes.sumOf { home -> home.loads.filter { it.cell == total.cell && it.mode == total.mode }.sumOf { it.hours[hour] } }, total.hours[hour])
                }
            }
            val json = wireJson.encodeToJsonElement(mobility).jsonObject
            assertFalse("paths" in json)
            assertFalse("loads" in json)
            assertTrue(json.getValue("homes").jsonArray.all { "paths" in it.jsonObject && "loads" in it.jsonObject })
        }

    @Test fun `worker concurrency is bounded and output order ignores completion order`() =
        runBlocking<Unit> {
            withTimeout(5000) {
                val homes = (0..5).map { MobilityHome(it, 1, emptyList()) }
                val started = Channel<Int>(Channel.UNLIMITED)
                val gates = homes.map { CompletableDeferred<Unit>() }
                val active = AtomicInteger()
                val maximum = AtomicInteger()
                val task =
                    async {
                        calculateHomes(homes, 2) { home ->
                            val count = active.incrementAndGet()
                            maximum.updateAndGet { maxOf(it, count) }
                            try {
                                started.send(home.cell)
                                gates[home.cell].await()
                                home
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    }
                assertEquals(setOf(0, 1), setOf(started.receive(), started.receive()))
                assertTrue(started.tryReceive().isFailure)
                gates[1].complete(Unit)
                assertEquals(2, started.receive())
                gates.forEach { it.complete(Unit) }
                assertEquals(homes, task.await())
                assertEquals(2, maximum.get())
                assertEquals(0, active.get())
            }
        }

    @Test fun `cancelling parent stops all home workers without publishing partial results`() =
        runBlocking<Unit> {
            withTimeout(5000) {
                val started = Channel<Unit>(Channel.UNLIMITED)
                val stopped = AtomicInteger()
                val task =
                    async {
                        calculateHomes((0..9).map { MobilityHome(it, 1, emptyList()) }, 2) {
                            try {
                                started.send(Unit)
                                awaitCancellation()
                            } finally {
                                stopped.incrementAndGet()
                            }
                        }
                    }
                repeat(2) { started.receive() }
                task.cancelAndJoin()
                assertFailsWith<CancellationException> { task.await() }
                assertEquals(2, stopped.get())
            }
        }

    @Test fun `worker failure cancels siblings and escapes the calculation`() =
        runBlocking<Unit> {
            withTimeout(5000) {
                val siblingStarted = CompletableDeferred<Unit>()
                val stopped = CompletableDeferred<Unit>()
                assertFailsWith<IllegalStateException> {
                    calculateHomes(listOf(MobilityHome(0, 1, emptyList()), MobilityHome(1, 1, emptyList())), 2) { home ->
                        if (home.cell == 0) {
                            siblingStarted.await()
                            error("routing failure")
                        }
                        try {
                            siblingStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
                assertTrue(stopped.isCompleted)
            }
        }

    @Test fun `empty town and single worker boundary stay valid`() =
        runBlocking<Unit> {
            val empty = fixture().let { it.copy(tiles = it.tiles.map { tile -> tile.copy(occupants = 0) }) }
            val city = MobilitySimulation(catalog, 0).simulate(empty)
            assertTrue(city.mobility!!.homes.isEmpty())
            assertTrue(city.mobility!!.aggregateLoads().isEmpty())
            assertEquals(0L, city.mobility!!.completedNeeds)
            city.mobility!!.validate(city, catalog)
        }
}
