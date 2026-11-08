package city.core

import kotlin.random.Random
import kotlin.test.*

class RouteMemoryIndexTest {
    @Test fun `groups and weighted selections preserve original order including appended memories`() {
        val random = Random(2026)
        val original = MutableList(300) { LearnedRoute(random.nextInt(7), Need.entries.random(random), random.nextBoolean(), random.nextInt(30), random.nextInt(1, 101)) }
        val index = RouteMemoryIndex(original)

        fun verify() {
            assertEquals(original, index.routes)
            for (weekday in 0..6) {
                for (need in Need.entries) {
                    for (returning in listOf(false, true)) {
                        val expected = original.filter { it.weekday == weekday && it.need == need && it.returning == returning }
                        val actual = index.get(weekday, need, returning)
                        assertEquals(expected, actual)
                        for (seed in 0L..20L) assertEquals(chooseRoute(expected, seed), chooseRoute(actual, seed))
                    }
                }
            }
        }
        verify()
        repeat(100) {
            val route = LearnedRoute(random.nextInt(7), Need.entries.random(random), random.nextBoolean(), random.nextInt(30), 80)
            original.add(route)
            index.add(route)
        }
        verify()
    }

    @Test fun `empty and pruned indices preserve the pruning contract without modifying the citizen`() {
        val empty = RouteMemoryIndex(emptyList())
        assertTrue(empty.get(0, Need.WORK, false).isEmpty())
        val routes = (0..9).map { LearnedRoute(0, Need.WORK, false, it, 10 + it) }
        val citizen = Citizen(1, "Alice", 30, routes, 0)
        val pruned = citizen.prune(TrafficRules(retainedRoutes = 3))
        val index = RouteMemoryIndex(pruned.routes)
        assertEquals(pruned.routes, index.get(0, Need.WORK, false))
        index.add(LearnedRoute(1, Need.FOOD, true, 4, 80))
        assertEquals(routes, citizen.routes)
        assertEquals(3, pruned.routes.size)
        assertEquals(4, index.routes.size)
    }
}
