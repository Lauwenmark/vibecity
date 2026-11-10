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
        val m = mobility(leg(40, 47, 51, 79, 80), leg(80, 79, 51, 47, 40))
        assertEquals(mapOf(10 to StationPassengers(1, 1), 20 to StationPassengers(1, 1)), m.metroStationPassengers)
        assertSame(m.metroStationPassengers, m.metroStationPassengers)
    }

    @Test fun `unfinished trips count boarding but no invented arrival and ignore other modes`() {
        val m = mobility(leg(40, 47, 47, 51), leg(80, 81, 85, 84), leg(100, 102, 106, 104))
        assertEquals(mapOf(10 to StationPassengers(1, 0)), m.metroStationPassengers)
        assertTrue(mobility(leg(47, 51, 79)).metroStationPassengers.isEmpty())
        assertTrue(mobility().metroStationPassengers.isEmpty())
    }

    @Test fun `derived counts do not change the serialized protocol`() {
        val m = mobility(leg(40, 47, 80))
        val before = wireJson.encodeToString(m)
        assertEquals(StationPassengers(1, 0), m.metroStationPassengers[10])
        assertEquals(before, wireJson.encodeToString(m))
        assertFalse(before.contains("metroStationPassengers"))
    }
}
