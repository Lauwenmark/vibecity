package city.server

import city.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.Socket
import java.nio.file.Files
import kotlin.test.*

class ServerTest {
    private fun flat() = City(width = 16, height = 16, tiles = List(256) { Tile() }, rules = Rules(speed = 0), terrain = Terrain.flat(16, 16))

    private class Peer(
        port: Int,
        name: String = "Maire",
        token: String = "",
    ) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

        init {
            send(Request("HELLO", name = name, token = token))
        }

        fun send(r: Request) {
            writer.write(wireJson.encodeToString(r))
            writer.newLine()
            writer.flush()
        }

        private var previous: City? = null

        fun next(): Response {
            val response = wireJson.decodeFromString<Response>(readFrame(reader, 64 * 1024 * 1024) ?: error("EOF")).restoreState(previous)
            if (response.type == "STATE") previous = response.city
            return response
        }

        fun until(predicate: (Response) -> Boolean): Response {
            repeat(30) {
                val r = next()
                if (predicate(r)) return r
            }
            error("No matching response")
        }

        override fun close() = socket.close()
    }

    @Test fun `two mayors receive same authoritative edits and conflicting build is rejected`() =
        runBlocking {
            val server = CityServer(flat(), port = 0, savePath = null)
            try {
                Peer(server.port, "Alice").use { a ->
                    Peer(server.port, "Bob").use { b ->
                        a.until { it.type == "STATE" }
                        b.until { it.type == "STATE" }
                        coroutineScope {
                            launch(Dispatchers.IO) { a.send(Request("BUILD", id = 1, tool = "park", x = 2, y = 2)) }
                            launch(Dispatchers.IO) { b.send(Request("BUILD", id = 1, tool = "school", x = 2, y = 2)) }
                        }
                        val ra = a.until { it.id == 1L && it.type in listOf("ACK", "ERROR") }
                        val rb = b.until { it.id == 1L && it.type in listOf("ACK", "ERROR") }
                        assertEquals(setOf("ACK", "ERROR"), setOf(ra.type, rb.type))
                        // A subsequent shared action produces a fresh snapshot on both streams.
                        a.send(Request("TAX", id = 2, value = 9))
                        val ca = a.until { it.type == "STATE" && it.city!!.rules.taxRate == 9 }.city
                        val cb = b.until { it.type == "STATE" && it.city!!.rules.taxRate == 9 }.city
                        assertEquals(ca, cb)
                        assertEquals(2, ca!!.revision)
                        a.send(Request("BOND", id = 2))
                        assertEquals("ERROR", a.until { it.id == 2L && it.type == "ERROR" }.type)
                    }
                }
            } finally {
                server.shutdown()
            }
        }

    @Test fun `construction and pause respond while simulation is blocked and stale result is discarded`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val slow =
                object : CityRule {
                    override val id = "test.slow"

                    override suspend fun evolve(
                        city: City,
                        catalog: Catalog,
                    ): City {
                        entered.complete(Unit)
                        // Deliberately emulate a plugin that finishes despite cancellation.
                        return withContext(NonCancellable) {
                            release.await()
                            finished.complete(Unit)
                            city
                        }
                    }
                }
            val server = CityServer(flat().copy(rules = Rules(speed = 1)), port = 0, savePath = null, tickMillis = 40, systems = listOf(slow))
            try {
                Peer(server.port).use { peer ->
                    peer.until { it.type == "STATE" }
                    withTimeout(5000) { entered.await() }
                    peer.send(Request("BUILD", id = 1, tool = "park", x = 2, y = 2))
                    assertEquals("ACK", peer.until { it.id == 1L }.type)
                    val built =
                        peer
                            .until {
                                it.city
                                    ?.tiles
                                    ?.get(34)
                                    ?.building == "park"
                            }.city!!
                    assertEquals(1, built.revision)
                    assertNull(built.day)
                    peer.send(Request("SPEED", id = 2, value = 0))
                    peer.until { it.type == "ACK" && it.id == 2L }
                    peer.until { it.city?.rules?.speed == 0 }
                    release.complete(Unit)
                    withTimeout(5000) { finished.await() }
                    peer.send(Request("TAX", id = 3, value = 8))
                    val latest = peer.until { it.city?.rules?.taxRate == 8 }.city!!
                    assertEquals("park", latest.tiles[34].building)
                    assertEquals(3, latest.revision)
                    assertNull(latest.day)
                    peer.send(Request("SPEED", id = 4, value = 1))
                    val resumed = peer.until { (it.city?.day ?: 0) >= 1 }.city!!
                    assertEquals("park", resumed.tiles[34].building)
                    assertEquals(8, resumed.rules.taxRate)
                    assertTrue(resumed.revision >= 5)
                }
            } finally {
                release.complete(Unit)
                server.shutdown()
            }
        }

    @Test fun `joining during successive edits receives a full baseline and retains citizens`() =
        runBlocking {
            val initial = flat().copy(households = listOf(Household(17, listOf(Citizen(1, "Alice", 30, position = 17)))))
            val server = CityServer(initial, port = 0, savePath = null)
            try {
                Peer(server.port, "Alice").use { a ->
                    assertEquals(initial.households, a.until { it.type == "STATE" }.city!!.households)
                    repeat(20) { a.send(Request("BUILD", id = it + 1L, tool = "trees", x = 2, y = 2)) }
                    Peer(server.port, "Bob").use { b ->
                        assertEquals(initial.households, b.until { it.type == "STATE" }.city!!.households)
                        val built = a.until { it.city?.revision == 20L }.city!!
                        assertTrue(built.tiles[34].trees)
                        assertEquals(initial.households, built.households)
                        a.send(Request("TAX", id = 21, value = 9))
                        val first = a.until { it.city?.revision == 21L }.city!!
                        val second = b.until { it.city?.revision == 21L }.city!!
                        assertEquals(first, second)
                        assertEquals(initial.households, second.households)
                    }
                }
            } finally {
                server.shutdown()
            }
        }

    @Test fun `bad token is disconnected while valid peer keeps working`() =
        runBlocking {
            val server = CityServer(flat(), port = 0, token = "secret", savePath = null)
            try {
                Peer(server.port, token = "wrong").use { assertFails { it.next() } }
                Peer(server.port, token = "secret").use { p ->
                    assertEquals("WELCOME", p.next().type)
                    p.send(Request("PING", id = 1))
                    assertEquals("PONG", p.until { it.id == 1L }.type)
                }
            } finally {
                server.shutdown()
            }
        }

    @Test fun `invalid command is isolated and graceful shutdown saves latest city`() =
        runBlocking {
            val directory = Files.createTempDirectory("city-test")
            val path = directory.resolve("save.json")
            val server = CityServer(flat(), port = 0, savePath = path)
            try {
                Peer(server.port).use { p ->
                    p.until { it.type == "STATE" }
                    p.send(Request("BUILD", id = 1, tool = "coal", x = -1))
                    assertEquals("ERROR", p.until { it.id == 1L }.type)
                    p.send(Request("BUILD", id = 2, tool = "park", x = 2, y = 2))
                    assertEquals("ACK", p.until { it.id == 2L }.type)
                }
                server.shutdown()
                val loaded = Persistence.load(path, Catalog())
                assertEquals("park", loaded.tiles[34].building)
                assertEquals(1, loaded.revision)
            } finally {
                server.close()
                Files.deleteIfExists(path)
                Files.delete(directory)
            }
        }

    @Test fun `persistence preserves plugin state and rejects missing buildings`() {
        val path = Files.createTempFile("city", ".json")
        try {
            val city = flat().copy(pluginData = mapOf("plugin.key" to "héllo"))
            Persistence.save(path, city)
            assertEquals(city, Persistence.load(path, Catalog()))
            val invalid = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i == 0) t.copy(building = "missing", anchor = 0) else t })
            Persistence.save(path, invalid)
            assertFails { Persistence.load(path, Catalog()) }
        } finally {
            Files.delete(path)
        }
    }

    @Test fun `server ticks and pause is authoritative`() =
        runBlocking {
            val server = CityServer(flat().copy(rules = Rules(speed = 3)), port = 0, savePath = null, tickMillis = 40)
            try {
                Peer(server.port).use { p ->
                    val state = p.until { it.city?.day?.let { d -> d >= 3 } == true }.city!!
                    assertEquals(0, state.day!! % 3)
                    p.send(Request("SPEED", id = 1, value = 0))
                    p.until { it.type == "ACK" && it.id == 1L }
                    val paused = p.until { it.type == "STATE" && it.city!!.rules.speed == 0 }.city!!
                    delay(160)
                    p.send(Request("TAX", id = 2, value = 8))
                    p.until { it.type == "ACK" && it.id == 2L }
                    val later = p.until { it.type == "STATE" && it.city!!.rules.taxRate == 8 }.city!!
                    assertEquals(paused.day, later.day)
                }
            } finally {
                server.shutdown()
            }
        }

    @Test fun `autosave catches year boundary even at triple speed`() =
        runBlocking {
            val directory = Files.createTempDirectory("city-autosave")
            val path = directory.resolve("save.json")
            val server = CityServer(flat().copy(month = 11, day = 364, rules = Rules(speed = 3)), port = 0, savePath = path, tickMillis = 40)
            try {
                Peer(server.port).use { peer ->
                    peer.until { (it.city?.month ?: 0) >= 12 }
                    assertTrue(Files.exists(path))
                    assertTrue(Persistence.load(path, Catalog()).month >= 12)
                }
            } finally {
                server.shutdown()
                Files.deleteIfExists(path)
                Files.delete(directory)
            }
        }

    @Test fun `plugin failure pauses simulation without killing server`() =
        runBlocking {
            val failing =
                object : CityRule {
                    override val id = "test.failure"

                    override suspend fun evolve(
                        city: City,
                        catalog: Catalog,
                    ): City = error("deliberate plugin failure")
                }
            val server = CityServer(flat().copy(rules = Rules(speed = 1)), port = 0, savePath = null, tickMillis = 40, systems = listOf(failing))
            try {
                Peer(server.port).use { peer ->
                    val stopped = peer.until { it.city?.notices?.any { notice -> notice.contains("deliberate plugin failure") } == true }.city!!
                    assertEquals(0, stopped.rules.speed)
                    assertEquals(0, stopped.month)
                    peer.send(Request("BUILD", id = 1, tool = "park", x = 2, y = 2))
                    assertEquals("ACK", peer.until { it.id == 1L }.type)
                }
            } finally {
                server.shutdown()
            }
        }

    @Test fun `two clients receive identical shared vertices after earthworks`() =
        runBlocking {
            val server = CityServer(flat(), port = 0, savePath = null)
            try {
                Peer(server.port, "Alice").use { a ->
                    Peer(server.port, "Bob").use { b ->
                        a.until { it.type == "STATE" }
                        b.until { it.type == "STATE" }
                        a.send(Request("BUILD", id = 1, tool = "raise", x = 8, y = 8))
                        a.until { it.type == "ACK" && it.id == 1L }
                        val ca = a.until { it.city?.revision == 1L }.city!!
                        val cb = b.until { it.city?.revision == 1L }.city!!
                        assertEquals(ca, cb)
                        assertTrue(ca.surface(ca.index(7, 8)).ramp)
                        b.send(Request("BUILD", id = 1, tool = "park", x = 7, y = 8))
                        assertEquals("ERROR", b.until { it.id == 1L }.type)
                        b.send(Request("BUILD", id = 2, tool = "level", x = 7, y = 8, value = 2))
                        b.until { it.type == "ACK" && it.id == 2L }
                        assertEquals(a.until { it.city?.revision == 2L }.city, b.until { it.city?.revision == 2L }.city)
                    }
                }
            } finally {
                server.shutdown()
            }
        }
}
