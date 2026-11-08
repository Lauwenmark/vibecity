package city.client

import city.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** Owns a socket and its IO jobs. All callbacks run on the Swing event dispatch thread. */
class Connection(
    private val host: String,
    private val port: Int,
    private val name: String,
    private val token: String,
    private val receive: (Response) -> Unit,
    private val disconnected: (String) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socket = Socket()
    private val closed = AtomicBoolean()
    private val requests = Channel<Request>(128)
    private val latestState = AtomicReference<Response?>()
    private val stateScheduled = AtomicBoolean()

    fun start() {
        scope.launch {
            try {
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.tcpNoDelay = true
                socket.soTimeout = 60000
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                writer.write(wireJson.encodeToString(Request("HELLO", name = name, token = token)))
                writer.newLine()
                writer.flush()
                launch {
                    try {
                        var sequence = 0L
                        for (request in requests) {
                            writer.write(wireJson.encodeToString(request.copy(id = ++sequence)))
                            writer.newLine()
                            writer.flush()
                        }
                    } catch (e: Exception) {
                        if (!closed.get()) fail(e.message ?: "Écriture impossible")
                    }
                }
                launch {
                    while (isActive) {
                        delay(15000)
                        requests.send(Request("PING"))
                    }
                }
                while (isActive) {
                    val line = readFrame(reader, 64 * 1024 * 1024) ?: break
                    val response = wireJson.decodeFromString<Response>(line)
                    require(response.version == PROTOCOL_VERSION)
                    if (response.type == "STATE") {
                        latestState.set(response)
                        if (stateScheduled.compareAndSet(false, true)) {
                            SwingUtilities.invokeLater {
                                stateScheduled.set(false)
                                latestState.getAndSet(null)?.let { if (!closed.get()) receive(it) }
                            }
                        }
                    } else {
                        SwingUtilities.invokeLater { if (!closed.get()) receive(response) }
                    }
                }
                if (!closed.get()) fail("Le serveur a fermé la connexion")
            } catch (e: Exception) {
                if (e !is CancellationException && !closed.get()) fail(e.message ?: "Connexion impossible")
            }
        }
    }

    fun send(request: Request) {
        if (!requests.trySend(request).isSuccess) SwingUtilities.invokeLater { receive(Response("ERROR", message = "File de commandes pleine ou connexion fermée ; modification non envoyée")) }
    }

    private fun fail(message: String) {
        close()
        SwingUtilities.invokeLater { disconnected(message) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            requests.close()
            scope.cancel()
        }
    }
}
