package city.server

import city.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class PortraitPersistenceTest {
    private fun city(): City {
        val tiles = MutableList(256) { Tile() }
        tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1)
        val resident =
            Citizen(
                1,
                "Alice",
                16,
                position = 17 * TRAVEL_MODE_COUNT,
                life = CitizenLife(-5844, Sex.FEMALE, 50.0, 1.0, LifeStage.SCHOOL),
            )
        return City(
            width = 16,
            height = 16,
            tiles = tiles,
            terrain = Terrain.flat(16, 16),
            households = listOf(Household(17, listOf(resident))),
            nextCitizenId = 2,
        )
    }

    @Test fun `old disk saves migrate deterministically and keep the code after aging and reload`() {
        val file = Files.createTempFile("portraits-legacy", ".json")
        try {
            val input = city()
            val encoded = wireJson.parseToJsonElement(wireJson.encodeToString(input)).jsonObject
            val home =
                encoded
                    .getValue("households")
                    .jsonArray
                    .single()
                    .jsonObject
            val person =
                home
                    .getValue("people")
                    .jsonArray
                    .single()
                    .jsonObject
            val legacyHome = JsonObject(home + ("people" to JsonArray(listOf(JsonObject(person - "portraitCode")))))
            Files.writeString(file, JsonObject(encoded + ("households" to JsonArray(listOf(legacyHome)))).toString())
            val first = Persistence.load(file, Catalog())
            val second = Persistence.load(file, Catalog())
            assertEquals(first, second)
            val resident =
                first.households
                    .single()
                    .people
                    .single()
            assertTrue(PortraitCodes.valid(resident.portraitCode))
            assertEquals(Sex.FEMALE, PortraitCodes.sex(resident.portraitCode))
            val aged = first.copy(households = listOf(Household(17, listOf(resident.copy(age = 85)))))
            Persistence.save(file, aged)
            assertEquals(aged, Persistence.load(file, Catalog()))
            assertEquals(
                resident.portraitCode,
                aged.households
                    .single()
                    .people
                    .single()
                    .portraitCode,
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun `custom appearances survive household delta wire transport and invalid codes are rejected`() {
        val baseline = city()
        val person =
            baseline.households
                .single()
                .people
                .single()
        val code = PortraitCodes.withVariant(person.portraitCode, PortraitPart.FRAME, 4)
        val updated = baseline.copy(revision = baseline.revision + 1, households = listOf(Household(17, listOf(person.copy(portraitCode = code)))))
        val compact = Response("STATE", city = updated).compactState(baseline)
        val restored = wireJson.decodeFromString<Response>(wireJson.encodeToString(compact)).restoreState(baseline)
        assertEquals(updated, restored.city)
        val file = Files.createTempFile("portraits-invalid", ".json")
        try {
            Persistence.save(file, baseline.copy(households = listOf(Household(17, listOf(person.copy(portraitCode = -1))))))
            assertFailsWith<IllegalArgumentException> { Persistence.load(file, Catalog()) }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
