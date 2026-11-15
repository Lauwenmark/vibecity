package city.server

import city.core.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class LifePersistenceTest {
    @Test fun `birth in full residential building survives save reload and next simulation day`() =
        runBlocking {
            val catalog = Catalog(listOf(BuildingDef("home", "Home", 1, 0, residents = 1)))
            val tiles = MutableList(256) { Tile() }
            tiles[17] = Tile(building = "home", anchor = 17)
            val mother = Citizen(1, "Mother", 20, position = 17 * TRAVEL_MODE_COUNT, life = CitizenLife(-7304, Sex.FEMALE, 80.0, 1.0, LifeStage.WORKER, lastSalary = 50.0, pregnancyDue = 1, pregnancyFather = 2))
            val initial = City(width = 16, height = 16, tiles = tiles, terrain = Terrain.flat(16, 16, 2), day = 1, households = listOf(Household(17, listOf(mother))), nextCitizenId = 3)
            val born = Simulation(catalog, workers = 1).step(initial)
            assertEquals(2, born.tiles[17].residentialOccupancy)
            val file = Files.createTempFile("life-birth", ".json")
            try {
                Persistence.save(file, born)
                val restored = Persistence.load(file, catalog)
                assertEquals(born, restored)
                assertEquals(Simulation(catalog, workers = 1).step(born), Simulation(catalog, workers = 4).step(restored))
                assertEquals(
                    listOf(1L, 2L),
                    restored.households
                        .single()
                        .people
                        .last()
                        .life!!
                        .parents,
                )
            } finally {
                Files.deleteIfExists(file)
            }
        }

    @Test fun `student relationship appointments and outcomes survive disk and deterministic continuation`() =
        runBlocking {
            val catalog = Catalog()
            val tiles = MutableList(256) { Tile(powered = true, watered = true) }
            val homes =
                listOf(17, 20).mapIndexed { index, cell ->
                    tiles[cell] = tiles[cell].copy(zone = Zone.RESIDENTIAL_DENSE, occupants = 1)
                    val id = index + 1L
                    Household(cell, listOf(Citizen(id, "Student $id", 15, position = cell * TRAVEL_MODE_COUNT, life = CitizenLife(-5500, Sex.FEMALE, 80.0, 1.0, LifeStage.SCHOOL, studentRelationship = StudentRelationship(3 - id, 0)))))
                }
            val input = City(width = 16, height = 16, tiles = tiles, terrain = Terrain.flat(16, 16, 2), households = homes, nextCitizenId = 3, day = 5)
            var result: City? = null
            for (seed in 0L..100L) {
                val candidate = MobilitySimulation(catalog, 1).simulate(input.copy(seed = seed))
                if (candidate.mobility!!.homes.any { h -> h.days.any { it.dateOutcome != null } }) {
                    result = candidate.copy(day = 6)
                    break
                }
            }
            val snapshot = assertNotNull(result)
            val file = Files.createTempFile("student-romance", ".json")
            try {
                Persistence.save(file, snapshot)
                val loaded = Persistence.load(file, catalog)
                assertEquals(snapshot, loaded)
                assertEquals(Simulation(catalog, workers = 1).step(snapshot), Simulation(catalog, workers = 4).step(loaded))
                val broken = snapshot.copy(households = snapshot.households.map { h -> h.copy(people = h.people.map { p -> if (p.id == 1L) p.copy(life = p.life!!.copy(studentRelationship = null)) else p }) })
                Persistence.save(file, broken)
                assertFailsWith<IllegalArgumentException> { Persistence.load(file, catalog) }
            } finally {
                Files.deleteIfExists(file)
            }
        }
}
