package city.core

/** Primitive min-heap ordered by nonnegative cost, then node, exactly like the routing comparator. */
internal class RouteQueue {
    private var values = LongArray(64)
    private var size = 0

    fun isNotEmpty(): Boolean = size > 0

    fun clear() {
        size = 0
    }

    fun add(
        node: Int,
        cost: Int,
    ) {
        require(node >= 0 && cost >= 0)
        val value = (cost.toLong() shl 32) or node.toLong()
        if (size == values.size) values = values.copyOf(values.size * 2)
        var child = size++
        while (child > 0) {
            val parent = (child - 1) / 2
            if (values[parent] <= value) break
            values[child] = values[parent]
            child = parent
        }
        values[child] = value
    }

    fun remove(): Long {
        check(size > 0)
        val result = values[0]
        val last = values[--size]
        var parent = 0
        while (parent < size / 2) {
            var child = parent * 2 + 1
            if (child + 1 < size && values[child + 1] < values[child]) child++
            if (last <= values[child]) break
            values[parent] = values[child]
            parent = child
        }
        if (size > 0) values[parent] = last
        return result
    }
}
