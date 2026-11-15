package city.server

import city.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.encodeToString
import java.lang.ref.WeakReference

/** Shares work only for the same broadcast, baseline object and wire format. */
internal class SharedStateEncoder(
    private val scope: CoroutineScope,
    private val byteBudget: Long = 64L * 1024 * 1024,
    private val entryLimit: Int = 8,
    parallelLimit: Int = 32,
    private val encodeFrame: suspend (Response, City?, Boolean) -> String = { response, previous, compressed ->
        val compact = response.compactState(previous)
        if (compressed) encodeCompressedFrame(compact) else wireJson.encodeToString(compact)
    },
) {
    // City/Response equality and hashing traverse large snapshots. Identity also prevents
    // grouping different baselines that happen to carry the same revision number.
    private class Key(
        val response: Response,
        previous: City?,
        val compressed: Boolean,
    ) {
        // A completed frame must not keep an otherwise unused historical city alive.
        private val baseline = WeakReference(previous)
        private val full = previous == null
        private val hash = 31 * (31 * System.identityHashCode(response) + System.identityHashCode(previous)) + compressed.hashCode()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key || response !== other.response || compressed != other.compressed || full != other.full) return false
            return full || baseline.get()?.let { it === other.baseline.get() } == true
        }

        override fun hashCode(): Int = hash
    }

    private val lock = Any()
    private val slots = Semaphore(parallelLimit)
    private val pending = mutableMapOf<Key, Deferred<String>>()
    private val completed = linkedMapOf<Key, String>()
    private var latest: Response? = null
    private var bytes = 0L

    init {
        require(byteBudget > 0 && entryLimit > 0)
    }

    /** Older work may finish for its waiters, but cannot populate the new broadcast's cache. */
    fun publish(response: Response) {
        synchronized(lock) {
            latest = response
            completed.clear()
            bytes = 0
        }
    }

    fun clear() {
        synchronized(lock) {
            latest = null
            completed.clear()
            bytes = 0
        }
    }

    suspend fun encode(
        response: Response,
        previous: City?,
        compressed: Boolean,
    ): String {
        currentCoroutineContext().ensureActive()
        val key = Key(response, previous, compressed)
        val existing =
            synchronized(lock) {
                completed[key]?.let { return it }
                pending[key]
            }
        if (existing != null) return existing.await()

        // Never wait for a permit or perform encoding while holding the cache lock.
        slots.acquire()
        var created = false
        val task: Deferred<String>
        try {
            task =
                synchronized(lock) {
                    completed[key]?.let { return it }
                    pending[key] ?: scope
                        .async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                            val frame = encodeFrame(response, previous, compressed)
                            ensureActive()
                            synchronized(lock) {
                                val size = frame.length.toLong() * 2 // Conservative String accounting.
                                if (latest === response && size <= byteBudget) {
                                    while (completed.size >= entryLimit || bytes + size > byteBudget) {
                                        val oldest = completed.entries.iterator()
                                        bytes -= oldest
                                            .next()
                                            .value.length
                                            .toLong() * 2
                                        oldest.remove()
                                    }
                                    completed[key] = frame
                                    bytes += size
                                }
                            }
                            frame
                        }.also {
                            pending[key] = it
                            created = true
                        }
                }
        } finally {
            if (!created) slots.release()
        }
        if (created) {
            // Also runs if the server cancels a lazy job before it starts.
            task.invokeOnCompletion {
                synchronized(lock) { if (pending[key] === task) pending.remove(key) }
                slots.release()
            }
        }
        task.start()
        // The server owns the task: cancelling one slow/disconnected waiter cannot
        // cancel an encoding awaited by the other clients. Pending work is bounded.
        return task.await()
    }
}
