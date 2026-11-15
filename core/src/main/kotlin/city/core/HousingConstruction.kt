package city.core

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** Capacity is the zoned building's built-volume proxy (all current zone buildings occupy one tile). */
@Serializable
data class HousingConstruction(
    val capacity: Int,
    val startedDay: Int,
    val completionDay: Int,
) {
    init {
        require(capacity in 1..80 && startedDay >= 0 && completionDay > startedDay)
    }
}

private val housingStages = listOf(12, 24, 40, 60, 80)

val Tile.completedHousingCapacity: Int
    get() = if (!zone.residential) 0 else housingCapacity ?: if (occupants == 0) 0 else housingStages.firstOrNull { it >= occupants }?.coerceAtMost(zone.capacity) ?: zone.capacity

val Tile.habitableHousingCapacity: Int
    get() = if (construction != null) 0 else completedHousingCapacity

fun housingConstructionDays(capacity: Int): Int = max(7, capacity * 3)

/** Freeze legacy capacities before deaths/moves, and finish sites without creating residents. */
internal fun advanceHousingConstruction(city: City): City =
    city.copy(
        tiles =
            city.tiles.map { tile ->
                if (!tile.zone.residential) {
                    tile
                } else {
                    val site = tile.construction
                    if (site != null && city.nextDay() >= site.completionDay) {
                        tile.copy(housingCapacity = site.capacity, construction = null)
                    } else {
                        tile.copy(housingCapacity = tile.completedHousingCapacity)
                    }
                }
            },
    )

/** Serial deterministic commit after parallel economic proposals. No citizen is created here. */
internal fun developHousing(
    city: City,
    proposed: List<Tile>,
    catalog: Catalog,
): City {
    val tiles =
        proposed
            .mapIndexed { i, t ->
                if (t.zone.residential) t.copy(occupants = min(t.occupants, city.tiles[i].occupants), housingCapacity = t.housingCapacity ?: city.tiles[i].completedHousingCapacity, constructionBlocked = false) else t
            }.toMutableList()
    val homes = city.households.associateBy { it.cell }.toMutableMap()
    val access = MobilityRouter(city.copy(tiles = tiles), catalog, null).exteriorRoadAccess()

    fun habitable(i: Int): Boolean = tiles[i].let { it.zone.residential && it.powered && it.fire == 0 && it.pollution < 65 && city.surface(i).flat && access[i] }
    val eligible = tiles.indices.filter { habitable(it) && proposed[it].occupants > city.tiles[it].occupants && tiles[it].construction == null }
    val demand = eligible.sumOf { proposed[it].occupants - city.tiles[it].occupants }
    var budget = demand
    val vacancies = eligible.sumOf { (tiles[it].habitableHousingCapacity - tiles[it].occupants).coerceAtLeast(0) }
    var shortage = (budget - vacancies - tiles.sumOf { it.construction?.capacity ?: 0 }).coerceAtLeast(0)
    var moved = false
    val relocationDestinations = mutableSetOf<Int>()

    fun start(
        i: Int,
        capacity: Int,
    ) {
        val t = tiles[i]
        tiles[i] = t.copy(occupants = 0, housingCapacity = 0, construction = HousingConstruction(capacity, city.nextDay(), city.nextDay() + housingConstructionDays(capacity)))
    }

    // New ground is preferable to evacuating an occupied building.
    for (i in eligible) {
        if (shortage <= 0) break
        if (tiles[i].completedHousingCapacity != 0 || tiles[i].occupants != 0) continue
        val capacity = min(12, tiles[i].zone.capacity)
        start(i, capacity)
        shortage -= capacity
    }
    // Reserve relocation space before admitting newcomers; otherwise immigration would consume
    // every spare bed and make rebuilding impossible even when relocation was initially feasible.
    for (i in eligible) {
        if (shortage <= 0) break
        val t = tiles[i]
        if (i in relocationDestinations || t.construction != null || t.occupants < t.completedHousingCapacity) continue
        val target = housingStages.firstOrNull { it > t.completedHousingCapacity && it <= t.zone.capacity } ?: continue
        val people = homes[i]?.people.orEmpty()
        if (people.size != t.occupants || people.any { it.position != i * TRAVEL_MODE_COUNT }) {
            tiles[i] = t.copy(constructionBlocked = true)
            continue
        }
        // Reserve all destinations before mutation. Families stay together, unrelated residents
        // from the same cell may occupy different homes. Route registries of hosts remain intact.
        val remaining =
            tiles.indices
                .filter { it != i && habitable(it) }
                .associateWith {
                    (tiles[it].habitableHousingCapacity - tiles[it].occupants).coerceAtLeast(0)
                }.toMutableMap()
        val assignments = mutableListOf<Pair<Int, List<Citizen>>>()
        for (group in relocationGroups(people)) {
            val dest =
                remaining.keys
                    .filter { remaining.getValue(it) >= group.size }
                    .minWithOrNull(compareBy<Int> { remaining.getValue(it) }.thenBy { it }) ?: break
            assignments.add(dest to group)
            remaining[dest] = remaining.getValue(dest) - group.size
        }
        if (assignments.sumOf { it.second.size } != people.size) {
            tiles[i] = t.copy(constructionBlocked = true)
            continue
        }
        for ((dest, group) in assignments) {
            val existing = homes[dest]
            val relocated =
                group.map { p ->
                    p.copy(
                        position = dest * TRAVEL_MODE_COUNT,
                        routes = emptyList(),
                        bicycle = p.bicycle?.let { bike -> if (bike.cell == i) bike.copy(cell = dest, parkedSince = null, checkedUntil = null, protectedParking = false) else bike },
                        wellbeing = p.wellbeing.copy(residenceDays = 0, workplace = null),
                    )
                }
            homes[dest] = Household(dest, existing?.people.orEmpty() + relocated, existing?.paths.orEmpty())
            tiles[dest] = tiles[dest].copy(occupants = tiles[dest].occupants + group.size)
            relocationDestinations.add(dest)
            moved = true
        }
        homes.remove(i)
        start(i, target)
        shortage -= target - t.completedHousingCapacity
    }
    // A finished home is filled only against today's demand; no pending migrants are retained.
    for (i in eligible) {
        if (budget <= 0) break
        val t = tiles[i]
        val available = (t.habitableHousingCapacity - t.occupants).coerceAtLeast(0)
        val arrivals = min(budget, available)
        tiles[i] = t.copy(occupants = t.occupants + arrivals)
        budget -= arrivals
    }
    return city.copy(tiles = tiles, households = homes.values.sortedBy { it.cell }, mobility = if (moved) null else city.mobility)
}

/** Connected partners and dependent children form indivisible relocation groups. */
private fun relocationGroups(people: List<Citizen>): List<List<Citizen>> {
    val byId = people.associateBy { it.id }
    val links = people.associate { it.id to mutableSetOf<Long>() }
    for (p in people) {
        val relatives = listOfNotNull(p.life?.partner) + if (p.age < 18) p.life?.parents.orEmpty() else emptyList()
        for (id in relatives.filter { it in byId }) {
            links.getValue(p.id).add(id)
            links.getValue(id).add(p.id)
        }
    }
    val unseen = byId.keys.toMutableSet()
    val groups = mutableListOf<List<Citizen>>()
    for (p in people.sortedBy { it.id }) {
        if (!unseen.remove(p.id)) continue
        val queue = ArrayDeque<Long>()
        queue.add(p.id)
        val group = mutableListOf<Citizen>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            group.add(byId.getValue(id))
            for (other in links.getValue(id)) if (unseen.remove(other)) queue.add(other)
        }
        groups.add(group)
    }
    return groups.sortedWith(compareByDescending<List<Citizen>> { it.size }.thenBy { it.first().id })
}
