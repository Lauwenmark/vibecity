package city.core

import kotlinx.coroutines.*
import java.util.PriorityQueue
import kotlin.math.*

/** The registry is home-local. Only its owning planning worker, then the executor, may mutate it. */
internal class HomeRoutes(
    paths: List<TravelPath>,
) {
    val paths = paths.toMutableList()
    private val indices = paths.withIndex().associate { it.value.nodes to it.index }.toMutableMap()

    fun intern(path: TravelPath): Int =
        indices.getOrPut(path.nodes) {
            paths.add(path)
            paths.lastIndex
        }
}

/** Planning-local index. Append order is preserved for deterministic weighted route selection. */
internal class RouteMemoryIndex(
    initial: List<LearnedRoute>,
) {
    private val groups = arrayOfNulls<MutableList<LearnedRoute>>(7 * Need.entries.size * 2)
    private val ordered = ArrayList<LearnedRoute>(initial.size)
    val routes: List<LearnedRoute> get() = ordered

    init {
        initial.forEach(::add)
    }

    private fun key(
        weekday: Int,
        need: Need,
        returning: Boolean,
    ): Int = (weekday * Need.entries.size + need.ordinal) * 2 + if (returning) 1 else 0

    fun get(
        weekday: Int,
        need: Need,
        returning: Boolean,
    ): List<LearnedRoute> = groups[key(weekday, need, returning)] ?: emptyList()

    fun add(route: LearnedRoute) {
        val key = key(route.weekday, route.need, route.returning)
        val group = groups[key] ?: ArrayList<LearnedRoute>().also { groups[key] = it }
        group.add(route)
        ordered.add(route)
    }
}

internal data class PreparedHome(
    val home: MobilityHome,
    val people: List<Citizen>,
    val registry: HomeRoutes,
)

class MobilitySimulation(
    private val catalog: Catalog,
    private val workers: Int = Runtime.getRuntime().availableProcessors(),
) {
    suspend fun simulate(
        city: City,
        previous: Mobility? = city.mobility,
    ): City =
        withContext(Dispatchers.Default) {
            val day = city.nextDay()
            val router = MobilityRouter(city, catalog, previous)
            val populated = reconcileCitizens(city, catalog)
            val nextId = populated.nextCitizenId
            val households = populated.households
            val prepared = arrayOfNulls<PreparedHome>(households.size)
            val byCell = households.withIndex().associate { it.value.cell to it.index }
            // Workers write disjoint slots; no mutable path registry or citizen is shared between homes.
            calculateHomes(households.map { MobilityHome(it.cell, it.people.size, emptyList()) }, workers) { home ->
                val index = byCell.getValue(home.cell)
                val household = households[index]
                val registry = HomeRoutes(household.paths)
                val destinations = router.destinationsFrom(home.cell)
                val choices = destinations.needs
                val educationPaths = destinations.education
                val educationOptions = educationPaths.mapValues { (_, paths) -> paths.map(registry::intern) }
                val options = choices.map { (need, paths) -> NeedOptions(need, paths.map(registry::intern)) }
                val validPaths = registry.paths.map(router::valid)
                val prune = epoch.plusDays(day.toLong()).dayOfMonth == 1
                val workPaths = mutableMapOf<Int, Int?>()
                for (job in household.people.mapNotNull { it.wellbeing.workplace }.distinct()) {
                    if (router.serves(Need.WORK, job)) workPaths[job] = router.route(home.cell * 4, job * 4)?.let(registry::intern)
                }
                val campusPaths = mutableMapOf<Int, Int?>()
                for (campus in household.people.mapNotNull { it.life?.study?.campus }.distinct()) {
                    campusPaths[campus] = if (router.serves(Need.EDUCATION, campus) && city.tiles[campus].building == "college") router.route(home.cell * 4, campus * 4)?.let(registry::intern) else null
                }
                val routeIndexes = ArrayList<RouteMemoryIndex>(household.people.size)
                val people =
                    household.people.map { resident ->
                        var person = selectStudies(if (prune) resident.prune(city.trafficRules) else resident, city, educationPaths["college"].orEmpty().firstOrNull()?.destination)
                        val learningPaths =
                            person.life?.study?.campus?.let { campus ->
                                if (campus in campusPaths) listOfNotNull(campusPaths[campus]) else educationOptions["college"].orEmpty().filter { registry.paths[it].destination == campus }
                            } ?: educationOptions.filterKeys { person.acceptsEducation(it) }.values.flatten()
                        val employer = person.wellbeing.workplace?.takeIf { router.serves(Need.WORK, it) }
                        val personalOptions =
                            options.map { option ->
                                when {
                                    option.need == Need.EDUCATION -> option.copy(paths = learningPaths)
                                    option.need == Need.WORK && employer != null -> option.copy(paths = listOfNotNull(workPaths[employer]))
                                    else -> option
                                }
                            }
                        val valid =
                            person.routes.filter { memory ->
                                val path = registry.paths.getOrNull(memory.path) ?: return@filter false
                                if (!validPaths.getOrElse(memory.path) { router.valid(path) }) return@filter false
                                val destination = if (memory.returning) path.nodes.first() / 4 else path.destination
                                val campus = person.life?.study?.campus
                                val suitable =
                                    when (memory.need) {
                                        Need.EDUCATION -> person.acceptsEducation(city.tiles[destination].building) && (campus == null || destination == campus)
                                        Need.WORK -> employer == null || destination == employer
                                        else -> true
                                    }
                                suitable && router.serves(memory.need, destination)
                            }
                        val memories = RouteMemoryIndex(valid)
                        routeIndexes.add(memories)
                        for (weekday in 0..6) {
                            for (option in personalOptions) {
                                if (!applicable(person, option.need, weekday) || option.paths.isEmpty()) continue
                                if (memories.get(weekday, option.need, false).isEmpty()) {
                                    val path = option.paths[noise(city.seed xor person.id, option.need.ordinal, weekday) % option.paths.size]
                                    memories.add(LearnedRoute(weekday, option.need, false, path, city.trafficRules.initialScore))
                                    router.path(registry.paths[path].nodes.reversed())?.let { back ->
                                        val backId = registry.intern(back)
                                        if (memories.get(weekday, option.need, true).none { it.path == backId }) {
                                            memories.add(LearnedRoute(weekday, option.need, true, backId, city.trafficRules.initialScore))
                                        }
                                    }
                                }
                            }
                        }
                        person = person.copy(routes = memories.routes)
                        person
                    }
                val activities =
                    people.mapIndexed { slot, person ->
                        val memories = routeIndexes[slot]
                        val variation = noise(city.seed xor person.id, home.cell, 0)
                        val list =
                            listOf(Need.WORK, Need.EDUCATION, Need.HEALTH, Need.FOOD, Need.LEISURE, Need.REST).map { need ->
                                val scheduled = applicable(person, need, day % 7) && (need != Need.HEALTH || (person.id + day) % 7 == 0L)
                                val selected = chooseRoute(memories.get(day % 7, need, false), city.seed xor person.id xor (day.toLong() shl 24) xor need.ordinal.toLong())
                                val departure =
                                    when (need) {
                                        Need.WORK -> 7 * 60 + variation % 90
                                        Need.EDUCATION -> (if (person.isStudent) 8 else 10) * 60 + variation % 30
                                        Need.HEALTH -> 10 * 60 + variation % 360
                                        Need.FOOD -> (if (day % 7 >= 5) 11 else 17) * 60 + variation % 90
                                        Need.LEISURE -> (if (day % 7 >= 5) 15 else 19) * 60 + variation % 60
                                        Need.REST -> 22 * 60
                                    }
                                val stay =
                                    when (need) {
                                        Need.WORK -> 480
                                        Need.EDUCATION -> if (person.isStudent) 360 else 60
                                        Need.FOOD, Need.HEALTH -> 30
                                        Need.LEISURE -> 45
                                        Need.REST -> 0
                                    }
                                val returnChoice =
                                    selected?.let { chosen ->
                                        val destination = registry.paths[chosen.path].nodes.last()
                                        chooseRoute(memories.get(day % 7, need, true).filter { registry.paths[it.path].nodes.first() == destination }, city.seed xor person.id xor day.toLong() xor (need.ordinal.toLong() shl 32))?.path
                                            ?: router.path(registry.paths[chosen.path].nodes.reversed())?.let(registry::intern)
                                    }
                                PlannedActivity(
                                    need,
                                    departure,
                                    stay,
                                    selected?.path,
                                    returnChoice,
                                    scheduled,
                                    when {
                                        !scheduled -> "Non prévu ce jour"
                                        need == Need.REST -> "Repos au domicile"
                                        selected == null -> "Aucune destination accessible"
                                        else -> "Prévu"
                                    },
                                )
                            }
                        CitizenDay(person.id, person.position, list)
                    }
                val result = home.copy(options = options, paths = registry.paths, days = activities)
                prepared[index] = PreparedHome(result, people, registry)
                result
            }
            val executor = DayExecutor(city, router, prepared.map { requireNotNull(it) }, day)
            executor.execute()
            val finalHomes = executor.homes()
            val mobility = Mobility(city.month, finalHomes, finalHomes.sumOf { it.completedNeeds }, finalHomes.sumOf { it.unmetNeeds }, day = day, roadPeakOccupancy = executor.peaks(), roadOccupancyLimit = city.trafficRules.roadSlots)
            val peak = IntArray(city.tiles.size)
            for (load in mobility.aggregateLoads()) peak[load.cell] = max(peak[load.cell], (load.hours.max().toLong() * 100 / load.hourlyCapacity(city)).coerceAtMost(100).toInt())
            city.copy(mobility = mobility, households = executor.households(), nextCitizenId = nextId, tiles = city.tiles.mapIndexed { i, tile -> tile.copy(traffic = peak[i]) })
        }
}

internal fun applicable(
    person: Citizen,
    need: Need,
    weekday: Int,
): Boolean =
    when (need) {
        Need.WORK -> person.isWorker && weekday < 5
        Need.EDUCATION -> if (person.isStudent) weekday < 5 else person.age >= 18 && weekday == (person.id % 7).toInt()
        Need.FOOD, Need.LEISURE, Need.HEALTH -> person.age >= 3
        Need.REST -> true
    }

/** One authoritative event queue owns every road reservation. Waiting agents retain their upstream slot. */
internal class DayExecutor(
    val city: City,
    val router: MobilityRouter,
    val prepared: List<PreparedHome>,
    val day: Int,
) {
    private val rules = city.trafficRules
    private val occupancy = IntArray(city.tiles.size)
    private val blockedNodes = BooleanArray(city.tiles.size * 4)
    private val peak = IntArray(city.tiles.size)
    private val admittedAt = IntArray(city.tiles.size) { -1 }
    private val admitted = IntArray(city.tiles.size)
    private val loads = List(prepared.size) { sortedMapOf<Int, IntArray>() }
    private val completed = LongArray(prepared.size)
    private val unmet = LongArray(prepared.size)

    private data class ActiveLeg(
        val need: Need,
        val returning: Boolean,
        val planned: Int,
        val departure: Int,
        var path: TravelPath,
        var index: Int = 0,
        var waiting: Int = 0,
        var waitSince: Int = -1,
        var nextDecision: Int = 0,
        var rerouted: Boolean = false,
        var selectedMemory: Int? = planned,
        var attemptWait: Int = 0,
        val nodes: MutableList<Int> = mutableListOf(),
        val times: MutableList<Int> = mutableListOf(),
        val decisions: MutableList<RouteDecision> = mutableListOf(),
    )

    private class Agent(
        val home: Int,
        val slot: Int,
        var person: Citizen,
        var plan: CitizenDay,
    ) {
        var activity = 0
        var node = plan.startNode
        var leg: ActiveLeg? = null
        var returningAt = -1
        var recovery = false
        val legs = mutableListOf<ExecutedLeg>()
    }

    private data class Event(
        val minute: Int,
        val priority: Int,
        val agent: Agent,
    )

    private val queue =
        PriorityQueue<Event> { a, b ->
            val minute = a.minute.compareTo(b.minute)
            val priority = a.priority.compareTo(b.priority)
            when {
                minute != 0 -> {
                    minute
                }

                priority != 0 -> {
                    priority
                }

                else -> {
                    a.agent.person.id
                        .compareTo(b.agent.person.id)
                }
            }
        }
    private val agents = prepared.flatMapIndexed { h, home -> home.people.mapIndexed { slot, person -> Agent(h, slot, person, home.home.days[slot]) } }

    private val agentsByHome = agents.groupBy { it.home }

    private fun enqueue(
        agent: Agent,
        minute: Int,
    ) {
        if (minute < 1440) queue.add(Event(minute, agent.leg?.waitSince?.takeIf { it >= 0 } ?: minute, agent))
    }

    private fun road(node: Int) = node % 4 == TravelMode.ROAD.ordinal

    private fun registry(agent: Agent) = prepared[agent.home].registry

    private fun homeNode(agent: Agent) = prepared[agent.home].home.cell * 4

    suspend fun execute() {
        for (agent in agents) {
            // Clear invalidated vehicle modes in place after construction; never teleport to a domicile.
            if (road(agent.node) && !city.tiles[agent.node / 4].road) {
                agent.node = agent.node / 4 * 4
                agent.plan = agent.plan.copy(startNode = agent.node)
            }
            if (road(agent.node)) {
                occupancy[agent.node / 4]++
                peak[agent.node / 4] = occupancy[agent.node / 4]
            }
            if (agent.node != homeNode(agent)) {
                agent.recovery = true
                val path = router.route(agent.node, homeNode(agent))
                if (path != null) start(agent, Need.REST, true, registry(agent).intern(path), 0)
            } else {
                nextActivity(agent, 0)
            }
        }
        require(occupancy.all { it <= rules.roadSlots }) { "La capacité configurée est inférieure à l’occupation conservée" }
        var events = 0
        while (queue.isNotEmpty()) {
            if (events++ % 256 == 0) currentCoroutineContext().ensureActive()
            val event = queue.remove()
            val agent = event.agent
            if (agent.leg != null) {
                move(agent, event.minute)
            } else if (agent.returningAt >= 0) {
                returnHome(agent, event.minute)
            } else {
                nextActivity(agent, event.minute)
            }
        }
        for (agent in agents) {
            if (agent.leg != null) finish(agent, null)
            // Every still-pending scheduled activity is unsatisfied; no midnight teleportation.
            val finished =
                agent.legs
                    .filter {
                        !it.returning && it.arrival != null && it.arrival + (
                            agent.plan.activities
                                .firstOrNull { a -> a.need == it.need }
                                ?.stay ?: 0
                        ) < 1440
                    }.map { it.need }
                    .toSet()
            val activities = agent.plan.activities.filter { it.scheduled }
            completed[agent.home] += activities.count { if (it.need == Need.REST) agent.node == homeNode(agent) else it.need in finished }
            unmet[agent.home] += activities.count { if (it.need == Need.REST) agent.node != homeNode(agent) else it.need !in finished }
            agent.person = agent.person.copy(position = agent.node)
        }
    }

    private suspend fun nextActivity(
        agent: Agent,
        minute: Int,
    ) {
        while (agent.activity < agent.plan.activities.size) {
            val activity = agent.plan.activities[agent.activity]
            if (!activity.scheduled || activity.need == Need.REST || activity.route == null) {
                agent.activity++
                continue
            }
            val departure = max(minute, activity.departure)
            if (departure >= 1320 || agent.node != homeNode(agent)) return
            if (departure > minute) {
                enqueue(agent, departure)
                return
            }
            start(agent, activity.need, false, activity.route, minute)
            return
        }
    }

    private fun start(
        agent: Agent,
        need: Need,
        returning: Boolean,
        pathId: Int,
        minute: Int,
    ) {
        val path = registry(agent).paths[pathId]
        check(path.nodes.first() == agent.node)
        agent.returningAt = -1
        agent.leg = ActiveLeg(need, returning, pathId, minute, path, nextDecision = minute + rules.reconsiderMinutes, nodes = mutableListOf(agent.node), times = mutableListOf(0))
        enqueue(agent, minute + if (path.nodes.size > 1) path.minutes[1] else 0)
    }

    private suspend fun returnHome(
        agent: Agent,
        minute: Int,
    ) {
        val need = agent.plan.activities[agent.activity].need
        val known = agent.person.routes.filter { it.weekday == day % 7 && it.need == need && it.returning && registry(agent).paths[it.path].nodes.first() == agent.node }
        val chosen = agent.plan.activities[agent.activity].returnRoute ?: chooseRoute(known, city.seed xor agent.person.id xor minute.toLong())?.path
        val pathId = chosen ?: router.route(agent.node, homeNode(agent))?.let { registry(agent).intern(it) }
        if (pathId != null) start(agent, need, true, pathId, minute)
    }

    private suspend fun move(
        agent: Agent,
        minute: Int,
    ) {
        val leg = agent.leg!!
        if (leg.index == leg.path.nodes.lastIndex) {
            arrive(agent, minute)
            return
        }
        val next = leg.path.nodes[leg.index + 1]
        val cell = next / 4
        if (road(next)) {
            if (admittedAt[cell] != minute) {
                admittedAt[cell] = minute
                admitted[cell] = 0
            }
            if (occupancy[cell] >= rules.roadSlots || admitted[cell] >= rules.roadEntriesPerMinute) {
                leg.waiting++
                leg.attemptWait++
                if (!agent.recovery && leg.attemptWait % 5 == 0) leg.selectedMemory?.let { adjust(agent, it, leg.need, leg.returning, -1) }
                if (leg.waitSince < 0) leg.waitSince = minute
                var retry = 1
                if (minute >= leg.nextDecision) {
                    leg.nextDecision = minute + rules.reconsiderMinutes + (agent.person.id % 5).toInt()
                    val draw = noise(city.seed xor agent.person.id, minute, day) % 100
                    if (draw < rules.reroutePercent) retry = reconsider(agent, minute, next) ?: 1 else leg.decisions.add(RouteDecision(minute, agent.node, "Patienter"))
                }
                enqueue(agent, minute + retry)
                return
            }
            occupancy[cell]++
            peak[cell] = max(peak[cell], occupancy[cell])
            admitted[cell]++
        }
        if (road(agent.node)) occupancy[agent.node / 4]--
        agent.node = next
        leg.index++
        leg.nodes.add(next)
        leg.times.add(minute - leg.departure)
        leg.waitSince = -1
        if (next % 4 != TravelMode.WALK.ordinal) loads[agent.home].getOrPut(next) { IntArray(24) }[minute / 60]++
        if (leg.index == leg.path.nodes.lastIndex) arrive(agent, minute) else enqueue(agent, minute + leg.path.minutes[leg.index + 1] - leg.path.minutes[leg.index])
    }

    private suspend fun reconsider(
        agent: Agent,
        minute: Int,
        blockedNext: Int,
    ): Int? {
        val leg = agent.leg!!
        val blocked = blockedNodes
        blocked.fill(false)
        for (cell in occupancy.indices) {
            if (occupancy[cell] >= rules.roadSlots) {
                blocked[cell * 4] = true
                blocked[cell * 4 + 1] = true
            }
        }
        blocked[blockedNext] = true
        blocked[blockedNext / 4 * 4] = true
        blocked[agent.node] = false
        val goal = leg.path.nodes.last()
        val alternatives =
            agent.person.routes.filter { memory ->
                if (memory.weekday != day % 7 || memory.need != leg.need || memory.returning != leg.returning) return@filter false
                val path = registry(agent).paths[memory.path]
                val index = path.nodes.indexOf(agent.node)
                index >= 0 && path.nodes.last() == goal && (index + 1 until path.nodes.size).none { blocked[path.nodes[it]] }
            }
        val known = chooseRoute(alternatives, city.seed xor agent.person.id xor (minute.toLong() shl 16))
        val candidate =
            known?.let { registry(agent).paths[it.path].let { path -> router.path(path.nodes.subList(path.nodes.indexOf(agent.node), path.nodes.size)) } }
                ?: router.route(agent.node, goal, blocked)
        if (candidate == null || candidate.nodes.size < 2) {
            leg.decisions.add(RouteDecision(minute, agent.node, "Aucun détour accessible"))
            return null
        }
        if (!agent.recovery) leg.selectedMemory?.let { adjust(agent, it, leg.need, leg.returning, -rules.abandonmentPenalty - if (leg.attemptWait % 5 > 0) 1 else 0) }
        leg.selectedMemory = known?.path
        leg.attemptWait = 0
        leg.rerouted = true
        leg.path = candidate
        leg.index = 0
        // The next edge has its own travel time: no immediate jump on the next retry.
        leg.decisions.add(RouteDecision(minute, agent.node, if (known != null) "Itinéraire connu" else "Nouveau détour"))
        return candidate.minutes[1]
    }

    private suspend fun arrive(
        agent: Agent,
        minute: Int,
    ) {
        val leg = agent.leg!!
        finish(agent, minute)
        if (agent.recovery) {
            agent.recovery = false
            nextActivity(agent, minute)
            return
        }
        if (leg.returning) {
            agent.activity++
            nextActivity(agent, minute)
        } else {
            val stay = agent.plan.activities[agent.activity].stay
            agent.returningAt = minute + stay
            enqueue(agent, agent.returningAt)
        }
    }

    private fun adjust(
        agent: Agent,
        pathId: Int,
        need: Need,
        returning: Boolean,
        delta: Int,
    ) {
        agent.person = agent.person.copy(routes = agent.person.routes.map { if (it.weekday == day % 7 && it.need == need && it.returning == returning && it.path == pathId) it.copy(score = (it.score + delta).coerceIn(0, rules.maxScore)) else it })
    }

    private fun finish(
        agent: Agent,
        arrival: Int?,
    ) {
        val leg = agent.leg!!
        val elapsed = (arrival ?: 1440) - leg.departure
        val plannedDuration = registry(agent).paths[leg.planned].duration
        val late = (elapsed - plannedDuration).coerceAtLeast(0)
        if (!agent.recovery) {
            leg.selectedMemory?.let { id ->
                val delta =
                    if (arrival == null) {
                        -rules.abandonmentPenalty
                    } else if (leg.attemptWait == 0) {
                        rules.successReward
                    } else {
                        0
                    }
                adjust(agent, id, leg.need, leg.returning, delta - if (leg.attemptWait % 5 > 0) 1 else 0)
            }
            if (arrival != null && leg.rerouted) {
                router.path(leg.nodes)?.let { path ->
                    val id = registry(agent).intern(path)
                    val existing = agent.person.routes.any { it.weekday == day % 7 && it.need == leg.need && it.returning == leg.returning && it.path == id }
                    if (existing && id != leg.selectedMemory) {
                        adjust(agent, id, leg.need, leg.returning, rules.successReward)
                    } else if (!existing) {
                        agent.person = agent.person.copy(routes = agent.person.routes + LearnedRoute(day % 7, leg.need, leg.returning, id, rules.alternativeScore))
                    }
                }
            }
        }
        agent.legs.add(
            ExecutedLeg(
                leg.need,
                leg.returning,
                leg.planned,
                leg.departure,
                arrival,
                TravelPath(leg.nodes.toList(), leg.times.toList()),
                leg.waiting,
                if (arrival == null) {
                    "Inachevé à minuit"
                } else if (late > 0) {
                    "Arrivé avec $late min de retard"
                } else {
                    "Arrivé"
                },
                leg.decisions.toList(),
            ),
        )
        agent.leg = null
    }

    private val results by lazy {
        prepared.mapIndexed { h, home ->
            val local = agentsByHome[h].orEmpty()
            val days = local.map { it.plan.copy(legs = it.legs.toList()) }
            val used =
                (
                    home.home.options.flatMap { it.paths } + local.flatMap { a -> a.person.routes.map { it.path } } +
                        days.flatMap { d -> d.activities.flatMap { listOfNotNull(it.route, it.returnRoute) } + d.legs.map { it.planned } }
                ).distinct().sorted()
            val remap = used.withIndex().associate { it.value to it.index }
            val paths = used.map { home.registry.paths[it] }
            val people = local.map { a -> a.person.copy(routes = a.person.routes.map { it.copy(path = remap.getValue(it.path)) }) }
            val executions =
                days.map { d ->
                    d.copy(
                        activities = d.activities.map { it.copy(route = it.route?.let(remap::getValue), returnRoute = it.returnRoute?.let(remap::getValue)) },
                        legs = d.legs.map { it.copy(planned = remap.getValue(it.planned)) },
                    )
                }
            val snapshot =
                home.home.copy(
                    options = home.home.options.map { it.copy(paths = it.paths.map(remap::getValue)) },
                    paths = paths,
                    loads = loads[h].map { (node, hours) -> TrafficLoad(node / 4, TravelMode.entries[node % 4], hours.toList()) },
                    completedNeeds = completed[h],
                    unmetNeeds = unmet[h],
                    days = executions,
                )
            snapshot to Household(home.home.cell, people, paths)
        }
    }

    fun homes(): List<MobilityHome> = results.map { it.first }

    fun households(): List<Household> = results.map { it.second }

    fun peaks(): Map<Int, Int> = peak.withIndex().filter { it.value > 0 }.associate { it.index to it.value }
}
