package city.server

import city.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class MobilityPersistenceTest {
    @Test fun `saved resident plans reload identically and invalid traffic is rejected`() =
        runBlocking<Unit> {
            val file = Files.createTempFile("mobility", ".json")
            try {
                val tiles = MutableList(256) { Tile() }
                tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 12, powered = true)
                tiles[30] = Tile(zone = Zone.COMMERCIAL_LIGHT, occupants = 8, powered = true)
                val city = MobilitySimulation(Catalog()).simulate(City(width = 16, height = 16, tiles = tiles, terrain = Terrain.flat(16, 16, 2)))
                Persistence.save(file, city)
                val loaded = Persistence.load(file, Catalog())
                assertEquals(city, loaded)
                assertSame(
                    loaded.households.single().paths,
                    loaded.mobility!!
                        .homes
                        .single()
                        .paths,
                )
                val invalid =
                    city.copy(
                        mobility =
                            city.mobility!!.copy(
                                homes =
                                    listOf(
                                        city.mobility!!
                                            .homes
                                            .single()
                                            .copy(loads = listOf(TrafficLoad(256, TravelMode.ROAD, List(24) { 1 }))),
                                    ),
                            ),
                    )
                Persistence.save(file, invalid)
                assertFailsWith<IllegalArgumentException> { Persistence.load(file, Catalog()) }
            } finally {
                Files.deleteIfExists(file)
            }
        }

    @Test fun `version three saves keep city data and invalidate only global mobility`() {
        val file = Files.createTempFile("legacy-mobility", ".json")
        try {
            val city = City(width = 16, height = 16, tiles = List(256) { Tile(traffic = 50) }, terrain = Terrain.flat(16, 16, 2), treasury = 12345)
            val encoded = wireJson.encodeToJsonElement(city).jsonObject
            val old =
                buildJsonObject {
                    put("month", 0)
                    put("homes", JsonArray(emptyList()))
                    put("paths", JsonArray(emptyList()))
                    put("loads", JsonArray(emptyList()))
                    put("completedNeeds", 0)
                    put("unmetNeeds", 0)
                }
            val bytes = wireJson.encodeToString(JsonObject(encoded + ("mobility" to old)))
            Files.writeString(file, bytes)
            val loaded = Persistence.load(file, Catalog())
            assertEquals(city.copy(tiles = city.tiles.map { it.copy(traffic = 0) }), loaded)
            assertEquals(bytes, Files.readString(file))
            assertNull(loaded.mobility)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun `missing execution marker migrates but malformed modern mobility is rejected`() {
        val file = Files.createTempFile("mobility-marker", ".json")
        try {
            val city = City(width = 16, height = 16, tiles = List(256) { Tile(traffic = 12) }, terrain = Terrain.flat(16, 16, 2), mobility = Mobility(0, emptyList(), 0, 0))
            val encoded = wireJson.encodeToJsonElement(city).jsonObject
            val mobility = encoded.getValue("mobility").jsonObject
            Files.writeString(file, JsonObject(encoded + ("mobility" to JsonObject(mobility - "executionVersion"))).toString())
            val migrated = Persistence.load(file, Catalog())
            assertNull(migrated.mobility)
            assertTrue(migrated.tiles.all { it.traffic == 0 })
            Files.writeString(file, JsonObject(encoded + ("mobility" to JsonObject(mobility + ("unknown" to JsonPrimitive(1))))).toString())
            assertFails { Persistence.load(file, Catalog()) }
            Files.writeString(file, wireJson.encodeToString(city).dropLast(5))
            assertFails { Persistence.load(file, Catalog()) }
        } finally {
            Files.delete(file)
        }
    }

    @Test fun `saved learning and identities resume deterministically and duplicate ids are rejected`() =
        runBlocking<Unit> {
            val file = Files.createTempFile("citizen-memory", ".json")
            try {
                val tiles = MutableList(256) { Tile() }
                tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 2, powered = true)
                tiles[30] = Tile(zone = Zone.COMMERCIAL_LIGHT, occupants = 8, powered = true)
                val city = MobilitySimulation(Catalog()).simulate(City(width = 16, height = 16, tiles = tiles, terrain = Terrain.flat(16, 16, 2)))
                Persistence.save(file, city)
                val restored = Persistence.load(file, Catalog())
                assertEquals(city.households, restored.households)
                assertEquals(MobilitySimulation(Catalog(), 1).simulate(city.copy(day = 1)), MobilitySimulation(Catalog(), 4).simulate(restored.copy(day = 1)))
                val home = restored.households.single()
                val invalid = restored.copy(households = listOf(home.copy(people = listOf(home.people.first(), home.people.first()))))
                Persistence.save(file, invalid)
                assertFailsWith<IllegalArgumentException> { Persistence.load(file, Catalog()) }
            } finally {
                Files.deleteIfExists(file)
            }
        }
}
