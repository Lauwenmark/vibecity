package city.server

import city.core.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.nio.file.*

/** A complete snapshot is written to a sibling temp file and atomically replaces the old save. */
object Persistence {
    @OptIn(ExperimentalSerializationApi::class)
    fun save(
        path: Path,
        city: City,
    ) {
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "city-", ".tmp")
        try {
            Files.newOutputStream(temporary).buffered().use { wireJson.encodeToStream(city, it) }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun load(
        path: Path,
        catalog: Catalog,
    ): City {
        // Modern saves are decoded directly from the stream, without a String or JSON tree.
        val original =
            try {
                Files.newInputStream(path).use { wireJson.decodeFromStream<City>(it) }
            } catch (failure: SerializationException) {
                if (failure is MissingFieldException && failure.missingFields == listOf("travelModeCount")) {
                    // Old large saves also stay streamed: prepend only the missing encoding marker.
                    Files.newInputStream(path).use { input ->
                        var first = input.read()
                        while (first in listOf(9, 10, 13, 32)) first = input.read()
                        require(first == '{'.code)
                        SequenceInputStream(ByteArrayInputStream("{\"travelModeCount\":4,".toByteArray()), input).use {
                            wireJson.decodeFromStream<City>(it)
                        }
                    }
                } else {
                    // Very old mobility without execution traces is invalidated; malformed modern data is rejected.
                    val encoded = wireJson.parseToJsonElement(Files.readString(path)).jsonObject
                    val legacy = (encoded["mobility"] as? JsonObject)?.let { "executionVersion" !in it } == true
                    if (!legacy) throw failure
                    val normalized = JsonObject(encoded + ("mobility" to JsonNull) + (if ("travelModeCount" !in encoded) mapOf("travelModeCount" to JsonPrimitive(4)) else emptyMap()))
                    wireJson.decodeFromJsonElement<City>(normalized).let { city ->
                        city.copy(tiles = city.tiles.map { it.copy(traffic = 0) })
                    }
                }
            }
        val city = original.upgradeTravelNodes().withSharedTerrain()
        require(city.month >= 0 && city.revision >= 0 && city.debt in 0..100_000)
        require(city.rules.taxRate in 0..20 && city.rules.speed in 0..3 && city.rules.serviceFunding in 0..100)
        city.tiles.forEachIndexed { i, t ->
            require(t.elevation in 0..12 && t.occupants >= 0)
            t.residentialOccupancy?.let { count ->
                // Births may temporarily exceed housing capacity.
                require(count >= 0 && catalog[requireNotNull(t.building)].residents > 0)
            }
            require(t.elevation == city.surface(i).minimum) { "Altitude incompatible avec les sommets" }
            if (t.building != null) {
                val def = catalog[requireNotNull(t.building)]
                require(t.anchor in city.tiles.indices)
                val anchor = city.tiles[t.anchor]
                require(anchor.anchor == t.anchor && anchor.building == t.building)
                val dx = i % city.width - t.anchor % city.width
                val dy = i / city.width - t.anchor / city.width
                require(dx in 0 until def.size && dy in 0 until def.size)
                if (i == t.anchor) {
                    for (y in 0 until def.size) {
                        for (x in 0 until def.size) {
                            val n = city.index(i % city.width + x, i / city.width + y)
                            require(city.tiles[n].anchor == i && city.tiles[n].building == t.building)
                        }
                    }
                }
            } else {
                require(t.anchor == -1)
            }
        }
        city.mobility?.validate(city, catalog)
        city.validateCitizens()
        if (original.terrain == null) {
            var suffix = 0
            while (true) {
                val label = if (suffix == 0) ".pre-slopes.json" else ".pre-slopes-$suffix.json"
                val backup = path.resolveSibling(path.fileName.toString() + label)
                if (!Files.exists(backup)) {
                    Files.copy(path, backup)
                    break
                }
                if (Files.mismatch(path, backup) == -1L) break
                suffix++
            }
        }
        return city.shareRouteRegistries()
    }
}
