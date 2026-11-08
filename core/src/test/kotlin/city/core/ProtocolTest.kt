package city.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.EOFException
import java.io.StringReader
import kotlin.test.*

class ProtocolTest {
    @Test fun `unicode json is one readable line and round trips`() {
        val response = Response("ERROR", message = "Électricité\nindisponible")
        val json = wireJson.encodeToString(response)
        assertFalse(json.contains('\n'))
        assertTrue(json.contains("Électricité"))
        assertEquals(response, wireJson.decodeFromString<Response>(json))
    }

    @Test fun `framing accepts CRLF and rejects oversized and partial commands`() {
        assertEquals("hello", readFrame(StringReader("hello\r\n").buffered(), 16))
        assertFailsWith<IllegalArgumentException> { readFrame(StringReader("123456\n").buffered(), 5) }
        assertFailsWith<EOFException> { readFrame(StringReader("partial").buffered(), 16) }
        assertNull(readFrame(StringReader("").buffered(), 16))
    }
}
