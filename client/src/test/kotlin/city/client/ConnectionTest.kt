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
    @Test fun `compact state chain is restored before Swing notification`() {
        ServerSocket(0).use { listener ->
            val initialReceived = CompletableFuture<Unit>()
            val finalState = CompletableFuture<City>()
            val release = CompletableFuture<Unit>()
            val paths = listOf(TravelPath(listOf(68, 72), listOf(0, 5)))
            val initial = City.generate(2000, 16).copy(households = listOf(Household(17, listOf(Citizen(1, "Alice", 30, position = 17)), paths)), mobility = Mobility(0, listOf(MobilityHome(17, 1, emptyList(), paths)), 0, 0))
            val taxed = Commands(Catalog()).apply(initial, Request("TAX", value = 9))
            val funded = Commands(Catalog()).apply(taxed, Request("FUNDING", value = 90))
            val sender =
                thread(isDaemon = true) {
                    try {
                        listener.accept().use { socket ->
                            readFrame(socket.getInputStream().bufferedReader(), 8192)
                            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                            writer.write(wireJson.encodeToString(Response("STATE", city = initial).compactState(null)) + "\n")
                            writer.flush()
                            initialReceived.get(10, TimeUnit.SECONDS)
                            writer.write(wireJson.encodeToString(Response("STATE", city = taxed).compactState(initial)) + "\n")
                            writer.write(wireJson.encodeToString(Response("STATE", city = funded).compactState(taxed)) + "\n")
                            writer.flush()
                            release.get(10, TimeUnit.SECONDS)
                        }
                    } catch (e: Throwable) {
                        finalState.completeExceptionally(e)
                    }
                }
            Connection("127.0.0.1", listener.localPort, "Test", "", {
                if (it.delta != null || it.retained != null || it.sharedRouteHomes.isNotEmpty()) finalState.completeExceptionally(AssertionError("Unrestored state"))
                if (it.city?.revision == initial.revision) {
                    val restored = requireNotNull(it.city)
                    if (restored != initial || restored.households
                            .single()
                            .paths !==
                        restored.mobility!!
                            .homes
                            .single()
                            .paths
                    ) {
                        finalState.completeExceptionally(AssertionError("Incomplete shared routes"))
                    }
                    initialReceived.complete(Unit)
                }
                if (it.city?.revision == funded.revision) finalState.complete(it.city)
            }, { finalState.completeExceptionally(AssertionError(it)) }).use { connection ->
                try {
                    connection.start()
                    assertEquals(funded, finalState.get(10, TimeUnit.SECONDS))
                } finally {
                    release.complete(Unit)
                    connection.close()
                    sender.join(5000)
                }
            }
        }
    }

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
