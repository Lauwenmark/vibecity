package city.server

import city.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SharedStateEncoderTest {
    private fun city() = City(width = 16, height = 16, tiles = List(256) { Tile() }, terrain = Terrain.flat(16, 16))

    private fun test(block: suspend (CoroutineScope) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(10000) { block(scope) }
            } finally {
                scope.cancel()
            }
        }

    @Test fun `simultaneous clients share work and cancellation of first waiter is isolated`() =
        test { scope ->
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val encoder =
                SharedStateEncoder(scope) { _, _, _ ->
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    "encoded"
                }
            val response = Response("STATE", city = city())
            encoder.publish(response)
            coroutineScope {
                val first = async { encoder.encode(response, null, true) }
                entered.await()
                val peers = List(8) { async(start = CoroutineStart.UNDISPATCHED) { encoder.encode(response, null, true) } }
                first.cancelAndJoin()
                assertEquals(1, calls.get())
                release.complete(Unit)
                assertEquals(List(8) { "encoded" }, peers.awaitAll())
            }
            assertEquals("encoded", encoder.encode(response, null, true))
            assertEquals(1, calls.get())
        }

    @Test fun `baseline identity broadcast metadata and compression remain distinct`() =
        test { scope ->
            val base = city()
            val differentBase = base.copy(tiles = base.tiles.mapIndexed { i, t -> if (i == 17) t.copy(road = true) else t })
            val next = base.copy(revision = 1, treasury = 12345)
            val response = Response("STATE", city = next, players = listOf("Alice"))
            val encoder = SharedStateEncoder(scope)
            encoder.publish(response)
            for (previous in listOf(null, base, differentBase)) {
                for (compressed in listOf(false, true)) {
                    val text = encoder.encode(response, previous, compressed)
                    assertEquals(compressed, text.startsWith("GZIP:"))
                    assertEquals(response, decodeResponseFrame(text).restoreState(previous))
                }
            }
            val joined = response.copy(players = listOf("Alice", "Bob"))
            encoder.publish(joined)
            assertEquals(joined, decodeResponseFrame(encoder.encode(joined, base, true)).restoreState(base))
        }

    @Test fun `completed cache respects byte entry and broadcast limits and accepts oversized frames`() =
        test { scope ->
            val calls = AtomicInteger()
            val encoder =
                SharedStateEncoder(scope, byteBudget = 12, entryLimit = 2) { r, _, _ ->
                    calls.incrementAndGet()
                    r.message
                }
            val response = Response("STATE", city = city(), message = "abc") // Six accounted bytes.
            val bases = List(3) { city() }
            encoder.publish(response)
            bases.forEach { encoder.encode(response, it, true) }
            assertEquals(3, calls.get())
            encoder.encode(response, bases[1], true)
            assertEquals(3, calls.get())
            encoder.encode(response, bases[0], true)
            assertEquals(4, calls.get())
            encoder.publish(response)
            encoder.encode(response, bases[0], true)
            assertEquals(5, calls.get())
            val huge = response.copy(message = "x".repeat(100))
            encoder.publish(huge)
            repeat(2) { assertEquals(huge.message, encoder.encode(huge, null, true)) }
            assertEquals(7, calls.get())
            encoder.clear()
        }

    @Test fun `entry count and byte budget each independently bound retention`() =
        test { scope ->
            for ((budget, limit) in listOf(12L to 8, 100L to 2)) {
                val calls = AtomicInteger()
                val encoder =
                    SharedStateEncoder(scope, byteBudget = budget, entryLimit = limit) { _, _, _ ->
                        calls.incrementAndGet()
                        "abc"
                    }
                val response = Response("STATE", city = city())
                val bases = List(3) { city() }
                encoder.publish(response)
                bases.forEach { encoder.encode(response, it, true) }
                encoder.encode(response, bases[0], true)
                assertEquals(4, calls.get(), "budget=$budget limit=$limit")
            }
        }

    @Test fun `a server stopped before lazy startup does not strand callers`() =
        test { scope ->
            scope.cancel()
            val encoder = SharedStateEncoder(scope, parallelLimit = 1)
            repeat(2) {
                assertFailsWith<CancellationException> { encoder.encode(Response("STATE", city = city()), null, true) }
            }
        }

    @Test fun `old in flight state cannot evict the latest completed broadcast`() =
        test { scope ->
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = Response("STATE", city = city(), message = "old")
            val latest = old.copy(message = "new")
            val encoder =
                SharedStateEncoder(scope, entryLimit = 1) { r, _, _ ->
                    calls.incrementAndGet()
                    if (r === old) {
                        entered.complete(Unit)
                        release.await()
                    }
                    r.message
                }
            encoder.publish(old)
            coroutineScope {
                val pending = async { encoder.encode(old, null, true) }
                entered.await()
                encoder.publish(latest)
                assertEquals("new", encoder.encode(latest, null, true))
                release.complete(Unit)
                assertEquals("old", pending.await())
                assertEquals("new", encoder.encode(latest, null, true))
                assertEquals(2, calls.get())
            }
        }

    @Test fun `encoding failure is retryable and server cancellation releases waiters`() =
        test { scope ->
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val encoder =
                SharedStateEncoder(scope) { r, _, _ ->
                    if (calls.incrementAndGet() == 1) error("encoding failed")
                    if (r.message == "wait") {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    "ok"
                }
            val response = Response("STATE", city = city())
            encoder.publish(response)
            assertFailsWith<IllegalStateException> { encoder.encode(response, null, true) }
            assertEquals("ok", encoder.encode(response, null, true))
            coroutineScope {
                val pending = async { encoder.encode(response.copy(message = "wait"), null, true) }
                entered.await()
                scope.cancel()
                assertFailsWith<CancellationException> { pending.await() }
            }
        }

    @Test fun `distinct in flight encodings are bounded and queued callers can cancel`() =
        test { scope ->
            val entered = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val encoder =
                SharedStateEncoder(scope, parallelLimit = 2) { _, _, _ ->
                    val count = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    try {
                        entered.send(Unit)
                        release.await()
                        "ok"
                    } finally {
                        active.decrementAndGet()
                    }
                }
            coroutineScope {
                val first = List(2) { async { encoder.encode(Response("STATE", city = city()), null, true) } }
                repeat(2) { entered.receive() }
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { encoder.encode(Response("STATE", city = city()), null, true) }
                cancelled.cancelAndJoin()
                val rest = List(4) { async(start = CoroutineStart.UNDISPATCHED) { encoder.encode(Response("STATE", city = city()), null, true) } }
                assertTrue(entered.tryReceive().isFailure)
                release.complete(Unit)
                assertEquals(List(6) { "ok" }, (first + rest).awaitAll())
                assertEquals(2, maximum.get())
            }
        }
}
