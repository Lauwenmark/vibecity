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
    val reference = retained ?: return this
    val current = requireNotNull(city) { "Missing state payload" }
    require(type == "STATE" && previous != null && previous.revision == reference.revision) { "Missing state baseline" }
    require(current.width == previous.width && current.height == previous.height && current.seed == previous.seed) { "Incompatible state baseline" }
    return copy(
        city = current.copy(households = if (reference.households) previous.households else current.households, mobility = if (reference.mobility) previous.mobility else current.mobility),
        retained = null,
    )
}

/** Enforces a bound before allocating an unbounded line; EOF never commits a partial command. */
fun readFrame(
    reader: BufferedReader,
    limit: Int,
): String? {
    val result = StringBuilder()
    while (true) {
        val c = reader.read()
        if (c < 0) {
            if (result.isNotEmpty()) throw EOFException("Incomplete frame")
            return null
        }
        if (c == '\n'.code) return result.toString().removeSuffix("\r")
        require(result.length < limit) { "Frame too large" }
        result.append(c.toChar())
    }
}
