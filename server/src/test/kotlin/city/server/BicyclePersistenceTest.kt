package city.server

import city.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class BicyclePersistenceTest {
    @Test fun `old four mode save migrates all nodes without losing identities memories or traces`() {
        val path = TravelPath(listOf(68, 69, 73, 72), listOf(0, 2, 4, 6))
        val person = Citizen(1, "Camille", 30, routes = listOf(LearnedRoute(0, Need.FOOD, false, 0, 80)), position = 72)
        val day = CitizenDay(1, 68, listOf(PlannedActivity(Need.FOOD, 480, 30, 0)), listOf(ExecutedLeg(Need.FOOD, false, 0, 480, 486, path, decisions = listOf(RouteDecision(482, 69, "Patienter")))))
        val city =
            City(
                width = 16,
                height = 16,
                tiles = List(256) { if (it == 17) Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1) else Tile() },
                terrain = Terrain.flat(16, 16, 2),
                households = listOf(Household(17, listOf(person), listOf(path))),
                nextCitizenId = 2,
                mobility = Mobility(0, listOf(MobilityHome(17, 1, listOf(NeedOptions(Need.FOOD, listOf(0))), listOf(path), days = listOf(day))), 0, 0),
                travelModeCount = 4,
            )
        val file = Files.createTempFile("bicycle-migration", ".json")
        try {
            val legacy = JsonObject(wireJson.encodeToJsonElement(city).jsonObject - "travelModeCount").toString()
            Files.writeString(file, legacy)
            val restored = Persistence.load(file, Catalog())
            val expected = listOf(85, 86, 91, 90)
            assertEquals(
                expected,
                restored.households
                    .single()
                    .paths
                    .single()
                    .nodes,
            )
            assertEquals(
                expected,
                restored.mobility!!
                    .homes
                    .single()
                    .days
                    .single()
                    .legs
                    .single()
                    .trace.nodes,
            )
            assertEquals(
                86,
                restored.mobility!!
                    .homes
                    .single()
                    .days
                    .single()
                    .legs
                    .single()
                    .decisions
                    .single()
                    .node,
            )
            assertEquals(
                85,
                restored.mobility!!
                    .homes
                    .single()
                    .days
                    .single()
                    .startNode,
            )
            assertEquals(
                person.copy(position = 90),
                restored.households
                    .single()
                    .people
                    .single(),
            )
            assertEquals(legacy, Files.readString(file))
            Persistence.save(file, restored)
            assertEquals(restored, Persistence.load(file, Catalog()))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun `bicycle state round trips and malformed locations are rejected`() {
        val bike = BicycleState(cell = 20, parkedSince = 500, checkedUntil = 700, protectedParking = true, savings = 42.0, updatedDay = 1, lastTheftDay = 0, lastTheftCell = 30, thefts = 1)
        val person = Citizen(1, "Cycliste", 30, position = 20 * TRAVEL_MODE_COUNT, bicycle = bike)
        val city = City(width = 16, height = 16, tiles = List(256) { Tile() }, terrain = Terrain.flat(16, 16, 2), households = listOf(Household(17, listOf(person))), nextCitizenId = 2)
        val file = Files.createTempFile("bicycle-state", ".json")
        try {
            Persistence.save(file, city)
            assertEquals(city, Persistence.load(file, Catalog()))
            val invalid = city.copy(households = listOf(Household(17, listOf(person.copy(bicycle = bike.copy(cell = 256))))))
            Files.writeString(file, wireJson.encodeToString(invalid))
            assertFailsWith<IllegalArgumentException> { Persistence.load(file, Catalog()) }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
