package city.client

import city.core.*
import city.server.CityServer
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.ActionEvent
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
        exportToolIcons()
        var initial = demoCity()
        val simulation = Simulation(Catalog())

        fun hasResidentsAndTraffic(city: City): Boolean =
            city.households.any { it.people.isNotEmpty() } &&
                city.mobility?.aggregateLoads()?.any { it.mode == TravelMode.ROAD && it.hours.any { count -> count > 0 } } == true
        // The starter district now has real construction delays (at least 36
        // days). Eighteen days used to assert car animation in an empty town.
        for (day in 0 until 180) {
            initial = simulation.step(initial)
            if (hasResidentsAndTraffic(initial)) break
        }
        check(hasResidentsAndTraffic(initial)) { "Smoke fixture did not develop residents and road traffic" }
        val firstResident =
            initial.households
                .first { it.people.isNotEmpty() }
                .people
                .first()
        ResidentPortraits.image(ResidentPortraits.key(firstResident)) // Prepare screenshot pixels off the EDT.
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
                val satisfaction = components(window).filterIsInstance<JCheckBox>().single { it.text == "Satisfaction" }
                check(canvas.showSatisfaction)
                satisfaction.doClick()
                check(!canvas.showSatisfaction)
                satisfaction.doClick()
                check(canvas.showSatisfaction)
                check(!canvas.trafficAnimationRunning)
                canvas.city = snapshot.copy(rules = snapshot.rules.copy(speed = 1))
                check(canvas.trafficAnimationRunning)
                canvas.city = canvas.city!!.copy(mobility = null, revision = snapshot.revision + 1)
                check(canvas.trafficAnimationRunning) // Construction invalidation must not stop decorative cars.
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
                val canvas = components(window).filterIsInstance<CityCanvas>().single()
                val snapshot = requireNotNull(canvas.city)
                val household = snapshot.households.first { it.people.isNotEmpty() }
                val home = requireNotNull(snapshot.mobility).homes.single { it.cell == household.cell }
                val resident = household.people.first()
                val day = home.days.single { it.citizenId == resident.id }
                components(window).filterIsInstance<JToggleButton>().single { it.name == "tool:inspect" }.doClick()
                val projection = CityProjection(snapshot.width, snapshot.height, canvas.zoom, canvas.rotation)
                val x = household.cell % snapshot.width
                val y = household.cell / snapshot.width
                canvas.focusCell(household.cell)
                val screenX = projection.screenX(x, y)
                val screenY = projection.centerY(x, y, snapshot.surface(household.cell))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
                check(canvas.selected == household.cell)
                components(window).filterIsInstance<JButton>().single { it.text == "Habitants / trajets…" }.doClick()
                val dialog = window.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible }
                val residents = components(dialog).filterIsInstance<JList<*>>().single()
                check(residents.isShowing && residents.model.size == household.people.size)
                residents.selectedIndex = 0
                check((residents.selectedValue as Citizen).id == resident.id)
                val open = residents.inputMap.get(KeyStroke.getKeyStroke("ENTER"))
                residents.actionMap.get(open).actionPerformed(ActionEvent(residents, ActionEvent.ACTION_PERFORMED, "ENTER"))
                val portrait = components(dialog).filterIsInstance<ResidentPortrait>().single { it.preferredSize.width == 256 }
                check(portrait.portraitKey == ResidentPortraits.key(resident))
                check(ResidentPortraits.cached(requireNotNull(portrait.portraitKey)) != null)
                val tabs = components(dialog).filterIsInstance<JTabbedPane>().single()
                tabs.selectedIndex = tabs.indexOfTab("Déplacements")
                dialog.validate()
                val table = components(dialog).filterIsInstance<JTable>().single()
                check(table.isShowing && table.rowCount == day.activities.size)
                val courses = (0 until table.rowCount).single { table.getValueAt(it, 0) == "Courses" }
                table.setRowSelectionInterval(courses, courses)
                components(dialog).filterIsInstance<JSlider>().single().value = 18 * 60
                check(canvas.residentPosition == day.positionAt(18 * 60) / TRAVEL_MODE_COUNT)
                dialog.validate()
                val image = BufferedImage(dialog.width, dialog.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                dialog.paint(graphics)
                graphics.dispose()
                Files.createDirectories(Path.of("build/previews"))
                ImageIO.write(image, "png", Path.of("build/previews/mobility.png").toFile())
                components(dialog).filterIsInstance<JButton>().single { it.text == "← Habitants de cette case" }.doClick()
                check(residents.isShowing && canvas.residentPosition == null)
                dialog.dispose()
            }
            SwingUtilities.invokeAndWait { components(window).filterIsInstance<JButton>().single { it.text == "Emprunter" }.doClick() }
            awaitText("BOND")
            SwingUtilities.invokeAndWait {
                components(window).filterIsInstance<JToggleButton>().single { it.name == "tool:park" }.doClick()
                val canvas = components(window).filterIsInstance<CityCanvas>().single()
                val x = 32
                val y = 30
                val screenX = 64 * 16 + 70 + (x - y) * 16
                val screenY = 85 + (x + y) * 8 - 6
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, screenX, screenY, 1, false, MouseEvent.BUTTON1))
            }
            awaitText("Validation Swing : park")
            for (tool in listOf("raise", "level")) {
                SwingUtilities.invokeAndWait {
                    components(window).filterIsInstance<JToggleButton>().single { it.name == "tool:$tool" }.doClick()
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
                check(components(window).filterIsInstance<JToggleButton>().any { it.name == "tool:coal" && it.icon != null && it.accessibleContext.accessibleName == "Centrale à charbon" })
                val canvas = components(window).filterIsInstance<CityCanvas>().single()
                components(window).filterIsInstance<JButton>().single { it.text == "↷ 90°" }.doClick()
                check(canvas.rotation == 1)
                components(window).filterIsInstance<JButton>().single { it.text == "↶ 90°" }.doClick()
                check(canvas.rotation == 0)
                canvas.rotation = 1
                canvas.zoom = 4.0
                window.validate()
                canvas.focusCell(32 * 64 + 32)
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
