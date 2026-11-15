package city.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.*

class StudentRomanceTest {
    private val catalog = Catalog()

    private fun person(
        id: Long,
        age: Int = 15,
        cell: Int = if (id == 1L) 17 else 20,
    ): Citizen =
        Citizen(
            id,
            "Élève $id",
            age,
            position = cell * TRAVEL_MODE_COUNT,
            life = CitizenLife(dateDay(epoch.minusYears(age.toLong())), Sex.FEMALE, 80.0, 1.0, LifeStage.SCHOOL),
        )

    private fun city(
        a: Citizen = person(1),
        b: Citizen = person(2),
        day: Int = 0,
        paired: Boolean = false,
    ): City {
        val tiles = MutableList(256) { Tile(powered = true, watered = true) }
        val people = listOf(a, b).map { p -> if (paired) p.copy(life = p.life!!.copy(studentRelationship = StudentRelationship(if (p.id == a.id) b.id else a.id, 0))) else p }
        val homes =
            people.groupBy { it.position / TRAVEL_MODE_COUNT }.map { (cell, residents) ->
                tiles[cell] = tiles[cell].copy(zone = Zone.RESIDENTIAL_DENSE, occupants = residents.size)
                Household(cell, residents)
            }
        tiles[33] = tiles[33].copy(building = "school", anchor = 33)
        tiles[35] = tiles[35].copy(building = "college", anchor = 35)
        tiles[36] = tiles[36].copy(building = "park", anchor = 36)
        tiles[19] = tiles[19].copy(zone = Zone.COMMERCIAL_LIGHT, occupants = 8)
        return City(width = 16, height = 16, tiles = tiles, households = homes, nextCitizenId = maxOf(a.id, b.id) + 1, day = day)
    }

    private fun people(c: City) = c.households.flatMap { it.people }.sortedBy { it.id }

    private fun paired(c: City) = people(c).all { it.life!!.studentRelationship != null }

    private val schools = mapOf(1L to 33, 2L to 33)

    private fun approachSeed(
        c: City,
        accepted: Boolean,
    ): Long =
        (0L..100000).first { seed ->
            val trial = c.copy(seed = seed)
            draw(trial, 1, 721) < StudentRomanceRules.APPROACH_CHANCE &&
                draw(trial, 2, 721) >= StudentRomanceRules.APPROACH_CHANCE &&
                (draw(trial.copy(seed = seed xor 1L), 2, 723) < StudentRomanceRules.ACCEPT_CHANCE) == accepted
        }

    @Test fun `weekly proposals require consent school age and known genealogy`() {
        val base = city(person(1, 13), person(2, 14))
        val willing = base.copy(seed = approachSeed(base, true))
        val formed = weeklyStudentRelationships(willing, schools)
        assertTrue(paired(formed))
        assertEquals(2L, people(formed)[0].life!!.studentRelationship!!.partner)
        assertNull(people(formed)[0].life!!.partner)
        assertFalse(paired(weeklyStudentRelationships(base.copy(seed = approachSeed(base, false)), schools)))
        assertFalse(paired(weeklyStudentRelationships(willing.copy(day = 1), schools)))
        assertFalse(paired(weeklyStudentRelationships(willing, mapOf(1L to 33, 2L to 35))))
        assertFalse(paired(weeklyStudentRelationships(willing, mapOf(1L to 33))))
        for ((a, b) in listOf(person(1, 12) to person(2, 13), person(1, 13) to person(2, 15))) {
            assertFalse(paired(weeklyStudentRelationships(city(a, b).copy(seed = willing.seed), schools)))
        }
        val siblings = willing.copy(households = willing.households.map { h -> h.copy(people = h.people.map { p -> p.copy(life = p.life!!.copy(parents = listOf(10))) }) })
        assertFalse(paired(weeklyStudentRelationships(siblings, schools)))
        formed.validateCitizens()
    }

    @Test fun `college students of any age can date but workers and adult partners cannot`() {
        fun student(id: Long) = person(id, 30).let { it.copy(life = it.life!!.copy(stage = LifeStage.STUDENT, study = Study(Course.MEDICINE, 35, started = 0))) }
        val base = city(student(1), student(2))
        val seeded = base.copy(seed = approachSeed(base, true))
        assertTrue(paired(weeklyStudentRelationships(seeded, schools)))
        for (life in listOf(student(2).life!!.copy(stage = LifeStage.WORKER, study = null), student(2).life!!.copy(partner = 3))) {
            val changed = city(student(1), student(2).copy(life = life)).copy(seed = seeded.seed)
            assertFalse(paired(weeklyStudentRelationships(changed, schools)))
        }
    }

    @Test fun `weekly breakup is strength dependent and does not reform immediately`() {
        val base = city(paired = true)
        val seed = (0L..100000).first { draw(base.copy(seed = it), 1, 720) in 0.011..0.099 }

        fun strength(value: Double) = base.copy(seed = seed, households = base.households.map { h -> h.copy(people = h.people.map { p -> p.copy(life = p.life!!.copy(studentRelationship = p.life.studentRelationship!!.copy(strength = value))) }) })
        assertTrue(paired(weeklyStudentRelationships(strength(100.0), schools)))
        assertFalse(paired(weeklyStudentRelationships(strength(0.0), schools)))
        assertTrue(paired(weeklyStudentRelationships(strength(0.0).copy(day = 1), schools)))
    }

    @Test fun `study exit disappearance and broken reciprocity clear both partners`() {
        val base = city(paired = true)
        for (changed in listOf(
            base.copy(households = base.households.dropLast(1)),
            base.copy(households = base.households.map { h -> h.copy(people = h.people.map { p -> if (p.id == 2L) p.copy(life = p.life!!.copy(stage = LifeStage.WORKER)) else p }) }),
            base.copy(households = base.households.map { h -> h.copy(people = h.people.map { p -> if (p.id == 2L) p.copy(life = p.life!!.copy(studentRelationship = null)) else p }) }),
        )) {
            assertTrue(people(repairStudentRelationships(changed)).all { it.life!!.studentRelationship == null })
        }
        // Moving to a different college retains an existing relationship.
        val transferred = base.copy(households = base.households.map { h -> h.copy(people = h.people.map { p -> p.copy(age = 20, life = p.life!!.copy(stage = LifeStage.STUDENT, study = Study(Course.IT, if (p.id == 1L) 35 else 50, started = 0))) }) })
        assertTrue(paired(repairStudentRelationships(transferred)))
    }

    @Test fun `final exam ends the relationship the same day for both students`() {
        val base = city(paired = true, day = 364)
        val graduating =
            base.copy(
                households =
                    base.households.map { h ->
                        h.copy(
                            people =
                                h.people.map { p ->
                                    if (p.id != 1L) p else p.copy(age = 24, life = p.life!!.copy(stage = LifeStage.STUDENT, study = Study(Course.IT, 35, year = 3, started = 0, points = 220.0, scheduledDays = 200, attendedDays = 200)))
                                },
                        )
                    },
            )
        val result = finishEducationDay(graduating)
        assertEquals(LifeStage.WORKER, people(result)[0].life!!.stage)
        assertTrue(people(result).all { it.life!!.studentRelationship == null })
    }

    @Test fun `scheduled dates add distinct real trips with shared presence and preserve ordinary leisure`() =
        runBlocking {
            val base = city(day = 5, paired = true)
            for (kind in StudentDateKind.entries) {
                val seed =
                    (0L..10000).first { seed ->
                        val c = base.copy(seed = seed)
                        5 in studentDateDays(c, 1) && (noise(seed xor 1, 726, 5) % 2 == 0) == (kind == StudentDateKind.VISIT)
                    }
                val input = base.copy(seed = seed)
                val result = MobilitySimulation(catalog, 1).simulate(input)
                assertEquals(result, MobilitySimulation(catalog, 4).simulate(input))
                val days = result.mobility!!.homes.flatMap { it.days }
                val dates = days.map { day -> assertNotNull(day.activities.singleOrNull { it.appointment != null }?.appointment) }
                assertTrue(dates.all { it.kind == kind })
                assertEquals(dates[0].meeting, dates[1].meeting)
                assertEquals(dates[0].destination, dates[1].destination)
                assertTrue(days.all { d -> d.activities.count { it.need == Need.LEISURE } == 2 })
                assertTrue(days.all { it.dateOutcome?.status == StudentDateStatus.SUCCESS })
                assertTrue(days.all { it.dateOutcome!!.happiness > 0 && it.dateOutcome.strengthChange > 0 })
                assertTrue(days.all { it.dateOutcome!!.togetherMinutes >= StudentRomanceRules.MINIMUM_TOGETHER })
                val stats = result.mobility!!.studentDateStatistics(result.width)
                assertEquals(if (kind == StudentDateKind.VISIT) 2 else 4, stats.trips)
                assertEquals(stats.trips, stats.completedTrips)
                assertTrue(stats.distance > 0)
                assertEquals(stats.trips, stats.departureHours.sum())
                assertTrue(people(result).all { it.life!!.studentRelationship!!.strength > 50 })
                assertTrue(
                    people(result).all { p ->
                        p.routes.none { memory ->
                            val home = result.households.first { p in it.people }
                            memory.need == Need.LEISURE && !memory.returning && home.paths[memory.path].destination != 36
                        }
                    },
                )
                result.validateCitizens()
                result.mobility!!.validate(result, catalog)
                val baseline = updateWellbeing(result.copy(mobility = result.mobility!!.copy(homes = result.mobility!!.homes.map { h -> h.copy(days = h.days.map { it.copy(dateOutcome = null) }) })), catalog)
                val happy = updateWellbeing(result, catalog)
                assertTrue(people(happy).zip(people(baseline)).all { (a, b) -> a.wellbeing.satisfaction > b.wellbeing.satisfaction })
                assertEquals(result, wireJson.decodeFromString<City>(wireJson.encodeToString(result)))
                val before = input.withSharedTerrain()
                val after = result.withSharedTerrain().copy(revision = before.revision + 1)
                val delta = wireJson.encodeToString(Response("STATE", city = after).compactState(before))
                assertEquals(after, wireJson.decodeFromString<Response>(delta).restoreState(before).city)
            }
        }

    private suspend fun appointmentExecution(
        delay: Int = 0,
        impossible: Boolean = false,
        noReturn: Boolean = false,
    ): City {
        val base = city(day = 5, paired = true)
        val router = MobilityRouter(base, catalog, null)
        val prepared =
            base.households.map { home ->
                val p = home.people.single()
                val out = assertNotNull(router.route(home.cell * TRAVEL_MODE_COUNT, 36 * TRAVEL_MODE_COUNT))
                val back = assertNotNull(router.route(36 * TRAVEL_MODE_COUNT, home.cell * TRAVEL_MODE_COUNT))
                val registry = HomeRoutes(listOf(out, back, TravelPath(listOf(home.cell * TRAVEL_MODE_COUNT), listOf(0))))
                val date = StudentAppointment(1, if (p.id == 1L) 2 else 1, StudentDateKind.OUTING, 36, 1000)
                val activities =
                    listOf(
                        PlannedActivity(Need.HEALTH, 0, 1000 - out.duration + if (p.id == 1L) delay else 0, 2, 2),
                        PlannedActivity(Need.LEISURE, 1000 - out.duration, 75, if (impossible && p.id == 1L) null else 0, 1, appointment = date),
                        PlannedActivity(Need.REST, 1320, 0),
                    )
                PreparedHome(MobilityHome(home.cell, 1, emptyList(), registry.paths, days = listOf(CitizenDay(p.id, p.position, activities))), listOf(p), registry)
            }
        val executor = DayExecutor(base, router, prepared, 5)
        executor.execute()
        var homes = executor.homes()
        if (noReturn) homes = homes.map { h -> h.copy(days = h.days.map { d -> if (d.citizenId != 1L) d else d.copy(legs = d.legs.map { leg -> if (leg.appointmentId == 1L && leg.returning) leg.copy(arrival = null) else leg }) }) }
        return finishStudentDates(base.copy(households = executor.households(), mobility = Mobility(0, homes, homes.sumOf { it.completedNeeds }, homes.sumOf { it.unmetNeeds }, day = 5)))
    }

    @Test fun `late and impossible journeys weaken relationships from actual execution`() =
        runBlocking {
            val success = appointmentExecution()
            val late = appointmentExecution(delay = 20)
            val absent = appointmentExecution(impossible = true)
            val tooLate = appointmentExecution(delay = 60)
            for ((result, expected) in listOf(success to StudentDateStatus.SUCCESS, late to StudentDateStatus.LATE, absent to StudentDateStatus.MISSED, tooLate to StudentDateStatus.MISSED)) {
                val outcomes =
                    result.mobility!!
                        .homes
                        .flatMap { it.days }
                        .map { assertNotNull(it.dateOutcome) }
                assertTrue(outcomes.all { it.status == expected })
                assertEquals(outcomes[0].strengthChange, outcomes[1].strengthChange)
                if (expected != StudentDateStatus.SUCCESS) assertTrue(outcomes.all { it.happiness < 0 && it.strengthChange < 0 })
                result.validateCitizens()
                result.mobility!!.validate(result, catalog)
            }
            assertEquals(1, absent.mobility!!.studentDateStatistics(16).missed)
            val failedReturn = appointmentExecution(noReturn = true)
            assertTrue(people(failedReturn)[0].life!!.studentRelationship!!.strength < people(success)[0].life!!.studentRelationship!!.strength)
            assertTrue(
                failedReturn.mobility!!
                    .homes[0]
                    .days[0]
                    .dateOutcome!!
                    .happiness <
                    success.mobility!!
                        .homes[0]
                        .days[0]
                        .dateOutcome!!
                        .happiness,
            )
        }

    @Test fun `full calendars do not create failed appointments or replace existing activities`() =
        runBlocking {
            val base = city(day = 5, paired = true)
            val seed = (0L..10000).first { 5 in studentDateDays(base.copy(seed = it), 1) }
            val c = base.copy(seed = seed)
            val prepared =
                c.households.map { home ->
                    val registry = HomeRoutes(listOf(TravelPath(listOf(home.cell * TRAVEL_MODE_COUNT), listOf(0))))
                    val activities = listOf(PlannedActivity(Need.EDUCATION, 0, 1320, 0, 0), PlannedActivity(Need.REST, 1320, 0))
                    PreparedHome(MobilityHome(home.cell, 1, emptyList(), registry.paths, days = home.people.map { CitizenDay(it.id, it.position, activities) }), home.people, registry)
                }
            val planned = planStudentDates(c, MobilityRouter(c, catalog, null), prepared)
            assertEquals(prepared.map { it.home.days }, planned.map { it.home.days })
            assertTrue(planned.flatMap { it.people }.all { it.life!!.studentRelationship!!.strength == 50.0 })
        }

    @Test fun `old life and day data load without relationships or appointments`() {
        val old = wireJson.decodeFromString<CitizenLife>("""{"birthDay":-5000,"sex":"FEMALE","knowledge":50.0,"learningAbility":1.0,"stage":"SCHOOL"}""")
        assertNull(old.studentRelationship)
        val day = wireJson.decodeFromString<CitizenDay>("""{"citizenId":1,"startNode":68,"activities":[{"need":"LEISURE","departure":1000,"stay":45}]}""")
        assertNull(day.dateOutcome)
        assertNull(day.activities.single().appointment)
    }

    @Test fun `real road congestion turns a successful visit into a missed date`() =
        runBlocking {
            suspend fun execute(congested: Boolean): City {
                val tiles = MutableList(256) { Tile(water = true) }
                tiles[17] = Tile(zone = Zone.RESIDENTIAL_DENSE, occupants = 1, powered = true)
                tiles[30] = Tile(zone = Zone.RESIDENTIAL_DENSE, occupants = if (congested) 2 else 1, powered = true)
                for (x in 1..14) tiles[32 + x] = Tile(water = true, road = true)
                val pair = listOf(person(1, cell = 17), person(2, cell = 30)).map { p -> p.copy(life = p.life!!.copy(studentRelationship = StudentRelationship(3 - p.id, 0))) }
                val opponent = person(3, 25, 30).let { it.copy(life = it.life!!.copy(stage = LifeStage.WORKER)) }
                val households = listOf(Household(17, listOf(pair[0])), Household(30, listOf(pair[1]) + if (congested) listOf(opponent) else emptyList()))
                val c = City(width = 16, height = 16, tiles = tiles, day = 5, households = households, nextCitizenId = 4, trafficRules = TrafficRules(roadSlots = 1, roadEntriesPerMinute = 1, reroutePercent = 0))
                val router = MobilityRouter(c, catalog, null)
                val crossing = assertNotNull(router.route(17 * TRAVEL_MODE_COUNT, 30 * TRAVEL_MODE_COUNT))
                val departure = 1000 - crossing.duration
                val prepared =
                    households.map { home ->
                        val registry = HomeRoutes(emptyList())
                        val days =
                            home.people.map { p ->
                                val target = if (p.id == 3L) 17 else 30
                                val route = registry.intern(assertNotNull(router.route(home.cell * TRAVEL_MODE_COUNT, target * TRAVEL_MODE_COUNT)))
                                val back = registry.intern(assertNotNull(router.route(target * TRAVEL_MODE_COUNT, home.cell * TRAVEL_MODE_COUNT)))
                                val date = if (p.id == 3L) null else StudentAppointment(1, 3 - p.id, StudentDateKind.VISIT, 30, 1000)
                                val start = if (p.id == 2L) 1000 else departure
                                CitizenDay(p.id, p.position, listOf(PlannedActivity(if (date == null) Need.WORK else Need.LEISURE, start, 75, route, back, appointment = date), PlannedActivity(Need.REST, 1320, 0)))
                            }
                        PreparedHome(MobilityHome(home.cell, home.people.size, emptyList(), registry.paths, days = days), home.people, registry)
                    }
                val executor = DayExecutor(c, router, prepared, 5)
                executor.execute()
                val homes = executor.homes()
                return finishStudentDates(c.copy(households = executor.households(), mobility = Mobility(0, homes, homes.sumOf { it.completedNeeds }, homes.sumOf { it.unmetNeeds }, day = 5, roadPeakOccupancy = executor.peaks(), roadOccupancyLimit = 1)))
            }
            val quiet = execute(false)
            val jammed = execute(true)
            assertTrue(
                quiet.mobility!!
                    .homes
                    .flatMap { it.days }
                    .mapNotNull { it.dateOutcome }
                    .all { it.status == StudentDateStatus.SUCCESS },
            )
            assertTrue(
                jammed.mobility!!
                    .homes
                    .flatMap { it.days }
                    .mapNotNull { it.dateOutcome }
                    .all { it.status == StudentDateStatus.MISSED && it.strengthChange < 0 },
            )
            assertTrue(
                jammed.mobility!!
                    .homes
                    .flatMap { it.days }
                    .flatMap { it.legs }
                    .any { it.appointmentId != null && it.waitMinutes > 0 },
            )
            assertTrue(
                jammed.mobility!!
                    .roadPeakOccupancy.values
                    .all { it == 1 },
            )
            assertTrue(jammed.mobility!!.aggregateLoads().isNotEmpty())
            jammed.validateCitizens()
            jammed.mobility!!.validate(jammed, catalog)
        }

    @Test fun `orientation preserves continuing students and clears relationships when studies are declined`() =
        runBlocking {
            val a = person(1, 18).let { it.copy(life = it.life!!.copy(stage = LifeStage.APPLICANT)) }
            val base = city(a, person(2, 17), day = 1, paired = true)
            for (continues in listOf(false, true)) {
                val seed = (0L..10000).first { (draw(base.copy(seed = it), 1, 701) < LifeRules.UNIVERSITY_CHANCE) == continues }
                val result = MobilitySimulation(catalog, 2).simulate(base.copy(seed = seed))
                assertEquals(continues, paired(result))
                assertEquals(if (continues) LifeStage.STUDENT else LifeStage.WORKER, people(result)[0].life!!.stage)
            }
        }
}
