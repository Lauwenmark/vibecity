package city.core

import kotlin.test.*

class HouseholdDeltaTest {
    private val path = TravelPath(listOf(17 * TRAVEL_MODE_COUNT, 18 * TRAVEL_MODE_COUNT), listOf(0, 5))
    private val otherPath = TravelPath(listOf(17 * TRAVEL_MODE_COUNT, 19 * TRAVEL_MODE_COUNT), listOf(0, 7))
    private val route = LearnedRoute(0, Need.WORK, false, 0, 80)
    private val alice = Citizen(1, "Alice", 30, listOf(route), 68)
    private val bob = Citizen(2, "Bob", 40, listOf(route.copy(score = 60)), 68)
    private val initial = City.generate(2000, 16).copy(households = listOf(Household(17, listOf(alice, bob), listOf(path))), nextCitizenId = 4)

    @Test fun `daily patches retain paths and update routes by citizen identity after reorder`() {
        val changed =
            initial.copy(
                revision = 3,
                households = listOf(Household(17, listOf(bob.copy(age = 41), alice.copy(routes = listOf(route.copy(score = 83)))), listOf(path))),
                mobility = Mobility(0, listOf(MobilityHome(17, 2, emptyList(), listOf(path))), 0, 0),
            )
        val compact = Response("STATE", city = changed).compactState(initial)
        val patch = assertNotNull(compact.householdDelta).homes.single()
        assertTrue(patch.paths.changed.isEmpty())
        assertTrue(
            patch.people
                .first()
                .routes.changed
                .isEmpty(),
        )
        assertEquals(
            83,
            patch.people
                .last()
                .routes.changed[0]!!
                .score,
        )
        val received = decodeResponseFrame(encodeCompressedFrame(compact)).restoreState(initial)
        assertEquals(changed, received.city)
        assertSame(
            initial.households.single().paths,
            received.city!!
                .households
                .single()
                .paths,
        )
        assertSame(
            received.city.households
                .single()
                .paths,
            received.city.mobility!!
                .homes
                .single()
                .paths,
        )
        assertNull(received.householdDelta)
    }

    @Test fun `skipped revisions migration removal and registry replacement reconstruct exactly`() {
        val changed =
            initial.copy(
                revision = 20,
                households =
                    listOf(
                        Household(18, listOf(alice.copy(position = 18 * TRAVEL_MODE_COUNT)), listOf(otherPath)),
                        Household(17, listOf(bob.copy(routes = emptyList()), Citizen(3, "Chloé", 20, position = 17 * TRAVEL_MODE_COUNT)), emptyList()),
                    ),
            )
        val received = decodeResponseFrame(encodeCompressedFrame(Response("STATE", city = changed).compactState(initial))).restoreState(initial).city!!
        assertEquals(changed, received)
        val empty = changed.copy(revision = 21, households = emptyList())
        assertEquals(empty, Response("STATE", city = empty).compactState(changed).restoreState(received).city)
        val replaced = initial.copy(revision = 40, households = listOf(Household(17, listOf(alice), listOf(otherPath, path))))
        assertEquals(replaced, Response("STATE", city = replaced).compactState(initial).restoreState(initial).city)
    }

    @Test fun `patches reject missing baselines conflicts and invalid registries`() {
        val changed = initial.copy(revision = 2, households = listOf(initial.households.single().copy(people = listOf(alice))))
        val response = Response("STATE", city = changed).compactState(initial)
        assertFails { response.restoreState(null) }
        assertFails { response.restoreState(initial.copy(revision = 1)) }
        assertFails { response.restoreState(initial.copy(seed = 7)) }
        assertFails { response.copy(retained = RetainedState(initial.revision, true, false)).restoreState(initial) }
        val delta = assertNotNull(response.householdDelta)
        assertFails { delta.copy(homes = delta.homes + delta.homes).restore(initial) }
        assertFails { RegistryDelta<TravelPath>(Int.MAX_VALUE, emptyMap()).restore(listOf(path)) }
        assertFails { RegistryDelta(1, mapOf(-1 to path)).restore(listOf(path)) }
        assertFails { RegistryDelta(2, mapOf(0 to path)).restore(listOf(path)) }
        assertFails { Response("ACK", householdDelta = delta).restoreState(initial) }
    }
}
