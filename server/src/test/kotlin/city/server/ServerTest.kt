package city.server

import city.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.Socket
import java.nio.file.Files
import kotlin.test.*

class ServerTest {
    private fun flat() = City(width = 16, height = 16, tiles = List(256) { Tile() }, rules = Rules(speed = 0))

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

        fun next(): Response = wireJson.decodeFromString(readFrame(reader, 64 * 1024 * 1024) ?: error("EOF"))

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
                    val state = p.until { it.city?.month?.let { m -> m >= 3 } == true }.city!!
                    assertEquals(0, state.month % 3)
                    p.send(Request("SPEED", id = 1, value = 0))
                    p.until { it.type == "ACK" && it.id == 1L }
                    val paused = p.until { it.type == "STATE" && it.city!!.rules.speed == 0 }.city!!
                    delay(160)
                    p.send(Request("TAX", id = 2, value = 8))
                    p.until { it.type == "ACK" && it.id == 2L }
                    val later = p.until { it.type == "STATE" && it.city!!.rules.taxRate == 8 }.city!!
                    assertEquals(paused.month, later.month)
                }
            } finally {
                server.shutdown()
            }
        }

    @Test fun `autosave catches year boundary even at triple speed`() =
        runBlocking {
            val directory = Files.createTempDirectory("city-autosave")
            val path = directory.resolve("save.json")
            val server = CityServer(flat().copy(month = 11, rules = Rules(speed = 3)), port = 0, savePath = path, tickMillis = 40)
            try {
                Peer(server.port).use { peer ->
                    peer.until { (it.city?.month ?: 0) >= 14 }
                    assertTrue(Files.exists(path))
                    assertTrue(Persistence.load(path, Catalog()).month >= 14)
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
}
