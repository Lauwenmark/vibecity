package city.client

import city.core.*
import java.awt.*
import java.awt.image.BufferedImage
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.*

/** All parts of a recipe are resolved in one skin family, one sex and one age row. */
internal object ResidentPortraits {
    data class Key(
        val code: Long,
        val age: PortraitAge,
    )

    private data class LayerKey(
        val family: Int,
        val sex: Sex,
        val age: PortraitAge,
        val part: PortraitPart,
        val variant: Int,
    )

    private fun <K> bounded(limit: Int) =
        object : LinkedHashMap<K, BufferedImage>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, BufferedImage>?): Boolean = size > limit
        }

    private val portraits = bounded<Key>(128)
    private val layers = bounded<LayerKey>(128)
    private val waiting = mutableMapOf<Key, MutableSet<JComponent>>()
    private val deferredTargets = java.util.Collections.newSetFromMap(WeakHashMap<JComponent, Boolean>())
    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64), { task ->
            Thread(task, "resident-portraits").apply { isDaemon = true }
        })

    fun key(person: Citizen): Key =
        Key(
            person.life?.sex?.let { PortraitCodes.withSex(person.portraitCode, it) } ?: person.portraitCode,
            PortraitAge.forYears(person.age),
        )

    fun cached(key: Key): BufferedImage? = synchronized(portraits) { portraits[key] }

    /** Resource I/O/composition happens outside both cache locks and the Swing EDT. */
    fun image(key: Key): BufferedImage {
        require(PortraitCodes.valid(key.code))
        cached(key)?.let { return it }
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            for (part in PortraitPart.entries) {
                val layerKey = LayerKey(PortraitCodes.family(key.code), PortraitCodes.sex(key.code), key.age, part, PortraitCodes.variant(key.code, part))
                val layer =
                    synchronized(layers) { layers[layerKey] } ?: load(layerKey).also {
                        synchronized(layers) { layers[layerKey] = it }
                    }
                g.drawImage(layer, 0, 0, null)
            }
        } finally {
            g.dispose()
        }
        return synchronized(portraits) { portraits.getOrPut(key) { image } }
    }

    private fun load(key: LayerKey): BufferedImage {
        val sex = if (key.sex == Sex.FEMALE) "female" else "male"
        val path = "/city/client/portraits/bank-v1/family-${key.family}/$sex/${key.age.resource}/${key.part.resource}-${key.variant}.png"
        val image = requireNotNull(javaClass.getResourceAsStream(path)) { "Pièce de portrait absente : $path" }.use { ImageIO.read(it) }
        require(image != null && image.width == 256 && image.height == 256) { "Pièce de portrait invalide : $path" }
        return image
    }

    fun request(
        key: Key,
        target: JComponent,
    ) {
        if (cached(key) != null) return
        synchronized(waiting) {
            waiting[key]?.let {
                it.add(target)
                return
            }
            // Bound pending work independently of city population and scrolling speed.
            if (waiting.size >= 64) {
                // A newly opened detail view must be retried even when the
                // pending work belongs to a now-hidden list after fast scrolling.
                deferredTargets.add(target)
                return
            }
            waiting[key] =
                java.util.Collections
                    .newSetFromMap(WeakHashMap<JComponent, Boolean>())
                    .apply { add(target) }
        }
        worker.execute {
            try {
                image(key)
            } finally {
                val targets =
                    synchronized(waiting) {
                        val targets = waiting.remove(key)?.toList().orEmpty() + deferredTargets.toList()
                        deferredTargets.clear()
                        targets
                    }
                SwingUtilities.invokeLater { targets.forEach { it.repaint() } }
            }
        }
    }
}

internal class ResidentPortrait(
    size: Int,
) : JPanel() {
    internal var portraitKey: ResidentPortraits.Key? = null
        private set
    private var repaintTarget: JComponent = this

    init {
        preferredSize = Dimension(size, size)
        minimumSize = preferredSize
        maximumSize = preferredSize
        background = UIManager.getColor("TextField.inactiveBackground")
        getAccessibleContext().accessibleName = "Portrait de l’habitant"
    }

    fun show(
        person: Citizen?,
        target: JComponent = this,
    ) {
        portraitKey = person?.let(ResidentPortraits::key)
        repaintTarget = target
        getAccessibleContext().accessibleName = person?.let { "Portrait de ${it.name}, ${it.age} ans" } ?: "Aucun habitant"
        portraitKey?.let { ResidentPortraits.request(it, target) }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val key = portraitKey ?: return
        val image = ResidentPortraits.cached(key)
        if (image == null) {
            ResidentPortraits.request(key, repaintTarget)
            return
        }
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            val side = minOf(width, height)
            graphics.drawImage(image, (width - side) / 2, (height - side) / 2, side, side, null)
        } finally {
            graphics.dispose()
        }
    }
}
