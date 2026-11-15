package city.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.EOFException

const val PROTOCOL_VERSION = 11
val wireJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

/** One UTF-8 JSON object followed by LF. No Java serialization or binary framing. */
@Serializable
data class Request(
    val type: String,
    val version: Int = PROTOCOL_VERSION,
    val id: Long = 0,
    val name: String = "",
    val token: String = "",
    val compressedStates: Boolean = false,
    val tool: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val value: Int = 0,
)

@Serializable
data class Response(
    val type: String,
    val version: Int = PROTOCOL_VERSION,
    val id: Long = 0,
    val message: String = "",
    val city: City? = null,
    val catalog: List<BuildingDef> = emptyList(),
    val players: List<String> = emptyList(),
    val retained: RetainedState? = null,
    val sharedRouteHomes: List<Int> = emptyList(),
    val delta: StateDelta? = null,
    val householdDelta: HouseholdDelta? = null,
)

/** Large immutable fields omitted only when the preceding transmitted state owns the same objects. */
@Serializable
data class RetainedState(
    val revision: Long,
    val households: Boolean,
    val mobility: Boolean,
)

/** Called by the single ordered encoder, never against a state that was merely queued. */
fun Response.compactState(previous: City?): Response {
    val current = city ?: return this
    if (type != "STATE") return this
    val compatible = previous != null && current.width == previous.width && current.height == previous.height && current.seed == previous.seed
    val households = compatible && current.households === previous!!.households
    val mobility = compatible && current.mobility === previous!!.mobility
    val compact =
        if (households || mobility) {
            copy(
                city = current.copy(households = if (households) emptyList() else current.households, mobility = if (mobility) null else current.mobility),
                retained = RetainedState(previous!!.revision, households, mobility),
            )
        } else {
            this
        }
    val payload = compact.city!!
    val moving = payload.mobility ?: return compact.compactHouseholds(previous).compactMap(previous)
    val byCell = current.households.associateBy { it.cell }
    val shared = mutableListOf<Int>()
    val homes =
        moving.homes.map { home ->
            if (home.paths.isNotEmpty() && byCell[home.cell]?.paths == home.paths) {
                shared.add(home.cell)
                home.copy(paths = emptyList())
            } else {
                home
            }
        }
    return (if (shared.isEmpty()) compact else compact.copy(city = payload.copy(mobility = moving.copy(homes = homes)), sharedRouteHomes = shared)).compactHouseholds(previous).compactMap(previous)
}

/** Reconstruct each wire state before coalescing notifications: subsequent deltas depend on it. */
fun Response.restoreState(previous: City?): Response {
    if (type != "STATE") {
        require(delta == null && householdDelta == null && retained == null && sharedRouteHomes.isEmpty()) { "Unexpected state payload" }
        return this
    }
    require((city != null) != (delta != null)) { "Expected one state payload" }
    val current = city ?: delta!!.restore(previous)
    val reference = retained
    var restored =
        if (reference == null) {
            current
        } else {
            require(previous != null && previous.revision == reference.revision) { "Missing state baseline" }
            require(current.width == previous.width && current.height == previous.height && current.seed == previous.seed) { "Incompatible state baseline" }
            current.copy(households = if (reference.households) previous.households else current.households, mobility = if (reference.mobility) previous.mobility else current.mobility)
        }
    if (householdDelta != null) {
        require(retained?.households != true && restored.households.isEmpty()) { "Conflicting household payload" }
        require(previous != null && restored.width == previous.width && restored.height == previous.height && restored.seed == previous.seed) { "Incompatible household baseline" }
        restored = restored.copy(households = householdDelta.restore(previous))
    }
    if (sharedRouteHomes.isNotEmpty()) {
        val shared = sharedRouteHomes.toSet()
        require(shared.size == sharedRouteHomes.size) { "Duplicate shared registry" }
        val moving = requireNotNull(restored.mobility) { "Missing mobility for shared registries" }
        val byCell = restored.households.associateBy { it.cell }
        val found = mutableSetOf<Int>()
        val homes =
            moving.homes.map { home ->
                if (home.cell in shared) {
                    require(found.add(home.cell) && home.paths.isEmpty()) { "Invalid shared registry" }
                    val household = requireNotNull(byCell[home.cell]) { "Missing household registry" }
                    home.copy(paths = household.paths)
                } else {
                    home
                }
            }
        require(found == shared) { "Missing mobility home" }
        restored = restored.copy(mobility = moving.copy(homes = homes))
    }
    return copy(city = restored.shareRouteRegistries(), retained = null, sharedRouteHomes = emptyList(), delta = null, householdDelta = null)
}

/** Enforces a bound before allocating an unbounded line; EOF never commits a partial command. */
fun readFrame(
    reader: BufferedReader,
    limit: Int,
): String? {
    require(limit >= 0)
    val result = StringBuilder(minOf(limit, 8192))
    val buffer = CharArray(8192)
    while (true) {
        // Read at most one character beyond the bound, and never append an oversized chunk.
        val requested = minOf(buffer.size.toLong(), limit.toLong() - result.length + 1).toInt()
        reader.mark(buffer.size)
        val count = reader.read(buffer, 0, requested)
        if (count < 0) {
            if (result.isNotEmpty()) throw EOFException("Incomplete frame")
            return null
        }
        var newline = 0
        while (newline < count && buffer[newline] != '\n') newline++
        require(result.length.toLong() + newline <= limit) { "Frame too large" }
        result.append(buffer, 0, newline)
        if (newline < count) {
            // Keep any following frame in the BufferedReader, including for existing callers.
            reader.reset()
            check(reader.skip(newline + 1L) == newline + 1L)
            if (result.isNotEmpty() && result.last() == '\r') result.setLength(result.length - 1)
            return result.toString()
        }
    }
}
