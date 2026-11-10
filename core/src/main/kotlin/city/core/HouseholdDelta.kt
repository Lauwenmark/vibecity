package city.core

import kotlinx.serialization.Serializable

/** Indexed replacement also expresses truncation; absent entries require an existing baseline. */
@Serializable
data class RegistryDelta<T>(
    val size: Int,
    val changed: Map<Int, T>,
) {
    fun restore(previous: List<T>): List<T> {
        require(size >= 0 && size.toLong() <= previous.size.toLong() + changed.size) { "Invalid registry size" }
        require(changed.keys.all { it in 0 until size }) { "Invalid registry index" }
        if (changed.isEmpty() && size == previous.size) return previous
        return List(size) { index -> changed[index] ?: previous.getOrNull(index) ?: error("Missing registry entry") }
    }
}

private fun <T> registryDelta(
    current: List<T>,
    previous: List<T>,
): RegistryDelta<T> = RegistryDelta(current.size, current.indices.filter { it >= previous.size || current[it] != previous[it] }.associateWith { current[it] })

@Serializable
data class CitizenDelta(
    val citizen: Citizen,
    val routes: RegistryDelta<LearnedRoute>,
)

@Serializable
data class HouseholdPatch(
    val cell: Int,
    val paths: RegistryDelta<TravelPath>,
    val people: List<CitizenDelta>,
)

@Serializable
data class HouseholdDelta(
    val revision: Long,
    val homes: List<HouseholdPatch>,
) {
    fun restore(previous: City?): List<Household> {
        require(previous != null && previous.revision == revision) { "Missing household baseline" }
        require(homes.map { it.cell }.distinct().size == homes.size) { "Duplicate household patch" }
        val byCell = previous.households.associateBy { it.cell }
        return homes.map { home ->
            require(home.cell in previous.tiles.indices) { "Invalid household cell" }
            val old = byCell[home.cell]
            val people = old?.people.orEmpty().associateBy { it.id }
            require(
                home.people
                    .map { it.citizen.id }
                    .distinct()
                    .size == home.people.size,
            ) { "Duplicate citizen patch" }
            Household(
                home.cell,
                home.people.map { patch ->
                    require(patch.citizen.routes.isEmpty()) { "Unexpected citizen routes" }
                    patch.citizen.copy(routes = patch.routes.restore(people[patch.citizen.id]?.routes.orEmpty()))
                },
                home.paths.restore(old?.paths.orEmpty()),
            )
        }
    }
}

/** Daily simulation changes people, but most learned routes and registered paths remain identical. */
internal fun Response.compactHouseholds(previous: City?): Response {
    val current = city ?: return this
    if (previous == null || retained?.households == true) return this
    if (current.width != previous.width || current.height != previous.height || current.seed != previous.seed) return this
    val byCell = previous.households.associateBy { it.cell }
    val homes =
        current.households.map { home ->
            val old = byCell[home.cell]
            val people = old?.people.orEmpty().associateBy { it.id }
            HouseholdPatch(
                home.cell,
                registryDelta(home.paths, old?.paths.orEmpty()),
                home.people.map { person -> CitizenDelta(person.copy(routes = emptyList()), registryDelta(person.routes, people[person.id]?.routes.orEmpty())) },
            )
        }
    return copy(city = current.copy(households = emptyList()), householdDelta = HouseholdDelta(previous.revision, homes))
}
