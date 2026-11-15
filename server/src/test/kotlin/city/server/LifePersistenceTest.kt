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
            val mother = Citizen(1, "Mother", 20, position = 68, life = CitizenLife(-7304, Sex.FEMALE, 80.0, 1.0, LifeStage.WORKER, lastSalary = 50.0, pregnancyDue = 1, pregnancyFather = 2))
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
}
