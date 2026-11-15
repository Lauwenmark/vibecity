package city.server

import city.core.*
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import kotlin.test.*

class LargeSavePersistenceTest {
    @Test fun `streaming encoding failure preserves existing save and removes partial file`() {
        val directory = Files.createTempDirectory("city-stream-failure")
        val file = directory.resolve("city.json")
        try {
            val city = City.generate(2000, 16).copy(pluginData = mapOf("text" to "é\n\"".repeat(100_000)))
            Persistence.save(file, city)
            assertEquals(city, Persistence.load(file, Catalog()))
            val invalid = city.copy(households = listOf(Household(17, listOf(Citizen(1, "Invalid", 30, position = 0, wellbeing = Wellbeing(satisfaction = Double.NaN))))))
            assertFailsWith<kotlinx.serialization.SerializationException> { Persistence.save(file, invalid) }
            assertEquals(city, Persistence.load(file, Catalog()))
            Files.list(directory).use { assertEquals(listOf(file), it.toList()) }
        } finally {
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }

    @Test fun `local saves larger than 64 MiB load without altering the file`() {
        val file = Files.createTempFile("large-city", ".json")
        try {
            val city = City.generate(2000, 16)
            Persistence.save(file, city)
            // Legal JSON whitespace crosses the old limit without a huge simulation fixture.
            Files.newBufferedWriter(file, APPEND).use { writer ->
                val padding = " ".repeat(8192)
                repeat(8192) { writer.write(padding) }
            }
            val size = Files.size(file)
            assertTrue(size > 64L * 1024 * 1024)
            assertEquals(city, Persistence.load(file, Catalog()))
            assertEquals(size, Files.size(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
