package city.core

import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.test.*

class RouteQueueTest {
    @Test fun `primitive heap preserves ordering duplicates and reuse across growth`() {
        val random = Random(42)
        val reference = PriorityQueue<Pair<Int, Int>>(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
        val actual = RouteQueue()
        repeat(4) {
            repeat(3000) {
                val node = random.nextInt(1000)
                val cost = random.nextInt(181)
                reference.add(node to cost)
                actual.add(node, cost)
            }
            reference.add(Int.MAX_VALUE to Int.MAX_VALUE)
            actual.add(Int.MAX_VALUE, Int.MAX_VALUE)
            while (reference.isNotEmpty()) {
                val expected = reference.remove()
                val value = actual.remove()
                assertEquals(expected.first, value.toInt())
                assertEquals(expected.second, (value ushr 32).toInt())
            }
            assertFalse(actual.isNotEmpty())
            actual.add(1, 1)
            actual.clear()
            assertFalse(actual.isNotEmpty())
        }
    }
}
