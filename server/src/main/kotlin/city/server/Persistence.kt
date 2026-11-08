package city.server

import city.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.*

/** A complete snapshot is written to a sibling temp file and atomically replaces the old save. */
object Persistence {
    fun save(
        path: Path,
        city: City,
    ) {
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "city-", ".tmp")
        try {
            Files.writeString(temporary, wireJson.encodeToString(city))
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun load(
        path: Path,
        catalog: Catalog,
    ): City {
        // Local snapshots include persistent citizens and routes and can exceed 64 MiB.
        // Keep loading compatible with save(), which has no serialized-size limit.
        val encoded = wireJson.parseToJsonElement(Files.readString(path)).jsonObject
        // Versions 3/4 had no temporal execution or persistent citizens. Rebuild only their derived cache.
        val legacyMobility = (encoded["mobility"] as? JsonObject)?.let { "executionVersion" !in it } == true
        val normalized = if (legacyMobility) JsonObject(encoded + ("mobility" to JsonNull)) else encoded
        val decoded = wireJson.decodeFromJsonElement<City>(normalized)
        val original = if (legacyMobility) decoded.copy(tiles = decoded.tiles.map { it.copy(traffic = 0) }) else decoded
        val city = original.withSharedTerrain()
        require(city.month >= 0 && city.revision >= 0 && city.debt in 0..100_000)
        require(city.rules.taxRate in 0..20 && city.rules.speed in 0..3 && city.rules.serviceFunding in 0..100)
        city.tiles.forEachIndexed { i, t ->
            require(t.elevation in 0..12 && t.occupants >= 0)
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
        return city
    }
}
