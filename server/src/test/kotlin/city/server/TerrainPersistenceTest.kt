package city.server

import city.core.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.*

class TerrainPersistenceTest {
    @Test fun `legacy load creates an untouched backup then new terrain round trips`() {
        val directory = Files.createTempDirectory("legacy-terrain")
        val file = directory.resolve("city.json")
        val backup = directory.resolve("city.json.pre-slopes.json")
        try {
            val old = City(width = 16, height = 16, tiles = List(256) { Tile() })
            // Exactly the old schema, with no terrain field at all.
            val raw = wireJson.encodeToString(old).replace(",\"terrain\":null", "")
            Files.writeString(file, raw)
            val city = Persistence.load(file, Catalog())
            assertNotNull(city.terrain)
            assertEquals(raw, Files.readString(backup))
            assertEquals(raw, Files.readString(file))
            val changed = Commands(Catalog()).apply(city, Request("BUILD", tool = "raise", x = 8, y = 8))
            Persistence.save(file, changed)
            assertEquals(changed, Persistence.load(file, Catalog()))
            assertEquals(raw, Files.readString(backup))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(backup)
            Files.delete(directory)
        }
    }

    @Test fun `different legacy backups are preserved and identical ones reused`() {
        val directory = Files.createTempDirectory("legacy-backups")
        val file = directory.resolve("city.json")
        val backup = directory.resolve("city.json.pre-slopes.json")
        val numbered = directory.resolve("city.json.pre-slopes-1.json")
        try {
            Files.writeString(backup, "previous backup")
            val old = City(width = 16, height = 16, tiles = List(256) { Tile() })
            Persistence.save(file, old)
            Persistence.load(file, Catalog())
            Persistence.load(file, Catalog())
            assertEquals("previous backup", Files.readString(backup))
            assertEquals(Files.readString(file), Files.readString(numbered))
            assertFalse(Files.exists(directory.resolve("city.json.pre-slopes-2.json")))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(backup)
            Files.deleteIfExists(numbered)
            Files.delete(directory)
        }
    }

    @Test fun `inconsistent compatibility altitude does not load`() {
        val path = Files.createTempFile("invalid-terrain", ".json")
        try {
            val invalid = City(width = 16, height = 16, tiles = List(256) { Tile(elevation = 3) }, terrain = Terrain.flat(16, 16, 2))
            Persistence.save(path, invalid)
            assertFailsWith<IllegalArgumentException> { Persistence.load(path, Catalog()) }
        } finally {
            Files.delete(path)
        }
    }
}
