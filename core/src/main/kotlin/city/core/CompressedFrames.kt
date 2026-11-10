package city.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val GZIP_FRAME_PREFIX = "GZIP:"

/** LF-safe transport encoding; stream JSON through gzip without a full uncompressed String. */
@OptIn(ExperimentalSerializationApi::class)
fun encodeCompressedFrame(response: Response): String {
    val bytes = ByteArrayOutputStream()
    object : GZIPOutputStream(bytes, 65536) {
        init {
            def.setLevel(1)
        }
    }.use { wireJson.encodeToStream(response, it) }
    return GZIP_FRAME_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
}

/** Control frames and uncompressed diagnostic sessions retain ordinary JSON framing. */
@OptIn(ExperimentalSerializationApi::class)
fun decodeResponseFrame(frame: String): Response {
    if (!frame.startsWith(GZIP_FRAME_PREFIX)) return wireJson.decodeFromString(frame)
    val compressed = Base64.getDecoder().decode(frame.substring(GZIP_FRAME_PREFIX.length))
    return GZIPInputStream(ByteArrayInputStream(compressed), 65536).use { stream ->
        val response = wireJson.decodeFromStream<Response>(stream)
        check(stream.read() == -1) { "Unexpected compressed frame data" }
        response
    }
}
