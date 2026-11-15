package city.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Persistent daily opinion, in percent. Defaults migrate older saves without erasing citizens. */
@Serializable
data class Wellbeing(
    val satisfaction: Double = 75.0,
    val trend: Double = 0.0,
    val pressure: Double = 0.0,
    val unhappyDays: Int = 0,
    val residenceDays: Int = 0,
    val workplace: Int? = null,
    val employedDays: Int = 0,
    val unemployedDays: Int = 0,
    val income: Double = 0.0,
    val costs: Double = 0.0,
    val travelMinutes: Int = 0,
    val averageDelay: Double = 0.0,
    val distance: Int = 0,
    val unmet: Int = 0,
    /** Penalties in percentage points, indexed by OpinionFactor. Kept numeric on the wire. */
    val factors: List<Double> = emptyList(),
    val averageFulfillmentMinutes: Double = 0.0,
)

enum class OpinionFactor(
    val label: String,
) {
    NEEDS("Besoins non satisfaits"),
    TRAVEL("Temps, retards et longueur des trajets"),
    CRIME("Criminalité au domicile et aux destinations"),
    FIRE("Protection incendie insuffisante"),
    UTILITIES("Eau et électricité"),
    POLLUTION("Pollution du domicile"),
    EMPLOYMENT("Chômage et instabilité de l’emploi"),
    BUDGET("Coût du logement, impôts et budget"),
    HOUSING("Qualité du logement et surpeuplement"),
    SERVICES("Accès aux soins et à l’éducation"),
    ENVIRONMENT("Bruit et manque d’espaces verts"),
}

val Household.satisfaction: Double get() = people.map { it.wellbeing.satisfaction }.average().takeIf { it.isFinite() } ?: 75.0

fun Household.moveProbability(): Double {
    if (people.isEmpty()) return 0.0
    val duration = people.map { it.wellbeing.unhappyDays }.average()
    val attachment = people.map { it.wellbeing.residenceDays }.average().coerceAtMost(730.0) / 730.0
    return ((60 - satisfaction).coerceAtLeast(0.0) / 60).pow(2) * (duration / 30).coerceIn(0.0, 1.0) * .08 / (1 + attachment)
}

/** Same radius/funding rules as municipal services; computed once per day, not per citizen. */
internal fun opinionServices(
    city: City,
    catalog: Catalog,
): Map<String, IntArray> {
    val fields = mutableMapOf<String, IntArray>()
    city.tiles.forEachIndexed { i, tile ->
        val def = tile.building?.takeIf { tile.anchor == i && tile.powered }?.let { catalog[it] }
        if (def != null && def.service.isNotEmpty() && def.radius > 0) {
            val field = fields.getOrPut(def.service) { IntArray(city.tiles.size) }
            for (y in max(0, i / city.width - def.radius)..min(city.height - 1, i / city.width + def.radius)) {
                for (x in max(0, i % city.width - def.radius)..min(city.width - 1, i % city.width + def.radius)) {
                    val d = abs(x - i % city.width) + abs(y - i / city.width)
                    if (d < def.radius) field[y * city.width + x] = max(field[y * city.width + x], (def.radius - d) * city.rules.serviceFunding / def.radius)
                }
            }
        }
    }
    return fields
}

/** Deterministic jobs with finite capacity, actual weekday work destinations and stable weekend contracts. */
fun updateWellbeing(
    city: City,
    catalog: Catalog,
): City {
    val moving = city.mobility ?: return city
    val services = opinionServices(city, catalog)
    val executions = moving.homes.associateBy { it.cell }
    val slots = IntArray(city.tiles.size) { i -> city.tiles[i].let { if ((it.zone.commercial || it.zone.industrial) && it.powered && it.fire == 0) it.occupants else 0 } }
    val jobs = mutableMapOf<Long, Int>()
    // Existing workers get first refusal, so adding newcomers cannot evict them from their jobs.
    for (home in city.households) {
        for (person in home.people) {
            val job = person.wellbeing.workplace
            if (person.isWorker && job != null && slots.getOrElse(job) { 0 } > 0) {
                jobs[person.id] = job
                slots[job]--
            }
        }
    }
    for (home in city.households) {
        val days = executions[home.cell]?.days.orEmpty()
        for ((index, person) in home.people.withIndex()) {
            if (!person.isWorker || person.id in jobs) continue
            val job =
                days
                    .getOrNull(index)
                    ?.legs
                    ?.firstOrNull { it.need == Need.WORK && !it.returning && it.arrival != null }
                    ?.trace
                    ?.destination
            if (job != null && slots[job] > 0) {
                jobs[person.id] = job
                slots[job]--
            }
        }
    }
    val households =
        city.households.map { home ->
            val tile = city.tiles[home.cell]
            val execution = executions[home.cell]

            fun service(key: String) = services[key]?.get(home.cell) ?: 0
            val capacity = if (tile.zone.residential) tile.zone.capacity else tile.building?.let { catalog[it].residents } ?: 0
            val crowding = (home.people.size.toDouble() / max(1, capacity) - .85).coerceAtLeast(0.0) / .15
            val rent = 5.0 + tile.landValue * .10 + if (tile.zone == Zone.RESIDENTIAL_LIGHT) 2 else 0
            val householdIncome = home.people.sumOf { it.dailyIncome } + if (home.people.none { it.age >= 18 }) home.people.size * 30.0 else 0.0
            val householdCosts = home.people.sumOf { p -> rent + (if (p.age < 18) 6 else 10) } + householdIncome * city.rules.taxRate / 100
            val budgetPenalty = ((householdCosts - householdIncome) / max(1.0, householdCosts)).coerceIn(0.0, 1.0) * 25
            val localNoise =
                (city.neighbors(home.cell) + home.cell).maxOf { i ->
                    val t = city.tiles[i]
                    max(if (t.zone.industrial) 75 else 0, if (t.road || t.rail) t.traffic else 0)
                }
            home.copy(
                people =
                    home.people.mapIndexed { index, person ->
                        val old = person.wellbeing
                        val day = execution?.days?.getOrNull(index)
                        val legs = day?.legs.orEmpty()
                        val activities = day?.activities.orEmpty().filter { it.scheduled }
                        var missed = 0
                        var needsPenalty = 0.0
                        val fulfillmentTimes = mutableListOf<Int>()
                        for (activity in activities.filter { it.appointment == null }) {
                            val leg = legs.firstOrNull { activity.matches(it) && !it.returning && it.arrival != null }
                            val fulfilled = if (activity.need == Need.REST) person.position == home.cell * 4 else leg?.arrival?.let { it + activity.stay < 1440 } == true
                            if (fulfilled && activity.need != Need.REST && leg?.arrival != null) fulfillmentTimes.add((leg.arrival + activity.stay - activity.departure).coerceAtLeast(0))
                            if (!fulfilled) {
                                missed++
                                needsPenalty +=
                                    when (activity.need) {
                                        Need.FOOD -> 18.0
                                        Need.REST -> 15.0
                                        Need.HEALTH -> if (person.age >= 65) 16.0 else 12.0
                                        Need.EDUCATION -> if (person.age < 18) 12.0 else 6.0
                                        Need.WORK -> 10.0
                                        Need.LEISURE -> 4.0
                                    }
                            }
                        }
                        val travel = legs.sumOf { (it.arrival ?: 1440) - it.departure }
                        val distance =
                            legs.sumOf { leg ->
                                leg.trace.nodes
                                    .zipWithNext()
                                    .sumOf { (a, b) -> abs(a / 4 % city.width - b / 4 % city.width) + abs(a / 4 / city.width - b / 4 / city.width) }
                            }
                        val delays =
                            legs.map { leg ->
                                val activity = activities.firstOrNull { it.matches(leg) }
                                val scheduledDeparture = if (!leg.returning) activity?.departure ?: leg.departure else leg.departure
                                max(0, (leg.arrival ?: 1440) - scheduledDeparture - (execution?.paths?.getOrNull(leg.planned)?.duration ?: 0))
                            }
                        val delay = delays.average().takeIf { it.isFinite() } ?: 0.0
                        val destinationCrime =
                            legs
                                .filter { it.arrival != null && !it.returning }
                                .map { city.tiles[it.trace.destination].crime }
                                .average()
                                .takeIf { it.isFinite() } ?: tile.crime.toDouble()
                        val job = jobs[person.id]
                        val employed =
                            if (job != null) {
                                if (job == old.workplace) {
                                    old.employedDays + 1
                                } else {
                                    1
                                }
                            } else {
                                0
                            }
                        val unemployed = 0 // Income is guaranteed until a labor market is introduced.
                        val penalties =
                            listOf(
                                needsPenalty.coerceAtMost(55.0),
                                max(max((travel - 90).coerceAtLeast(0) / 12.0, delay / 4), (distance - 80).coerceAtLeast(0) / 20.0).coerceAtMost(25.0),
                                (tile.crime * .65 + destinationCrime * .35) * .18,
                                (40 - service("fire")).coerceAtLeast(0) * .15,
                                (if (!tile.watered) 22.0 else 0.0) + (if (!tile.powered) 18.0 else 0.0),
                                tile.pollution * .22,
                                if (unemployed > 0) {
                                    8.0 + min(12.0, unemployed / 3.0)
                                } else if (employed in 1..14) {
                                    3.0
                                } else {
                                    0.0
                                },
                                budgetPenalty,
                                crowding.coerceAtMost(2.0) * 5 + (40 - tile.landValue).coerceAtLeast(0) * .10,
                                (30 - service("health")).coerceAtLeast(0) * (if (person.age >= 65) .25 else .10) + (if (person.age in 3..17) (40 - service("education")).coerceAtLeast(0) * .2 else 0.0),
                                (localNoise - 25).coerceAtLeast(0) * .12 + (25 - service("park") - if (tile.trees) 25 else 0).coerceAtLeast(0) * .12,
                            )
                        val pressure = old.pressure * .8 + penalties.sum().coerceAtMost(100.0) * .2
                        val target = (100 - pressure).coerceIn(0.0, 100.0)
                        val difference = target - old.satisfaction
                        val change = (if (difference >= 0) min(.35, difference) else max(-4.0, difference * .12)) + (day?.dateOutcome?.happiness ?: 0.0)
                        val score = (old.satisfaction + change).coerceIn(0.0, 100.0)
                        person.copy(
                            wellbeing =
                                Wellbeing(
                                    satisfaction = score,
                                    trend = change,
                                    pressure = pressure,
                                    unhappyDays = if (score < 60) old.unhappyDays + 1 else max(0, old.unhappyDays - 2),
                                    residenceDays = old.residenceDays + 1,
                                    workplace = job,
                                    employedDays = employed,
                                    unemployedDays = unemployed,
                                    income = householdIncome / max(1, home.people.size),
                                    costs = householdCosts / max(1, home.people.size),
                                    travelMinutes = travel,
                                    averageDelay = delay,
                                    distance = distance,
                                    unmet = missed,
                                    factors = penalties,
                                    averageFulfillmentMinutes = fulfillmentTimes.average().takeIf { it.isFinite() } ?: 0.0,
                                ),
                        )
                    },
            )
        }
    return city.copy(households = households)
}

/** Moves occur before the next day's routes are built, preserving snapshot/path invariants. */
fun migrateUnhappyHouseholds(
    city: City,
    catalog: Catalog,
): City {
    val tiles = city.tiles.toMutableList()
    val homes = city.households.associateBy { it.cell }.toMutableMap()
    val services = opinionServices(city, catalog)

    fun quality(i: Int): Double {
        val t = tiles[i]
        return (if (t.powered) 18.0 else 0.0) + (if (t.watered) 22 else 0) - t.pollution * .22 - t.crime * .18 + t.landValue * .1 + (services["fire"]?.get(i) ?: 0) * .1 + (services["park"]?.get(i) ?: 0) * .1
    }
    var departed = 0
    var relocated = 0
    for (home in city.households) {
        val residentCount = if (tiles[home.cell].zone.residential) tiles[home.cell].occupants else tiles[home.cell].residentialOccupancy ?: tiles[home.cell].building?.let { catalog[it].residents } ?: 0
        if (home.people.isEmpty() || home.people.size != residentCount || home.people.any { it.position != home.cell * 4 }) continue
        val chance = home.moveProbability()
        if (chance <= 0 || noise(city.seed xor home.people.first().id, home.cell, city.nextDay()) / 2147483648.0 >= chance) continue
        // A cell is the existing simulation's household. Never merge unrelated route registries.
        val destination =
            tiles.indices.firstOrNull { i ->
                val t = tiles[i]
                i != home.cell && t.zone.residential && t.occupants == 0 && homes[i]?.people.isNullOrEmpty() &&
                    t.zone.capacity >= home.people.size && t.powered && t.watered && t.fire == 0 && city.surface(i).flat &&
                    quality(i) >= quality(home.cell) + 8 && city.neighbors(i).any { tiles[it].road || tiles[it].rail || tiles[it].subway }
            }
        val source = tiles[home.cell]
        tiles[home.cell] = if (source.zone.residential) source.copy(occupants = 0) else source.copy(residentialOccupancy = 0)
        homes.remove(home.cell)
        if (destination == null) {
            departed += home.people.size
        } else {
            tiles[destination] = tiles[destination].copy(occupants = home.people.size)
            homes[destination] = Household(destination, home.people.map { it.copy(position = destination * 4, routes = emptyList(), wellbeing = it.wellbeing.copy(residenceDays = 0)) })
            relocated += home.people.size
        }
    }
    if (departed == 0 && relocated == 0) return city
    val notices =
        buildList {
            addAll(city.notices.filterNot { it.startsWith("Déménagements :") })
            add("Déménagements : $relocated habitants relogés dans la ville, $departed départs.")
        }
    return city.copy(tiles = tiles, households = homes.values.sortedBy { it.cell }, mobility = null, stats = city.stats.copy(population = (city.stats.population - departed).coerceAtLeast(0)), notices = notices)
}
