package city.client

import city.core.City
import city.core.surface
import java.awt.Graphics2D
import java.awt.RenderingHints
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

    /** Paint once at the frontmost footprint cell, after all its ground tiles. */
    fun draw(
        g: Graphics2D,
        city: City,
        index: Int,
        size: Int,
        projection: CityProjection,
    ) {
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
        if (index != front) return
        val image = images.getValue(tile.building!!)
        val width = (32 * size * projection.zoom).roundToInt()
        val height = (width.toDouble() * image.height / image.width).roundToInt()
        val x = projection.screenX(index % city.width, index / city.width)
        val bottom = projection.centerY(index % city.width, index / city.width, city.surface(index)) + (8 * projection.zoom).roundToInt()
        val copy = g.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            copy.drawImage(image, x - width / 2, bottom - height, width, height, null)
        } finally {
            copy.dispose()
        }
    }
}
