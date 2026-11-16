package city.client

import city.core.*
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.test.*

class ResidentNavigationTest {
    private val alice = Citizen(1, "Alice", 23, position = 17 * TRAVEL_MODE_COUNT, life = CitizenLife(-8400, Sex.FEMALE, 80.0, 1.1, LifeStage.WORKER))
    private val bob = Citizen(2, "Bob", 70, position = 17 * TRAVEL_MODE_COUNT, life = CitizenLife(-25000, Sex.MALE, 75.0, 1.0, LifeStage.RETIRED))

    private fun city(people: List<Citizen>) = City(width = 16, height = 16, tiles = List(256) { Tile() }, households = listOf(Household(17, people)), nextCitizenId = 3)

    @Test fun `index works before mobility exists and keeps identity through reordered snapshots`() {
        SwingUtilities.invokeAndWait {
            var position: Int? = null
            val panel = MobilityPanel { _, at, _ -> position = at }
            panel.updateSnapshot(city(listOf(alice, bob)), 17)
            assertEquals(2, panel.residentList.model.size)
            assertNull(position)
            panel.openResident(1)
            assertNull(position)
            panel.updateSnapshot(city(listOf(bob, alice)))
            assertEquals(2L, panel.residentList.selectedValue.id)
            assertNull(position)
            panel.back.doClick()
            assertNull(position)
            assertEquals(2L, panel.residentList.selectedValue.id)
            panel.openResident(0)
            panel.updateSnapshot(city(listOf(alice)))
            assertNull(position)
            panel.updateSnapshot(city(listOf(alice)), 18)
            assertEquals(0, panel.residentList.model.size)
        }
    }

    @Test fun `render actual index and portrait sheet with Metal`() {
        for (person in listOf(alice, bob)) ResidentPortraits.image(ResidentPortraits.key(person))
        SwingUtilities.invokeAndWait {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
            val panel = MobilityPanel { _, _, _ -> }
            panel.updateSnapshot(city(listOf(alice, bob)), 17)
            panel.setSize(900, 900)

            fun layout(c: Container) {
                c.doLayout()
                c.components.filterIsInstance<Container>().forEach(::layout)
            }

            fun capture(name: String) {
                layout(panel)
                val image = BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                panel.printAll(graphics)
                graphics.dispose()
                val file = File("build/reports/residents/$name.png")
                file.parentFile.mkdirs()
                ImageIO.write(image, "png", file)
            }
            capture("index")
            panel.openResident(0)
            capture("details")

            fun portraits(c: Container): List<ResidentPortrait> =
                c.components.flatMap {
                    (if (it is ResidentPortrait) listOf(it) else emptyList()) + (if (it is Container) portraits(it) else emptyList())
                }
            val portrait = portraits(panel).single { it.preferredSize.width == 256 }
            assertEquals(256, portrait.width)
            assertEquals(256, portrait.height)
            assertEquals(ResidentPortraits.key(alice), portrait.portraitKey)
            panel.updateSnapshot(city(listOf(alice.copy(age = 85), bob)))
            assertEquals(ResidentPortraits.key(alice.copy(age = 85)), portrait.portraitKey)
            assertEquals(alice.portraitCode, portrait.portraitKey!!.code)
        }
    }
}
