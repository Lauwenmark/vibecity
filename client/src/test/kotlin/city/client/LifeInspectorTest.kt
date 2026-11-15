package city.client

import city.core.*
import java.awt.Component
import java.awt.Container
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.*

class LifeInspectorTest {
    private fun texts(component: Component): List<String> =
        (if (component is JTextArea) listOf(component.text) else emptyList()) +
            (if (component is Container) component.components.flatMap(::texts) else emptyList())

    @Test fun `inspector shows individual education family income and age cohorts`() {
        val p =
            Citizen(
                1,
                "Camille",
                20,
                position = 68,
                life =
                    CitizenLife(
                        -7304,
                        Sex.FEMALE,
                        85.0,
                        1.25,
                        LifeStage.STUDENT,
                        study = Study(Course.IT, 35, started = 0, points = 50.0, attendedDays = 40, scheduledDays = 45),
                        partner = 2,
                        pregnancyDue = 200,
                        pregnancyFather = 2,
                    ),
            )
        val day = CitizenDay(1, 68, listOf(PlannedActivity(Need.REST, 1320, 0)))
        val city =
            City(
                width = 16,
                height = 16,
                tiles = List(256) { Tile() },
                day = 46,
                households = listOf(Household(17, listOf(p))),
                nextCitizenId = 3,
                mobility = Mobility(0, listOf(MobilityHome(17, 1, emptyList(), days = listOf(day))), 0, 0, day = 45),
            )
        SwingUtilities.invokeAndWait {
            val panel = MobilityPanel { _, _, _ -> }
            panel.updateSnapshot(city)
            val text = texts(panel).joinToString("\n")
            for (expected in listOf("NI", "CA", "Informatique", "40/45", "Partenaire : #2", "Naissance prévue", "18–24 ans : 1")) assertContains(text, expected)
            panel.updateSnapshot(city.copy(households = listOf(Household(17, listOf(p.copy(age = 25))))))
            assertContains(texts(panel).joinToString("\n"), "25–44 ans : 1")
        }
    }
}
