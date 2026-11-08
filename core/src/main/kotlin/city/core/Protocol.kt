package city.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.EOFException

const val PROTOCOL_VERSION = 6
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
    if (type != "STATE" || previous == null) return this
    if (current.width != previous.width || current.height != previous.height || current.seed != previous.seed) return this
    val households = current.households === previous.households
    val mobility = current.mobility === previous.mobility
    if (!households && !mobility) return this
    return copy(
        city = current.copy(households = if (households) emptyList() else current.households, mobility = if (mobility) null else current.mobility),
        retained = RetainedState(previous.revision, households, mobility),
    )
}

/** Restore before coalescing Swing notifications: every network state is a dependency of the next. */
fun Response.restoreState(previous: City?): Response {
    val reference = retained ?: return if (type == "STATE" && city != null) copy(city = city.shareRouteRegistries()) else this
    val current = requireNotNull(city) { "Missing state payload" }
    require(type == "STATE" && previous != null && previous.revision == reference.revision) { "Missing state baseline" }
    require(current.width == previous.width && current.height == previous.height && current.seed == previous.seed) { "Incompatible state baseline" }
    return copy(
        city = current.copy(households = if (reference.households) previous.households else current.households, mobility = if (reference.mobility) previous.mobility else current.mobility).shareRouteRegistries(),
        retained = null,
    )
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
