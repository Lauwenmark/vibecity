package city.core

import kotlinx.coroutines.*
import kotlin.math.*

/** Independent read-only analysis + parallel cell evolution + one deterministic reduction. */
class Simulation(
    private val catalog: Catalog,
    private val plugins: List<TileRule> = emptyList(),
    private val workers: Int = Runtime.getRuntime().availableProcessors(),
    private val systems: List<CityRule> = emptyList(),
) {
    private data class Network(
        val supplied: BooleanArray,
        val capacity: Int,
    )

    private data class Services(
        val fields: Map<String, IntArray>,
        val pollution: IntArray,
    )

    /** One server step executes one civil day; economic growth and accounting run on its first day. */
    suspend fun step(city: City): City {
        val day = city.nextDay()
        val date = epoch.plusDays(day.toLong())
        val month = (date.year - 1900) * 12 + date.monthValue - 1
        var current = city.copy(month = month)
        current =
            if (date.dayOfMonth == 1) {
                economicStep(current)
            } else {
                val power = network(current, false, null)
                val water = network(current, true, power.supplied)
                current.copy(tiles = current.tiles.mapIndexed { i, tile -> tile.copy(powered = power.supplied[i], watered = water.supplied[i]) }, revision = city.revision + 1)
            }
        val result = MobilitySimulation(catalog, workers).simulate(current.copy(day = day), city.mobility)
        return result.copy(day = day + 1)
    }

    private suspend fun economicStep(city: City): City =
        coroutineScope {
            val powerTask = async(Dispatchers.Default) { network(city, false, null) }
            val accessTask = async(Dispatchers.Default) { transport(city) }
            val power = powerTask.await()
            val servicesTask = async(Dispatchers.Default) { services(city, power.supplied) }
            val waterTask = async(Dispatchers.Default) { network(city, true, power.supplied) }
            val water = waterTask.await()
            val services = servicesTask.await()
            val access = accessTask.await()
            val population =
                city.tiles.filter { it.zone.residential }.sumOf { it.occupants } +
                    city.tiles
                        .withIndex()
                        .filter { (i, t) -> t.anchor == i && t.building != null && power.supplied[i] }
                        .sumOf { catalog[it.value.building!!].residents }
            val jobs = city.tiles.filter { it.zone.commercial || it.zone.industrial }.sumOf { it.occupants }
            val rDemand = (45 + jobs / 4 - population / 8 - (city.rules.taxRate - 7) * 8).coerceIn(-100, 100)
            val cDemand = (25 + population / 10 - city.tiles.filter { it.zone.commercial }.sumOf { it.occupants } / 3 - (city.rules.taxRate - 7) * 6).coerceIn(-100, 100)
            val iDemand = (45 + population / 6 - city.tiles.filter { it.zone.industrial }.sumOf { it.occupants } / 4 - (city.rules.taxRate - 7) * 6).coerceIn(-100, 100)
            val batch = max(64, (city.tiles.size + workers.coerceAtLeast(1) - 1) / workers.coerceAtLeast(1))
            val evolved =
                city.tiles.indices
                    .chunked(batch)
                    .map { indices ->
                        async(Dispatchers.Default) {
                            indices.map { i ->
                                val t = city.tiles[i]

                                fun service(key: String) = services.fields[key]?.get(i) ?: 0
                                val pollution = (services.pollution[i] + (if (t.zone.industrial) t.occupants / 2 else 0) - service("environment") / 2 - if (t.trees) 15 else 0).coerceIn(0, 100)
                                val crime = (t.occupants / 2 + city.stats.unemployment / 2 - service("police")).coerceIn(0, 100)
                                val land = (55 + service("park") / 2 + service("education") / 4 + service("health") / 4 - pollution / 2 - crime / 3).coerceIn(0, 100)
                                val demand =
                                    when {
                                        t.zone.residential -> rDemand
                                        t.zone.commercial -> cDemand + service("commerce") / 3
                                        else -> iDemand + service("industry") / 3
                                    }
                                val capacity = t.zone.capacity * (if (water.supplied[i]) 100 else 60) / 100
                                val desirable = city.surface(i).flat && power.supplied[i] && access[i] && demand > 0 && (t.zone.industrial || pollution < 65)
                                val growth = if (desirable) max(1, city.rules.growthRate + demand / 25 + land / 40) else -max(1, t.occupants / 8)
                                var fire = if (t.fire > 0) (t.fire + 1 - service("fire") / 10).coerceAtLeast(0) else 0
                                if (city.rules.disasters && t.fire == 0 && (t.occupants > 0 || t.building != null) &&
                                    (noise(city.seed, i, city.month) % 100000 == 0 || (city.neighbors(i).any { city.tiles[it].fire > 0 } && noise(city.seed, i, city.month) % 8 == 0))
                                ) {
                                    fire = 1
                                }
                                var result =
                                    t.copy(
                                        powered = power.supplied[i],
                                        watered = water.supplied[i],
                                        pollution = pollution,
                                        crime = crime,
                                        landValue = land,
                                        traffic = 0,
                                        occupants =
                                            if (t.zone == Zone.NONE) {
                                                0
                                            } else if (fire > 0) {
                                                max(0, t.occupants / 2)
                                            } else {
                                                (t.occupants + growth).coerceIn(0, capacity)
                                            },
                                        fire = fire,
                                    )
                                for (rule in plugins) result = rule.apply(TileContext(city, i, catalog), result)
                                require(result.occupants >= 0 && result.elevation in 0..12 && (city.terrain == null || result.elevation == t.elevation)) { "Invalid plugin tile $i" }
                                result
                            }
                        }
                    }.awaitAll()
                    .flatten()
            // Structures burn down atomically, preserving their footprint invariant.
            val burnt =
                evolved
                    .withIndex()
                    .filter { it.value.fire > 6 }
                    .map { if (it.value.anchor >= 0) it.value.anchor else it.index }
                    .toSet()
            val tiles = evolved.mapIndexed { i, t -> if (i in burnt || t.anchor in burnt) t.copy(building = null, anchor = -1, occupants = 0, fire = 0, trees = false) else t }
            val residents =
                tiles.filter { it.zone.residential }.sumOf { it.occupants } +
                    tiles
                        .withIndex()
                        .filter { (i, t) -> t.anchor == i && t.building != null && t.powered }
                        .sumOf { catalog[it.value.building!!].residents }
            val employment = tiles.filter { it.zone.commercial || it.zone.industrial }.sumOf { it.occupants }
            val income = (residents + employment).toLong() * city.rules.taxRate / 12
            val upkeep = tiles.withIndex().filter { (i, t) -> t.anchor == i && t.building != null }.sumOf { catalog[it.value.building!!].upkeep.toLong() } * city.rules.serviceFunding / 100
            val expenses = upkeep + tiles.count { it.road || it.rail || it.subway } / 10 + city.debt / 120
            val stats =
                Statistics(
                    residents,
                    employment,
                    income,
                    expenses,
                    rDemand,
                    cDemand,
                    iDemand,
                    power.capacity,
                    water.capacity,
                    if (residents == 0) 0 else ((residents / 2 - employment).coerceAtLeast(0) * 100 / max(1, residents / 2)),
                )
            val notices =
                buildList {
                    if (city.treasury + income - expenses < 0) add("Déficit : adaptez les impôts, les dépenses ou émettez un emprunt.")
                    if (tiles.any { it.zone != Zone.NONE && !it.powered }) add("Des zones ne sont pas alimentées en électricité.")
                    if (tiles.any { it.fire > 0 }) add("Incendie ! Construisez et financez des casernes.")
                    if (residents > 0 && employment < residents / 3) add("Le chômage freine la ville : développez des emplois.")
                }
            var result =
                city.copy(
                    tiles = tiles,
                    month = city.month,
                    revision = city.revision + 1,
                    treasury = city.treasury + income - expenses,
                    stats = stats,
                    history = (city.history + stats).takeLast(120),
                    notices = notices,
                )
            for (system in systems) result = system.evolve(result, catalog)
            require(result.width == city.width && result.height == city.height && result.revision == city.revision + 1) { "Plugin changed snapshot identity" }
            result
        }

    /** Connected components allocate finite capacity independently; disconnected plants cannot help. */
    private fun network(
        city: City,
        water: Boolean,
        powered: BooleanArray?,
    ): Network {
        val visited = BooleanArray(city.tiles.size)
        val supplied = BooleanArray(city.tiles.size)
        var total = 0

        fun conducts(t: Tile) = if (water) t.pipe || t.building != null || (t.zone != Zone.NONE && t.occupants > 0) else t.powerLine || t.building != null || t.zone != Zone.NONE
        for (start in city.tiles.indices) {
            if (visited[start] || !conducts(city.tiles[start])) continue
            val component = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            var capacity = 0
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component.add(i)
                val t = city.tiles[i]
                if (t.building != null && t.anchor == i) {
                    val def = catalog[t.building]
                    capacity +=
                        if (water) {
                            if (powered!![i]) def.water * if (def.id == "pump" && city.neighbors(i).any { city.tiles[it].water }) 2 else 1 else 0
                        } else {
                            def.power
                        }
                }
                for (n in city.neighbors(i)) {
                    if (!visited[n] && conducts(city.tiles[n])) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }
            total += capacity
            // Each occupied/zoned cell consumes one unit. Infrastructure itself does not exhaust supply.
            component.sorted().forEach { i ->
                val t = city.tiles[i]
                val demand = if (t.zone != Zone.NONE || t.building != null) 1 else 0
                if (capacity > 0) {
                    supplied[i] = true
                    capacity -= demand
                }
            }
        }
        return Network(supplied, total)
    }

    private fun services(
        city: City,
        powered: BooleanArray,
    ): Services {
        val fields = mutableMapOf<String, IntArray>()
        val pollution = IntArray(city.tiles.size)
        city.tiles.forEachIndexed { i, t ->
            val def = if (t.building != null && t.anchor == i) catalog[t.building] else null
            if ((def != null && (def.service.isNotEmpty() || def.pollution > 0)) || (t.zone.industrial && t.occupants > 0)) {
                val radius = def?.radius?.takeIf { it > 0 } ?: 6
                val field = def?.service?.takeIf { it.isNotEmpty() && powered[i] }?.let { fields.getOrPut(it) { IntArray(city.tiles.size) } }
                for (y in max(0, i / city.width - radius)..min(city.height - 1, i / city.width + radius)) {
                    for (x in max(0, i % city.width - radius)..min(city.width - 1, i % city.width + radius)) {
                        val distance = abs(x - i % city.width) + abs(y - i / city.width)
                        if (distance <= radius) {
                            val n = y * city.width + x
                            if (field != null) field[n] = max(field[n], (radius - distance) * city.rules.serviceFunding / radius)
                            pollution[n] += ((def?.pollution ?: t.occupants / 2) * (radius - distance) / radius)
                        }
                    }
                }
            }
        }
        return Services(fields, pollution)
    }

    private fun transport(city: City): BooleanArray {
        val access = BooleanArray(city.tiles.size)
        city.tiles.forEachIndexed { i, t ->
            if (t.road || t.rail || t.subway) {
                for (dy in -3..3) {
                    for (dx in -3..3) {
                        if (abs(dx) + abs(dy) <= 3) {
                            val x = i % city.width + dx
                            val y = i / city.width + dy
                            if (x in 0 until city.width && y in 0 until city.height) access[y * city.width + x] = true
                        }
                    }
                }
            }
        }
        return access
    }
}
