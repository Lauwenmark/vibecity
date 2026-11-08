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

    @Test fun `framing accepts CRLF and rejects oversized and partial commands`() {
        assertEquals("hello", readFrame(StringReader("hello\r\n").buffered(), 16))
        assertFailsWith<IllegalArgumentException> { readFrame(StringReader("123456\n").buffered(), 5) }
        assertFailsWith<EOFException> { readFrame(StringReader("partial").buffered(), 16) }
        assertNull(readFrame(StringReader("").buffered(), 16))
    }
}
