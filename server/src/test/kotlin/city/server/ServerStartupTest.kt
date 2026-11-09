package city.server

import city.core.*
import java.nio.file.Files
import kotlin.test.*

class ServerStartupTest {
    @Test fun `size 256 generates a full sized new map with either option syntax`() {
        val directory = Files.createTempDirectory("server-startup")
        try {
            for (args in listOf(arrayOf("--size", "256"), arrayOf("--size=256"))) {
                val city = loadServerCity(args, directory.resolve("new.json"), Catalog()) {}
                assertEquals(256, city.width)
                assertEquals(256, city.height)
                assertEquals(256 * 256, city.tiles.size)
                assertEquals(256, assertNotNull(city.terrain).width)
            }
        } finally {
            Files.delete(directory)
        }
    }

    @Test fun `existing save is preserved and ignored generation options are explained`() {
        val directory = Files.createTempDirectory("server-startup")
        val path = directory.resolve("existing.json")
        try {
            Persistence.save(path, City.generate(size = 64))
            val original = Files.readAllBytes(path)
            val messages = mutableListOf<String>()
            val city = loadServerCity(arrayOf("--size", "256"), path, Catalog(), messages::add)
            assertEquals(64, city.width)
            assertEquals(64, city.height)
            assertContentEquals(original, Files.readAllBytes(path))
            assertTrue(messages.any { "Loaded save" in it && "64×64" in it })
            assertTrue(messages.any { "ignored" in it && "--save" in it })
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }

    @Test fun `missing option values are rejected`() {
        for (args in listOf(arrayOf("--size"), arrayOf("--size="), arrayOf("--size", "--save", "city.json"))) {
            assertFailsWith<IllegalArgumentException> { serverOption(args, "--size") }
        }
    }
}
