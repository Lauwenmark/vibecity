package city.client

import java.awt.Container
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.swing.JLabel
import javax.swing.SwingUtilities

/** Runs in an isolated working directory, never against the user's plugins or saves. */
fun main() {
    val jar = Files.createDirectories(Path.of("plugins")).resolve("broken.jar")
    JarOutputStream(Files.newOutputStream(jar)).use {
        it.putNextEntry(JarEntry("META-INF/services/city.core.CityPlugin"))
        it.write("missing.Plugin\n".toByteArray())
        it.closeEntry()
    }
    lateinit var window: CityWindow

    fun labels(root: Container): List<JLabel> = root.components.flatMap { (if (it is JLabel) listOf(it) else emptyList()) + (if (it is Container) labels(it) else emptyList()) }

    fun awaitText(fragment: String) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            var found = false
            SwingUtilities.invokeAndWait { found = labels(window).any { it.text.contains(fragment) } }
            if (found) return
            Thread.sleep(50)
        }
        error("UI did not display $fragment")
    }
    try {
        SwingUtilities.invokeAndWait {
            window = CityWindow()
            window.isVisible = true
            window.startLocal()
        }
        awaitText("Impossible d’ouvrir la ville")
        Files.delete(jar)
        SwingUtilities.invokeAndWait { window.startLocal() }
        awaitText("1 maire(s)")
        println("Plugin startup smoke OK: invalid provider reported, corrected startup connected")
    } finally {
        SwingUtilities.invokeAndWait { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) }
        Files.deleteIfExists(jar)
    }
}
