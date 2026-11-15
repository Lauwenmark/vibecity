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

    @Test fun `inspector distinguishes ordinary leisure and student appointment traces`() {
        val paths = listOf(TravelPath(listOf(68, 144), listOf(0, 10)), TravelPath(listOf(68, 80), listOf(0, 12)))
        val p = Citizen(1, "Camille", 15, position = 68, life = CitizenLife(-5500, Sex.FEMALE, 80.0, 1.0, LifeStage.SCHOOL, studentRelationship = StudentRelationship(2, 0, 62.0)))
        val date = StudentAppointment(1, 2, StudentDateKind.VISIT, 20, 1000)
        val day =
            CitizenDay(
                1,
                68,
                listOf(PlannedActivity(Need.LEISURE, 900, 45, 0), PlannedActivity(Need.LEISURE, 988, 75, 1, appointment = date)),
                listOf(ExecutedLeg(Need.LEISURE, false, 0, 900, 910, paths[0], status = "Sortie ordinaire"), ExecutedLeg(Need.LEISURE, false, 1, 988, 1000, paths[1], status = "Trajet de visite", appointmentId = 1)),
                StudentDateOutcome(StudentDateStatus.SUCCESS, 75, 1.0, 6.0),
            )
        val home = MobilityHome(17, 1, emptyList(), paths, days = listOf(day))
        val city = City(width = 16, height = 16, tiles = List(256) { Tile() }, households = listOf(Household(17, listOf(p), paths)), nextCitizenId = 3, mobility = Mobility(0, listOf(home), 2, 0))
        SwingUtilities.invokeAndWait {
            var shown: TravelPath? = null
            val panel = MobilityPanel { path, _, _ -> shown = path }
            panel.updateSnapshot(city)
            assertEquals("Loisirs", panel.table.getValueAt(0, 0))
            assertEquals("Visite au partenaire", panel.table.getValueAt(1, 0))
            panel.table.setRowSelectionInterval(1, 1)
            assertEquals(paths[1], shown)
            val text = texts(panel).joinToString("\n")
            for (expected in listOf("Relation étudiante : #2", "solidité", "Rendez-vous réussi", "Trajet de visite", "75 min ensemble", "Rendez-vous étudiants : 1")) assertContains(text, expected)
            assertFalse(text.contains("Sortie ordinaire"))
        }
    }
}
