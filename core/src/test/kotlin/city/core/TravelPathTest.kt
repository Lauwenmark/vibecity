package city.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class TravelPathTest {
    @Test fun `list and array inputs are snapshotted and views cannot mutate registry keys`() {
        val nodes = mutableListOf(4, 5, 9, 8)
        val minutes = intArrayOf(0, 2, 4, 6)
        val fromList = TravelPath(nodes, minutes.toList())
        val fromArray = TravelPath(nodes.toIntArray(), minutes)
        val registry = HomeRoutes(listOf(fromList))
        val hash = fromList.hashCode()
        nodes[0] = 99
        minutes[0] = 99
        assertEquals(listOf(4, 5, 9, 8), fromList.nodes)
        assertEquals(listOf(0, 2, 4, 6), fromArray.minutes)
        assertEquals(fromList, fromArray)
        assertEquals(hash, fromList.hashCode())
        assertFails { (fromList.nodes as MutableList<Int>)[0] = 100 }
        assertFails { (fromList.minutes as MutableList<Int>).clear() }
        assertEquals(0, registry.intern(fromArray))
        assertEquals(1, registry.paths.size)
        assertEquals(0, registry.intern(TravelPath(listOf(4, 5, 9, 8), listOf(0, 3, 5, 7))))
    }

    @Test fun `content equality hashing copy and destructuring preserve value semantics`() {
        val path = TravelPath(listOf(128, 129, 133, 132), listOf(0, 2, 4, 6))
        val copy = path.copy()
        assertEquals(path, copy)
        assertEquals(path.hashCode(), copy.hashCode())
        assertEquals("found", mapOf(path to "found")[copy])
        assertEquals(listOf(128, 129, 133, 132).hashCode(), path.nodes.hashCode())
        assertEquals(path.nodes, path.nodes.toList())
        assertEquals(path.nodes.toList(), path.nodes)
        assertNotEquals(path, path.copy(minutes = listOf(0, 3, 5, 7)))
        val (nodes, minutes) = path
        assertEquals(path.nodes, nodes)
        assertEquals(path.minutes, minutes)
        assertEquals(6, path.duration)
        assertEquals(132 / TRAVEL_MODE_COUNT, path.destination)
    }

    @Test fun `legacy JSON representation round trips exactly including field order variants`() {
        val json = """{"nodes":[128,129,133,132],"minutes":[0,2,4,6]}"""
        val path = wireJson.decodeFromString<TravelPath>(json)
        assertEquals(json, wireJson.encodeToString(path))
        assertEquals(path, wireJson.decodeFromString<TravelPath>("""{"minutes":[0,2,4,6],"nodes":[128,129,133,132]}"""))
        assertEquals(TravelPath(emptyList(), emptyList()), wireJson.decodeFromString<TravelPath>("""{"nodes":[],"minutes":[]}"""))
        assertFails { wireJson.decodeFromString<TravelPath>("""{"nodes":[0]}""") }
        assertFails { wireJson.decodeFromString<TravelPath>("""{"minutes":[0]}""") }
        assertFails { wireJson.decodeFromString<TravelPath>("""{"nodes":[0],"minutes":[0],"extra":1}""") }
    }
}
