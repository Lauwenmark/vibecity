package city.client

import city.core.City
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Compact classpath assets are decoded once, never while visiting individual tiles. */
internal object MetroSprites {
    private fun load(name: String): BufferedImage =
        checkNotNull(MetroSprites::class.java.getResourceAsStream("/city/client/metro/$name.png")) {
            "Missing metro sprite: $name"
        }.use { checkNotNull(ImageIO.read(it)) }

    private val tunnels by lazy { Array(16) { load("tunnel-${it.toString().padStart(2, '0')}") } }
    private val station by lazy { load("station") }

    /** Bits are screen-facing NE, SE, SW, NW; rotate connectivity, not the bitmap. */
    fun mask(
        city: City,
        index: Int,
        rotation: Int,
    ): Int {
        val x = index % city.width
        val y = index / city.width
        var mask = 0
        if (y > 0 && city.tiles[index - city.width].subway) mask = mask or 1
        if (x + 1 < city.width && city.tiles[index + 1].subway) mask = mask or 2
        if (y + 1 < city.height && city.tiles[index + city.width].subway) mask = mask or 4
        if (x > 0 && city.tiles[index - 1].subway) mask = mask or 8
        val turn = Math.floorMod(rotation, 4)
        return ((mask shl turn) or (mask ushr (4 - turn))) and 15
    }

    fun tunnel(
        g: Graphics2D,
        polygon: Polygon,
        mask: Int,
    ) {
        val image = tunnels[mask]
        // The two triangles follow the same shared terrain vertices as CityProjection.
        val xs = intArrayOf(128, 256, 128, 0)
        val ys = intArrayOf(0, 64, 128, 64)
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

    fun station(
        g: Graphics2D,
        x: Int,
        y: Int,
        zoom: Double,
    ) {
        val width = (32 * zoom).roundToInt()
        val height = (26 * zoom).roundToInt()
        val copy = g.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            copy.drawImage(station, x - width / 2, y + (8 * zoom).roundToInt() - height, width, height, null)
        } finally {
            copy.dispose()
        }
    }
}
