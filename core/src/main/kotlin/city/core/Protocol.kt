package city.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.EOFException

const val PROTOCOL_VERSION = 4
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
)

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
