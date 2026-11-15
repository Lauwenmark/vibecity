package city.core

import kotlin.math.*

internal fun deliverBabies(city: City): City {
    var next = city.nextCitizenId
    val homes =
        city.households.map { home ->
            val babies = mutableListOf<Citizen>()
            val people =
                home.people.map { mother ->
                    val life = requireNotNull(mother.life)
                    if (life.pregnancyDue == null || life.pregnancyDue > city.nextDay()) {
                        mother
                    } else {
                        val id = next++
                        val n = noise(city.seed xor id, 704, city.nextDay())
                        val parents = listOfNotNull(mother.id, life.pregnancyFather)
                        babies.add(
                            Citizen(
                                id,
                                "${FIRST_NAMES[n % FIRST_NAMES.size]} #$id",
                                0,
                                position = home.cell * TRAVEL_MODE_COUNT,
                                life =
                                    CitizenLife(
                                        city.nextDay(),
                                        if (n % 2 == 0) Sex.FEMALE else Sex.MALE,
                                        (n % 6).toDouble(),
                                        .5 + n % 1001 / 1000.0,
                                        LifeStage.CHILD,
                                        parents = parents,
                                        ancestors = (parents + life.pregnancyAncestors).distinct(),
                                    ),
                            ),
                        )
                        mother.copy(life = life.copy(pregnancyDue = null, pregnancyFather = null, pregnancyAncestors = emptyList(), children = life.children + 1))
                    }
                }
            home.copy(people = people + babies)
        }
    return syncPopulation(city.copy(nextCitizenId = next), homes)
}

internal fun related(
    a: Citizen,
    b: Citizen,
): Boolean {
    val aa = a.life?.ancestors.orEmpty() + a.life?.parents.orEmpty()
    val ba = b.life?.ancestors.orEmpty() + b.life?.parents.orEmpty()
    return a.id == b.id || a.id in ba || b.id in aa || aa.any { it in ba }
}

internal fun monthlyFamilies(
    city: City,
    catalog: Catalog,
): City {
    val people =
        city.households
            .flatMap { it.people }
            .associateBy { it.id }
            .toMutableMap()
    val cells =
        city.households
            .flatMap { h -> h.people.map { it.id to h.cell } }
            .toMap()
            .toMutableMap()
    val occupants = city.households.associate { it.cell to it.people.map { p -> p.id }.toMutableList() }.toMutableMap()
    val candidates =
        people.values
            .filter { it.age >= 18 && it.life?.stage in listOf(LifeStage.WORKER, LifeStage.RETIRED) && it.life?.partner == null && it.life?.studentRelationship == null }
            .map { it.id }
            .sorted()
    for (id in candidates) {
        var person = people.getValue(id)
        if (person.life!!.partner != null || draw(city, id, 705) >= LifeRules.RELATIONSHIP_CHANCE) continue
        val start = noise(city.seed xor id, 706, city.nextDay()) % candidates.size
        val other =
            (0 until min(32, candidates.size))
                .asSequence()
                .map { people.getValue(candidates[(start + it) % candidates.size]) }
                .firstOrNull { it.life!!.partner == null && abs(it.age - person.age) <= 20 && !related(person, it) } ?: continue
        person = person.copy(life = person.life!!.copy(partner = other.id))
        people[id] = person
        people[other.id] = other.copy(life = other.life!!.copy(partner = id))
    }

    fun capacity(cell: Int): Int =
        city.tiles[cell].let {
            if (it.zone.residential) {
                it.zone.capacity
            } else if (it.anchor == cell) {
                it.building?.let { b -> catalog[b].residents } ?: 0
            } else {
                0
            }
        }

    fun atParents(p: Citizen): Boolean = p.life!!.parents.any { cells[it] == cells[p.id] }

    fun move(
        ids: Set<Long>,
        destination: Int,
    ): Boolean {
        val migrating = ids.filter { cells[it] != destination }
        if (migrating.isEmpty() || occupants[destination].orEmpty().size + migrating.size > capacity(destination)) return false
        if (migrating.any { people.getValue(it).position != cells.getValue(it) * TRAVEL_MODE_COUNT }) return false
        val residents = (occupants[destination].orEmpty() + migrating).map { people.getValue(it) }
        val income = residents.sumOf { it.dailyIncome }
        val rent = 5.0 + city.tiles[destination].landValue * .1 + if (city.tiles[destination].zone == Zone.RESIDENTIAL_LIGHT) 2 else 0
        if (income * (1 - city.rules.taxRate / 100.0) < residents.sumOf { rent + if (it.age < 18) 6 else 10 }) return false
        for (id in migrating) {
            occupants[cells.getValue(id)]?.remove(id)
            occupants.getOrPut(destination) { mutableListOf() }.add(id)
            val previousHome = cells.getValue(id)
            cells[id] = destination
            val person = people.getValue(id)
            people[id] = person.copy(bicycle = person.bicycle?.let { if (it.cell == previousHome) it.copy(cell = destination, parkedSince = null, checkedUntil = null, protectedParking = false) else it }, position = destination * TRAVEL_MODE_COUNT, routes = emptyList(), wellbeing = person.wellbeing.copy(residenceDays = 0, workplace = null))
        }
        return true
    }

    fun withChildren(adults: Set<Long>): Set<Long> =
        adults +
            adults.flatMap { id -> occupants[cells.getValue(id)].orEmpty() }.distinct().filter { id ->
                val child = people.getValue(id)
                child.age < 18 && child.life!!.parents.any { it in adults } &&
                    child.life!!
                        .parents
                        .filter { it in people && cells[it] == cells[id] }
                        .all { it in adults }
            }
    val vacant = city.tiles.indices.filter { capacity(it) > 0 && occupants[it].isNullOrEmpty() && city.tiles[it].powered && city.tiles[it].watered && city.tiles[it].fire == 0 && city.surface(it).flat }
    val processed = mutableSetOf<Long>()
    for (id in people.keys.sorted()) {
        val p = people.getValue(id)
        if (p.age < 18 || p.life!!.stage !in listOf(LifeStage.WORKER, LifeStage.RETIRED) || !processed.add(id)) continue
        val partner = p.life!!.partner?.let { people[it] }
        if (partner != null) processed.add(partner.id)
        val chance = if (partner != null && (atParents(p) || atParents(partner) || cells[id] != cells[partner.id])) LifeRules.COUPLE_MOVE_CHANCE else LifeRules.MOVE_CHANCE
        if (draw(city, id, 707) >= chance) continue
        val group = withChildren(setOfNotNull(id, partner?.id))
        if (partner != null && cells[id] != cells[partner.id]) {
            val destinations = listOf(p, partner).filterNot(::atParents).map { cells.getValue(it.id) }
            if (destinations.any { move(group, it) }) continue
        }
        val destination = vacant.firstOrNull { occupants[it].isNullOrEmpty() && capacity(it) >= group.size && move(group, it) }
        if (destination != null) continue
    }
    for (id in people.keys.sorted()) {
        val woman = people.getValue(id)
        val life = woman.life!!
        val man = life.partner?.let { people[it] } ?: continue
        if (life.sex != Sex.FEMALE || woman.age !in 18..44 || life.pregnancyDue != null || man.life!!.sex != Sex.MALE || cells[id] != cells[man.id]) continue
        val chance = (if (woman.age < 35) .025 else .012) / (1 + life.children)
        if (draw(city, id, 708) >= chance) continue
        people[id] =
            woman.copy(
                life =
                    life.copy(
                        pregnancyDue = dateDay(epoch.plusDays(city.nextDay().toLong()).plusMonths(9)),
                        pregnancyFather = man.id,
                        pregnancyAncestors = (life.parents + man.life!!.parents).distinct(),
                    ),
            )
    }
    val previous = city.households.associateBy { it.cell }
    return syncPopulation(city, occupants.map { (cell, ids) -> Household(cell, ids.map { people.getValue(it) }, previous[cell]?.paths.orEmpty()) })
}
