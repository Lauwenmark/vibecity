package city.core

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

@Serializable
enum class Need(
    val label: String,
) {
    WORK("Travail"),
    EDUCATION("Éducation"),
    FOOD("Courses"),
    LEISURE("Loisirs"),
    HEALTH("Soins"),
    REST("Repos"),
}

@Serializable
enum class TravelMode(
    val label: String,
    val capacity: Int,
) {
    WALK("Marche", 0),
    ROAD("Route", 120),
    RAIL("Train", 600),
    METRO("Métro", 900),
}

/** A node is cell * 4 + TravelMode.ordinal. Minutes are cumulative, including transfers. */
@Serializable
data class TravelPath(
    val nodes: List<Int>,
    val minutes: List<Int>,
) {
    val duration: Int get() = minutes.last()
    val destination: Int get() = nodes.last() / 4
}

@Serializable
data class NeedOptions(
    val need: Need,
    val paths: List<Int>,
)

/** Slots identify individual residents without serializing a duplicate itinerary for each one. */
@Serializable
data class MobilityHome(
    val cell: Int,
    val residents: Int,
    val options: List<NeedOptions>,
    val paths: List<TravelPath> = emptyList(),
    val loads: List<TrafficLoad> = emptyList(),
    val completedNeeds: Long = 0,
    val unmetNeeds: Long = 0,
    val days: List<CitizenDay> = emptyList(),
)

/** Sparse hourly counts: one entry per used infrastructure cell and mode, 24 hours per entry. */
@Serializable
data class TrafficLoad(
    val cell: Int,
    val mode: TravelMode,
    val hours: List<Int>,
)

@Serializable
data class Mobility(
    val month: Int,
    val homes: List<MobilityHome>,
    val completedNeeds: Long,
    val unmetNeeds: Long,
    val day: Int = 0,
    val executionVersion: Int = 1,
    val roadPeakOccupancy: Map<Int, Int> = emptyMap(),
    val roadOccupancyLimit: Int = 6,
)

data class Resident(
    val home: Int,
    val slot: Int,
    val seed: Long,
) {
    val id: Long get() = (home.toLong() shl 32) + slot
    val personality: Int get() = noise(seed xor id, home, slot)
    val age: Int get() = 6 + personality % 80
    val label: String get() = "Habitant ${slot + 1} · $age ans"
}

/** Every outing starts at home and returns before the next one; REST stays at home. */
data class NeedJourney(
    val need: Need,
    val path: Int?,
    val scheduled: Boolean,
    val departure: Int,
    val returnDeparture: Int,
    val arrivalHome: Int,
    val status: String,
) {
    val fulfilled: Boolean get() = scheduled && (path != null || need == Need.REST) && status == "Prévu"
}

fun Mobility.itinerary(
    resident: Resident,
    home: MobilityHome,
): List<NeedJourney> {
    require(resident.home == home.cell && resident.slot in 0 until home.residents)
    val executed = home.days.getOrNull(resident.slot)
    if (executed != null) {
        return executed.activities.map { activity ->
            val outbound = executed.legs.firstOrNull { it.need == activity.need && !it.returning }
            val inbound = executed.legs.firstOrNull { it.need == activity.need && it.returning }
            NeedJourney(activity.need, activity.route, activity.scheduled, activity.departure, inbound?.departure ?: activity.departure, inbound?.arrival ?: 1439, outbound?.status ?: activity.reason)
        }
    }
    val variation = resident.personality
    var available = 6 * 60
    return listOf(Need.WORK, Need.EDUCATION, Need.HEALTH, Need.FOOD, Need.LEISURE, Need.REST).map { need ->
        val relevant =
            when (need) {
                Need.WORK -> resident.age in 18..66
                Need.EDUCATION -> resident.age < 18
                else -> true
            }
        val scheduled = relevant && (need != Need.HEALTH || (variation.toLong() + month) % 7 == 0L)
        val options =
            home.options
                .firstOrNull { it.need == need }
                ?.paths
                .orEmpty()
        val path = if (options.isEmpty()) null else options[(variation / (need.ordinal + 1)) % options.size]
        val desired =
            when (need) {
                Need.WORK -> 7 * 60 + variation % 90
                Need.EDUCATION -> 8 * 60 + variation % 30
                Need.FOOD -> 17 * 60 + variation % 90
                Need.LEISURE -> 19 * 60 + variation % 60
                Need.HEALTH -> 10 * 60 + variation % 360
                Need.REST -> 22 * 60
            }
        val duration = path?.let { home.paths[it].duration } ?: 0
        val stay =
            when (need) {
                Need.WORK -> 8 * 60
                Need.EDUCATION -> 6 * 60
                Need.FOOD -> 30
                Need.LEISURE -> 45
                Need.HEALTH -> 30
                Need.REST -> 0
            }
        val departure = max(available, desired)
        val back = departure + duration + stay
        val arrival = back + duration
        val status =
            when {
                !relevant -> "Non applicable à cet âge"
                !scheduled -> "Pas nécessaire aujourd’hui"
                need == Need.REST -> "Prévu"
                path == null -> "Aucune destination accessible"
                arrival > 22 * 60 -> "Temps insuffisant avant le repos"
                else -> "Prévu"
            }
        if (scheduled && status == "Prévu" && need != Need.REST) available = arrival
        NeedJourney(need, path, scheduled, departure, back, arrival, status)
    }
}

/** Position along a planned day, including the return journey and time at the destination. */
fun Mobility.position(
    resident: Resident,
    home: MobilityHome,
    minute: Int,
): Int {
    home.days.getOrNull(resident.slot)?.let { return it.positionAt(minute) / 4 }
    for (trip in itinerary(resident, home)) {
        if (!trip.fulfilled || trip.path == null || minute < trip.departure || minute >= trip.arrivalHome) continue
        val path = home.paths[trip.path]
        if (minute < trip.departure + path.duration) {
            val elapsed = minute - trip.departure
            return path.nodes[path.minutes.indexOfLast { it <= elapsed }.coerceAtLeast(0)] / 4
        }
        if (minute < trip.returnDeparture) return path.destination
        val remaining = path.duration - (minute - trip.returnDeparture)
        return path.nodes[path.minutes.indexOfFirst { it >= remaining }.coerceAtLeast(0)] / 4
    }
    return resident.home
}

/** Derived city-wide totals; paths and source counts remain owned by each home. */
fun Mobility.aggregateLoads(): List<TrafficLoad> {
    val totals = sortedMapOf<Int, IntArray>()
    for (home in homes) {
        for (load in home.loads) {
            val hours = totals.getOrPut(load.cell * 4 + load.mode.ordinal) { IntArray(24) }
            for (hour in 0..23) hours[hour] += load.hours[hour]
        }
    }
    return totals.map { (node, hours) -> TrafficLoad(node / 4, TravelMode.entries[node % 4], hours.toList()) }
}

/** A bounded number of workers claim homes dynamically; each worker owns its result list. */
internal suspend fun calculateHomes(
    homes: List<MobilityHome>,
    workers: Int,
    calculate: suspend (MobilityHome) -> MobilityHome,
): List<MobilityHome> =
    coroutineScope {
        val next = AtomicInteger()
        List(workers.coerceAtLeast(1).coerceAtMost(homes.size)) {
            async(Dispatchers.Default) {
                val results = mutableListOf<MobilityHome>()
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= homes.size) break
                    results.add(calculate(homes[index]))
                }
                results
            }
        }.awaitAll().flatten().sortedBy { it.cell }
    }

/** Built once before workers start; all graph fields are read-only during searches. */
internal class MobilityRouter(
    val city: City,
    val catalog: Catalog,
    previous: Mobility?,
) {
    private val size = city.tiles.size
    private val neighbors = Array(size) { city.neighbors(it) }
    private val transfers = Array(size) { mutableListOf<Int>() }
    private val delay = IntArray(size * 4)
    private val destinations =
        Need.entries.associateWith { need ->
            city.tiles.indices.filter { i ->
                val t = city.tiles[i]
                val def = t.building?.takeIf { t.anchor == i }?.let { catalog[it] }
                t.fire == 0 &&
                    when (need) {
                        Need.WORK -> (t.zone.commercial || t.zone.industrial) && t.occupants > 0 && t.powered
                        Need.FOOD -> t.zone.commercial && t.occupants > 0 && t.powered
                        Need.EDUCATION -> def?.service == "education" && t.powered && city.rules.serviceFunding > 0
                        Need.LEISURE -> def?.service == "park" && t.powered && city.rules.serviceFunding > 0
                        Need.HEALTH -> def?.service == "health" && t.powered && city.rules.serviceFunding > 0
                        Need.REST -> false
                    }
            }
        }

    init {
        previous?.aggregateLoads()?.forEach { load ->
            if (load.cell in city.tiles.indices && load.mode.capacity > 0) {
                delay[load.cell * 4 + load.mode.ordinal] = ((load.hours.max().toLong() * 2 / load.hourlyCapacity(city)) - 1).coerceIn(0, 8).toInt()
            }
        }
        city.tiles.forEachIndexed { i, tile ->
            val mode =
                when (tile.building) {
                    "station" -> TravelMode.RAIL
                    "metro" -> TravelMode.METRO
                    else -> null
                }
            if (mode != null && tile.powered && city.rules.serviceFunding > 0) {
                for (n in neighbors[i] + i) {
                    val connected = if (mode == TravelMode.RAIL) city.tiles[n].rail else city.tiles[n].subway
                    if (connected) {
                        val node = n * 4 + mode.ordinal
                        transfers[i].add(node)
                    }
                }
            }
        }
    }

    private fun walkable(i: Int) = (!city.tiles[i].water || city.tiles[i].road) && city.tiles[i].fire == 0

    fun edges(
        node: Int,
        congested: Boolean = false,
    ): List<Pair<Int, Int>> {
        val i = node / 4
        if (i !in city.tiles.indices || city.tiles[i].fire > 0) return emptyList()
        val mode = TravelMode.entries[node % 4]
        return buildList {
            when (mode) {
                TravelMode.WALK -> {
                    if (walkable(i)) {
                        for (n in neighbors[i]) if (walkable(n) && abs(city.surface(i).minimum - city.surface(n).minimum) <= 1) add(n * 4 to 5)
                        if (city.tiles[i].road) add(i * 4 + 1 to 2)
                        for (target in transfers[i]) if (city.tiles[target / 4].fire == 0) add(target to 5)
                    }
                }

                TravelMode.ROAD -> {
                    if (city.tiles[i].road) {
                        add(i * 4 to 2)
                        for (n in neighbors[i]) if (city.tiles[n].road && city.tiles[n].fire == 0 && city.transportConnects(i, n)) add(n * 4 + 1 to (2 + if (congested) delay[n * 4 + 1] else 0))
                    }
                }

                TravelMode.RAIL, TravelMode.METRO -> {
                    val exists = if (mode == TravelMode.RAIL) city.tiles[i].rail else city.tiles[i].subway
                    if (exists) {
                        for (n in neighbors[i]) {
                            val connects = if (mode == TravelMode.RAIL) city.tiles[n].rail && city.transportConnects(i, n) else city.tiles[n].subway
                            if (connects && city.tiles[n].fire == 0) add(n * 4 + mode.ordinal to (1 + if (congested) delay[n * 4 + mode.ordinal] else 0))
                        }
                        for (n in neighbors[i] + i) if (node in transfers[n] && walkable(n)) add(n * 4 to 5)
                    }
                }
            }
        }
    }

    fun duration(
        from: Int,
        to: Int,
    ): Int? = edges(from).firstOrNull { it.first == to }?.second

    fun valid(path: TravelPath): Boolean = path.nodes.isNotEmpty() && path.nodes.all { it in 0 until size * 4 } && path.nodes.zipWithNext().all { (a, b) -> duration(a, b) != null }

    fun serves(
        need: Need,
        destination: Int,
    ): Boolean = destination in destinations.getValue(need)

    fun path(nodes: List<Int>): TravelPath? {
        if (nodes.isEmpty()) return null
        var elapsed = 0
        val minutes = mutableListOf(0)
        for ((a, b) in nodes.zipWithNext()) {
            elapsed += duration(a, b) ?: return null
            minutes.add(elapsed)
        }
        return TravelPath(nodes, minutes)
    }

    private suspend fun search(
        start: Int,
        goal: Int? = null,
        blocked: Set<Int> = emptySet(),
    ): Pair<IntArray, IntArray> {
        val distance = IntArray(size * 4) { Int.MAX_VALUE }
        val parent = IntArray(size * 4) { -1 }

        data class Visit(
            val node: Int,
            val cost: Int,
        )
        val queue = PriorityQueue<Visit>(compareBy<Visit> { it.cost }.thenBy { it.node })
        distance[start] = 0
        queue.add(Visit(start, 0))
        var visits = 0
        while (queue.isNotEmpty()) {
            if (visits++ % 256 == 0) currentCoroutineContext().ensureActive()
            val visit = queue.remove()
            if (distance[visit.node] != visit.cost) continue
            if (visit.node == goal) break
            for ((next, cost) in edges(visit.node, true)) {
                if (next in blocked) continue
                val total = visit.cost + cost
                if (total < distance[next] && total <= 180) {
                    distance[next] = total
                    parent[next] = visit.node
                    queue.add(Visit(next, total))
                }
            }
        }
        return distance to parent
    }

    private fun reconstruct(
        target: Int,
        distance: IntArray,
        parent: IntArray,
    ): TravelPath? {
        if (distance[target] == Int.MAX_VALUE) return null
        val nodes = mutableListOf<Int>()
        var node = target
        while (node >= 0) {
            nodes.add(node)
            node = parent[node]
        }
        nodes.reverse()
        return path(nodes)
    }

    suspend fun route(
        start: Int,
        goal: Int,
        blocked: Set<Int> = emptySet(),
    ): TravelPath? {
        val (distance, parent) = search(start, goal, blocked)
        return reconstruct(goal, distance, parent)
    }

    suspend fun from(home: Int): Map<Need, List<TravelPath>> {
        val (distance, parent) = search(home * 4)
        return destinations.filterKeys { it != Need.REST }.mapValues { (_, targets) ->
            targets
                .filter { distance[it * 4] < Int.MAX_VALUE }
                .sortedWith(compareBy<Int> { distance[it * 4] }.thenBy { it })
                .take(2)
                .mapNotNull { reconstruct(it * 4, distance, parent) }
        }
    }
}

/** Validate serialized derived data before exposing it to the inspector or the next simulation. */
fun Mobility.validate(
    city: City,
    catalog: Catalog,
) {
    require(month == city.month && completedNeeds >= 0 && unmetNeeds >= 0 && day >= 0 && executionVersion == 1)
    require(homes.map { it.cell }.distinct().size == homes.size)
    for (home in homes) {
        require(home.cell in city.tiles.indices && home.residents > 0)
        val tile = city.tiles[home.cell]
        val expected =
            if (tile.zone.residential) {
                tile.occupants
            } else if (tile.anchor == home.cell && tile.powered && tile.building != null) {
                catalog[tile.building].residents
            } else {
                0
            }
        require(home.residents == expected)
        require(
            home.options
                .map { it.need }
                .distinct()
                .size == home.options.size,
        )
        for (option in home.options) {
            require(option.paths.size <= 2 && option.need != Need.REST)
            for (index in option.paths) {
                require(index in home.paths.indices)
                require(home.paths[index].nodes.firstOrNull() == home.cell * 4)
            }
        }
        require(home.completedNeeds >= 0 && home.unmetNeeds >= 0)
        for (path in home.paths) {
            require(path.nodes.isNotEmpty() && path.nodes.size == path.minutes.size)
            require(path.nodes.all { it in 0 until city.tiles.size * 4 })
            require(path.minutes.first() == 0 && path.minutes.last() in 0..1440)
            require(path.minutes.zipWithNext().all { (a, b) -> b > a })
            require(path.nodes.last() % 4 == 0)
        }
        require(
            home.loads
                .map { it.cell * 4 + it.mode.ordinal }
                .distinct()
                .size == home.loads.size,
        )
        for (load in home.loads) {
            require(load.cell in city.tiles.indices && load.mode.capacity > 0)
            require(load.hours.size == 24 && load.hours.all { it >= 0 })
        }
    }
    require(completedNeeds == homes.sumOf { it.completedNeeds } && unmetNeeds == homes.sumOf { it.unmetNeeds })
}

/** ROAD throughput is configurable; other modes keep their nominal display capacity. */
fun TrafficLoad.hourlyCapacity(city: City): Int = if (mode == TravelMode.ROAD) city.trafficRules.roadEntriesPerMinute * 60 else mode.capacity
