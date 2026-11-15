package city.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class StudentRelationship(
    val partner: Long,
    val started: Int,
    val strength: Double = 50.0,
)

object StudentRomanceRules {
    const val APPROACH_CHANCE = .05
    const val ACCEPT_CHANCE = .50
    const val DATE_MINUTES = 75
    const val MINIMUM_TOGETHER = 30
    const val LATE_TOLERANCE = 15

    fun breakupChance(strength: Double): Double = .10 - .09 * strength.coerceIn(0.0, 100.0) / 100
}

@Serializable
enum class StudentDateKind(
    val label: String,
) {
    VISIT("Visite au partenaire"),
    OUTING("Sortie en couple"),
}

/** ID is the smaller citizen ID, unique within the day's appointments. */
@Serializable
data class StudentAppointment(
    val id: Long,
    val partner: Long,
    val kind: StudentDateKind,
    val destination: Int,
    val meeting: Int,
    val end: Int = meeting + StudentRomanceRules.DATE_MINUTES,
)

@Serializable
enum class StudentDateStatus(
    val label: String,
) {
    SUCCESS("Rendez-vous réussi"),
    LATE("Rendez-vous avec retard"),
    MISSED("Rendez-vous manqué"),
}

@Serializable
data class StudentDateOutcome(
    val status: StudentDateStatus,
    val togetherMinutes: Int,
    val happiness: Double,
    val strengthChange: Double,
)

private fun Citizen.studying(): Boolean = age >= 13 && life?.stage in listOf(LifeStage.SCHOOL, LifeStage.STUDENT)

/** Run after orientation and after exams, so continuing from school to college is not a breakup. */
internal fun repairStudentRelationships(city: City): City {
    val people = city.households.flatMap { it.people }.associateBy { it.id }
    return city.copy(
        households =
            city.households.map { home ->
                home.copy(
                    people =
                        home.people.map { person ->
                            val relationship = person.life?.studentRelationship
                            val other = relationship?.partner?.let(people::get)
                            if (relationship != null && (!person.studying() || person.life.partner != null || other == null || !other.studying() || other.life?.partner != null || other.life?.studentRelationship?.partner != person.id)) {
                                person.copy(life = person.life.copy(studentRelationship = null))
                            } else {
                                person
                            }
                        },
                )
            },
    )
}

/** School identity comes from this day's actual planned course destination, never residential proximity. */
internal fun weeklyStudentRelationships(
    city: City,
    schools: Map<Long, Int>,
): City {
    val repaired = repairStudentRelationships(city)
    if (city.nextDay() % 7 != 0) return repaired
    val people =
        repaired.households
            .flatMap { it.people }
            .associateBy { it.id }
            .toMutableMap()
    val unavailable = mutableSetOf<Long>()
    for (id in people.keys.sorted()) {
        val person = people.getValue(id)
        val relationship = person.life?.studentRelationship ?: continue
        if (id > relationship.partner) continue
        if (draw(city, id, 720) < StudentRomanceRules.breakupChance(relationship.strength)) {
            for (member in listOf(id, relationship.partner)) {
                val p = people.getValue(member)
                people[member] = p.copy(life = p.life!!.copy(studentRelationship = null))
                unavailable.add(member)
            }
        }
    }
    val candidates = people.values.filter { it.studying() && it.life?.partner == null && it.life?.studentRelationship == null && it.id in schools && it.id !in unavailable }.sortedBy { it.id }
    val groups = candidates.groupBy { schools.getValue(it.id) to it.age }
    val solicited = mutableSetOf<Long>()
    for (candidate in candidates) {
        val person = people.getValue(candidate.id)
        if (person.life!!.studentRelationship != null || draw(city, person.id, 721) >= StudentRomanceRules.APPROACH_CHANCE) continue
        val school = schools.getValue(person.id)
        val buckets = (person.age - 1..person.age + 1).map { groups[school to it].orEmpty() }
        val size = buckets.sumOf { it.size }
        val start = noise(city.seed xor person.id, 722, city.nextDay()) % size

        fun candidateAt(offset: Int): Citizen {
            var index = offset
            for (bucket in buckets) {
                if (index < bucket.size) return people.getValue(bucket[index].id)
                index -= bucket.size
            }
            error("Invalid candidate index")
        }
        val other =
            (0 until min(32, size))
                .asSequence()
                .map { candidateAt((start + it) % size) }
                .firstOrNull { it.id !in solicited && it.life!!.studentRelationship == null && !related(person, it) } ?: continue
        solicited.add(other.id)
        // One proposal only; a refusal never causes a search for a second person this week.
        if (draw(city.copy(seed = city.seed xor person.id), other.id, 723) >= StudentRomanceRules.ACCEPT_CHANCE) continue
        people[person.id] = person.copy(life = person.life.copy(studentRelationship = StudentRelationship(other.id, city.nextDay())))
        people[other.id] = other.copy(life = other.life!!.copy(studentRelationship = StudentRelationship(person.id, city.nextDay())))
    }
    return repaired.copy(households = repaired.households.map { home -> home.copy(people = home.people.map { people.getValue(it.id) }) })
}

/** One or two distinct days per civil week. The seed and pair ID make worker order irrelevant. */
internal fun studentDateDays(
    city: City,
    pair: Long,
): Set<Int> {
    val week = city.nextDay() / 7
    val n = noise(city.seed xor pair, 724, week)
    val first = n % 7
    return if (n % 2 == 0) setOf(first) else setOf(first, (first + 1 + noise(city.seed xor pair, 725, week) % 6) % 7)
}

/** Locate a real gap while preserving the executor's existing activity order, including returns. */
private fun dateSlot(
    day: CitizenDay,
    registry: HomeRoutes,
    departure: Int,
    end: Int,
): Int? {
    var available = 0
    for ((index, activity) in day.activities.withIndex()) {
        if (!activity.scheduled) continue
        val start = max(available, activity.departure)
        if (available <= departure && end <= start) return index
        if (activity.need == Need.REST) return null
        val path = activity.route?.let { registry.paths[it] } ?: continue
        val back = activity.returnRoute?.let { registry.paths[it].duration } ?: path.duration
        available = start + path.duration + activity.stay + back
    }
    return if (available <= departure && end <= 1320) day.activities.size else null
}

/** Cross-home coordination happens once after independent planning; registries remain home-local. */
internal suspend fun planStudentDates(
    city: City,
    router: MobilityRouter,
    input: List<PreparedHome>,
): List<PreparedHome> {
    val schools =
        input
            .flatMap { home ->
                home.home.days.mapNotNull { day ->
                    day.activities
                        .firstOrNull { it.need == Need.EDUCATION }
                        ?.route
                        ?.let { day.citizenId to home.registry.paths[it].destination }
                }
            }.toMap()
    val updated = weeklyStudentRelationships(city.copy(households = input.map { Household(it.home.cell, it.people, it.registry.paths) }), schools)
    val homes = input.mapIndexed { i, home -> home.copy(people = updated.households[i].people) }.toMutableList()
    val locations = homes.flatMapIndexed { h, home -> home.people.mapIndexed { slot, p -> p.id to (h to slot) } }.toMap()
    val people = homes.flatMap { it.people }.associateBy { it.id }
    val leisure = city.tiles.indices.filter { router.serves(Need.LEISURE, it) }
    for (person in people.values.sortedBy { it.id }) {
        currentCoroutineContext().ensureActive()
        val relationship = person.life?.studentRelationship ?: continue
        if (person.id > relationship.partner || city.nextDay() % 7 !in studentDateDays(city, person.id)) continue
        val members = listOf(person.id, relationship.partner)
        val places = members.map { locations.getValue(it) }
        val n = noise(city.seed xor person.id, 726, city.nextDay())
        val kind = if (n % 2 == 0 || leisure.isEmpty()) StudentDateKind.VISIT else StudentDateKind.OUTING
        val destination = if (kind == StudentDateKind.VISIT) homes[places[(n / 2) % 2].first].home.cell else leisure[n % leisure.size]
        val outbound = places.map { (h, _) -> router.route(homes[h].home.cell * TRAVEL_MODE_COUNT, destination * TRAVEL_MODE_COUNT) }
        val inbound = places.map { (h, _) -> router.route(destination * TRAVEL_MODE_COUNT, homes[h].home.cell * TRAVEL_MODE_COUNT) }
        // Missing routes still produce a failed appointment if the calendars permit it.
        val durations = outbound.map { it?.duration ?: 15 }
        val returns = inbound.map { it?.duration ?: 15 }
        val earliest = if (city.nextDay() % 7 < 5) 16 * 60 else 10 * 60
        val times = (earliest..1245 step 15).toList()
        val offset = noise(city.seed xor person.id, 727, city.nextDay()) % times.size
        var chosen: Pair<Int, List<Int>>? = null
        for (index in times.indices) {
            val meeting = times[(offset + index) % times.size]
            val slots =
                places.mapIndexed { member, (h, slot) ->
                    dateSlot(homes[h].home.days[slot], homes[h].registry, meeting - durations[member], meeting + StudentRomanceRules.DATE_MINUTES + returns[member])
                }
            if (slots.all { it != null }) {
                chosen = meeting to slots.map { it!! }
                break
            }
        }
        val (meeting, slots) = chosen ?: continue
        for ((member, location) in places.withIndex()) {
            val (h, slot) = location
            val home = homes[h]
            val date = StudentAppointment(person.id, members[1 - member], kind, destination, meeting)
            val activity =
                PlannedActivity(
                    Need.LEISURE,
                    meeting - durations[member],
                    StudentRomanceRules.DATE_MINUTES,
                    outbound[member]?.let(home.registry::intern),
                    inbound[member]?.let(home.registry::intern),
                    reason = if (outbound[member] == null || inbound[member] == null) "Rendez-vous : trajet inaccessible" else kind.label,
                    appointment = date,
                )
            val days = home.home.days.toMutableList()
            val activities = days[slot].activities.toMutableList().apply { add(slots[member], activity) }
            days[slot] = days[slot].copy(activities = activities)
            homes[h] = home.copy(home = home.home.copy(days = days, paths = home.registry.paths))
        }
    }
    return homes
}

/** Evaluate actual overlapping stays, then apply one shared strength change and individual happiness. */
internal fun finishStudentDates(city: City): City {
    val mobility = city.mobility ?: return city

    data class Participant(
        val home: MobilityHome,
        val day: CitizenDay,
        val date: StudentAppointment,
    )
    val groups =
        mobility.homes
            .flatMap { home ->
                home.days.mapNotNull { day ->
                    day.activities
                        .firstOrNull { it.appointment != null }
                        ?.appointment
                        ?.let { Participant(home, day, it) }
                }
            }.groupBy { it.date.id }
    val outcomes = mutableMapOf<Long, StudentDateOutcome>()
    for (participants in groups.values) {
        val date = participants.first().date
        val arrivals =
            participants.map { p ->
                p.day.legs
                    .firstOrNull { it.appointmentId == date.id && !it.returning && it.trace.destination == date.destination }
                    ?.arrival
            }
        val departures =
            participants.map { p ->
                p.day.legs
                    .firstOrNull { it.appointmentId == date.id && it.returning }
                    ?.departure ?: 1440
            }
        val together = if (participants.size == 2 && arrivals.all { it != null }) (min(date.end, departures.min()) - max(date.meeting, arrivals.maxOf { it!! })).coerceAtLeast(0) else 0
        val missed = together < StudentRomanceRules.MINIMUM_TOGETHER
        val late = arrivals.any { it == null || it > date.meeting + StudentRomanceRules.LATE_TOLERANCE }
        val status =
            if (missed) {
                StudentDateStatus.MISSED
            } else if (late) {
                StudentDateStatus.LATE
            } else {
                StudentDateStatus.SUCCESS
            }
        var strength =
            when (status) {
                StudentDateStatus.SUCCESS -> 4.0
                StudentDateStatus.LATE -> -6.0
                StudentDateStatus.MISSED -> -8.0
            }
        val happiness =
            participants.map { p ->
                var effect =
                    when (status) {
                        StudentDateStatus.SUCCESS -> .7
                        StudentDateStatus.LATE -> -.4
                        StudentDateStatus.MISSED -> -1.2
                    }
                val activity = p.day.activities.first { it.appointment?.id == date.id }
                // A host reserves time but the zero-distance stay is not a transport trip.
                for (returning in listOf(false, true)) {
                    val route = (if (returning) activity.returnRoute else activity.route)?.let { p.home.paths[it] }
                    if (route != null && route.nodes.size == 1) continue
                    val leg = p.day.legs.firstOrNull { it.appointmentId == date.id && it.returning == returning }
                    val expected = if (returning) (leg?.departure ?: date.end) + (route?.duration ?: 0) else date.meeting
                    val success = leg?.arrival != null && leg.arrival <= expected + StudentRomanceRules.LATE_TOLERANCE
                    strength += if (success) 1.0 else -2.0
                    effect += if (success) .15 else -.3
                }
                effect
            }
        participants.forEachIndexed { index, p -> outcomes[p.day.citizenId] = StudentDateOutcome(status, together, happiness[index], strength) }
    }
    val resolvedHomes =
        mobility.homes.map { home ->
            var completed = home.completedNeeds
            var unmet = home.unmetNeeds
            val days =
                home.days.map { day ->
                    val outcome = outcomes[day.citizenId]
                    val activity = day.activities.firstOrNull { it.appointment != null }
                    if (outcome != null && activity != null) {
                        val counted = day.legs.any { activity.matches(it) && !it.returning && it.arrival != null && it.arrival + activity.stay < 1440 }
                        val fulfilled = outcome.status != StudentDateStatus.MISSED
                        val delta = (if (fulfilled) 1 else 0) - (if (counted) 1 else 0)
                        completed += delta
                        unmet -= delta
                    }
                    day.copy(dateOutcome = outcome)
                }
            home.copy(days = days, completedNeeds = completed, unmetNeeds = unmet)
        }
    return city.copy(
        households =
            city.households.map { home ->
                home.copy(
                    people =
                        home.people.map { p ->
                            val relationship = p.life?.studentRelationship
                            val outcome = outcomes[p.id]
                            if (relationship == null || outcome == null) p else p.copy(life = p.life.copy(studentRelationship = relationship.copy(strength = (relationship.strength + outcome.strengthChange).coerceIn(0.0, 100.0))))
                        },
                )
            },
        mobility = mobility.copy(homes = resolvedHomes, completedNeeds = resolvedHomes.sumOf { it.completedNeeds }, unmetNeeds = resolvedHomes.sumOf { it.unmetNeeds }),
    )
}

data class StudentDateStatistics(
    val appointments: Int,
    val missed: Int,
    val trips: Int,
    val completedTrips: Int,
    val distance: Long,
    val departureHours: List<Int>,
)

fun Mobility.studentDateStatistics(width: Int): StudentDateStatistics {
    val dates = mutableSetOf<Long>()
    val missed = mutableSetOf<Long>()
    var planned = 0
    var completed = 0
    var distance = 0L
    val hours = IntArray(24)
    for (home in homes) {
        for (day in home.days) {
            val activity = day.activities.firstOrNull { it.appointment != null } ?: continue
            val date = activity.appointment!!
            dates.add(date.id)
            if (day.dateOutcome?.status == StudentDateStatus.MISSED) missed.add(date.id)
            planned += listOf(activity.route, activity.returnRoute).count { it == null || home.paths[it].nodes.size > 1 }
            for (leg in day.legs) {
                if (leg.appointmentId == null || home.paths[leg.planned].nodes.size == 1) continue
                hours[leg.departure / 60]++
                if (leg.arrival != null) completed++
                distance +=
                    leg.trace.nodes
                        .zipWithNext()
                        .sumOf { (a, b) -> (abs(a / TRAVEL_MODE_COUNT % width - b / TRAVEL_MODE_COUNT % width) + abs(a / TRAVEL_MODE_COUNT / width - b / TRAVEL_MODE_COUNT / width)).toLong() }
            }
        }
    }
    return StudentDateStatistics(dates.size, missed.size, planned, completed, distance, hours.toList())
}
