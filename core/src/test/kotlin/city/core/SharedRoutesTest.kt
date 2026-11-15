package city.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class SharedRoutesTest {
    @Test fun `decoded state shares equal registries without changing values or indices`() {
        val paths = listOf(TravelPath(listOf(17 * TRAVEL_MODE_COUNT, 18 * TRAVEL_MODE_COUNT), listOf(0, 5)))
        val original = City(width = 16, height = 16, tiles = List(256) { Tile() }, households = listOf(Household(17, emptyList(), paths)), mobility = Mobility(0, listOf(MobilityHome(17, 1, emptyList(), paths)), 0, 0))
        val encoded = wireJson.encodeToString(Response("STATE", city = original))
        val decoded = wireJson.decodeFromString<Response>(encoded)
        val restored = decoded.restoreState(null).city!!
        assertEquals(original, restored)
        assertSame(
            restored.households.single().paths,
            restored.mobility!!
                .homes
                .single()
                .paths,
        )
        assertSame(restored, restored.shareRouteRegistries())
        assertEquals(encoded, wireJson.encodeToString(Response("STATE", city = restored)))
        val mismatch =
            original.copy(
                mobility =
                    original.mobility!!.copy(
                        homes =
                            listOf(
                                original.mobility!!
                                    .homes
                                    .single()
                                    .copy(paths = listOf(paths.single().copy(minutes = listOf(0, 8)))),
                            ),
                    ),
            )
        assertSame(mismatch, mismatch.shareRouteRegistries())
        val noHouseholds = original.copy(households = emptyList())
        assertSame(noHouseholds, noHouseholds.shareRouteRegistries())
    }
}
