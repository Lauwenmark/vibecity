package city.core

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.max

/** A bicycle stays at its last cell, including overnight and across saves. Times are absolute minutes. */
@Serializable
data class BicycleState(
    val cell: Int? = null,
    val parkedSince: Int? = null,
    val checkedUntil: Int? = null,
    val protectedParking: Boolean = false,
    val savings: Double = 0.0,
    val updatedDay: Int = -1,
    val lastTheftDay: Int? = null,
    val lastTheftCell: Int? = null,
    val thefts: Int = 0,
) {
    fun reluctance(day: Int): Double = lastTheftDay?.let { max(0.0, 55.0 - (day - it).coerceAtLeast(0) * .5) } ?: 0.0

    fun unhappiness(day: Int): Double = lastTheftDay?.let { max(0.0, 8.0 - (day - it).coerceAtLeast(0) * .5) } ?: 0.0
}

/** One parking pool for all cells of a multi-tile building. Zoned buildings default to no rack. */
fun City.bicycleParkingCell(cell: Int): Int = tiles[cell].anchor.takeIf { it in tiles.indices } ?: cell

fun City.bicycleParkingCapacity(
    cell: Int,
    catalog: Catalog,
): Int {
    val tile = tiles[bicycleParkingCell(cell)]
    return if (tile.fire > 0) 0 else tile.building?.let { catalog[it].bicycleParking } ?: 0
}

internal fun Citizen.prepareBicycle(
    city: City,
    home: Int,
): Citizen {
    val day = city.nextDay()
    var bike = bicycle ?: BicycleState(cell = home.takeIf { age >= 10 && noise(city.seed xor id, 719, 0) % 100 < 60 }, updatedDay = day)
    if (bike.updatedDay < day) {
        val savings = (bike.savings + (wellbeing.income - wellbeing.costs).coerceAtLeast(0.0) * .1).coerceAtMost(120.0)
        bike = bike.copy(savings = savings, updatedDay = day)
        // Buy at home only, never replace a bicycle that is merely parked elsewhere.
        if (age >= 10 && bike.cell == null && position == home * TRAVEL_MODE_COUNT && savings >= 120.0) {
            bike = bike.copy(cell = home, savings = savings - 120.0)
        }
    }
    return copy(bicycle = bike)
}

internal fun bicycleChoicePercent(
    person: Citizen,
    day: Int,
    steps: Int,
    minutes: Int,
    alternativeMinutes: Int,
    parking: Boolean,
): Double {
    if (person.age < 10 || person.bicycle?.cell == null || steps < 2) return 0.0
    val distance =
        when {
            steps <= 3 -> 12.0
            steps <= 16 -> 60.0
            steps <= 32 -> 40.0
            else -> 12.0
        }
    val speed = ((alternativeMinutes - minutes) * 1.5).coerceIn(-35.0, 15.0)
    return (distance + speed + if (parking) 20.0 else 0.0).let { it - person.bicycle.reluctance(day) }.coerceIn(0.0, 95.0)
}

/** At crime 100, an unprotected eight-hour stay has about a 2% risk; racks divide the hazard by ten. */
internal fun bicycleTheftProbability(
    crime: Int,
    minutes: Int,
    protected: Boolean,
): Double = 1 - exp(-.000042 * crime.coerceIn(0, 100) / 100.0 * minutes.coerceAtLeast(0) * if (protected) .1 else 1.0)

/** Preserve all old positions, routes and traces when the node stride grows from four to five. */
fun City.upgradeTravelNodes(): City {
    if (travelModeCount == TRAVEL_MODE_COUNT) return this
    require(travelModeCount == 4) { "Unsupported travel node encoding" }

    fun node(value: Int): Int {
        require(value in 0 until tiles.size * 4)
        return value / 4 * TRAVEL_MODE_COUNT + value % 4
    }

    fun path(value: TravelPath) = value.copy(nodes = value.nodes.map(::node))
    return copy(
        travelModeCount = TRAVEL_MODE_COUNT,
        households = households.map { home -> home.copy(people = home.people.map { it.copy(position = node(it.position)) }, paths = home.paths.map(::path)) },
        mobility =
            mobility?.let { traffic ->
                traffic.copy(
                    homes =
                        traffic.homes.map { home ->
                            home.copy(
                                paths = home.paths.map(::path),
                                days =
                                    home.days.map { day ->
                                        day.copy(
                                            startNode = node(day.startNode),
                                            legs =
                                                day.legs.map { leg ->
                                                    leg.copy(trace = path(leg.trace), decisions = leg.decisions.map { it.copy(node = node(it.node)) })
                                                },
                                        )
                                    },
                            )
                        },
                )
            },
    )
}
