package city.client

import java.awt.*
import java.awt.image.BufferedImage
import kotlin.math.*

/** EDT-owned, bounded LRU of screen-space chunks. Overlapping geometry is rendered in painter order
 * independently in each chunk, including a gutter to avoid antialiasing seams at their edges.
 */
internal class SceneCache {
    private data class Key(
        val x: Int,
        val y: Int,
    )

    private val images =
        object : LinkedHashMap<Key, BufferedImage>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, BufferedImage>?): Boolean = size > MAX_CHUNKS
        }
    var renderedChunks = 0L
        private set
    val size: Int get() = images.size

    fun clear() = images.clear()

    fun invalidate(bounds: Rectangle) {
        for (y in Math.floorDiv(bounds.y, CHUNK)..Math.floorDiv(bounds.y + bounds.height - 1, CHUNK)) {
            for (x in Math.floorDiv(bounds.x, CHUNK)..Math.floorDiv(bounds.x + bounds.width - 1, CHUNK)) {
                images.remove(Key(x, y))
            }
        }
    }

    fun paint(
        g: Graphics2D,
        clip: Rectangle,
        background: Color,
        renderer: CityRenderer,
    ) {
        if (clip.isEmpty) return
        for (y in Math.floorDiv(clip.y, CHUNK)..Math.floorDiv(clip.y + clip.height - 1, CHUNK)) {
            for (x in Math.floorDiv(clip.x, CHUNK)..Math.floorDiv(clip.x + clip.width - 1, CHUNK)) {
                val key = Key(x, y)
                val image =
                    images.getOrPut(key) {
                        val image = BufferedImage(CHUNK + 2 * GUTTER, CHUNK + 2 * GUTTER, BufferedImage.TYPE_INT_RGB)
                        val drawing = image.createGraphics()
                        try {
                            drawing.color = background
                            drawing.fillRect(0, 0, image.width, image.height)
                            drawing.translate(-x * CHUNK + GUTTER, -y * CHUNK + GUTTER)
                            val region = Rectangle(x * CHUNK - GUTTER, y * CHUNK - GUTTER, image.width, image.height)
                            drawing.clip = region
                            renderer.render(drawing, region)
                        } finally {
                            drawing.dispose()
                        }
                        renderedChunks++
                        image
                    }
                g.drawImage(
                    image,
                    x * CHUNK,
                    y * CHUNK,
                    (x + 1) * CHUNK,
                    (y + 1) * CHUNK,
                    GUTTER,
                    GUTTER,
                    CHUNK + GUTTER,
                    CHUNK + GUTTER,
                    null,
                )
            }
        }
    }

    companion object {
        private const val CHUNK = 256
        private const val GUTTER = 2

        // About 33 MiB of pixels, irrespective of map dimensions or navigation history.
        const val MAX_CHUNKS = 128
    }
}
