package city.server

import city.core.*
import java.nio.file.Files
import kotlin.test.*

class MobilityPersistenceTest {
    @Test fun `saved resident plans reload identically and invalid traffic is rejected`() {
        val file = Files.createTempFile("mobility", ".json")
        try {
            val tiles = MutableList(256) { Tile() }
            tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 12, powered = true)
            tiles[30] = Tile(zone = Zone.COMMERCIAL_LIGHT, occupants = 8, powered = true)
            val city = MobilitySimulation(Catalog()).simulate(City(width = 16, height = 16, tiles = tiles, terrain = Terrain.flat(16, 16, 2)))
            Persistence.save(file, city)
            assertEquals(city, Persistence.load(file, Catalog()))
            val invalid = city.copy(mobility = city.mobility!!.copy(loads = listOf(TrafficLoad(256, TravelMode.ROAD, List(24) { 1 }))))
            Persistence.save(file, invalid)
            assertFailsWith<IllegalArgumentException> { Persistence.load(file, Catalog()) }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
