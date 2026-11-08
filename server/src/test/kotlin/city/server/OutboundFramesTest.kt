package city.server

import kotlin.test.*

class OutboundFramesTest {
    @Test fun `byte budget includes in flight frames and preserves control reserve`() {
        val queue = OutboundFrames(stateBudget = 20, controlBudget = 10)
        assertTrue(queue.trySend("123456", state = true))
        val flight = queue.frames.tryReceive().getOrThrow()
        assertEquals(12, queue.retainedBytes)
        assertFalse(queue.trySend("12345", state = true))
        assertTrue(queue.trySend("ACK"))
        queue.delivered(flight)
        assertTrue(queue.trySend("12345", state = true))
        queue.close()
        assertEquals(0, queue.retainedBytes)
        assertFalse(queue.trySend("closed", state = true))
        assertEquals(0, queue.retainedBytes)
    }

    @Test fun `single oversized snapshot is permitted but cannot accumulate`() {
        val queue = OutboundFrames(stateBudget = 10)
        assertTrue(queue.trySend("large snapshot", state = true))
        val flight = queue.frames.tryReceive().getOrThrow()
        assertFalse(queue.trySend("next", state = true))
        assertTrue(queue.trySend("PONG"))
        queue.close()
        assertEquals(flight.bytes, queue.retainedBytes)
        queue.delivered(flight)
        assertEquals(0, queue.retainedBytes)
    }
}
