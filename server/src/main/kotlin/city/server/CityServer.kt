package city.server

import city.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    testMode: Boolean = false,
    private val saveSnapshot: (Path, City) -> Unit = Persistence::save,
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
    private val shutdownAttempt = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val clientCount = AtomicInteger()
    private val events = Channel<Event>(256)
    private val commands = Commands(catalog, ignoreConstructionYears = testMode)
    private val simulation = Simulation(catalog, rules, systems = systems)
    private val sessions = linkedSetOf<Session>() // owned by actor only
    private var city = initial.withSharedTerrain()

    private var simulationJob: Job? = null
    private var stopping: CompletableDeferred<Unit>? = null // owned by actor only

    // One active and one pending save; shutdown may retain one additional final snapshot.
    private val saves = Channel<Save>(1)

    private data class Save(
        val snapshot: City,
        val session: Session? = null,
        val id: Long = 0,
        val shutdown: CompletableDeferred<Unit>? = null,
    )

    private sealed interface Event {
        data class Saved(
            val session: Session?,
            val id: Long,
            val shutdown: CompletableDeferred<Unit>?,
            val failure: Exception?,
        ) : Event

        data class Simulated(
            val revision: Long,
            val result: City?,
            val failure: Exception?,
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
        val compressedStates: Boolean,
    ) {
        val outgoing = OutboundFrames()
        var lastId = 0L
        val states = Channel<Response>(Channel.CONFLATED)

        fun close() {
            states.cancel()
            outgoing.close()
            runCatching { socket.close() }
        }
    }

    private val actor =
        scope.launch {
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
                            require(stopping == null || r.type == "PING") { "Arrêt en cours : modification non acceptée" }
                            when (r.type) {
                                "PING" -> {
                                    send(s, Response("PONG", id = r.id))
                                }

                                "SAVE" -> {
                                    check(savePath != null) { "Sauvegarde désactivée" }
                                    check(saves.trySend(Save(city, s, r.id)).isSuccess) { "Une sauvegarde est déjà en attente ; réessayez plus tard" }
                                }

                                else -> {
                                    val updated = commands.apply(city, r)
                                    val changed = updated !== city
                                    if (changed) {
                                        city = updated
                                        simulationJob?.cancel()
                                    }
                                    send(s, Response("ACK", id = r.id, message = "${s.name} : ${r.tool.ifEmpty { r.type }}"))
                                    if (changed) broadcast()
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            send(s, Response("ERROR", id = r.id, message = e.message ?: "Commande refusée"))
                        }
                    }

                    is Event.Tick -> {
                        if (stopping != null || city.rules.speed == 0) {
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
                        if (stopping != null || event.revision != city.revision) continue
                        val previousYear = city.month / 12
                        if (event.failure != null) {
                            System.err.println("Simulation paused: ${event.failure.message}")
                            city = city.copy(rules = city.rules.copy(speed = 0), notices = listOf("Simulation arrêtée : ${event.failure.message}"))
                        } else {
                            city = checkNotNull(event.result)
                            if (city.month / 12 > previousYear && savePath != null) {
                                if (!saves.trySend(Save(city)).isSuccess) {
                                    pauseAfterSaveFailure("Échec de la sauvegarde automatique : une sauvegarde est déjà en attente")
                                }
                            }
                        }
                        broadcast()
                    }

                    is Event.Stop -> {
                        simulationJob?.cancel()
                        stopping = event.done
                        if (savePath == null) {
                            finishShutdown(event.done)
                            break
                        }
                        val finalSave = Save(city, shutdown = event.done)
                        // Never suspend the actor on a full save queue: PING and departures still work.
                        scope.launch { saves.send(finalSave) }
                    }

                    is Event.Saved -> {
                        val save = event
                        val failure = event.failure
                        when {
                            save.shutdown != null -> {
                                if (failure == null) {
                                    finishShutdown(save.shutdown)
                                    break
                                }
                                stopping = null
                                pauseAfterSaveFailure("Échec de la sauvegarde à l’arrêt : ${failure.message}")
                                broadcast()
                                shutdownAttempt.compareAndSet(save.shutdown, null)
                                save.shutdown.completeExceptionally(failure)
                            }

                            save.session != null -> {
                                if (save.session in sessions) {
                                    send(save.session, Response(if (failure == null) "ACK" else "ERROR", id = save.id, message = if (failure == null) "Ville sauvegardée" else failure.message ?: "Échec de la sauvegarde"))
                                }
                            }

                            failure != null -> {
                                System.err.println("Autosave failed: ${failure.message}")
                                // A pending final save supersedes this autosave and owns error recovery.
                                if (stopping == null) {
                                    pauseAfterSaveFailure("Échec de la sauvegarde automatique : ${failure.message}")
                                    broadcast()
                                }
                            }
                        }
                    }
                }
            }
        }

    init {
        scope.launch {
            for (save in saves) {
                val failure =
                    try {
                        saveSnapshot(checkNotNull(savePath), save.snapshot)
                        null
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        e
                    }
                // Completion events do not retain the potentially large snapshot in the actor mailbox.
                events.send(Event.Saved(save.session, save.id, save.shutdown, failure))
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
                    // Each peer belongs directly to the supervisor, never to the accept loop.
                    scope.launch { handle(socket) }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun handle(socket: Socket) {
        var session: Session? = null
        try {
            // Catch outside the scope: writer failures are rethrown when its children finish.
            coroutineScope {
                socket.tcpNoDelay = true
                socket.soTimeout = 10000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                // A read timeout alone restarts on each fragment. Close the socket at
                // an absolute deadline, including while the blocking reader waits.
                val handshakeFinished = AtomicBoolean(false)
                val deadline =
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        delay(10_000)
                        if (handshakeFinished.compareAndSet(false, true)) socket.close()
                    }
                val hello =
                    try {
                        val hello = wireJson.decodeFromString<Request>(readFrame(reader, 8192) ?: return@coroutineScope)
                        require(hello.type == "HELLO" && hello.version == PROTOCOL_VERSION) { "HELLO version $PROTOCOL_VERSION attendu" }
                        require(hello.name.matches(Regex("[\\p{L}\\p{N} _-]{1,24}"))) { "Nom invalide" }
                        require(MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), hello.token.toByteArray(Charsets.UTF_8))) { "Jeton incorrect" }
                        check(handshakeFinished.compareAndSet(false, true)) { "HELLO timeout" }
                        hello
                    } finally {
                        handshakeFinished.set(true)
                        deadline.cancel()
                    }
                val connected = Session(socket, hello.name, hello.compressedStates)
                session = connected
                val writerJob =
                    launch {
                        try {
                            var previous: City? = null
                            var nextStateAt = 0L
                            while (isActive) {
                                // Prefer control replies, then encode only the latest pending city.
                                // Each peer's delta baseline is its last completely written state.
                                val waitMillis = (nextStateAt - System.nanoTime()) / 1_000_000
                                val next =
                                    select<Any?> {
                                        connected.outgoing.frames.onReceiveCatching { it.getOrNull() }
                                        if (waitMillis > 0) {
                                            onTimeout(waitMillis) { Unit }
                                        } else {
                                            connected.states.onReceiveCatching { it.getOrNull() }
                                        }
                                    } ?: break
                                when (next) {
                                    is OutboundFrames.Frame -> {
                                        try {
                                            writer.write(next.text)
                                            writer.newLine()
                                            writer.flush()
                                        } finally {
                                            connected.outgoing.delivered(next)
                                        }
                                    }

                                    is Response -> {
                                        val frame =
                                            withContext(Dispatchers.Default) {
                                                val response = next.compactState(previous)
                                                if (connected.compressedStates) encodeCompressedFrame(response) else wireJson.encodeToString(response)
                                            }
                                        writer.write(frame)
                                        writer.newLine()
                                        writer.flush()
                                        previous = next.city
                                        nextStateAt = System.nanoTime() + 50_000_000
                                    }
                                }
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
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (running.get()) System.err.println("Client closed: ${e.message}")
        } finally {
            session?.close()
            // A full command mailbox must not lose the departure and retain a dead session.
            withContext(NonCancellable) {
                session?.let { if (running.get()) runCatching { events.send(Event.Leave(it)) } }
            }
            sockets.remove(socket)
            clientCount.decrementAndGet()
            runCatching { socket.close() }
        }
    }

    private fun send(
        s: Session,
        response: Response,
    ) {
        if (!s.outgoing.trySend(wireJson.encodeToString(response))) {
            s.close()
            sessions.remove(s)
        }
    }

    private fun broadcast() {
        val response = Response("STATE", city = city, players = sessions.map { it.name })
        sessions.forEach { it.states.trySend(response) }
    }

    private fun pauseAfterSaveFailure(message: String) {
        simulationJob?.cancel()
        city = city.copy(revision = city.revision + 1, rules = city.rules.copy(speed = 0), notices = city.notices + message)
    }

    private fun finishShutdown(done: CompletableDeferred<Unit>) {
        running.set(false)
        runCatching { listener.close() }
        closeResources()
        done.complete(Unit)
    }

    /** Drains commands already admitted, then saves; socket closure unblocks all blocking IO. */
    suspend fun shutdown() {
        while (true) {
            shutdownAttempt.get()?.let {
                it.await()
                return
            }
            if (!running.get()) return
            val done = CompletableDeferred<Unit>()
            if (!shutdownAttempt.compareAndSet(null, done)) continue
            // The attempt belongs to the server, not to a caller that may stop waiting.
            scope.launch { events.send(Event.Stop(done)) }.invokeOnCompletion { failure ->
                if (failure != null) done.completeExceptionally(failure)
            }
            done.await()
            return
        }
    }

    private fun closeResources() {
        sockets.forEach { runCatching { it.close() } }
        saves.cancel()
        events.close()
        scope.cancel()
    }

    override fun close() {
        running.set(false)
        shutdownAttempt.get()?.completeExceptionally(CancellationException("Server closed without saving"))
        runCatching { listener.close() }
        closeResources()
    }
}
