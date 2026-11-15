package city.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class StationPassengersTest {
    private fun leg(vararg nodes: Int): ExecutedLeg = ExecutedLeg(Need.WORK, false, 0, 0, trace = TravelPath(nodes.toList(), nodes.indices.toList()))

    private fun mobility(vararg legs: ExecutedLeg): Mobility =
        Mobility(
            0,
            listOf(MobilityHome(0, 1, emptyList(), days = listOf(CitizenDay(1, 0, emptyList(), legs.toList())))),
            0,
            0,
        )

    @Test fun `counts actual transfers at station walking node including return journeys`() {
        // Station 10 serves tunnel 11; station 20 serves tunnel 19.
        val m = mobility(leg(10 * TRAVEL_MODE_COUNT, 11 * TRAVEL_MODE_COUNT + 3, 12 * TRAVEL_MODE_COUNT + 3, 19 * TRAVEL_MODE_COUNT + 3, 20 * TRAVEL_MODE_COUNT), leg(20 * TRAVEL_MODE_COUNT, 19 * TRAVEL_MODE_COUNT + 3, 12 * TRAVEL_MODE_COUNT + 3, 11 * TRAVEL_MODE_COUNT + 3, 10 * TRAVEL_MODE_COUNT))
        assertEquals(mapOf(10 to StationPassengers(1, 1), 20 to StationPassengers(1, 1)), m.metroStationPassengers)
        assertSame(m.metroStationPassengers, m.metroStationPassengers)
    }

    @Test fun `unfinished trips count boarding but no invented arrival and ignore other modes`() {
        val m = mobility(leg(10 * TRAVEL_MODE_COUNT, 11 * TRAVEL_MODE_COUNT + 3, 11 * TRAVEL_MODE_COUNT + 3, 12 * TRAVEL_MODE_COUNT + 3), leg(20 * TRAVEL_MODE_COUNT, 20 * TRAVEL_MODE_COUNT + 1, 21 * TRAVEL_MODE_COUNT + 1, 21 * TRAVEL_MODE_COUNT), leg(25 * TRAVEL_MODE_COUNT, 25 * TRAVEL_MODE_COUNT + 2, 26 * TRAVEL_MODE_COUNT + 2, 26 * TRAVEL_MODE_COUNT))
        assertEquals(mapOf(10 to StationPassengers(1, 0)), m.metroStationPassengers)
        assertTrue(mobility(leg(11 * TRAVEL_MODE_COUNT + 3, 12 * TRAVEL_MODE_COUNT + 3, 19 * TRAVEL_MODE_COUNT + 3)).metroStationPassengers.isEmpty())
        assertTrue(mobility().metroStationPassengers.isEmpty())
    }

    @Test fun `derived counts do not change the serialized protocol`() {
        val m = mobility(leg(10 * TRAVEL_MODE_COUNT, 11 * TRAVEL_MODE_COUNT + 3, 20 * TRAVEL_MODE_COUNT))
        val before = wireJson.encodeToString(m)
        assertEquals(StationPassengers(1, 0), m.metroStationPassengers[10])
        assertEquals(before, wireJson.encodeToString(m))
        assertFalse(before.contains("metroStationPassengers"))
    }
}
