package city.core

import kotlinx.serialization.encodeToString
import java.io.StringReader
import java.util.Base64
import kotlin.test.*

class CompressedFramesTest {
    @Test fun `large unicode state compresses and preserves following control frame`() {
        val response = Response("STATE", city = City.generate(2000, 16).copy(pluginData = mapOf("test" to "École — 市民 🚆\n".repeat(10000))))
        val encoded = encodeCompressedFrame(response)
        assertTrue(encoded.length < wireJson.encodeToString(response).length / 5)
        assertFalse(encoded.contains('\n'))
        val ack = Response("ACK", id = 42)
        val reader = StringReader(encoded + "\n" + wireJson.encodeToString(ack) + "\n").buffered()
        assertEquals(response, decodeResponseFrame(readFrame(reader, Int.MAX_VALUE)!!))
        assertEquals(ack, decodeResponseFrame(readFrame(reader, Int.MAX_VALUE)!!))
        assertNull(readFrame(reader, Int.MAX_VALUE))
    }

    @Test fun `compressed corruption and truncation are rejected`() {
        val frame = encodeCompressedFrame(Response("STATE", city = City.generate(2000, 16)))
        val bytes = Base64.getDecoder().decode(frame.removePrefix("GZIP:"))
        // Corrupt the gzip CRC, keeping a valid base64 envelope and JSON payload.
        bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 1).toByte()
        assertFails { decodeResponseFrame("GZIP:" + Base64.getEncoder().encodeToString(bytes)) }
        assertFails { decodeResponseFrame("GZIP:" + Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 10))) }
        assertFails { decodeResponseFrame("GZIP:!") }
    }
}
