package city.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

val epoch: LocalDate = LocalDate.of(1900, 1, 1)

fun City.nextDay(): Int = day ?: ChronoUnit.DAYS.between(epoch, epoch.plusMonths(month.toLong())).toInt()

fun dayName(day: Int): String = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")[Math.floorMod(day, 7)]

@Serializable
data class TrafficRules(
    val roadSlots: Int = 6,
    val roadEntriesPerMinute: Int = 2,
    val reconsiderMinutes: Int = 8,
    val reroutePercent: Int = 45,
    val initialScore: Int = 80,
    val alternativeScore: Int = 20,
    val successReward: Int = 3,
    val abandonmentPenalty: Int = 12,
    val maxScore: Int = 100,
    val minimumScore: Int = 5,
    val retainedRoutes: Int = 5,
) {
    init {
        require(roadSlots in 1..1000 && roadEntriesPerMinute in 1..1000)
        require(reconsiderMinutes in 1..120 && reroutePercent in 0..100)
        require(minimumScore in 1..100 && maxScore in minimumScore..1000)
        require(initialScore in minimumScore..maxScore && alternativeScore in minimumScore..maxScore)
        require(successReward in 0..100 && abandonmentPenalty in 0..100 && retainedRoutes in 1..20)
    }
}

@Serializable
data class LearnedRoute(
    val weekday: Int,
    val need: Need,
    val returning: Boolean,
    val path: Int,
    val score: Int,
)

@Serializable
data class Citizen(
    val id: Long,
    val name: String,
    val age: Int,
    val routes: List<LearnedRoute> = emptyList(),
    val position: Int,
    val wellbeing: Wellbeing = Wellbeing(),
    val life: CitizenLife? = null,
    val bicycle: BicycleState? = null,
)

@Serializable
data class Household(
    val cell: Int,
    val people: List<Citizen>,
    val paths: List<TravelPath> = emptyList(),
)

@Serializable
data class PlannedActivity(
    val need: Need,
    val departure: Int,
    val stay: Int,
    val route: Int? = null,
    val returnRoute: Int? = null,
    val scheduled: Boolean = true,
    val reason: String = "Prévu",
    val appointment: StudentAppointment? = null,
)

@Serializable
data class RouteDecision(
    val minute: Int,
    val node: Int,
    val action: String,
)

@Serializable
data class ExecutedLeg(
    val need: Need,
    val returning: Boolean,
    val planned: Int,
    val departure: Int,
    val arrival: Int? = null,
    val trace: TravelPath,
    val waitMinutes: Int = 0,
    val status: String = "En cours",
    val decisions: List<RouteDecision> = emptyList(),
    val appointmentId: Long? = null,
)

@Serializable
data class CitizenDay(
    val citizenId: Long,
    val startNode: Int,
    val activities: List<PlannedActivity>,
    val legs: List<ExecutedLeg> = emptyList(),
    val dateOutcome: StudentDateOutcome? = null,
) {
    fun positionAt(minute: Int): Int {
        val leg = legs.lastOrNull { it.departure <= minute } ?: return startNode
        val elapsed = minute - leg.departure
        return leg.trace.nodes[
            leg.trace.minutes
                .indexOfLast { it <= elapsed }
                .coerceAtLeast(0),
        ]
    }
}

internal fun Citizen.prune(rules: TrafficRules): Citizen =
    copy(
        routes =
            routes.groupBy { Triple(it.weekday, it.need, it.returning) }.values.flatMap { group ->
                group.filter { it.score >= rules.minimumScore }.sortedByDescending { it.score }.take(rules.retainedRoutes)
            },
    )

/** Seeded weighted draw, independent of coroutine execution order. */
internal fun chooseRoute(
    routes: List<LearnedRoute>,
    seed: Long,
): LearnedRoute? {
    if (routes.isEmpty()) return null
    val total = routes.sumOf { it.score.coerceAtLeast(1).toLong() * it.score.coerceAtLeast(1) }
    var draw = noise(seed, 0, 0).toLong() % total
    for (route in routes) {
        draw -= route.score.coerceAtLeast(1).toLong() * route.score.coerceAtLeast(1)
        if (draw < 0) return route
    }
    return routes.last()
}

fun City.validateCitizens() {
    require(travelModeCount == TRAVEL_MODE_COUNT)
    require(day == null || day >= 0)
    require(nextCitizenId > 0)
    require(households.map { it.cell }.distinct().size == households.size)
    val ids = mutableSetOf<Long>()
    for (household in households) {
        require(household.cell in tiles.indices)
        for (path in household.paths) {
            require(path.nodes.isNotEmpty() && path.nodes.size == path.minutes.size)
            require(path.nodes.all { it in 0 until tiles.size * TRAVEL_MODE_COUNT })
            require(path.minutes.first() == 0 && path.minutes.zipWithNext().all { (a, b) -> b > a })
        }
        for (person in household.people) {
            require(person.id in 1 until nextCitizenId && ids.add(person.id))
            require(person.name.length in 1..120 && person.age in 0..120 && person.position in 0 until tiles.size * TRAVEL_MODE_COUNT)
            person.life?.validate(person, this)
            if (person.position % TRAVEL_MODE_COUNT == TravelMode.BICYCLE.ordinal) require(person.bicycle?.cell == person.position / TRAVEL_MODE_COUNT)
            person.bicycle?.let { bike ->
                require(bike.cell == null || bike.cell in tiles.indices)
                require(bike.savings.isFinite() && bike.savings in 0.0..120.0 && bike.updatedDay >= -1 && bike.thefts >= 0)
                require((bike.lastTheftDay == null) == (bike.lastTheftCell == null))
                require(bike.lastTheftDay == null || bike.lastTheftDay >= 0)
                require(bike.lastTheftCell == null || bike.lastTheftCell in tiles.indices)
                require((bike.parkedSince == null) == (bike.checkedUntil == null))
                require(bike.parkedSince == null || (bike.cell != null && bike.parkedSince >= 0 && bike.checkedUntil!! >= bike.parkedSince))
                require(!bike.protectedParking || bike.parkedSince != null)
                require(person.position % TRAVEL_MODE_COUNT != TravelMode.BICYCLE.ordinal || bike.cell == person.position / TRAVEL_MODE_COUNT)
            }

            val opinion = person.wellbeing
            require(opinion.satisfaction.isFinite() && opinion.satisfaction in 0.0..100.0)
            require(opinion.pressure.isFinite() && opinion.pressure in 0.0..100.0 && opinion.trend.isFinite())
            require(opinion.unhappyDays >= 0 && opinion.residenceDays >= 0 && opinion.employedDays >= 0 && opinion.unemployedDays >= 0)
            require(opinion.workplace == null || opinion.workplace in tiles.indices)
            require(opinion.income.isFinite() && opinion.income >= 0 && opinion.costs.isFinite() && opinion.costs >= 0)
            require(opinion.travelMinutes >= 0 && opinion.averageDelay.isFinite() && opinion.averageDelay >= 0 && opinion.distance >= 0 && opinion.unmet >= 0)
            require(opinion.averageFulfillmentMinutes.isFinite() && opinion.averageFulfillmentMinutes >= 0)
            require(opinion.factors.isEmpty() || opinion.factors.size == OpinionFactor.entries.size)
            require(opinion.factors.all { it.isFinite() && it >= 0 })
            for (route in person.routes) {
                require(route.weekday in 0..6 && route.path in household.paths.indices && route.score in 0..trafficRules.maxScore)
            }
        }
    }
    val citizensById = households.flatMap { it.people }.associateBy { it.id }
    for (person in citizensById.values) {
        person.life?.studentRelationship?.let { relation ->
            val other = requireNotNull(citizensById[relation.partner]?.life?.studentRelationship)
            require(other.partner == person.id && other.started == relation.started && other.strength == relation.strength)
            require(person.life.stage in listOf(LifeStage.SCHOOL, LifeStage.STUDENT, LifeStage.APPLICANT) && person.age >= 13)
        }
    }
    mobility?.let { traffic ->
        require(traffic.day >= 0 && traffic.roadOccupancyLimit in 1..1000)
        require(traffic.roadPeakOccupancy.all { (cell, count) -> cell in tiles.indices && count in 0..traffic.roadOccupancyLimit })
        for (home in traffic.homes) {
            val household = requireNotNull(households.firstOrNull { it.cell == home.cell }) { "Domicile absent" }
            require(home.paths == household.paths && home.residents == household.people.size)
            require(home.days.map { it.citizenId } == household.people.map { it.id })
            for (personDay in home.days) {
                require(personDay.startNode in 0 until tiles.size * TRAVEL_MODE_COUNT)
                require(
                    personDay.activities
                        .map { it.need to it.appointment?.id }
                        .distinct()
                        .size == personDay.activities.size,
                )
                require(personDay.activities.count { it.appointment != null } <= 1)
                personDay.dateOutcome?.let { outcome ->
                    require(personDay.activities.any { it.appointment != null })
                    require(outcome.togetherMinutes in 0..StudentRomanceRules.DATE_MINUTES && outcome.happiness.isFinite() && outcome.strengthChange.isFinite())
                }
                for (activity in personDay.activities) {
                    activity.appointment?.let { date ->
                        require(activity.need == Need.LEISURE && date.id == minOf(personDay.citizenId, date.partner))
                        require(date.partner in 1 until nextCitizenId && date.partner != personDay.citizenId)
                        require(date.destination in tiles.indices && date.meeting in 0..1245 && date.end == date.meeting + StudentRomanceRules.DATE_MINUTES)
                    }
                    require(activity.route == null || activity.route in home.paths.indices)
                    require(activity.returnRoute == null || activity.returnRoute in home.paths.indices)
                }
                for (leg in personDay.legs) {
                    require(leg.appointmentId == null || personDay.activities.any { it.matches(leg) })
                    require(leg.planned in home.paths.indices && leg.departure in 0..1439 && (leg.arrival == null || leg.arrival in leg.departure..1439))
                    require(leg.trace.nodes.isNotEmpty() && leg.trace.nodes.size == leg.trace.minutes.size)
                    require(leg.trace.nodes.all { it in 0 until tiles.size * TRAVEL_MODE_COUNT })
                    require(
                        leg.trace.minutes.first() == 0 &&
                            leg.trace.minutes
                                .zipWithNext()
                                .all { (a, b) -> b > a },
                    )
                    require(leg.trace.nodes.first() == home.paths[leg.planned].nodes.first())
                    require(leg.trace.minutes.last() <= 1439 - leg.departure)
                    require(leg.arrival == null || leg.trace.minutes.last() == leg.arrival - leg.departure)
                    require(leg.waitMinutes >= 0 && leg.decisions.all { it.minute in 0..1439 && it.node in 0 until tiles.size * TRAVEL_MODE_COUNT })
                }
            }
        }
    }
}

/** A social appointment is distinct from the ordinary leisure activity. */
fun PlannedActivity.matches(leg: ExecutedLeg): Boolean = need == leg.need && appointment?.id == leg.appointmentId
