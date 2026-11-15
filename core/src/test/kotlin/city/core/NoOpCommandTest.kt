package city.core

import kotlin.test.*

class NoOpCommandTest {
    private val commands = Commands(Catalog())
    private val city = City(width = 16, height = 16, tiles = List(256) { Tile(traffic = 42) }, terrain = Terrain.flat(16, 16), mobility = Mobility(0, emptyList(), 0, 0))

    @Test fun `unchanged rules retain revision mobility and snapshot identity`() {
        for (request in listOf(Request("TAX", value = 7), Request("FUNDING", value = 100), Request("SPEED", value = 1), Request("DISASTERS", value = 0))) {
            assertSame(city, commands.apply(city, request))
        }
        assertFailsWith<IllegalArgumentException> { commands.apply(city, Request("SPEED", value = 4)) }
    }

    @Test fun `repeating free construction and leveling preserves traffic and mobility`() {
        for (tool in listOf("pipe", "subway", "trees", "road", "rail", "power", "zone:RESIDENTIAL_LIGHT", "water", "level")) {
            val request = Request("BUILD", tool = tool, x = 2, y = 2, value = 3)
            val built = commands.apply(city, request).let { it.copy(mobility = city.mobility, tiles = it.tiles.map { tile -> tile.copy(traffic = 42) }) }
            assertSame(built, commands.apply(built, request), tool)
        }
    }

    @Test fun `free edits that remove trees and paid transactions are not no ops`() {
        val planted = city.copy(tiles = city.tiles.map { it.copy(powerLine = true, trees = true) })
        val cleared = commands.apply(planted, Request("BUILD", tool = "power", x = 2, y = 2))
        assertFalse(cleared.tiles[34].trees)
        assertEquals(planted.treasury, cleared.treasury)
        assertEquals(planted.revision + 1, cleared.revision)
        assertNull(cleared.mobility)
        val paid = commands.apply(city, Request("BOND"))
        assertEquals(city.treasury + 10_000, paid.treasury)
        assertEquals(city.revision + 1, paid.revision)
    }
}
