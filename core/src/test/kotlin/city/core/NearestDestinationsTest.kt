package city.core

import kotlin.random.Random
import kotlin.test.*

class NearestDestinationsTest {
    @Test fun `linear selection matches full sorting including ties and unreachable cells`() {
        val random = Random(2000)
        repeat(500) { size ->
            val distances = IntArray(size * TRAVEL_MODE_COUNT) { Int.MAX_VALUE }
            for (cell in 0 until size) {
                distances[cell * TRAVEL_MODE_COUNT] = if (random.nextBoolean()) Int.MAX_VALUE else random.nextInt(0, 20)
            }
            val targets = (0 until size).shuffled(random)
            val expected =
                targets
                    .filter { distances[it * TRAVEL_MODE_COUNT] < Int.MAX_VALUE }
                    .sortedWith(compareBy<Int> { distances[it * TRAVEL_MODE_COUNT] }.thenBy { it })
                    .take(2)
            assertEquals(expected, nearestDestinations(targets, distances), "size=$size")
        }
        val tied = IntArray(5 * TRAVEL_MODE_COUNT) { 10 }
        assertEquals(listOf(0, 1), nearestDestinations(listOf(4, 3, 2, 1, 0), tied))
        assertEquals(emptyList(), nearestDestinations(listOf(0, 1), IntArray(2 * TRAVEL_MODE_COUNT) { Int.MAX_VALUE }))
        assertEquals(listOf(3), nearestDestinations(listOf(3), tied))
    }
}
