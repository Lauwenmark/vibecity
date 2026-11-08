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
    // The transaction actor stays on IO; expensive simulation and encoding use Default.
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
    private var city = initial.withSharedTerrain()

    private var simulationJob: Job? = null
    private var snapshotSequence = 0L
    private var snapshotGeneration = 0L

    private data class Snapshot(
        val sequence: Long,
        val response: Response,
        val generation: Long,
    )

    private val snapshots = Channel<Snapshot>(Channel.CONFLATED)

    private sealed interface Event {
        data class Simulated(
            val revision: Long,
            val result: City?,
            val failure: Exception?,
        ) : Event

        data class Encoded(
            val sequence: Long,
            val frame: String,
        ) : Event

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
        var firstSnapshot = 0L

        fun close() {
            outgoing.close()
            runCatching { socket.close() }
        }
    }

    private val actor =
        scope.launch {
            for (event in events) {
                when (event) {
                    is Event.Join -> {
                        event.session.firstSnapshot = snapshotSequence + 1
                        snapshotGeneration++
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
                                    simulationJob?.cancel()
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
                        if (city.rules.speed == 0) {
                            event.done.complete(Unit)
                        } else {
                            val base = city
                            simulationJob =
                                scope
                                    .launch(Dispatchers.Default) {
                                        try {
                                            var result = base
                                            repeat(base.rules.speed) { result = simulation.step(result) }
                                            events.send(Event.Simulated(base.revision, result, null))
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            events.send(Event.Simulated(base.revision, null, e))
                                        }
                                    }.also { job ->
                                        // Also completes if cancellation happens before the coroutine starts.
                                        job.invokeOnCompletion { event.done.complete(Unit) }
                                    }
                        }
                    }

                    is Event.Simulated -> {
                        // A command may have invalidated this calculation, even after it finished.
                        if (event.revision != city.revision) continue
                        val previousYear = city.month / 12
                        if (event.failure != null) {
                            System.err.println("Simulation paused: ${event.failure.message}")
                            city = city.copy(rules = city.rules.copy(speed = 0), notices = listOf("Simulation arrêtée : ${event.failure.message}"))
                        } else {
                            city = checkNotNull(event.result)
                            if (city.month / 12 > previousYear && savePath != null) withContext(Dispatchers.IO) { Persistence.save(savePath, city) }
                        }
                        broadcast()
                    }

                    is Event.Encoded -> {
                        // Encoded states form an ordered baseline chain. Newer queued work must not
                        // discard a completed frame or continuous construction would starve updates.
                        val iterator = sessions.iterator()
                        while (iterator.hasNext()) {
                            val session = iterator.next()
                            if (event.sequence < session.firstSnapshot) continue
                            if (!session.outgoing.trySend(event.frame).isSuccess) {
                                session.close()
                                iterator.remove()
                            }
                        }
                    }

                    is Event.Stop -> {
                        simulationJob?.cancel()
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
        scope.launch(Dispatchers.Default) {
            var previous: City? = null
            var generation = -1L
            for (snapshot in snapshots) {
                // A join forces a full baseline even if intermediate queued snapshots were conflated.
                val response = snapshot.response.compactState(if (generation == snapshot.generation) previous else null)
                val frame = wireJson.encodeToString(response)
                events.send(Event.Encoded(snapshot.sequence, frame))
                previous = snapshot.response.city
                generation = snapshot.generation
            }
        }
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
                require(hello.type == "HELLO" && hello.version == PROTOCOL_VERSION) { "HELLO version $PROTOCOL_VERSION attendu" }
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
        snapshots.trySend(Snapshot(++snapshotSequence, Response("STATE", city = city, players = sessions.map { it.name }), snapshotGeneration))
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
