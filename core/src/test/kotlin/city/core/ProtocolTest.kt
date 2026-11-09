package city.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.EOFException
import java.io.StringReader
import kotlin.test.*

class ProtocolTest {
    @Test fun `unicode json is one readable line and round trips`() {
        val response = Response("ERROR", message = "Électricité\nindisponible")
        val json = wireJson.encodeToString(response)
        assertFalse(json.contains('\n'))
        assertTrue(json.contains("Électricité"))
        assertEquals(response, wireJson.decodeFromString<Response>(json))
    }

    @Test fun `construction omits unchanged citizens and restores the exact authoritative state`() {
        val households = listOf(Household(17, List(2000) { Citizen(it.toLong() + 1, "Habitant $it", 30, position = 17) }))
        val initial = City(width = 16, height = 16, tiles = List(256) { Tile() }, households = households, mobility = Mobility(0, emptyList(), 0, 0))
        val built = Commands(Catalog()).apply(initial, Request("BUILD", tool = "park", x = 2, y = 2))
        val full = Response("STATE", city = built)
        val compact = full.compactState(initial)
        assertTrue(compact.retained!!.households)
        assertFalse(compact.retained.mobility)
        assertTrue(compact.city!!.households.isEmpty())
        assertTrue(wireJson.encodeToString(compact).length < wireJson.encodeToString(full).length / 2)
        val restored = wireJson.decodeFromString<Response>(wireJson.encodeToString(compact)).restoreState(initial)
        assertEquals(full, restored)
        assertSame(households, restored.city!!.households)
        assertNull(restored.city.mobility)
        assertFailsWith<IllegalArgumentException> { compact.restoreState(null) }
        assertFailsWith<IllegalArgumentException> { compact.restoreState(initial.copy(revision = 99)) }
        assertNull(full.compactState(null).retained)
    }

    @Test fun `state chain retains mobility but transmits changed citizens including an empty population`() {
        val city = City(width = 16, height = 16, tiles = List(256) { Tile() }, households = listOf(Household(17, listOf(Citizen(1, "Alice", 30, position = 17)))), mobility = Mobility(0, emptyList(), 0, 0))
        val tax = Commands(Catalog()).apply(city, Request("TAX", value = 9))
        val compact = Response("STATE", city = tax).compactState(city)
        assertTrue(compact.retained!!.mobility)
        val restored = compact.restoreState(city).city!!
        assertSame(city.mobility, restored.mobility)
        val empty = tax.copy(revision = tax.revision + 1, households = emptyList(), mobility = null)
        val next = Response("STATE", city = empty).compactState(tax)
        assertEquals(empty, next.restoreState(restored).city)
        assertTrue(next.city!!.households.isEmpty())
        assertNull(next.retained)
    }

    @Test fun `rapid earthworks transmit sparse maps and reconstruct every authoritative field`() {
        var previous =
            City(
                width = 64,
                height = 64,
                tiles = List(64 * 64) { Tile() },
                terrain = Terrain.flat(64, 64),
                households = listOf(Household(17, listOf(Citizen(1, "Alice", 30, position = 17)))),
                mobility = Mobility(0, emptyList(), 0, 0),
            )
        var received = previous
        val commands = Commands(Catalog())
        repeat(24) { step ->
            val current = commands.apply(previous, Request("BUILD", tool = "level", x = 8 + step, y = 8, value = 3))
            val full = Response("STATE", city = current, players = listOf("Alice"))
            val compact = full.compactState(previous)
            assertNull(compact.city)
            val delta = assertNotNull(compact.delta)
            assertTrue(delta.tiles.size < 32)
            assertTrue(delta.vertices.size < 32)
            val encoded = wireJson.encodeToString(compact)
            assertTrue(encoded.length < wireJson.encodeToString(full).length / 20)
            val restored = wireJson.decodeFromString<Response>(encoded).restoreState(received)
            assertEquals(full, restored)
            assertNull(restored.city!!.mobility)
            received = restored.city
            previous = current
        }
        // Delta metadata must also survive a simulation, empty populations and plugin changes.
        val changed =
            previous.copy(
                revision = previous.revision + 1,
                month = 2,
                day = 60,
                debt = 500,
                rules = Rules(speed = 0, taxRate = 9),
                stats = Statistics(population = 42),
                trafficRules = TrafficRules(roadSlots = 8),
                history = listOf(previous.stats),
                notices = listOf("Test"),
                pluginData = mapOf("test" to "value"),
                households = emptyList(),
                nextCitizenId = 42,
                mobility = Mobility(2, emptyList(), 0, 0),
            )
        val response = Response("STATE", city = changed)
        assertEquals(response, wireJson.decodeFromString<Response>(wireJson.encodeToString(response.compactState(previous))).restoreState(received))
    }

    @Test fun `map deltas reject missing or incompatible baselines and invalid patches`() {
        val city = City(width = 16, height = 16, tiles = List(256) { Tile() }, terrain = Terrain.flat(16, 16))
        val response = Response("STATE", city = city.copy(revision = 1)).compactState(city)
        val delta = assertNotNull(response.delta)
        assertFailsWith<IllegalArgumentException> { response.restoreState(null) }
        assertFailsWith<IllegalArgumentException> { response.restoreState(city.copy(revision = 2)) }
        assertFailsWith<IllegalArgumentException> { response.restoreState(city.copy(seed = 42)) }
        assertFailsWith<IllegalArgumentException> { response.restoreState(city.copy(terrain = null)) }
        assertFailsWith<IllegalArgumentException> { response.copy(city = city).restoreState(city) }
        assertFailsWith<IllegalArgumentException> { response.copy(delta = delta.copy(tiles = mapOf(256 to Tile()))).restoreState(city) }
        assertFailsWith<IllegalArgumentException> { response.copy(delta = delta.copy(vertices = mapOf(-1 to 2))).restoreState(city) }
        assertFailsWith<IllegalArgumentException> { response.copy(delta = delta.copy(vertices = mapOf(0 to 13))).restoreState(city) }
        assertFailsWith<IllegalArgumentException> { response.copy(delta = delta.copy(vertices = mapOf(0 to 6))).restoreState(city) }
        val dense = city.copy(revision = 1, tiles = List(256) { Tile(trees = true) })
        val full = Response("STATE", city = dense).compactState(city)
        assertNotNull(full.city)
        assertNull(full.delta)
        assertEquals(dense, full.restoreState(city).city)
        assertNull(Response("STATE", city = dense).compactState(null).delta)
    }

    @Test fun `block framing preserves following frames and exact boundaries`() {
        for (size in listOf(0, 1, 8191, 8192, 8193, 16384)) {
            val text = "é".repeat(size)
            val reader = StringReader(text + "\r\nnext\nlast\n").buffered()
            assertEquals(text, readFrame(reader, size + 1))
            assertEquals("next", readFrame(reader, 4))
            assertEquals("last", readFrame(reader, 4))
            assertNull(readFrame(reader, 4))
            assertEquals(text, readFrame(StringReader(text + "\n").buffered(), size))
            if (size > 0) {
                assertFailsWith<IllegalArgumentException> { readFrame(StringReader(text + "\n").buffered(), size - 1) }
                assertFailsWith<EOFException> { readFrame(StringReader(text).buffered(), size) }
            }
        }
        val source = StringReader("first\r\nsecond\n")
        val fragmented =
            object : java.io.Reader() {
                override fun read(
                    buffer: CharArray,
                    offset: Int,
                    length: Int,
                ): Int = source.read(buffer, offset, minOf(length, 3))

                override fun close() = source.close()
            }.buffered()
        assertEquals("first", readFrame(fragmented, 6))
        assertEquals("second", readFrame(fragmented, 6))
        assertNull(readFrame(fragmented, 6))
    }

    @Test fun `framing accepts CRLF and rejects oversized and partial commands`() {
        assertEquals("hello", readFrame(StringReader("hello\r\n").buffered(), 16))
        assertFailsWith<IllegalArgumentException> { readFrame(StringReader("123456\n").buffered(), 5) }
        assertFailsWith<EOFException> { readFrame(StringReader("partial").buffered(), 16) }
        assertNull(readFrame(StringReader("").buffered(), 16))
    }
}
