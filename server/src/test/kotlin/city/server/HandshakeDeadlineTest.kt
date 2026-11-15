package city.server
import city.core.*
import java.net.Socket
import kotlin.test.*

class HandshakeDeadlineTest {
    @Test fun `hello deadline is an absolute ten seconds`() {
        CityServer(City(width = 16, height = 16, tiles = List(256) { Tile() }, rules = Rules(speed = 0)), port = 0, savePath = null).use { server ->
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 7000
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("{")
                writer.flush()
                Thread.sleep(6000)
                writer.write(" ")
                writer.flush()

                val reply = readFrame(socket.getInputStream().bufferedReader(), 8192)
                assertNull(reply, "Fragmented HELLO must expire without waiting for another fragment")
            }
        }
    }
}
