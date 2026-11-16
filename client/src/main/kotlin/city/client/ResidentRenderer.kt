package city.client

import city.core.*
import java.awt.*
import javax.swing.*

internal fun residentSex(person: Citizen): String =
    when (person.life?.sex) {
        Sex.FEMALE -> "Féminin"
        Sex.MALE -> "Masculin"
        null -> "Sexe non renseigné"
    }

internal fun residentOccupation(person: Citizen): String =
    when (person.life?.stage) {
        LifeStage.CHILD -> {
            "Petite enfance"
        }

        LifeStage.SCHOOL -> {
            "Scolarité"
        }

        LifeStage.APPLICANT -> {
            "En orientation"
        }

        LifeStage.STUDENT -> {
            "Études : ${person.life
                ?.study
                ?.course
                ?.label ?: "supérieures"}"
        }

        LifeStage.WORKER -> {
            if (person.wellbeing.workplace == null) "Sans emploi" else "En emploi"
        }

        LifeStage.RETIRED -> {
            "Retraite"
        }

        null -> {
            if (person.wellbeing.workplace != null) "En emploi" else "Occupation non renseignée"
        }
    }

internal class ResidentRenderer :
    JPanel(BorderLayout(12, 4)),
    ListCellRenderer<Citizen> {
    private val name = JLabel()
    private val identity = JLabel()
    private val occupation = JLabel()
    private val happiness = JProgressBar(0, 100)
    private val labels = JPanel(GridLayout(3, 1))
    private val residentPortrait = ResidentPortrait(64)

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        val portrait = JPanel(GridBagLayout())
        portrait.isOpaque = false
        portrait.add(residentPortrait)
        add(portrait, BorderLayout.WEST)
        labels.isOpaque = false
        labels.add(name)
        labels.add(identity)
        labels.add(occupation)
        add(labels)
        happiness.isStringPainted = true
        happiness.preferredSize = Dimension(150, 24)
        val right = JPanel(GridBagLayout())
        right.isOpaque = false
        right.add(happiness)
        add(right, BorderLayout.EAST)
    }

    override fun paintComponent(g: Graphics) {
        doLayout()
        super.paintComponent(g)
    }

    override fun doLayout() {
        super.doLayout()

        fun layoutChildren(parent: Container) {
            parent.components.filterIsInstance<Container>().forEach {
                it.doLayout()
                layoutChildren(it)
            }
        }
        layoutChildren(this)
    }

    override fun getListCellRendererComponent(
        list: JList<out Citizen>,
        value: Citizen,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        residentPortrait.show(value, list)
        background = if (isSelected) list.selectionBackground else list.background
        val ink = if (isSelected) list.selectionForeground else list.foreground
        for (label in listOf(name, identity, occupation)) label.foreground = ink
        name.text = value.name
        name.font = list.font.deriveFont(Font.BOLD)
        identity.text = "${value.age} ans · ${residentSex(value)}"
        occupation.text = residentOccupation(value)
        happiness.value = value.wellbeing.satisfaction.toInt()
        happiness.string = "Bonheur : %.0f %%".format(value.wellbeing.satisfaction)
        getAccessibleContext().accessibleName = "${value.name}, ${identity.text}, ${occupation.text}, ${happiness.string}"
        return this
    }
}
