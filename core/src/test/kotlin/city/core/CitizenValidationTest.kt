package city.core

import kotlin.test.*

class CitizenValidationTest {
    private fun populated(size: Int): City {
        val households = List(size * size) { cell -> Household(cell, listOf(Citizen(cell + 1L, "Resident $cell", 30, position = cell * TRAVEL_MODE_COUNT))) }
        val homes =
            households.map { home ->
                MobilityHome(home.cell, 1, emptyList(), days = home.people.map { CitizenDay(it.id, it.position, emptyList()) })
            }
        return City(
            width = size,
            height = size,
            tiles = List(size * size) { Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1) },
            households = households,
            nextCitizenId = households.size + 1L,
            mobility = Mobility(0, homes, 0, 0),
        )
    }

    @Test fun `validating many mobility homes scans households only linearly`() {
        val city = populated(64)
        var reads = 0
        val counted =
            object : AbstractList<Household>() {
                override val size: Int get() = city.households.size

                override fun get(index: Int): Household {
                    reads++
                    return city.households[index]
                }
            }
        city.copy(households = counted).validateCitizens()
        // Count accesses rather than wall-clock time so slow CI machines cannot make this flaky.
        assertTrue(reads <= 8 * counted.size, "Expected linear household access, got $reads reads for ${counted.size} homes")
    }

    @Test fun `indexed validation still rejects duplicate missing and inconsistent households`() {
        val city = populated(16)
        city.validateCitizens()
        assertFailsWith<IllegalArgumentException> { city.copy(households = city.households + city.households.first()).validateCitizens() }
        val missing = assertFailsWith<IllegalArgumentException> { city.copy(households = city.households.dropLast(1)).validateCitizens() }
        assertEquals("Domicile absent", missing.message)
        val mobility = city.mobility!!
        val first = mobility.homes.first()
        for (invalid in listOf(first.copy(residents = 2), first.copy(days = emptyList()), first.copy(paths = listOf(TravelPath(listOf(0), listOf(0)))))) {
            assertFailsWith<IllegalArgumentException> { city.copy(mobility = mobility.copy(homes = listOf(invalid) + mobility.homes.drop(1))).validateCitizens() }
        }
    }
}
