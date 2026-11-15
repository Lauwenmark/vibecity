package city.client

import city.core.City
import city.core.surface
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Screen-facing artwork, decoded once and shared by all scene chunks. */
internal object BuildingSprites {
    val ids = setOf("coal", "oil", "gas", "nuclear", "wind", "solar", "fusion", "hydro", "microwave", "pump")
    private val civicIds =
        setOf(
            "fire",
            "police",
            "school",
            "cityhall",
            "mayor",
            "hospital",
            "prison",
            "station",
            "stadium",
            "airport",
            "college",
            "treatment",
            "museum",
            "zoo",
            "park",
            "large_park",
            "library",
            "tower",
        )
    val allIds = ids + civicIds

    fun resourcePath(id: String) = "/city/client/${if (id in civicIds) "civic" else "utilities"}/$id.png"

    private val images by lazy {
        allIds.associateWith { id ->
            checkNotNull(javaClass.getResourceAsStream(resourcePath(id))) {
                "Missing building sprite: $id"
            }.use { checkNotNull(ImageIO.read(it)) }
        }
    }

    // Trace source alpha once: the mask cost and detail do not depend on camera zoom.
    private val silhouettes = mutableMapOf<String, Shape>()

    fun placement(
        city: City,
        index: Int,
        size: Int,
        projection: CityProjection,
    ): Rectangle? {
        val tile = city.tiles[index]
        val anchor = tile.anchor.takeIf { it in city.tiles.indices } ?: index
        val ax = anchor % city.width
        val ay = anchor / city.width
        val last = size - 1
        val front =
            when (projection.rotation) {
                1 -> city.index(ax + last, ay)
                2 -> anchor
                3 -> city.index(ax, ay + last)
                else -> city.index(ax + last, ay + last)
            }
        if (index != front) return null
        val image = images.getValue(tile.building!!)
        val width = (32 * size * projection.zoom).roundToInt()
        val height = (width.toDouble() * image.height / image.width).roundToInt()
        val x = projection.screenX(index % city.width, index / city.width)
        val bottom = projection.centerY(index % city.width, index / city.width, city.surface(index)) + (8 * projection.zoom).roundToInt()
        return Rectangle(x - width / 2, bottom - height, width, height)
    }

    fun silhouette(
        city: City,
        index: Int,
        size: Int,
        projection: CityProjection,
    ): Shape? {
        val bounds = placement(city, index, size, projection) ?: return null
        val id = city.tiles[index].building!!
        val image = images.getValue(id)
        val silhouette =
            silhouettes.getOrPut(id) {
                Area(
                    Path2D.Double().apply {
                        for (y in 0 until image.height) {
                            var x = 0
                            while (x < image.width) {
                                if ((image.getRGB(x, y) ushr 24) == 0) {
                                    x++
                                    continue
                                }
                                val start = x++
                                while (x < image.width && (image.getRGB(x, y) ushr 24) != 0) x++
                                // Include the bilinear interpolation fringe around source pixels.
                                append(Rectangle(start - 1, y - 1, x - start + 2, 3), false)
                            }
                        }
                    },
                )
            }
        return AffineTransform
            .getTranslateInstance(bounds.x.toDouble(), bounds.y.toDouble())
            .apply {
                scale(bounds.width.toDouble() / image.width, bounds.height.toDouble() / image.height)
            }.createTransformedShape(silhouette)
    }

    /** Paint once at the frontmost footprint cell, after all its ground tiles. */
    fun draw(
        g: Graphics2D,
        city: City,
        index: Int,
        size: Int,
        projection: CityProjection,
    ) {
        val bounds = placement(city, index, size, projection) ?: return
        val image = images.getValue(city.tiles[index].building!!)
        val copy = g.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            copy.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null)
        } finally {
            copy.dispose()
        }
    }
}
