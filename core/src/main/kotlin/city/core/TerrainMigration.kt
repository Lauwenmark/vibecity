package city.core

import kotlin.math.max

/** Converts independent legacy tile elevations to shared vertices without deleting development.
 * Connected developed cells become flat pads. Their altitude can rise to reconcile incompatible
 * legacy heights; neighboring open land is adjusted until all corners differ by at most one level.
 */
fun City.withSharedTerrain(): City {
    if (terrain != null) return this
    require(tiles.all { it.elevation in 0..Terrain.MAX_HEIGHT }) { "Invalid legacy altitude" }
    val stride = width + 1
    val count = stride * (height + 1)
    val parents = IntArray(count) { it }

    fun root(index: Int): Int {
        var i = index
        while (parents[i] != i) {
            parents[i] = parents[parents[i]]
            i = parents[i]
        }
        return i
    }

    fun unite(
        a: Int,
        b: Int,
    ) {
        parents[root(b)] = root(a)
    }

    fun corners(i: Int): IntArray {
        val n = (i / width) * stride + i % width
        return intArrayOf(n, n + 1, n + stride, n + stride + 1)
    }
    val data = IntArray(count)
    tiles.forEachIndexed { i, t ->
        val vertices = corners(i)
        vertices.forEach { data[it] = max(data[it], t.elevation) }
        if (t.protectedTerrain()) vertices.drop(1).forEach { unite(vertices[0], it) }
    }
    val groups = Array(count) { mutableListOf<Int>() }
    data.indices.forEach { groups[root(it)].add(it) }
    groups.filter { it.isNotEmpty() }.forEach { group ->
        val altitude = group.maxOf { data[it] }
        group.forEach { data[it] = altitude }
    }
    val queue = ArrayDeque<Int>()
    data.indices.forEach { queue.add(it) }
    while (queue.isNotEmpty()) {
        val i = queue.removeFirst()
        val x = i % stride
        val y = i / stride
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0..width || ny !in 0..height) continue
                val n = ny * stride + nx
                if (data[n] < data[i] - 1) {
                    val altitude = data[i] - 1
                    groups[root(n)].forEach {
                        data[it] = altitude
                        queue.add(it)
                    }
                }
            }
        }
    }
    return withTerrain(Terrain(width, height, data.toList()))
}
