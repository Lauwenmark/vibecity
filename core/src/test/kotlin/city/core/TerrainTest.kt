package city.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class TerrainTest {
    private val commands = Commands(Catalog())

    private fun flat() = City(width = 16, height = 16, tiles = List(256) { Tile() }, terrain = Terrain.flat(16, 16))

    private fun ramp() = flat().withTerrain(Terrain(16, 16, List(17 * 17) { if (it % 17 <= 7) 2 else 3 }))

    private fun build(
        c: City,
        tool: String,
        x: Int,
        y: Int,
        value: Int = 0,
    ) = commands.apply(c, Request("BUILD", tool = tool, x = x, y = y, value = value))

    @Test fun `generated surfaces share edges and remain gentle`() {
        val c = City.generate()
        assertTrue(c.tiles.indices.any { !c.surface(it).flat })
        assertEquals(c, City.generate())
        for (y in 0 until c.height) {
            for (x in 0 until c.width) {
                val s = c.surface(c.index(x, y))
                assertTrue(s.maximum - s.minimum <= 1)
                assertEquals(s.minimum, c.tiles[c.index(x, y)].elevation)
                if (x + 1 < c.width) {
                    val next = c.surface(c.index(x + 1, y))
                    assertEquals(s.east, next.north)
                    assertEquals(s.south, next.west)
                }
                if (y + 1 < c.height) {
                    val next = c.surface(c.index(x, y + 1))
                    assertEquals(s.west, next.north)
                    assertEquals(s.south, next.east)
                }
            }
        }
    }

    @Test fun `raising a tile creates ramps and corners around a flat summit`() {
        val c = build(flat(), "raise", 8, 8)
        assertEquals(Surface(3, 3, 3, 3), c.surface(c.index(8, 8)))
        assertEquals(Surface(2, 3, 3, 2), c.surface(c.index(7, 8)))
        assertEquals(Surface(2, 2, 3, 2), c.surface(c.index(7, 7)))
        assertEquals(99980, c.treasury)
        assertEquals(1, c.revision)
        assertEquals(2, c.tiles[c.index(7, 8)].elevation)
    }

    @Test fun `terracing propagates slopes but refuses collateral damage atomically`() {
        val original = build(flat(), "park", 7, 8)
        assertFailsWith<IllegalArgumentException> { build(original, "raise", 8, 8) }
        assertEquals(99990, original.treasury)
        assertEquals("park", original.tiles[original.index(7, 8)].building)
        val pipes = build(flat(), "pipe", 7, 8)
        assertFailsWith<IllegalArgumentException> { build(pipes, "raise", 8, 8) }
        var c = flat()
        repeat(4) { c = build(c, "raise", 8, 8) }
        assertEquals(6, c.surface(c.index(8, 8)).minimum)
        assertTrue(c.surface(c.index(6, 8)).maximum > 2)
        assertTrue(c.tiles.indices.all { c.surface(it).maximum - c.surface(it).minimum <= 1 })
    }

    @Test fun `level and bounds protect funds and produce flat ground`() {
        val c = build(ramp(), "level", 7, 8, 3)
        assertTrue(c.surface(c.index(7, 8)).flat)
        assertEquals(3, c.surface(c.index(7, 8)).minimum)
        assertFailsWith<IllegalArgumentException> { build(c, "level", 7, 8, 13) }
        assertFailsWith<IllegalArgumentException> { build(flat().copy(treasury = 0), "raise", 8, 8) }
        val high = flat().withTerrain(Terrain.flat(16, 16, 12))
        assertFailsWith<IllegalArgumentException> { build(high, "raise", 8, 8) }
        assertEquals(c.treasury, build(c, "level", 7, 8, 3).treasury)
    }

    @Test fun `roads and rails accept straight ramps but not compound slopes`() {
        val c = build(flat(), "raise", 8, 8)
        assertNotNull(build(c, "road", 7, 8))
        assertNotNull(build(c, "rail", 7, 8))
        assertFailsWith<IllegalArgumentException> { build(c, "road", 7, 7) }
        assertFailsWith<IllegalArgumentException> { build(c, "rail", 7, 7) }
        assertTrue(c.transportConnects(c.index(7, 8), c.index(8, 8)))
        assertFalse(c.transportConnects(c.index(7, 8), c.index(7, 9)))
    }

    @Test fun `fixed buildings require a flat footprint rather than equal minimum heights`() {
        val c = ramp()
        assertEquals(c.tiles[c.index(6, 8)].elevation, c.tiles[c.index(7, 8)].elevation)
        assertFailsWith<IllegalArgumentException> { build(c, "park", 7, 8) }
        assertFailsWith<IllegalArgumentException> { build(c, "coal", 5, 8) }
        assertEquals("park", build(c, "park", 6, 8).tiles[c.index(6, 8)].building)
    }

    @Test fun `sloped zones cannot develop even with power and a nearby road`() =
        runBlocking {
            var c = build(ramp(), "coal", 0, 0)
            for (x in 4..7) c = build(c, "power", x, 2)
            c = build(c, "zone:RESIDENTIAL_LIGHT", 7, 3)
            c = build(c, "road", 6, 3)
            repeat(8) { c = Simulation(Catalog()).step(c) }
            assertTrue(c.tiles[c.index(7, 3)].powered)
            assertEquals(0, c.tiles[c.index(7, 3)].occupants)
        }

    @Test fun `hydro needs surface water on a simple ramp`() {
        val c = build(ramp(), "water", 7, 8)
        assertEquals("hydro", build(c, "hydro", 7, 8).tiles[c.index(7, 8)].building)
        assertFailsWith<IllegalArgumentException> { build(ramp(), "hydro", 7, 8) }
        val lake = build(ramp(), "water", 9, 8)
        assertFailsWith<IllegalArgumentException> { build(lake, "hydro", 9, 8) }
    }

    @Test fun `earthworks update zero-level water and remain serializable`() {
        var c = build(flat(), "level", 8, 8, 0)
        assertTrue(c.tiles[c.index(8, 8)].water)
        c = build(c, "raise", 8, 8)
        assertFalse(c.tiles[c.index(8, 8)].water)
        assertEquals(c, wireJson.decodeFromString<City>(wireJson.encodeToString(c)))
    }

    @Test fun `legacy conversion preserves development and reconciles neighboring pads`() {
        val old =
            flat().copy(
                terrain = null,
                tiles =
                    flat().tiles.mapIndexed { i, t ->
                        when (i) {
                            85 -> t.copy(building = "park", anchor = i, elevation = 2)
                            86 -> t.copy(building = "park", anchor = i, elevation = 5)
                            else -> t
                        }
                    },
                pluginData = mapOf("example" to "retained"),
            )
        val converted = old.withSharedTerrain()
        assertNotNull(converted.terrain)
        assertTrue(converted.surface(85).flat)
        assertTrue(converted.surface(86).flat)
        assertEquals(converted.surface(85).minimum, converted.surface(86).minimum)
        assertEquals(old.treasury, converted.treasury)
        assertEquals(old.pluginData, converted.pluginData)
        assertEquals(old.tiles.map { it.building }, converted.tiles.map { it.building })
        assertSame(converted, converted.withSharedTerrain())
    }

    @Test fun `invalid shared terrain is rejected`() {
        assertFailsWith<IllegalArgumentException> { Terrain(16, 16, listOf(2)) }
        assertFailsWith<IllegalArgumentException> { Terrain(16, 16, List(289) { if (it == 0) 5 else 2 }) }
    }
}
