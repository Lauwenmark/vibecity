package city.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class SharedWireRoutesTest {
    private fun city(): City {
        val paths = List(100) { TravelPath(List(30) { n -> n * TRAVEL_MODE_COUNT }, List(30) { n -> n * 5 }) }
        return City(width = 16, height = 16, tiles = List(256) { Tile() }, households = listOf(Household(17, emptyList(), paths)), mobility = Mobility(0, listOf(MobilityHome(17, 1, emptyList(), paths)), 0, 0))
    }

    @Test fun `complete and simulated snapshots omit duplicate routes and restore exactly`() {
        val original = city()
        val full = Response("STATE", city = original)
        val packed = full.compactState(null)
        assertEquals(listOf(17), packed.sharedRouteHomes)
        assertTrue(
            packed.city!!
                .mobility!!
                .homes
                .single()
                .paths
                .isEmpty(),
        )
        assertTrue(wireJson.encodeToString(packed).length < wireJson.encodeToString(full).length)
        val received = wireJson.decodeFromString<Response>(wireJson.encodeToString(packed)).restoreState(null)
        assertEquals(full, received)
        assertSame(
            received.city!!
                .households
                .single()
                .paths,
            received.city!!
                .mobility!!
                .homes
                .single()
                .paths,
        )
        val simulated = original.copy(revision = 1, households = original.households.toList(), mobility = original.mobility!!.copy(day = 1))
        assertEquals(simulated, Response("STATE", city = simulated).compactState(original).restoreState(received.city).city)
        val next = original.copy(revision = 2, mobility = original.mobility!!.copy(day = 2))
        val retained = Response("STATE", city = next).compactState(original)
        assertTrue(retained.retained!!.households)
        assertEquals(next, retained.restoreState(original).city)
    }

    @Test fun `invalid route references cannot silently replace data`() {
        val full = Response("STATE", city = city())
        val packed = full.compactState(null)
        assertFails { packed.copy(sharedRouteHomes = listOf(17, 17)).restoreState(null) }
        assertFails { packed.copy(sharedRouteHomes = listOf(18)).restoreState(null) }
        assertFails { packed.copy(city = packed.city!!.copy(households = emptyList())).restoreState(null) }
        assertFails { full.copy(sharedRouteHomes = listOf(17)).restoreState(null) }
    }
}
