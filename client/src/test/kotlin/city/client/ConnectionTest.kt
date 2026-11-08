package city.client

import city.core.*
import kotlinx.serialization.encodeToString
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.*

class ConnectionTest {
    @Test fun `large snapshot reaches Swing and subsequent frames remain readable`() {
        ServerSocket(0).use { listener ->
            val state = CompletableFuture<Response>()
            val ack = CompletableFuture<Response>()
            val sent = CompletableFuture<Unit>()
            val release = CompletableFuture<Unit>()
            val city = City.generate(2000, 16)
            val sender =
                thread(isDaemon = true) {
                    try {
                        listener.accept().use { socket ->
                            socket.soTimeout = 30000
                            readFrame(socket.getInputStream().bufferedReader(), 8192)
                            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                            // JSON whitespace makes a valid STATE exceed the former 64 MiB cap.
                            val padding = " ".repeat(8192)
                            repeat(8192) { writer.write(padding) }
                            writer.write(wireJson.encodeToString(Response("STATE", city = city)))
                            writer.newLine()
                            writer.write(wireJson.encodeToString(Response("ACK", id = 42)))
                            writer.newLine()
                            writer.flush()
                            sent.complete(Unit)
                            release.get(30, TimeUnit.SECONDS)
                        }
                    } catch (e: Throwable) {
                        sent.completeExceptionally(e)
                    }
                }
            Connection(
                "127.0.0.1",
                listener.localPort,
                "Test",
                "",
                receive = {
                    if (!SwingUtilities.isEventDispatchThread()) {
                        state.completeExceptionally(AssertionError("Callback outside Swing EDT"))
                    } else if (it.type == "STATE") {
                        state.complete(it)
                    } else if (it.type == "ACK") {
                        ack.complete(it)
                    }
                },
                disconnected = {
                    val error = AssertionError(it)
                    state.completeExceptionally(error)
                    ack.completeExceptionally(error)
                },
            ).use { connection ->
                try {
                    connection.start()
                    assertEquals(city, state.get(30, TimeUnit.SECONDS).city)
                    assertEquals(42L, ack.get(30, TimeUnit.SECONDS).id)
                    sent.get(30, TimeUnit.SECONDS)
                } finally {
                    release.complete(Unit)
                    connection.close()
                    sender.join(5000)
                }
            }
        }
    }
}
