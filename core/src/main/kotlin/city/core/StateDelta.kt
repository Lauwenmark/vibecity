package city.core

import kotlinx.serialization.Serializable

/** Network-only map patch. Other fields replace their previous values, including null/empty values. */
@Serializable
data class StateDelta(
    val baseRevision: Long,
    val width: Int,
    val height: Int,
    val seed: Long,
    val tiles: Map<Int, Tile>,
    val vertices: Map<Int, Int>,
    val revision: Long,
    val month: Int,
    val treasury: Long,
    val debt: Long,
    val rules: Rules,
    val stats: Statistics,
    val history: List<Statistics>,
    val notices: List<String>,
    val pluginData: Map<String, String>,
    val mobility: Mobility?,
    val day: Int?,
    val households: List<Household>,
    val nextCitizenId: Long,
    val trafficRules: TrafficRules,
) {
    fun restore(previous: City?): City {
        require(previous != null && previous.revision == baseRevision) { "Missing map baseline" }
        require(previous.width == width && previous.height == height && previous.seed == seed) { "Incompatible map baseline" }
        val terrain = requireNotNull(previous.terrain) { "Missing terrain baseline" }
        require(tiles.keys.all { it in previous.tiles.indices }) { "Invalid tile index" }
        require(vertices.keys.all { it in terrain.vertices.indices }) { "Invalid vertex index" }
        val updatedTiles = if (tiles.isEmpty()) previous.tiles else previous.tiles.toMutableList().also { list -> tiles.forEach { (i, tile) -> list[i] = tile } }
        val updatedTerrain =
            if (vertices.isEmpty()) {
                terrain
            } else {
                terrain.copy(vertices = terrain.vertices.toMutableList().also { list -> vertices.forEach { (i, value) -> list[i] = value } })
            }
        return City(
            width = width,
            height = height,
            seed = seed,
            tiles = updatedTiles,
            terrain = updatedTerrain,
            revision = revision,
            month = month,
            treasury = treasury,
            debt = debt,
            rules = rules,
            stats = stats,
            history = history,
            notices = notices,
            pluginData = pluginData,
            mobility = mobility,
            day = day,
            households = households,
            nextCitizenId = nextCitizenId,
            trafficRules = trafficRules,
        )
    }
}

/** Compare values: construction can copy every Tile even though only a few actually changed. */
internal fun Response.compactMap(previous: City?): Response {
    val current = city ?: return this
    if (previous == null || current.width != previous.width || current.height != previous.height || current.seed != previous.seed) return this
    val terrain = current.terrain ?: return this
    val oldTerrain = previous.terrain ?: return this
    val tiles =
        current.tiles.indices
            .filter { current.tiles[it] != previous.tiles[it] }
            .associateWith { current.tiles[it] }
    val vertices =
        terrain.vertices.indices
            .filter { terrain.vertices[it] != oldTerrain.vertices[it] }
            .associateWith { terrain.vertices[it] }
    // Dense changes (e.g. simulation) are better represented by the existing full map.
    if (tiles.size > current.tiles.size / 2 || vertices.size > terrain.vertices.size / 2) return this
    return copy(
        city = null,
        delta =
            StateDelta(
                baseRevision = previous.revision,
                width = current.width,
                height = current.height,
                seed = current.seed,
                tiles = tiles,
                vertices = vertices,
                revision = current.revision,
                month = current.month,
                treasury = current.treasury,
                debt = current.debt,
                rules = current.rules,
                stats = current.stats,
                history = current.history,
                notices = current.notices,
                pluginData = current.pluginData,
                mobility = current.mobility,
                day = current.day,
                households = current.households,
                nextCitizenId = current.nextCitizenId,
                trafficRules = current.trafficRules,
            ),
    )
}
