package city.client

import city.core.*
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** Inspect plans, actual traces and each citizen's weekly route memory without running client-side routing. */
internal class MobilityPanel(
    private val show: (TravelPath?, Int?, Int) -> Unit,
) : JPanel(BorderLayout(8, 8)) {
    private var city: City? = null
    private var homes = emptyList<MobilityHome>()
    private var refreshing = false
    private val home = JComboBox<String>()
    private val resident = JSpinner(SpinnerNumberModel(1, 1, 1, 1))
    private val hour = JSlider(0, 1439, 8 * 60)
    private val weekday = JComboBox((0..6).map(::dayName).toTypedArray())
    private val view = JComboBox(arrayOf("Plan du matin", "Parcours réalisé", "Carnet d’itinéraires"))
    private val returning = JCheckBox("Retour")
    private val remembered = JComboBox<String>()
    private var memories = emptyList<LearnedRoute>()
    private val heading = JLabel()
    private val detail = JTextArea()
    private val model =
        object : DefaultTableModel(arrayOf("Besoin", "Départ prévu", "Destination", "Durée prévue", "Réalisation"), 0) {
            override fun isCellEditable(
                row: Int,
                column: Int,
            ) = false
        }
    internal val table = JTable(model)

    init {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val controls = JPanel(GridLayout(0, 1, 4, 4))
        controls.add(JLabel("Domicile (x, y), puis habitant"))
        controls.add(home)
        controls.add(resident)
        controls.add(heading)
        controls.add(JLabel("Heure observée · journée exécutée"))
        hour.majorTickSpacing = 360
        hour.paintTicks = true
        controls.add(hour)
        val modes = JPanel(FlowLayout(FlowLayout.LEFT))
        modes.add(view)
        modes.add(weekday)
        modes.add(returning)
        controls.add(modes)
        controls.add(remembered)
        add(controls, BorderLayout.NORTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = 26
        add(JScrollPane(table), BorderLayout.CENTER)
        detail.isEditable = false
        detail.lineWrap = true
        detail.wrapStyleWord = true
        detail.rows = 7
        add(JScrollPane(detail), BorderLayout.SOUTH)
        home.addActionListener {
            if (!refreshing) {
                updateResidentLimit()
                refresh()
            }
        }
        resident.addChangeListener { if (!refreshing) refresh() }
        hour.addChangeListener { if (!refreshing) display() }
        weekday.addActionListener { if (!refreshing) refreshMemories() }
        view.addActionListener { if (!refreshing) refreshMemories() }
        returning.addActionListener { if (!refreshing) refreshMemories() }
        remembered.addActionListener { if (!refreshing) display() }
        table.selectionModel.addListSelectionListener { if (!refreshing && !it.valueIsAdjusting) refreshMemories() }
    }

    fun updateSnapshot(
        value: City?,
        preferredHome: Int? = null,
    ) {
        val old = preferredHome ?: homes.getOrNull(home.selectedIndex)?.cell
        val first = city == null
        city = value
        refreshing = true
        homes = value?.mobility?.homes.orEmpty()
        home.removeAllItems()
        homes.forEach { home.addItem("${it.cell % value!!.width}, ${it.cell / value.width} · ${it.residents} habitants") }
        home.selectedIndex =
            homes.indexOfFirst { it.cell == old }.let {
                if (it >= 0) {
                    it
                } else if (homes.isEmpty()) {
                    -1
                } else {
                    0
                }
            }
        if (first) weekday.selectedIndex = (value?.mobility?.day ?: 0) % 7
        updateResidentLimit()
        refreshing = false
        refresh()
    }

    private fun updateResidentLimit() {
        val count = homes.getOrNull(home.selectedIndex)?.residents ?: 1
        val selected = (resident.value as Int).coerceIn(1, count)
        val wasRefreshing = refreshing
        refreshing = true
        resident.model = SpinnerNumberModel(selected, 1, count, 1)
        resident.isEnabled = homes.isNotEmpty()
        refreshing = wasRefreshing
    }

    private fun refresh() {
        val selected = table.selectedRow.coerceAtLeast(0)
        refreshing = true
        model.rowCount = 0
        val c = city
        val h = homes.getOrNull(home.selectedIndex)
        val day = h?.days?.getOrNull((resident.value as Int) - 1)
        if (c != null && h != null && day != null) {
            for (activity in day.activities) {
                val path = activity.route?.let { h.paths[it] }
                val leg = day.legs.firstOrNull { it.need == activity.need && !it.returning }
                val status = leg?.status ?: if (activity.scheduled && activity.route != null) "Non effectué" else activity.reason
                model.addRow(arrayOf(activity.need.label, if (activity.scheduled) time(activity.departure) else "—", path?.destination?.let { "${it % c.width}, ${it / c.width}" } ?: "—", path?.let { "${it.duration} min" } ?: "—", status))
            }
            if (model.rowCount > 0) table.setRowSelectionInterval(selected.coerceAtMost(model.rowCount - 1), selected.coerceAtMost(model.rowCount - 1))
        }
        refreshing = false
        refreshMemories()
    }

    private fun refreshMemories() {
        val h = homes.getOrNull(home.selectedIndex)
        val person =
            city
                ?.households
                ?.firstOrNull { it.cell == h?.cell }
                ?.people
                ?.getOrNull((resident.value as Int) - 1)
        val need =
            h
                ?.days
                ?.getOrNull((resident.value as Int) - 1)
                ?.activities
                ?.getOrNull(table.selectedRow)
                ?.need
        memories =
            person
                ?.routes
                .orEmpty()
                .filter { it.weekday == weekday.selectedIndex && it.need == need && it.returning == returning.isSelected }
                .sortedByDescending { it.score }
        refreshing = true
        remembered.removeAllItems()
        memories.forEachIndexed { i, memory -> remembered.addItem("Itinéraire ${i + 1} · score ${memory.score} · ${h!!.paths[memory.path].duration} min") }
        remembered.isEnabled = view.selectedIndex == 2 && memories.isNotEmpty()
        weekday.isEnabled = view.selectedIndex == 2
        refreshing = false
        display()
    }

    private fun display() {
        val c = city
        val mobility = c?.mobility
        val h = homes.getOrNull(home.selectedIndex)
        val slot = (resident.value as Int) - 1
        val day = h?.days?.getOrNull(slot)
        val minute = hour.value
        if (c == null || mobility == null || h == null || day == null) {
            heading.text = "Aucun habitant à inspecter"
            detail.text = "Les déplacements seront exécutés au prochain jour de simulation. La mémoire des habitants est conservée après une construction."
            show(null, null, minute / 60)
            return
        }
        val person =
            c.households
                .firstOrNull { it.cell == h.cell }
                ?.people
                ?.getOrNull(slot)
        val activity = day.activities.getOrNull(table.selectedRow)
        val leg = day.legs.firstOrNull { it.need == activity?.need && it.returning == returning.isSelected }
        val path =
            when (view.selectedIndex) {
                1 -> leg?.trace
                2 -> memories.getOrNull(remembered.selectedIndex)?.let { h.paths[it.path] }
                else -> if (returning.isSelected) activity?.returnRoute?.let { h.paths[it] } else activity?.route?.let { h.paths[it] }
            }
        val position = day.positionAt(minute) / 4
        heading.text = "${person?.name ?: day.citizenId} · ${person?.age ?: "?"} ans · ${dayName(mobility.day)} ${epoch.plusDays(mobility.day.toLong())} · ${time(minute)}"
        detail.text =
            buildString {
                person?.wellbeing?.let { w ->
                    append("Satisfaction : %.1f %% (%+.2f / jour), %d jours d’insatisfaction, ancienneté %d jours.\n".format(w.satisfaction, w.trend, w.unhappyDays, w.residenceDays))
                    append("Emploi : ${w.workplace?.let { "${it % c.width}, ${it / c.width}" } ?: "aucun"} ; revenus et dépenses du foyer par personne : %.1f / %.1f $ par jour.\n".format(w.income, w.costs))
                    append("Déplacements : ${w.travelMinutes} min, ${w.distance} cases ; retard moyen %.1f min ; ${w.unmet} besoins non satisfaits.\n".format(w.averageDelay))
                    append("Délai moyen de satisfaction des activités accomplies : %.1f min (activité comprise).\n".format(w.averageFulfillmentMinutes))
                    val factors =
                        OpinionFactor.entries
                            .map { it to w.factors.getOrElse(it.ordinal) { 0.0 } }
                            .filter { it.second > .05 }
                            .sortedByDescending { it.second }
                    append(if (factors.isEmpty()) "Aucun facteur négatif.\n" else factors.joinToString(" ; ") { (factor, value) -> "${factor.label} : −%.1f".format(value) } + "\n")
                }
                append("Position réelle : ${position % c.width}, ${position / c.width} (point blanc). Plan du matin, parcours réalisé et carnet sont distincts.\n")
                if (leg != null) {
                    append("${leg.status} ; départ ${time(leg.departure)}, arrivée ${leg.arrival?.let(::time) ?: "non atteinte"}. Attente : ${leg.waitMinutes} min.\n")
                    append(leg.decisions.joinToString(" ; ") { "${time(it.minute)} : ${it.action}" })
                } else {
                    append(activity?.reason ?: "Sélectionnez un besoin.")
                }
                append("\nCarnet : choisissez un jour et un itinéraire pour consulter son score et son tracé, aller ou retour.\n")
                append("Ville : ${mobility.completedNeeds} besoins satisfaits, ${mobility.unmetNeeds} non satisfaits. Les voitures illustrent les passages effectivement admis.")
            }
        show(path, position, minute / 60)
    }

    private fun time(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
}
