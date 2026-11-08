package city.core

/** Canonicalize only equal immutable registries; never repair mismatched state or remap indices. */
fun City.shareRouteRegistries(): City {
    val current = mobility ?: return this
    val byCell = households.associateBy { it.cell }
    var changed = false
    val homes =
        current.homes.map { home ->
            val paths = byCell[home.cell]?.paths
            if (paths != null && paths !== home.paths && paths == home.paths) {
                changed = true
                home.copy(paths = paths)
            } else {
                home
            }
        }
    return if (changed) copy(mobility = current.copy(homes = homes)) else this
}
