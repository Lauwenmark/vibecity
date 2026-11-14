package city.client

import city.core.City
import city.core.transportConnects
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Ground-plane sprites with identical ports, decoded once for all scene chunks. */
internal object TransportSprites {
    private data class ProjectedKey(
        val kind: String,
        val mask: Int,
        val xs: List<Int>,
        val ys: List<Int>,
    )

    private val projected =
        object : LinkedHashMap<ProjectedKey, BufferedImage>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProjectedKey, BufferedImage>?) = size > 128
        }

    private val images by lazy {
        listOf("road", "rail", "rail-overlay").associateWith { kind ->
            Array(16) { mask ->
                val path = "/city/client/transport/$kind-${mask.toString().padStart(2, '0')}.png"
                checkNotNull(javaClass.getResourceAsStream(path)) { "Missing transport sprite: $path" }
                    .use { checkNotNull(ImageIO.read(it)) }
            }
        }
    }

    /** Screen-facing NE=1, SE=2, SW=4, NW=8; suppress connections forbidden by terrain. */
    fun mask(
        city: City,
        index: Int,
        rotation: Int,
        rail: Boolean,
    ): Int {
        val x = index % city.width
        val y = index / city.width
        var mask = 0

        fun connect(
            neighbor: Int,
            bit: Int,
        ) {
            val tile = city.tiles[neighbor]
            if ((if (rail) tile.rail else tile.road) && city.transportConnects(index, neighbor)) mask = mask or bit
        }
        if (y > 0) connect(index - city.width, 1)
        if (x + 1 < city.width) connect(index + 1, 2)
        if (y + 1 < city.height) connect(index + city.width, 4)
        if (x > 0) connect(index - 1, 8)
        val turn = Math.floorMod(rotation, 4)
        return ((mask shl turn) or (mask ushr (4 - turn))) and 15
    }

    fun draw(
        g: Graphics2D,
        polygon: Polygon,
        city: City,
        index: Int,
        rotation: Int,
    ) {
        val tile = city.tiles[index]
        if (tile.road) paint(g, polygon, "road", mask(city, index, rotation, rail = false))
        if (tile.rail) paint(g, polygon, if (tile.road) "rail-overlay" else "rail", mask(city, index, rotation, rail = true))
    }

    private fun paint(
        g: Graphics2D,
        polygon: Polygon,
        kind: String,
        mask: Int,
    ) {
        val bounds = polygon.bounds
        val key = ProjectedKey(kind, mask, polygon.xpoints.take(4).map { it - bounds.x }, polygon.ypoints.take(4).map { it - bounds.y })
        // Rasterize once independently of the caller's clip. Direct affine sampling can
        // round differently at scene-chunk boundaries, especially at fractional zoom.
        val prepared =
            synchronized(projected) {
                projected.getOrPut(key) {
                    BufferedImage(bounds.width + 1, bounds.height + 1, BufferedImage.TYPE_INT_ARGB).also { result ->
                        val local = result.createGraphics()
                        try {
                            project(local, Polygon(key.xs.toIntArray(), key.ys.toIntArray(), 4), kind, mask)
                        } finally {
                            local.dispose()
                        }
                    }
                }
            }
        g.drawImage(prepared, bounds.x, bounds.y, null)
    }

    private fun project(
        g: Graphics2D,
        polygon: Polygon,
        kind: String,
        mask: Int,
    ) {
        val image = images.getValue(kind)[mask]
        val xs = intArrayOf(128, 256, 128, 0)
        val ys = intArrayOf(0, 64, 128, 64)
        // Same two triangles as CityProjection, including rotated/sloping ground.
        for (indices in arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 3))) {
            val (a, b, c) = indices

            fun basis(
                x: IntArray,
                y: IntArray,
            ) = AffineTransform(
                (x[b] - x[a]).toDouble(),
                (y[b] - y[a]).toDouble(),
                (x[c] - x[a]).toDouble(),
                (y[c] - y[a]).toDouble(),
                x[a].toDouble(),
                y[a].toDouble(),
            )
            val transform = basis(polygon.xpoints, polygon.ypoints)
            transform.concatenate(basis(xs, ys).createInverse())
            val copy = g.create() as Graphics2D
            try {
                copy.clip(Polygon(indices.map { polygon.xpoints[it] }.toIntArray(), indices.map { polygon.ypoints[it] }.toIntArray(), 3))
                copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                copy.drawImage(image, transform, null)
            } finally {
                copy.dispose()
            }
        }
    }
}
