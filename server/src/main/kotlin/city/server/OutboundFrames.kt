package city.server

import kotlinx.coroutines.channels.Channel

/** Counts conservative String storage (two bytes per character), including a frame being written. */
internal class OutboundFrames(
    private val stateBudget: Long = 64L * 1024 * 1024,
    private val controlBudget: Long = 1024L * 1024,
) {
    data class Frame(
        val text: String,
        val state: Boolean,
    ) {
        val bytes: Long get() = text.length.toLong() * 2
    }

    val frames = Channel<Frame>(32)
    private var stateBytes = 0L
    private var controlBytes = 0L
    private var stateCount = 0
    private var controlCount = 0
    val retainedBytes: Long @Synchronized get() = stateBytes + controlBytes

    init {
        require(stateBudget > 0 && controlBudget > 0)
    }

    @Synchronized fun trySend(
        text: String,
        state: Boolean = false,
    ): Boolean {
        val frame = Frame(text, state)
        val bytes = if (state) stateBytes else controlBytes
        val count = if (state) stateCount else controlCount
        val budget = if (state) stateBudget else controlBudget
        // Permit one oversized snapshot/catalog, never a backlog of oversized frames.
        // Control replies have a separate reserve so an in-flight large state cannot block ACK/PONG.
        if (count > 0 && bytes + frame.bytes > budget) return false
        if (state) {
            stateBytes += frame.bytes
            stateCount++
        } else {
            controlBytes += frame.bytes
            controlCount++
        }
        if (frames.trySend(frame).isSuccess) return true
        delivered(frame)
        return false
    }

    @Synchronized fun delivered(frame: Frame) {
        if (frame.state) {
            stateBytes -= frame.bytes
            stateCount--
        } else {
            controlBytes -= frame.bytes
            controlCount--
        }
    }

    @Synchronized fun close() {
        frames.close()
        while (true) delivered(frames.tryReceive().getOrNull() ?: break)
    }
}
