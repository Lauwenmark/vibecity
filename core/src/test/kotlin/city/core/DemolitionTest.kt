package city.core

import kotlin.test.*

class DemolitionTest {
    private fun resident(
        id: Long,
        cell: Int,
        student: Boolean = false,
        partner: Long? = null,
    ): Citizen =
        Citizen(
            id,
            "Resident $id",
            if (student) 15 else 30,
            position = cell * TRAVEL_MODE_COUNT,
            life =
                CitizenLife(
                    -10000,
                    Sex.FEMALE,
                    80.0,
                    1.0,
                    if (student) LifeStage.SCHOOL else LifeStage.WORKER,
                    partner = partner.takeUnless { student },
                    studentRelationship = partner?.takeIf { student }?.let { StudentRelationship(it, 0) },
                ),
        )

    @Test fun `demolition evicts whole household and clears active relationships without erasing genealogy`() {
        val tiles = MutableList(256) { Tile() }
        tiles[17] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 2)
        tiles[20] = Tile(zone = Zone.RESIDENTIAL_LIGHT, occupants = 2)
        val survivor = resident(3, 20, partner = 1).let { it.copy(life = it.life!!.copy(parents = listOf(1), ancestors = listOf(1))) }
        val initial =
            City(
                width = 16,
                height = 16,
                tiles = tiles,
                rules = Rules(speed = 0),
                stats = Statistics(population = 4),
                households =
                    listOf(
                        Household(17, listOf(resident(1, 17, partner = 3), resident(2, 17, student = true, partner = 4))),
                        Household(20, listOf(survivor, resident(4, 20, student = true, partner = 2))),
                    ),
                nextCitizenId = 5,
            )
        initial.validateCitizens()
        val command = Request("BUILD", tool = "bulldoze", x = 1, y = 1)
        assertFailsWith<IllegalArgumentException> { Commands(Catalog()).apply(initial.copy(treasury = 0), command) }
        assertEquals(4, initial.households.sumOf { it.people.size })
        val result = Commands(Catalog()).apply(initial, command)
        assertEquals(listOf(20), result.households.map { it.cell })
        assertEquals(
            listOf(3L, 4L),
            result.households
                .single()
                .people
                .map { it.id },
        )
        assertEquals(2, result.stats.population)
        assertEquals(0, result.tiles[17].occupants)
        assertEquals(5, result.nextCitizenId)
        assertEquals(initial.treasury - 2, result.treasury)
        assertNull(
            result.households
                .single()
                .people[0]
                .life!!
                .partner,
        )
        assertNull(
            result.households
                .single()
                .people[1]
                .life!!
                .studentRelationship,
        )
        assertEquals(
            listOf(1L),
            result.households
                .single()
                .people[0]
                .life!!
                .parents,
        )
        assertEquals(
            listOf(1L),
            result.households
                .single()
                .people[0]
                .life!!
                .ancestors,
        )
        result.validateCitizens()
    }
}
