package city.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class PortraitTest {
    @Test fun `appearance is persisted and does not depend on age name home or routes`() {
        val person = Citizen(914, "A", 16, position = 0, life = CitizenLife(-5000, Sex.MALE, 50.0, 1.0, LifeStage.SCHOOL))
        val aged = person.copy(age = 85, name = "B", position = 45)
        assertEquals(person.portraitCode, aged.portraitCode)
        assertEquals(person, wireJson.decodeFromString<Citizen>(wireJson.encodeToString(person)))
        assertEquals(Sex.MALE, PortraitCodes.sex(person.portraitCode))
        assertTrue(PortraitCodes.valid(person.portraitCode))
        for (part in PortraitPart.entries) assertEquals(PortraitCodes.variant(person.portraitCode, part), PortraitCodes.variant(aged.portraitCode, part))
    }

    @Test fun `legacy defaults migrate deterministically without an age dependent seed`() {
        fun decode(age: Int) = wireJson.decodeFromString<Citizen>("""{"id":81,"name":"Old","age":$age,"position":0}""")
        assertEquals(decode(8).portraitCode, decode(85).portraitCode)
        val migrated = decode(30)
        assertEquals(migrated, wireJson.decodeFromString<Citizen>(wireJson.encodeToString(migrated)))
        val city = City(width = 16, height = 16, tiles = List(256) { Tile() }, seed = 72)
        val initialized = migrated.initialized(city)
        assertEquals(initialized.life!!.sex, PortraitCodes.sex(initialized.portraitCode))
        assertEquals(initialized, initialized.initialized(city.copy(day = 1000)))
        for (part in PortraitPart.entries) assertEquals(PortraitCodes.variant(migrated.portraitCode, part), PortraitCodes.variant(initialized.portraitCode, part))
    }

    @Test fun `all bank families and variants are reachable and invalid codes are rejected`() {
        val codes = (1L..1000).map { PortraitCodes.create(it, Sex.FEMALE) }
        assertTrue(codes.all(PortraitCodes::valid))
        assertEquals((0 until 3).toSet(), codes.map(PortraitCodes::family).toSet())
        for (part in PortraitPart.entries) {
            assertEquals((0 until 5).toSet(), codes.map { PortraitCodes.variant(it, part) }.toSet())
        }
        assertFalse(PortraitCodes.valid(0))
        assertFalse(PortraitCodes.valid(-1))
        assertFalse(PortraitCodes.valid(codes.first() or 3L))
        assertFalse(PortraitCodes.valid(codes.first() or (7L shl 3)))
        assertFalse(PortraitCodes.valid(codes.first() or (1L shl 28)))
    }

    @Test fun `age bands change only on the documented birthday boundaries`() {
        val samples =
            mapOf(
                0 to PortraitAge.CHILD,
                12 to PortraitAge.CHILD,
                13 to PortraitAge.TEEN,
                19 to PortraitAge.TEEN,
                20 to PortraitAge.ADULT,
                44 to PortraitAge.ADULT,
                45 to PortraitAge.MATURE,
                64 to PortraitAge.MATURE,
                65 to PortraitAge.SENIOR,
                79 to PortraitAge.SENIOR,
                80 to PortraitAge.ELDER,
                120 to PortraitAge.ELDER,
            )
        for ((age, expected) in samples) assertEquals(expected, PortraitAge.forYears(age))
    }

    @Test fun `arrivals and newborns have valid permanent codes matching their sex`() {
        val tiles = MutableList(256) { Tile() }
        tiles[17] = Tile(zone = Zone.RESIDENTIAL_DENSE, occupants = 3)
        val city = reconcileCitizens(City(width = 16, height = 16, tiles = tiles), Catalog())
        val arrivals = city.households.single().people
        assertEquals(3, arrivals.size)
        for (person in arrivals) {
            assertTrue(PortraitCodes.valid(person.portraitCode))
            assertEquals(person.life!!.sex, PortraitCodes.sex(person.portraitCode))
        }
        val mother = arrivals.first().let { it.copy(life = it.life!!.copy(sex = Sex.FEMALE, pregnancyDue = 0, pregnancyFather = arrivals[1].id)) }
        val born = deliverBabies(city.copy(households = listOf(Household(17, listOf(mother) + arrivals.drop(1)))))
        val baby =
            born.households
                .single()
                .people
                .last()
        assertEquals(0, baby.age)
        assertTrue(PortraitCodes.valid(baby.portraitCode))
        assertEquals(baby.life!!.sex, PortraitCodes.sex(baby.portraitCode))
        assertEquals(baby.portraitCode, baby.copy(age = 85).portraitCode)
    }
}
