package city.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Corners in painter order: north (x,y), east (x+1,y), south (x+1,y+1), west (x,y+1). */
data class Surface(
    val north: Int,
    val east: Int,
    val south: Int,
    val west: Int,
) {
    val minimum: Int get() = min(min(north, east), min(south, west))
    val maximum: Int get() = max(max(north, east), max(south, west))

    /** The surface is triangulated along the north-south diagonal. */
    val centerHeight: Double get() = (north + south) / 2.0
    val flat: Boolean get() = minimum == maximum

    /** A simple planar ramp, as opposed to a corner or saddle. */
    val ramp: Boolean get() = !flat && ((north == west && east == south) || (north == east && west == south))

    fun allowsTransport(
        dx: Int,
        dy: Int,
    ): Boolean =
        flat ||
            (ramp && if (dx != 0) north == west && east == south else dy != 0 && north == east && west == south)
}

/** Authoritative shared vertices. Adjacent vertices, including diagonals, differ by at most one level. */
@Serializable
data class Terrain(
    val width: Int,
    val height: Int,
    val vertices: List<Int>,
) {
    init {
        require(width in 16..256 && height in 16..256)
        require(vertices.size == (width + 1) * (height + 1)) { "Invalid terrain dimensions" }
        require(vertices.all { it in 0..MAX_HEIGHT }) { "Invalid terrain height" }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val surface = surface(x, y)
                require(surface.maximum - surface.minimum <= 1) { "Terrain discontinuity at $x,$y" }
            }
        }
    }

    fun vertex(
        x: Int,
        y: Int,
    ): Int = vertices[y * (width + 1) + x]

    fun surface(
        x: Int,
        y: Int,
    ): Surface = Surface(vertex(x, y), vertex(x + 1, y), vertex(x + 1, y + 1), vertex(x, y + 1))

    /** Minimal monotone change satisfying the four edited vertices and all slope constraints. */
    fun shiftCell(
        x: Int,
        y: Int,
        delta: Int,
    ): Terrain {
        require(delta == 1 || delta == -1)
        val anchors = listOf(x to y, x + 1 to y, x + 1 to y + 1, x to y + 1)
        val heights = anchors.map { (ax, ay) -> vertex(ax, ay) + delta }
        require(heights.all { it in 0..MAX_HEIGHT }) { "Limite du relief" }
        return copy(
            vertices =
                vertices.mapIndexed { i, old ->
                    val vx = i % (width + 1)
                    val vy = i / (width + 1)
                    var lower = 0
                    var upper = MAX_HEIGHT
                    anchors.forEachIndexed { n, (ax, ay) ->
                        val distance = max(abs(vx - ax), abs(vy - ay))
                        lower = max(lower, heights[n] - distance)
                        upper = min(upper, heights[n] + distance)
                    }
                    old.coerceIn(lower, upper)
                },
        )
    }

    /** Levels a cell rectangle and smoothly connects it to the surrounding height field. */
    fun level(
        x: Int,
        y: Int,
        columns: Int,
        rows: Int,
        altitude: Int,
    ): Terrain {
        require(altitude in 0..MAX_HEIGHT)
        require(columns > 0 && rows > 0 && x >= 0 && y >= 0 && x + columns <= width && y + rows <= height)
        return copy(
            vertices =
                vertices.mapIndexed { i, old ->
                    val vx = i % (width + 1)
                    val vy = i / (width + 1)
                    val distance = max(maxOf(x - vx, 0, vx - x - columns), maxOf(y - vy, 0, vy - y - rows))
                    old.coerceIn(max(0, altitude - distance), min(MAX_HEIGHT, altitude + distance))
                },
        )
    }

    companion object {
        const val MAX_HEIGHT = 12

        fun flat(
            width: Int,
            height: Int,
            altitude: Int = 2,
        ): Terrain = Terrain(width, height, List((width + 1) * (height + 1)) { altitude })

        fun generate(
            seed: Long,
            size: Int,
        ): Terrain {
            val data =
                IntArray((size + 1) * (size + 1)) { i ->
                    val x = i % (size + 1)
                    val y = i / (size + 1)
                    val river = size * .24 + sin(y * .13 + seed % 17) * size * .055
                    val distance = abs(x - river)
                    val hills = (2 + 2 * sin(x * .09 + seed % 13) * cos(y * .1)).roundToInt().coerceIn(1, 5)
                    if (distance < 2) 0 else min(hills, (distance - 1).toInt())
                }
            // Lower steep vertices towards low neighbors. This preserves the river bed.
            val queue = ArrayDeque<Int>()
            data.indices.forEach { queue.add(it) }
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val x = i % (size + 1)
                val y = i / (size + 1)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0..size || ny !in 0..size) continue
                        val n = ny * (size + 1) + nx
                        if (data[n] > data[i] + 1) {
                            data[n] = data[i] + 1
                            queue.add(n)
                        }
                    }
                }
            }
            return Terrain(size, size, data.toList())
        }
    }
}

/** Legacy snapshots have no vertex field; new saves and server states always have one. */
fun City.surface(index: Int): Surface =
    terrain?.surface(index % width, index / width)
        ?: tiles[index].elevation.let { Surface(it, it, it, it) }

fun City.transportConnects(
    a: Int,
    b: Int,
): Boolean {
    val dx = b % width - a % width
    val dy = b / width - a / width
    return abs(dx) + abs(dy) == 1 && surface(a).allowsTransport(dx, dy) && surface(b).allowsTransport(-dx, -dy)
}

/** Synchronizes the compatibility altitude; water flags may also describe manually placed surface water. */
fun City.withTerrain(value: Terrain): City = copy(terrain = value, tiles = tiles.mapIndexed { i, t -> t.copy(elevation = value.surface(i % width, i / width).minimum) })

/** Atomic earthwork, including collateral changes required to keep shared edges continuous. */
fun City.terraform(
    x: Int,
    y: Int,
    tool: String,
    altitude: Int,
): City {
    val base = withSharedTerrain()
    val old = requireNotNull(base.terrain)
    val next =
        when (tool) {
            "raise" -> old.shiftCell(x, y, 1)
            "lower" -> old.shiftCell(x, y, -1)
            "level" -> old.level(x, y, 1, 1, altitude)
            else -> error("Unknown terrain tool")
        }
    val cost = old.vertices.indices.count { old.vertices[it] != next.vertices[it] } * 5L
    require(treasury >= cost) { "Trésorerie insuffisante : terrassement $cost $" }
    val changed = tiles.indices.filter { old.surface(it % width, it / width) != next.surface(it % width, it / width) }.toSet()
    require(changed.none { tiles[it].protectedTerrain() }) { "Le terrassement toucherait une zone, un bâtiment ou un réseau. Dégagez aussi les cases voisines concernées." }
    return base.copy(
        terrain = next,
        treasury = treasury - cost,
        tiles =
            base.tiles.mapIndexed { i, t ->
                if (i in changed) {
                    val s = next.surface(i % width, i / width)
                    t.copy(elevation = s.minimum, water = s.maximum == 0, trees = false)
                } else {
                    t
                }
            },
    )
}

internal fun Tile.protectedTerrain(): Boolean = building != null || zone != Zone.NONE || road || rail || powerLine || pipe || subway
