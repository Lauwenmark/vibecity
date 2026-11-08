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
    private var oversizedStateBytes = 0L
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
        // One oversized state has its own slot, including while the writer owns it.
        // Normal deltas retain their budget so an edit cannot disconnect a peer that
        // is still receiving its large baseline. Never discard dependent state frames.
        // Control replies keep a separate reserve, with one oversized catalog allowed.
        if (state) {
            if (frame.bytes > stateBudget) {
                if (oversizedStateBytes > 0) return false
            } else if (stateBytes - oversizedStateBytes + frame.bytes > stateBudget) {
                return false
            }
        } else if (controlCount > 0 && controlBytes + frame.bytes > controlBudget) {
            return false
        }
        if (state) {
            stateBytes += frame.bytes
            if (frame.bytes > stateBudget) oversizedStateBytes = frame.bytes
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
            if (frame.bytes > stateBudget) oversizedStateBytes = 0
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
