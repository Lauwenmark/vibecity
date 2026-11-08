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

/** Independent routing and daily assignment per home, with structured cancellation. */
class MobilitySimulation(
    private val catalog: Catalog,
    private val workers: Int = Runtime.getRuntime().availableProcessors(),
) {
    suspend fun simulate(
        city: City,
        previous: Mobility? = city.mobility,
    ): City =
        withContext(Dispatchers.Default) {
            val router = MobilityRouter(city, catalog, previous)
            val residents =
                city.tiles.mapIndexedNotNull { i, tile ->
                    val count =
                        if (tile.zone.residential) {
                            tile.occupants
                        } else if (tile.anchor == i && tile.powered && tile.building != null) {
                            catalog[tile.building].residents
                        } else {
                            0
                        }
                    if (count > 0) MobilityHome(i, count, emptyList()) else null
                }
            val homes =
                calculateHomes(residents, workers) { home ->
                    val paths = mutableListOf<TravelPath>()
                    val options =
                        router.from(home.cell).map { (need, choices) ->
                            NeedOptions(
                                need,
                                choices.map { path ->
                                    paths.add(path)
                                    paths.lastIndex
                                },
                            )
                        }
                    countDay(city, home.copy(options = options, paths = paths))
                }
            val mobility = Mobility(city.month, homes, homes.sumOf { it.completedNeeds }, homes.sumOf { it.unmetNeeds })
            val peak = IntArray(city.tiles.size)
            for (load in mobility.aggregateLoads()) peak[load.cell] = max(peak[load.cell], (load.hours.max().toLong() * 100 / load.mode.capacity).coerceAtMost(100).toInt())
            city.copy(tiles = city.tiles.mapIndexed { i, tile -> tile.copy(traffic = peak[i]) }, mobility = mobility)
        }

    private suspend fun countDay(
        city: City,
        home: MobilityHome,
    ): MobilityHome {
        val planned = Mobility(city.month, emptyList(), 0, 0)
        val loads = sortedMapOf<Int, IntArray>()
        var completed = 0L
        var unmet = 0L

        fun record(
            path: TravelPath,
            start: Int,
            returning: Boolean,
        ) {
            for (j in path.nodes.indices) {
                val node = path.nodes[j]
                if (node % 4 == TravelMode.WALK.ordinal) continue
                val minute = start + if (returning) path.duration - path.minutes[j] else path.minutes[j]
                if (minute in 0 until 1440) loads.getOrPut(node) { IntArray(24) }[minute / 60]++
            }
        }
        for (slot in 0 until home.residents) {
            if (slot % 64 == 0) currentCoroutineContext().ensureActive()
            for (trip in planned.itinerary(Resident(home.cell, slot, city.seed), home)) {
                if (!trip.scheduled) continue
                if (!trip.fulfilled) {
                    unmet++
                    continue
                }
                completed++
                if (trip.path != null) {
                    record(home.paths[trip.path], trip.departure, false)
                    record(home.paths[trip.path], trip.returnDeparture, true)
                }
            }
        }
        return home.copy(
            loads = loads.map { (node, hours) -> TrafficLoad(node / 4, TravelMode.entries[node % 4], hours.toList()) },
            completedNeeds = completed,
            unmetNeeds = unmet,
        )
    }
}

/** Built once before workers start; all graph fields are read-only during searches. */
private class MobilityRouter(
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
                delay[load.cell * 4 + load.mode.ordinal] = ((load.hours.max().toLong() * 2 / load.mode.capacity) - 1).coerceIn(0, 8).toInt()
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

    suspend fun from(home: Int): Map<Need, List<TravelPath>> {
        val distance = IntArray(size * 4) { Int.MAX_VALUE }
        val parent = IntArray(size * 4) { -1 }

        data class Visit(
            val node: Int,
            val cost: Int,
        )
        val queue = PriorityQueue<Visit>(compareBy<Visit> { it.cost }.thenBy { it.node })
        distance[home * 4] = 0
        queue.add(Visit(home * 4, 0))

        fun relax(
            from: Int,
            to: Int,
            cost: Int,
        ) {
            val next = distance[from] + cost
            // Three hours one way is the maximum trip residents are willing to plan.
            if (next < distance[to] && next <= 180) {
                distance[to] = next
                parent[to] = from
                queue.add(Visit(to, next))
            }
        }
        var visits = 0
        while (queue.isNotEmpty()) {
            if (visits++ % 256 == 0) currentCoroutineContext().ensureActive()
            val visit = queue.remove()
            val node = visit.node
            if (distance[node] != visit.cost) continue
            val i = node / 4
            val mode = TravelMode.entries[node % 4]
            val t = city.tiles[i]
            when (mode) {
                TravelMode.WALK -> {
                    if (!walkable(i)) continue
                    for (n in neighbors[i]) {
                        if (walkable(n) && abs(city.surface(i).minimum - city.surface(n).minimum) <= 1) relax(node, n * 4, 5)
                    }
                    if (t.road) relax(node, i * 4 + 1, 2)
                    for (target in transfers[i]) relax(node, target, 5)
                }

                TravelMode.ROAD -> {
                    relax(node, i * 4, 2)
                    for (n in neighbors[i]) if (city.tiles[n].road && city.tiles[n].fire == 0 && city.transportConnects(i, n)) relax(node, n * 4 + 1, 2 + delay[n * 4 + 1])
                }

                TravelMode.RAIL, TravelMode.METRO -> {
                    for (n in neighbors[i]) {
                        val connected = if (mode == TravelMode.RAIL) city.tiles[n].rail && city.transportConnects(i, n) else city.tiles[n].subway
                        if (connected && city.tiles[n].fire == 0) relax(node, n * 4 + mode.ordinal, 1 + delay[n * 4 + mode.ordinal])
                    }
                    for (n in neighbors[i] + i) if (node in transfers[n]) relax(node, n * 4, 5)
                }
            }
        }
        return destinations.filterKeys { it != Need.REST }.mapValues { (_, targets) ->
            targets.filter { distance[it * 4] < Int.MAX_VALUE }.sortedWith(compareBy<Int> { distance[it * 4] }.thenBy { it }).take(2).map { target ->
                val nodes = mutableListOf<Int>()
                var node = target * 4
                while (node >= 0) {
                    nodes.add(node)
                    node = parent[node]
                }
                nodes.reverse()
                TravelPath(nodes, nodes.map { distance[it] })
            }
        }
    }
}

/** Validate serialized derived data before exposing it to the inspector or the next simulation. */
fun Mobility.validate(
    city: City,
    catalog: Catalog,
) {
    require(month == city.month && completedNeeds >= 0 && unmetNeeds >= 0)
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
            require(path.minutes.first() == 0 && path.minutes.last() in 0..180)
            require(path.minutes.zipWithNext().all { (a, b) -> b > a })
            require(path.nodes.first() % 4 == 0 && path.nodes.last() % 4 == 0)
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
