package city.server

import city.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_CLIENTS = 32

/** Single-writer actor for transactions; bounded per-client queues isolate slow peers. */
class CityServer(
    initial: City = City.generate(),
    val catalog: Catalog = Catalog(),
    rules: List<TileRule> = emptyList(),
    host: String = "127.0.0.1",
    port: Int = 2000,
    private val token: String = "",
    private val savePath: Path? = Path.of("saves/city.json"),
    private val tickMillis: Long = 1500,
    systems: List<CityRule> = emptyList(),
) : AutoCloseable {
    init {
        require(tickMillis > 0)
        catalog.freeze()
    }

    // Enough blocking-IO slots for both directions of every peer plus the listener.
    // The CPU actor uses Default and can evict slow writers even when all sockets block.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CLIENTS * 2 + 4))
    private val listener =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(host, port))
        }
    val port: Int get() = listener.localPort
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val clientCount = AtomicInteger()
    private val events = Channel<Event>(256)
    private val commands = Commands(catalog)
    private val simulation = Simulation(catalog, rules, systems = systems)
    private val sessions = linkedSetOf<Session>() // owned by actor only
    private var city = initial

    private sealed interface Event {
        data class Join(
            val session: Session,
        ) : Event

        data class Leave(
            val session: Session,
        ) : Event

        data class Command(
            val session: Session,
            val request: Request,
        ) : Event

        data class Tick(
            val done: CompletableDeferred<Unit>,
        ) : Event

        data class Stop(
            val done: CompletableDeferred<Unit>,
        ) : Event
    }

    private class Session(
        val socket: Socket,
        val name: String,
    ) {
        val outgoing = Channel<String>(32)
        var lastId = 0L

        fun close() {
            outgoing.close()
            runCatching { socket.close() }
        }
    }

    private val actor =
        scope.launch(Dispatchers.Default) {
            for (event in events) {
                when (event) {
                    is Event.Join -> {
                        sessions.add(event.session)
                        send(event.session, Response("WELCOME", catalog = catalog.all()))
                        broadcast()
                    }

                    is Event.Leave -> {
                        sessions.remove(event.session)
                        broadcast()
                    }

                    is Event.Command -> {
                        val s = event.session
                        val r = event.request
                        if (s !in sessions) continue
                        if (r.id <= s.lastId) {
                            send(s, Response("ERROR", id = r.id, message = "Identifiant non croissant"))
                            continue
                        }
                        s.lastId = r.id
                        try {
                            require(r.version == PROTOCOL_VERSION) { "Version incompatible" }
                            when (r.type) {
                                "PING" -> {
                                    send(s, Response("PONG", id = r.id))
                                }

                                "SAVE" -> {
                                    val path = savePath ?: error("Sauvegarde désactivée")
                                    withContext(Dispatchers.IO) { Persistence.save(path, city) }
                                    send(s, Response("ACK", id = r.id, message = "Ville sauvegardée"))
                                }

                                else -> {
                                    city = commands.apply(city, r)
                                    send(s, Response("ACK", id = r.id, message = "${s.name} : ${r.tool.ifEmpty { r.type }}"))
                                    broadcast()
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            send(s, Response("ERROR", id = r.id, message = e.message ?: "Commande refusée"))
                        }
                    }

                    is Event.Tick -> {
                        try {
                            if (city.rules.speed > 0) {
                                val previousYear = city.month / 12
                                repeat(city.rules.speed) { city = simulation.step(city) }
                                if (city.month / 12 > previousYear && savePath != null) withContext(Dispatchers.IO) { Persistence.save(savePath, city) }
                                broadcast()
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            System.err.println("Simulation paused: ${e.message}")
                            city = city.copy(rules = city.rules.copy(speed = 0), notices = listOf("Simulation arrêtée : ${e.message}"))
                            broadcast()
                        } finally {
                            event.done.complete(Unit)
                        }
                    }

                    is Event.Stop -> {
                        try {
                            if (savePath != null) withContext(Dispatchers.IO) { Persistence.save(savePath, city) }
                            event.done.complete(Unit)
                        } catch (e: Exception) {
                            event.done.completeExceptionally(e)
                        }
                        break
                    }
                }
            }
        }

    init {
        scope.launch {
            try {
                while (running.get()) {
                    val socket = listener.accept()
                    if (clientCount.incrementAndGet() > MAX_CLIENTS) {
                        clientCount.decrementAndGet()
                        socket.close()
                        continue
                    }
                    sockets.add(socket)
                    launch { handle(socket) }
                }
            } catch (e: Exception) {
                if (running.get()) System.err.println("Accept: ${e.message}")
            }
        }
        scope.launch {
            while (isActive) {
                // Await completion so slow simulation never accumulates queued ticks.
                val done = CompletableDeferred<Unit>()
                delay(tickMillis)
                events.send(Event.Tick(done))
                done.await()
            }
        }
    }

    private suspend fun handle(socket: Socket) =
        coroutineScope {
            var session: Session? = null
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 10000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val hello = wireJson.decodeFromString<Request>(readFrame(reader, 8192) ?: return@coroutineScope)
                require(hello.type == "HELLO" && hello.version == PROTOCOL_VERSION) { "HELLO version 1 attendu" }
                require(hello.name.matches(Regex("[\\p{L}\\p{N} _-]{1,24}"))) { "Nom invalide" }
                require(MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), hello.token.toByteArray(Charsets.UTF_8))) { "Jeton incorrect" }
                val connected = Session(socket, hello.name)
                session = connected
                val writerJob =
                    launch {
                        try {
                            for (line in connected.outgoing) {
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        } finally {
                            connected.close()
                        }
                    }
                events.send(Event.Join(connected))
                socket.soTimeout = 45000
                try {
                    var window = System.nanoTime()
                    var count = 0
                    while (isActive) {
                        val line = readFrame(reader, 8192) ?: break
                        val now = System.nanoTime()
                        if (now - window > 1_000_000_000L) {
                            window = now
                            count = 0
                        }
                        require(++count <= 100) { "Trop de commandes" }
                        val request = wireJson.decodeFromString<Request>(line)
                        events.send(Event.Command(connected, request))
                    }
                } finally {
                    connected.close()
                    writerJob.cancel()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (running.get()) System.err.println("Client closed: ${e.message}")
            } finally {
                session?.let { events.trySend(Event.Leave(it)) }
                sockets.remove(socket)
                clientCount.decrementAndGet()
                runCatching { socket.close() }
            }
        }

    private fun send(
        s: Session,
        response: Response,
    ) {
        if (!s.outgoing.trySend(wireJson.encodeToString(response)).isSuccess) {
            s.close()
            sessions.remove(s)
        }
    }

    private fun broadcast() {
        val frame = wireJson.encodeToString(Response("STATE", city = city, players = sessions.map { it.name }))
        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next()
            if (!s.outgoing.trySend(frame).isSuccess) {
                s.close()
                iterator.remove()
            }
        }
    }

    /** Drains commands already admitted, then saves; socket closure unblocks all blocking IO. */
    suspend fun shutdown() {
        if (!running.compareAndSet(true, false)) return
        listener.close()
        try {
            val done = CompletableDeferred<Unit>()
            events.send(Event.Stop(done))
            done.await()
            actor.join()
        } finally {
            closeResources()
        }
    }

    private fun closeResources() {
        sockets.forEach { runCatching { it.close() } }
        events.close()
        scope.cancel()
    }

    override fun close() {
        running.set(false)
        runCatching { listener.close() }
        closeResources()
    }
}
