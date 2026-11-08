package city.core

import kotlinx.coroutines.*
import java.util.PriorityQueue
import kotlin.math.*

/** Frozen router from 94ce800: independent behavioral oracle for allocation optimizations. */
internal class ReferenceMobilityRouter(
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
