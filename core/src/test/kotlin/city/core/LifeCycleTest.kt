package city.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import kotlin.test.*

class LifeCycleTest {
    private val catalog = Catalog()

    private fun person(
        id: Long = 1,
        age: Int = 20,
        stage: LifeStage = LifeStage.WORKER,
        knowledge: Double = 80.0,
        ca: Double = 1.0,
    ): Citizen = Citizen(id, "Person $id", age, position = 17 * TRAVEL_MODE_COUNT, life = CitizenLife(dateDay(epoch.minusYears(age.toLong())), Sex.FEMALE, knowledge, ca, stage, lastSalary = if (stage == LifeStage.WORKER) 50.0 else 0.0))

    private fun city(
        vararg people: Citizen,
        day: Int = 0,
    ): City {
        val tiles = MutableList(256) { Tile(powered = true, watered = true, landValue = 50) }
        tiles[17] = tiles[17].copy(zone = Zone.RESIDENTIAL_DENSE, occupants = people.size)
        tiles[18] = tiles[18].copy(zone = Zone.COMMERCIAL_LIGHT, occupants = 10)
        tiles[33] = tiles[33].copy(building = "school", anchor = 33)
        tiles[35] = tiles[35].copy(building = "college", anchor = 35)
        tiles[36] = tiles[36].copy(building = "museum", anchor = 36)
        return City(width = 16, height = 16, tiles = tiles, day = day, households = listOf(Household(17, people.toList())), nextCitizenId = (people.maxOfOrNull { it.id } ?: 0) + 1)
    }

    private fun student(ca: Double = 1.0): Citizen = person(stage = LifeStage.STUDENT, ca = ca).let { it.copy(life = it.life!!.copy(study = Study(Course.TEACHING, 35, started = 0))) }

    private fun lesson(
        p: Citizen,
        attends: Boolean = true,
        scheduled: Boolean = true,
        destination: Int = 35,
        arrival: Int = 500,
    ): CitizenDay =
        CitizenDay(
            p.id,
            68,
            listOf(PlannedActivity(Need.EDUCATION, 480, if (p.isStudent) 360 else 60, scheduled = scheduled)),
            if (attends) listOf(ExecutedLeg(Need.EDUCATION, false, 0, 480, arrival, TravelPath(listOf(17 * TRAVEL_MODE_COUNT, destination * TRAVEL_MODE_COUNT), listOf(0, arrival - 480)))) else emptyList(),
        )

    @Test fun `old saves migrate once without replacing identities and knowledge persists on wire`() {
        val old = wireJson.decodeFromString<Citizen>("""{"id":1,"name":"A","age":30,"position":68}""")
        assertNull(old.life)
        val migrated = reconcileCitizens(city(old), catalog)
        val p =
            migrated.households
                .single()
                .people
                .single()
        assertEquals(old.id, p.id)
        assertEquals(old.age, p.age)
        assertNotNull(p.life)
        assertEquals(p, p.initialized(migrated.copy(day = 1000)))
        assertEquals(migrated, wireJson.decodeFromString<City>(wireJson.encodeToString(migrated)))
        migrated.validateCitizens()
    }

    @Test fun `mortality rises after sixty and oldest citizen disappears without respawning`() =
        runBlocking {
            assertTrue(mortalityProbability(20) < mortalityProbability(60))
            assertTrue(mortalityProbability(60) < mortalityProbability(90))
            assertEquals(1.0, mortalityProbability(121))
            val original = city(person(age = 120, stage = LifeStage.RETIRED), day = 365)
            val result = beginLifeDay(original, catalog)
            assertTrue(result.households.isEmpty())
            assertEquals(0, result.tiles[17].occupants)
            assertEquals(2, result.nextCitizenId)
            assertTrue(MobilitySimulation(catalog, 1).simulate(result).households.isEmpty())
        }

    @Test fun `birthdays open school and orientation and retirement freezes last salary`() {
        val small = person(age = 2, stage = LifeStage.CHILD)
        assertEquals(
            LifeStage.SCHOOL,
            beginLifeDay(city(small, day = 365), catalog)
                .households
                .single()
                .people
                .single()
                .life!!
                .stage,
        )
        val teen = person(age = 17, stage = LifeStage.SCHOOL)
        assertEquals(
            LifeStage.APPLICANT,
            beginLifeDay(city(teen, day = 365), catalog)
                .households
                .single()
                .people
                .single()
                .life!!
                .stage,
        )
        val worker = person(age = 64).let { it.copy(life = it.life!!.copy(lastSalary = 90.0)) }
        val retired =
            beginLifeDay(city(worker, day = 365), catalog)
                .households
                .single()
                .people
                .single()
        assertEquals(65, retired.age)
        assertEquals(LifeStage.RETIRED, retired.life!!.stage)
        assertEquals(60.0, retired.dailyIncome)
        assertFalse(applicable(retired, Need.WORK, 0))
    }

    @Test fun `admission requires choice qualification and campus and is not retried daily`() {
        val p = person(age = 18, stage = LifeStage.APPLICANT)
        val willing = city(p).let { c -> c.copy(seed = (0L..10000).first { draw(c.copy(seed = it), p.id, 701) < LifeRules.UNIVERSITY_CHANCE }) }
        val admitted = selectStudies(p, willing, 35)
        assertEquals(LifeStage.STUDENT, admitted.life!!.stage)
        assertTrue(
            admitted.life!!
                .study!!
                .course.minimumKnowledge <= p.life!!.knowledge,
        )
        assertEquals(LifeStage.WORKER, selectStudies(p, willing, null).life!!.stage)
        assertEquals(LifeStage.WORKER, selectStudies(p.copy(life = p.life!!.copy(knowledge = 10.0)), willing, 35).life!!.stage)
        val unwilling = willing.copy(seed = (0L..10000).first { draw(willing.copy(seed = it), p.id, 701) >= LifeRules.UNIVERSITY_CHANCE })
        val worker = selectStudies(p, unwilling, 35)
        assertEquals(LifeStage.WORKER, worker.life!!.stage)
        assertEquals(worker, selectStudies(worker, willing, 35))
    }

    @Test fun `school university and cultural routes are distinct and babies do not commute`() =
        runBlocking {
            val child = person(age = 10, stage = LifeStage.SCHOOL)
            val baby = person(id = 2, age = 1, stage = LifeStage.CHILD)
            val student = student().copy(id = 3)
            val result = MobilitySimulation(catalog, 2).simulate(city(child, baby, student))
            val home = result.mobility!!.homes.single()
            val childDay = home.days[0]
            val collegeDay = home.days[2]
            assertEquals(33, home.paths[childDay.activities.single { it.need == Need.EDUCATION }.route!!].destination)
            assertEquals(35, home.paths[collegeDay.activities.single { it.need == Need.EDUCATION }.route!!].destination)
            assertTrue(
                home.days[1]
                    .activities
                    .filter { it.scheduled }
                    .all { it.need == Need.REST },
            )
            assertFalse(collegeDay.activities.single { it.need == Need.WORK }.scheduled)
            result.validateCitizens()
            result.mobility!!.validate(result, catalog)
        }

    @Test fun `nearby libraries do not hide university and unreachable university gives work`() =
        runBlocking {
            val p = person(stage = LifeStage.APPLICANT)
            val source = city(p).let { c -> c.copy(seed = (0L..10000).first { draw(c.copy(seed = it), p.id, 701) < LifeRules.UNIVERSITY_CHANCE }) }
            val withLibraries = source.copy(tiles = source.tiles.mapIndexed { i, t -> if (i in listOf(1, 16)) t.copy(building = "library", anchor = i) else t })
            val enrolled =
                MobilitySimulation(catalog, 1)
                    .simulate(withLibraries)
                    .households
                    .single()
                    .people
                    .single()
            assertEquals(LifeStage.STUDENT, enrolled.life!!.stage)
            val isolated = withLibraries.copy(tiles = withLibraries.tiles.mapIndexed { i, t -> if (i in listOf(19, 34, 36, 51)) Tile(water = true) else t })
            assertEquals(
                LifeStage.WORKER,
                MobilitySimulation(catalog, 1)
                    .simulate(isolated)
                    .households
                    .single()
                    .people
                    .single()
                    .life!!
                    .stage,
            )
        }

    @Test fun `presence earns points and knowledge while late arrival and wrong campus do not`() {
        val p = student()
        val c = city(p)
        val present = advanceEducation(p, lesson(p), c)
        assertTrue(present.life!!.knowledge > p.life!!.knowledge)
        assertTrue(present.life!!.study!!.points > 0)
        assertEquals(1, present.life!!.study!!.attendedDays)
        for (day in listOf(lesson(p, false), lesson(p, arrival = 1200), lesson(p, destination = 33))) {
            val absent = advanceEducation(p, day, c)
            assertEquals(0.0, absent.life!!.study!!.points)
            assertEquals(0, absent.life!!.study!!.attendedDays)
            assertEquals(p.life!!.knowledge, absent.life!!.knowledge)
        }
    }

    @Test fun `regularity helps and prolonged absences recover without weekend penalty`() {
        var p = student()
        val c = city(p)
        repeat(10) { p = advanceEducation(p, lesson(p), c) }
        val regular = p.life!!.study!!
        p = advanceEducation(p, lesson(p, scheduled = false), c)
        assertEquals(regular, p.life!!.study)
        repeat(8) { p = advanceEducation(p, lesson(p, false), c) }
        assertTrue(p.life!!.study!!.backlog > 0)
        assertEquals(0, p.life!!.study!!.presenceStreak)
        repeat(20) { p = advanceEducation(p, lesson(p), c) }
        assertEquals(0, p.life!!.study!!.backlog)
        assertTrue(p.life!!.study!!.presenceStreak > 0)
    }

    @Test fun `low ability diligent student passes and brilliant absentee fails`() {
        fun year(
            original: Citizen,
            attendance: (Int) -> Boolean,
        ): Citizen {
            var p = original
            repeat(365) { day -> p = advanceEducation(p, lesson(p, attendance(day), day % 7 < 5), city(p, day = day)) }
            return p
        }
        val diligent = year(student(.5)) { true }
        assertEquals(2, diligent.life!!.study!!.year)
        assertEquals(0.0, diligent.life!!.study!!.points)
        assertTrue(diligent.life!!.knowledge > 80)
        val absent = year(student(1.5)) { it % 7 < 3 }
        assertEquals(1, absent.life!!.study!!.year)
        assertTrue(absent.life!!.study!!.repeated)
    }

    @Test fun `one repeat is allowed then work and final success retains diploma independently of NI`() {
        val p = student()
        val failed = advanceEducation(p, lesson(p, false), city(p, day = 364))
        assertTrue(failed.life!!.study!!.repeated)
        val excluded = advanceEducation(failed, lesson(failed, false), city(failed, day = 729))
        assertEquals(LifeStage.WORKER, excluded.life!!.stage)
        assertNull(excluded.life!!.diploma)
        assertEquals(50.0, excluded.dailyIncome)
        val lastYear = p.copy(life = p.life!!.copy(study = p.life!!.study!!.copy(year = 3, points = 300.0, scheduledDays = 250, attendedDays = 250)))
        val graduated = advanceEducation(lastYear, lesson(lastYear), city(lastYear, day = 364))
        assertEquals(Course.TEACHING, graduated.life!!.diploma)
        assertEquals(LifeStage.WORKER, graduated.life!!.stage)
        assertEquals(65.0, graduated.dailyIncome)
        val older = advanceEducation(graduated, null, city(graduated, day = 365))
        assertEquals(Course.TEACHING, older.life!!.diploma)
        assertTrue(older.life!!.knowledge < graduated.life!!.knowledge)
    }

    @Test fun `cultural visits including museum leisure maintain knowledge but cannot grant diploma`() {
        val p = person()
        val c = city(p)
        val idle = advanceEducation(p, null, c)
        val visit = advanceEducation(p, lesson(p, destination = 36), c)
        assertTrue(idle.life!!.knowledge < p.life!!.knowledge)
        assertTrue(visit.life!!.knowledge > p.life!!.knowledge)
        assertNull(visit.life!!.diploma)
        val museum = lesson(p, destination = 36).let { it.copy(activities = listOf(PlannedActivity(Need.LEISURE, 480, 45)), legs = it.legs.map { leg -> leg.copy(need = Need.LEISURE) }) }
        assertTrue(advanceEducation(p, museum, c).life!!.knowledge > p.life!!.knowledge)
        val quick = p.copy(life = p.life!!.copy(learningAbility = 1.5))
        assertTrue(advanceEducation(quick, null, c).life!!.knowledge > idle.life!!.knowledge)
    }

    @Test fun `pregnancy delivers once and preserves ancestry even after father dies`() {
        val mother = person().let { it.copy(life = it.life!!.copy(pregnancyDue = 1, pregnancyFather = 2, pregnancyAncestors = listOf(3, 4))) }
        val c = city(mother, day = 1).copy(nextCitizenId = 5)
        val result = beginLifeDay(c, catalog)
        val people = result.households.single().people
        assertEquals(2, people.size)
        val newborn = people.last()
        assertEquals(5, newborn.id)
        assertEquals(0, newborn.age)
        assertEquals(1, newborn.life!!.birthDay)
        assertEquals(listOf(1L, 2L), newborn.life!!.parents)
        assertEquals(setOf(1L, 2L, 3L, 4L), newborn.life!!.ancestors.toSet())
        assertNull(people.first().life!!.pregnancyDue)
        assertEquals(2, result.tiles[17].occupants)
        assertEquals(
            2,
            beginLifeDay(result.copy(day = 2), catalog)
                .households
                .single()
                .people.size,
        )
        result.validateCitizens()
    }

    @Test fun `relationships reject close relatives and broken partnerships are cleared`() {
        val a = person().let { it.copy(life = it.life!!.copy(parents = listOf(10), ancestors = listOf(10, 20), partner = 2)) }
        val b = person(id = 2).let { it.copy(life = it.life!!.copy(parents = listOf(11), ancestors = listOf(11, 20))) }
        assertTrue(related(a, b))
        assertTrue(related(a, person(id = 10)))
        assertFalse(related(a, person(id = 3)))
        assertNull(
            repairPartners(city(a))
                .households
                .single()
                .people
                .single()
                .life!!
                .partner,
        )
    }

    @Test fun `new life fields survive network deltas and cohort statistics are derived`() {
        val before = city(student()).withSharedTerrain()
        val after = before.copy(revision = 1, households = listOf(Household(17, listOf(advanceEducation(student(), lesson(student()), before)))))
        val packet = wireJson.encodeToString(Response("STATE", city = after).compactState(before))
        assertEquals(after, wireJson.decodeFromString<Response>(packet).restoreState(before).city)
        assertEquals(1, after.educationByAge().getValue("18–24").first)
        assertTrue(after.educationByAge().getValue("18–24").second > 80)
    }

    @Test fun `invalid education values cannot enter a save`() {
        for (life in listOf(person().life!!.copy(knowledge = Double.NaN), person().life!!.copy(learningAbility = 0.0), person().life!!.copy(partner = 1), person().life!!.copy(stage = LifeStage.STUDENT))) {
            assertFailsWith<IllegalArgumentException> { city(person().copy(life = life)).validateCitizens() }
        }
    }

    @Test fun `monthly love is reciprocal and does not match a close relative`() {
        val a = person()
        val b = person(id = 2).let { it.copy(life = it.life!!.copy(sex = Sex.MALE)) }
        val initial = city(a, b)
        val seed = (0L..100000).first { draw(initial.copy(seed = it), 1, 705) < LifeRules.RELATIONSHIP_CHANCE }
        val paired = monthlyFamilies(initial.copy(seed = seed), catalog).households.flatMap { it.people }
        assertEquals(2L, paired.first().life!!.partner)
        assertEquals(1L, paired.last().life!!.partner)
        val siblings = initial.copy(seed = seed, nextCitizenId = 11, households = listOf(Household(17, listOf(a, b).map { it.copy(life = it.life!!.copy(parents = listOf(10))) })))
        assertTrue(monthlyFamilies(siblings, catalog).households.flatMap { it.people }.all { it.life!!.partner == null })
    }

    @Test fun `partners leave parents together with their child without moving the grandparents`() {
        val a = person().let { it.copy(life = it.life!!.copy(partner = 2, parents = listOf(3))) }
        val b = person(id = 2).let { it.copy(life = it.life!!.copy(partner = 1, parents = listOf(4), sex = Sex.MALE)) }
        val child = person(id = 5, age = 1, stage = LifeStage.CHILD).let { it.copy(life = it.life!!.copy(parents = listOf(1, 2))) }
        val initial =
            city(a, b, person(id = 3, age = 50), person(id = 4, age = 50), child).let { c ->
                c.copy(tiles = c.tiles.mapIndexed { i, t -> if (i == 20) t.copy(zone = Zone.RESIDENTIAL_LIGHT, housingCapacity = 12) else t })
            }
        val seed = (0L..100000).first { draw(initial.copy(seed = it), 1, 707) < LifeRules.COUPLE_MOVE_CHANCE }
        val moved = monthlyFamilies(initial.copy(seed = seed), catalog)
        assertEquals(
            setOf(1L, 2L, 5L),
            moved.households
                .single { it.cell == 20 }
                .people
                .map { it.id }
                .toSet(),
        )
        assertEquals(
            setOf(3L, 4L),
            moved.households
                .single { it.cell == 17 }
                .people
                .map { it.id }
                .toSet(),
        )
        assertTrue(
            moved.households
                .single { it.cell == 20 }
                .people
                .all { it.position == 20 * TRAVEL_MODE_COUNT && it.routes.isEmpty() },
        )
        assertEquals(3, moved.tiles[20].occupants)
        assertEquals(2, moved.tiles[17].occupants)
        assertEquals(5, moved.stats.population)
        moved.validateCitizens()
    }

    @Test fun `partner joins independent partner and capacity blocks impossible moves`() {
        val a = person().let { it.copy(life = it.life!!.copy(partner = 2, parents = listOf(3))) }
        val b = person(id = 2).let { it.copy(position = 20 * TRAVEL_MODE_COUNT, life = it.life!!.copy(partner = 1, sex = Sex.MALE)) }
        val initial =
            city(a, person(id = 3, age = 50)).let { c ->
                c.copy(
                    tiles = c.tiles.mapIndexed { i, t -> if (i == 20) t.copy(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1) else t },
                    households = c.households + Household(20, listOf(b)),
                )
            }
        val seed = (0L..100000).first { draw(initial.copy(seed = it), 1, 707) < LifeRules.COUPLE_MOVE_CHANCE }
        val moved = monthlyFamilies(initial.copy(seed = seed), catalog)
        assertEquals(
            setOf(1L, 2L),
            moved.households
                .single { it.cell == 20 }
                .people
                .map { it.id }
                .toSet(),
        )
        val noHousing = city(a, person(id = 3, age = 50)).copy(nextCitizenId = 4)
        assertEquals(
            listOf(1L, 3L),
            monthlyFamilies(noHousing.copy(seed = seed), catalog)
                .households
                .single()
                .people
                .map { it.id },
        )
    }

    @Test fun `conception requires opposite sexes cohabitation age limit and no current pregnancy`() {
        val woman = person().let { it.copy(life = it.life!!.copy(partner = 2)) }
        val man = person(id = 2).let { it.copy(life = it.life!!.copy(partner = 1, sex = Sex.MALE)) }
        val initial = city(woman, man)
        val seed = (0L..100000).first { draw(initial.copy(seed = it), 1, 708) < .025 }

        fun result(p: Citizen): Citizen =
            monthlyFamilies(city(p, man).copy(seed = seed), catalog)
                .households
                .single()
                .people
                .first()
        val pregnant = result(woman)
        assertEquals(dateDay(epoch.plusMonths(9)), pregnant.life!!.pregnancyDue)
        assertEquals(pregnant.life!!.pregnancyDue, result(pregnant).life!!.pregnancyDue)
        assertNull(result(woman.copy(age = 45)).life!!.pregnancyDue)
        assertNull(result(woman.copy(life = woman.life!!.copy(sex = Sex.MALE))).life!!.pregnancyDue)
        val apart =
            initial.copy(
                seed = seed,
                tiles =
                    initial.tiles.mapIndexed { i, t ->
                        when (i) {
                            17 -> t.copy(occupants = 1)
                            20 -> t.copy(zone = Zone.RESIDENTIAL_LIGHT, occupants = 1)
                            else -> t
                        }
                    },
                households = listOf(Household(17, listOf(woman)), Household(20, listOf(man.copy(position = 20 * TRAVEL_MODE_COUNT)))),
            )
        // Keep both away from home so the monthly move cannot co-locate them before conception.
        val separated = apart.copy(households = apart.households.map { h -> h.copy(people = h.people.map { it.copy(position = 0) }) })
        assertNull(
            monthlyFamilies(separated, catalog)
                .households
                .flatMap { it.people }
                .first { it.id == 1L }
                .life!!
                .pregnancyDue,
        )
    }

    @Test fun `leap birthday transitions on February twenty eighth in non leap years`() {
        val p = person(age = 17, stage = LifeStage.SCHOOL).let { it.copy(life = it.life!!.copy(birthDay = dateDay(LocalDate.of(1884, 2, 29)))) }
        val c = city(p, day = dateDay(LocalDate.of(1902, 2, 28)))
        val older =
            beginLifeDay(c, catalog)
                .households
                .single()
                .people
                .single()
        assertEquals(18, older.age)
        assertEquals(LifeStage.APPLICANT, older.life!!.stage)
    }

    @Test fun `daily simulation keeps newborns students and retirees deterministic across worker counts`() =
        runBlocking {
            val mother = person(id = 2).let { it.copy(life = it.life!!.copy(pregnancyDue = 2, pregnancyFather = 3)) }
            var serial = city(student(), mother, person(id = 3), person(id = 4, age = 64), day = 1)
            var parallel = serial
            repeat(45) {
                serial = Simulation(catalog, workers = 1).step(serial)
                parallel = Simulation(catalog, workers = 4).step(parallel)
                assertEquals(serial, parallel)
                serial.validateCitizens()
                serial.mobility!!.validate(serial, catalog)
                assertEquals(serial.tiles.filter { it.zone.residential }.sumOf { it.occupants }, serial.stats.population)
            }
            assertTrue(serial.households.flatMap { it.people }.any { it.life!!.parents == listOf(2L, 3L) })
        }
}
