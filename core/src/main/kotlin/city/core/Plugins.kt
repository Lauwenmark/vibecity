package city.core

/** Version 1 trusted JVM SPI. Implementations must be deterministic and thread-safe. */
interface CityPlugin {
    val id: String
    val apiVersion: Int get() = 1

    fun configure(context: PluginContext)
}

class PluginContext(
    val catalog: Catalog,
    val rules: MutableList<TileRule>,
    val systems: MutableList<CityRule> = mutableListOf(),
)

/** Called concurrently on immutable input. Return the proposed cell to replace core behavior. */
interface TileRule {
    val id: String

    fun apply(
        context: TileContext,
        proposed: Tile,
    ): Tile
}

data class TileContext(
    val city: City,
    val index: Int,
    val catalog: Catalog,
)

/** Ordered post-tick extension for economy, citizen models and custom persisted state.
 * Systems may use structured concurrency internally and must preserve revision/dimensions.
 */
interface CityRule {
    val id: String

    suspend fun evolve(
        city: City,
        catalog: Catalog,
    ): City
}
