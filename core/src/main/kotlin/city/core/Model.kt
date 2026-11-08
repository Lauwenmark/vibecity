package city.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
enum class Zone(
    val capacity: Int,
) {
    NONE(0),
    RESIDENTIAL_LIGHT(12),
    RESIDENTIAL_DENSE(80),
    COMMERCIAL_LIGHT(8),
    COMMERCIAL_DENSE(60),
    INDUSTRIAL_LIGHT(12),
    INDUSTRIAL_DENSE(70),
    ;

    val residential get() = name.startsWith("RESIDENTIAL")
    val commercial get() = name.startsWith("COMMERCIAL")
    val industrial get() = name.startsWith("INDUSTRIAL")
}

/** A value snapshot. Server systems replace cells; neither clients nor plugins own mutable city state. */
@Serializable
data class Tile(
    /** Compatibility altitude: minimum of the shared corners when City.terrain is present. */
    val elevation: Int = 2,
    val water: Boolean = false,
    val trees: Boolean = false,
    val zone: Zone = Zone.NONE,
    val road: Boolean = false,
    val rail: Boolean = false,
    val powerLine: Boolean = false,
    val pipe: Boolean = false,
    val subway: Boolean = false,
    val building: String? = null,
    val anchor: Int = -1,
    val occupants: Int = 0,
    val powered: Boolean = false,
    val watered: Boolean = false,
    val pollution: Int = 0,
    val crime: Int = 0,
    val landValue: Int = 50,
    val traffic: Int = 0,
    val fire: Int = 0,
)

@Serializable
data class Rules(
    val taxRate: Int = 7,
    val serviceFunding: Int = 100,
    val growthRate: Int = 4,
    val disasters: Boolean = false,
    val speed: Int = 1,
)

@Serializable
data class Statistics(
    val population: Int = 0,
    val jobs: Int = 0,
    val income: Long = 0,
    val expenses: Long = 0,
    val residentialDemand: Int = 50,
    val commercialDemand: Int = 30,
    val industrialDemand: Int = 50,
    val powerCapacity: Int = 0,
    val waterCapacity: Int = 0,
    val unemployment: Int = 0,
)

@Serializable
data class City(
    val width: Int = 64,
    val height: Int = 64,
    val seed: Long = 2000,
    val tiles: List<Tile>,
    val revision: Long = 0,
    val month: Int = 0,
    val treasury: Long = 100_000,
    val debt: Long = 0,
    val rules: Rules = Rules(),
    val stats: Statistics = Statistics(),
    val history: List<Statistics> = emptyList(),
    val notices: List<String> = emptyList(),
    val pluginData: Map<String, String> = emptyMap(),
    val terrain: Terrain? = null,
) {
    init {
        require(width in 16..256 && height in 16..256 && tiles.size == width * height)
        require(terrain == null || (terrain.width == width && terrain.height == height))
    }

    fun index(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until width && y in 0 until height) { "Hors de la carte" }
        return y * width + x
    }

    fun neighbors(i: Int): List<Int> =
        buildList {
            if (i % width > 0) add(i - 1)
            if (i % width < width - 1) add(i + 1)
            if (i >= width) add(i - width)
            if (i + width < tiles.size) add(i + width)
        }

    companion object {
        /** Seeded continuous hills with a meandering river; no external terrain assets. */
        fun generate(
            seed: Long = 2000,
            size: Int = 64,
        ): City {
            require(size in 16..256)
            val terrain = Terrain.generate(seed, size)
            val tiles =
                List(size * size) { i ->
                    val surface = terrain.surface(i % size, i / size)
                    val water = surface.maximum == 0
                    Tile(elevation = surface.minimum, water = water, trees = !water && noise(seed, i, 0) % 11 == 0)
                }
            return City(width = size, height = size, seed = seed, tiles = tiles, terrain = terrain)
        }
    }
}

/** Stable per-cell randomness makes results independent of worker scheduling. */
fun noise(
    seed: Long,
    index: Int,
    month: Int,
): Int {
    var n = seed xor (index.toLong() * 0x45d9f3b) xor (month.toLong() * 0x119de1f3)
    n = (n xor (n ushr 16)) * 0x45d9f3b
    return ((n xor (n ushr 16)) and 0x7fffffff).toInt()
}

@Serializable
data class BuildingDef(
    val id: String,
    val name: String,
    val cost: Int,
    val upkeep: Int,
    val size: Int = 1,
    val color: Int = 0x8198a3,
    val power: Int = 0,
    val water: Int = 0,
    val pollution: Int = 0,
    val service: String = "",
    val radius: Int = 0,
    val year: Int = 1900,
    val residents: Int = 0,
)

/** Registry is frozen before the server starts accepting connections. Clients receive its catalog. */
class Catalog(
    definitions: List<BuildingDef> = standardBuildings(),
) {
    private val entries = mutableMapOf<String, BuildingDef>()
    private var frozen = false

    init {
        definitions.forEach(::register)
    }

    /** Prevents runtime registry mutation while parallel simulation tasks read it. */
    fun freeze() {
        frozen = true
    }

    fun register(def: BuildingDef) {
        check(!frozen) { "Catalog is frozen for this server session" }
        require(def.id.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
        require(def.cost >= 0 && def.upkeep >= 0 && def.size in 1..6 && def.radius in 0..64)
        require(def.power >= 0 && def.water >= 0 && def.residents >= 0)
        entries[def.id] = def
    }

    operator fun get(id: String): BuildingDef = entries[id] ?: error("Bâtiment inconnu : $id")

    fun all(): List<BuildingDef> = entries.values.sortedBy { it.id }
}

fun standardBuildings(): List<BuildingDef> =
    listOf(
        BuildingDef("coal", "Centrale à charbon", 4000, 80, 4, 0x737680, power = 1200, pollution = 80),
        BuildingDef("oil", "Centrale au fioul", 6600, 100, 4, 0x6b7e89, power = 1800, pollution = 65),
        BuildingDef("gas", "Centrale à gaz", 2000, 55, 3, 0x8d9ba2, power = 600, pollution = 25, year = 1950),
        BuildingDef("nuclear", "Centrale nucléaire", 15000, 180, 4, 0xd1c9a6, power = 5000, year = 1955),
        BuildingDef("wind", "Éolienne", 100, 4, 1, 0xd8e8e3, power = 30, year = 1980),
        BuildingDef("solar", "Centrale solaire", 1300, 30, 3, 0x405c98, power = 250, year = 1990),
        BuildingDef("fusion", "Centrale à fusion", 40000, 250, 4, 0x8dd9dd, power = 15000, year = 2050),
        BuildingDef("hydro", "Barrage hydroélectrique", 400, 8, 1, 0x8aaebd, power = 100),
        BuildingDef("microwave", "Centrale micro-ondes", 28000, 220, 4, 0xadc2dd, power = 7000, year = 2020),
        BuildingDef("pump", "Station de pompage", 100, 6, 1, 0x63bcd6, water = 180),
        BuildingDef("tower", "Château d’eau", 250, 8, 1, 0x86bdd2, water = 60),
        BuildingDef("treatment", "Épuration", 500, 12, 2, 0x609aaa, service = "environment", radius = 8),
        BuildingDef("desalination", "Dessalement", 1000, 25, 3, 0x729dbb, water = 500),
        BuildingDef("police", "Commissariat", 500, 25, 3, 0x6497d5, service = "police", radius = 12),
        BuildingDef("fire", "Caserne de pompiers", 500, 25, 3, 0xd46d55, service = "fire", radius = 12),
        BuildingDef("hospital", "Hôpital", 500, 25, 3, 0xe0d9ca, service = "health", radius = 14),
        BuildingDef("school", "École", 250, 12, 3, 0xddb879, service = "education", radius = 10),
        BuildingDef("college", "Université", 1000, 35, 4, 0xc6a880, service = "education", radius = 20),
        BuildingDef("library", "Bibliothèque", 500, 10, 2, 0xc9aa8a, service = "education", radius = 7),
        BuildingDef("museum", "Musée", 1000, 15, 3, 0xd9c3aa, service = "park", radius = 10),
        BuildingDef("park", "Petit parc", 10, 1, 1, 0x4da568, service = "park", radius = 4),
        BuildingDef("large_park", "Grand parc", 150, 6, 3, 0x58a66c, service = "park", radius = 8),
        BuildingDef("zoo", "Zoo", 3000, 35, 4, 0xaab879, service = "park", radius = 15),
        BuildingDef("stadium", "Stade", 5000, 50, 4, 0xb7c5a4, service = "park", radius = 18),
        BuildingDef("prison", "Prison", 1000, 30, 4, 0x979b9c, service = "police", radius = 18),
        BuildingDef("bus", "Dépôt de bus", 200, 8, 2, 0xc7a277, service = "transport", radius = 8),
        BuildingDef("station", "Gare ferroviaire", 500, 15, 3, 0xb8997a, service = "transport", radius = 12),
        BuildingDef("metro", "Station de métro", 250, 10, 1, 0x8d8fb8, service = "transport", radius = 10, year = 1910),
        BuildingDef("airport", "Aéroport", 10000, 120, 6, 0xa6b4c1, service = "commerce", radius = 24, year = 1930),
        BuildingDef("seaport", "Port maritime", 5000, 65, 4, 0x8cabb5, service = "industry", radius = 24),
        BuildingDef("mayor", "Maison du maire", 1000, 5, 2, 0xd6baa0, service = "park", radius = 8),
        BuildingDef("cityhall", "Hôtel de ville", 3000, 20, 3, 0xd6c5a4, service = "park", radius = 12),
        BuildingDef("arcology", "Arcologie Plymouth", 100000, 500, 4, 0x89b6c4, residents = 10000, year = 2050),
        BuildingDef("forest_arcology", "Arcologie Forest", 120000, 600, 4, 0x61996a, residents = 15000, year = 2050),
        BuildingDef("darco", "Arcologie Darco", 150000, 750, 4, 0x9393aa, residents = 20000, year = 2050),
        BuildingDef("launch", "Arcologie Launch", 200000, 900, 4, 0xc6d0dd, residents = 30000, year = 2050),
    )
