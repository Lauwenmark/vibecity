package city.server

import city.core.*
import kotlinx.coroutines.runBlocking
import java.nio.file.*
import kotlin.test.*

class PluginTest {
    @Test fun `real jar contributes catalog and development rule`() =
        runBlocking {
            val directory = Files.createTempDirectory("city-plugins")
            val jar = directory.resolve("example.jar")
            try {
                Files.copy(Path.of(System.getProperty("examplePluginJar")), jar)
                val catalog = Catalog()
                PluginLoader(directory, catalog).use { plugins ->
                    assertEquals("Jardin communautaire", catalog["community_garden"].name)
                    assertEquals(listOf("example.garden-attraction"), plugins.rules.map { it.id })
                    var city = City(width = 16, height = 16, tiles = List(256) { Tile() }, terrain = Terrain.flat(16, 16))
                    city = Commands(catalog).apply(city, Request("BUILD", tool = "community_garden", x = 5, y = 5))
                    city = Commands(catalog).apply(city, Request("BUILD", tool = "coal", x = 1, y = 2))
                    city = Commands(catalog).apply(city, Request("BUILD", tool = "zone:RESIDENTIAL_LIGHT", x = 5, y = 6))
                    for (x in 0..5) city = Commands(catalog).apply(city, Request("BUILD", tool = "road", x = x, y = 7))
                    val chantier = Simulation(catalog, plugins.rules).step(city)
                    assertEquals(0, chantier.tiles[101].occupants)
                    assertNotNull(chantier.tiles[101].construction)
                    // The development bonus admits residents only into an already completed home.
                    city = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i == 101) t.copy(housingCapacity = 12) else t })
                    val standard = Simulation(catalog).step(city)
                    val extended = Simulation(catalog, plugins.rules).step(city)
                    assertEquals(standard.tiles[101].occupants + 1, extended.tiles[101].occupants)
                    assertTrue(extended.tiles[101].landValue > standard.tiles[101].landValue)
                    val save = directory.resolve("city.json")
                    Persistence.save(save, city)
                    assertEquals(city, Persistence.load(save, catalog))
                    assertFails { Persistence.load(save, Catalog()) }
                    Files.delete(save)
                }
            } finally {
                Files.deleteIfExists(jar)
                Files.delete(directory)
            }
        }
}
