package city.client

import city.core.*
import city.server.CityServer
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.nio.file.*
import javax.imageio.ImageIO
import javax.swing.*

private lateinit var window: CityWindow

/** Exercises the actual JFrame, TCP connection, tool palette and authoritative budget action. */
fun main() =
    runBlocking {
        var initial = demoCity()
        repeat(18) { initial = Simulation(Catalog()).step(initial) }
        val server = CityServer(initial.copy(rules = initial.rules.copy(speed = 0)), port = 0, savePath = null)
        try {
            SwingUtilities.invokeAndWait {
                window = CityWindow()
                window.setSize(1450, 900)
                window.isVisible = true
                window.connect("127.0.0.1", server.port, "Validation Swing")
            }

            fun components(root: Container): List<Component> = root.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }

            fun awaitText(fragment: String) {
                val deadline = System.nanoTime() + 10_000_000_000
                while (System.nanoTime() < deadline) {
                    var found = false
                    SwingUtilities.invokeAndWait { found = components(window).filterIsInstance<JLabel>().any { it.text.contains(fragment) } }
                    if (found) return
                    Thread.sleep(50)
                }
                error("UI did not display $fragment")
            }
            awaitText("1 maire(s)")
            SwingUtilities.invokeAndWait {
                val canvas = components(window).filterIsInstance<CityCanvas>().single()
                val snapshot = requireNotNull(canvas.city)
                check(!canvas.trafficAnimationRunning)
                canvas.city = snapshot.copy(rules = snapshot.rules.copy(speed = 1))
                check(canvas.trafficAnimationRunning)
                canvas.layer = Layer.WATER
                check(!canvas.trafficAnimationRunning)
                canvas.layer = Layer.SURFACE
                val toggle = components(window).filterIsInstance<JCheckBox>().single { it.text == "Voitures" }
                toggle.doClick()
                check(!canvas.trafficAnimationRunning)
                toggle.doClick()
                check(canvas.trafficAnimationRunning)
                window.isVisible = false
                check(!canvas.trafficAnimationRunning)
                window.isVisible = true
                check(canvas.trafficAnimationRunning)
                canvas.city = snapshot
                check(!canvas.trafficAnimationRunning)
            }
            SwingUtilities.invokeAndWait {
                components(window).filterIsInstance<JButton>().single { it.text == "Habitants / trajets…" }.doClick()
                val dialog = window.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible }
                val table = components(dialog).filterIsInstance<JTable>().single()
                check(table.rowCount == 6)
                val courses = (0 until table.rowCount).single { table.getValueAt(it, 0) == "Courses" }
                table.setRowSelectionInterval(courses, courses)
                components(dialog).filterIsInstance<JSlider>().single().value = 18 * 60
                val canvas = components(window).filterIsInstance<CityCanvas>().single()
                check(canvas.residentPosition != null)
                dialog.validate()
                val image = BufferedImage(dialog.width, dialog.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                dialog.paint(graphics)
                graphics.dispose()
                Files.createDirectories(Path.of("build/previews"))
                ImageIO.write(image, "png", Path.of("build/previews/mobility.png").toFile())
                dialog.dispose()
            }
            SwingUtilities.invokeAndWait { components(window).filterIsInstance<JButton>().single { it.text == "Emprunter" }.doClick() }
            awaitText("BOND")
            SwingUtilities.invokeAndWait {
                components(window).filterIsInstance<JToggleButton>().single { it.text.startsWith("Petit parc") }.doClick()
                val canvas = components(window).filterIsInstance<CityCanvas>().single()
                val x = 32
                val y = 30
                val screenX = 64 * 16 + 70 + (x - y) * 16
                val screenY = 85 + (x + y) * 8 - 6
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
            }
            awaitText("Validation Swing : park")
            for ((label, tool) in listOf("Élever" to "raise", "Aplanir" to "level")) {
                SwingUtilities.invokeAndWait {
                    components(window).filterIsInstance<JToggleButton>().single { it.text.startsWith(label) }.doClick()
                    val canvas = components(window).filterIsInstance<CityCanvas>().single()
                    val city = requireNotNull(canvas.city)
                    val projection = CityProjection(city.width, city.height, 1.0)
                    val x = 26
                    val y = 35
                    val screenX = projection.screenX(x, y)
                    val screenY = projection.centerY(x, y, city.surface(city.index(x, y)))
                    canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
                    canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
                }
                awaitText("Validation Swing : $tool")
            }
            SwingUtilities.invokeAndWait {
                check(components(window).filterIsInstance<JToggleButton>().any { it.text.contains("Centrale à charbon") })
                val image = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                window.paint(graphics)
                graphics.dispose()
                Files.createDirectories(Path.of("build/previews"))
                ImageIO.write(image, "png", Path.of("build/previews/application.png").toFile())
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
            }
            println("Swing smoke OK: connected, catalog loaded, shared bond, construction and terrain edits acknowledged, screenshot rendered.")
        } finally {
            if (::window.isInitialized) SwingUtilities.invokeAndWait { window.dispose() }
            server.shutdown()
        }
    }
