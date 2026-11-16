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
    private var cohortHomes: List<Household>? = null
    private var cohorts = emptyMap<String, Pair<Int, Double>>()
    private var socialMobility: Mobility? = null
    private var socialStatistics: StudentDateStatistics? = null
    private var refreshing = false
    private var selectedId: Long? = null
    private var selectedCell: Int? = null
    private var people = emptyList<Citizen>()
    internal val residentList = JList<Citizen>()
    private val indexTitle = JLabel()
    private val cards = CardLayout()
    private val pages = JPanel(cards)
    private var showingDetails = false
    private val listScroll = JScrollPane(residentList)
    internal val back = JButton("← Habitants de cette case")
    private val identity = JLabel()
    internal val residentPortrait = ResidentPortrait(256)
    private val happiness = JProgressBar(0, 100)
    private val summary = textArea()
    private val biography = textArea()
    private val statistics = textArea()
    private val memoryControls = JPanel(FlowLayout(FlowLayout.LEFT))
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
        val index = JPanel(BorderLayout(8, 8))
        index.add(indexTitle, BorderLayout.NORTH)
        residentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        residentList.fixedCellHeight = 100
        residentList.cellRenderer = ResidentRenderer()
        residentList.accessibleContext.accessibleName = "Habitants de la case"
        index.add(listScroll)
        index.add(JLabel("Cliquez sur un habitant ou appuyez sur Entrée pour ouvrir sa fiche."), BorderLayout.SOUTH)
        residentList.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val row = residentList.locationToIndex(e.point)
                    if (SwingUtilities.isLeftMouseButton(e) && row >= 0 && residentList.getCellBounds(row, row).contains(e.point)) openResident(row)
                }
            },
        )
        residentList.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openResident")
        residentList.actionMap.put(
            "openResident",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    openResident(residentList.selectedIndex)
                }
            },
        )
        val sheet = JPanel(BorderLayout(8, 12))
        val header = JPanel(BorderLayout(16, 8))
        header.add(residentPortrait, BorderLayout.WEST)
        val labels = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        heading.font = heading.font.deriveFont(Font.BOLD, 24f)
        labels.add(Box.createVerticalStrut(24))
        heading.alignmentX = Component.LEFT_ALIGNMENT
        identity.alignmentX = Component.LEFT_ALIGNMENT
        happiness.alignmentX = Component.LEFT_ALIGNMENT
        happiness.maximumSize = Dimension(500, 26)
        labels.add(heading)
        labels.add(Box.createVerticalStrut(16))
        labels.add(identity)
        happiness.isStringPainted = true
        labels.add(Box.createVerticalStrut(24))
        labels.add(happiness)
        header.add(labels)
        val top = JPanel(BorderLayout(8, 12))
        top.add(back, BorderLayout.NORTH)
        top.add(header)
        sheet.add(top, BorderLayout.NORTH)
        val tabs = JTabbedPane()
        tabs.addTab("Synthèse", JScrollPane(summary))
        tabs.addTab("Vie et études", JScrollPane(biography))
        val travel = JPanel(BorderLayout(8, 8))
        val controls = JPanel(GridLayout(0, 1, 4, 4))
        hour.majorTickSpacing = 360
        hour.paintTicks = true
        val clock = JLabel("Heure observée : 08:00")
        hour.addChangeListener { clock.text = "Heure observée : ${time(hour.value)}" }
        controls.add(clock)
        controls.add(hour)
        val modes = JPanel(FlowLayout(FlowLayout.LEFT))
        modes.add(view)
        modes.add(returning)
        controls.add(modes)
        memoryControls.add(weekday)
        memoryControls.add(remembered)
        memoryControls.isVisible = false
        controls.add(memoryControls)
        travel.add(controls, BorderLayout.NORTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = 26
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(detail))
        split.resizeWeight = .5
        travel.add(split)
        tabs.addTab("Déplacements", travel)
        tabs.addTab("Statistiques de la ville", JScrollPane(statistics))
        sheet.add(tabs)
        sheet.preferredSize = Dimension(780, 800)
        val sheetScroll = JScrollPane(sheet)
        sheetScroll.verticalScrollBar.unitIncrement = 20
        pages.add(index, "index")
        pages.add(sheetScroll, "details")
        add(pages)
        back.addActionListener { showIndex() }
        detail.isEditable = false
        detail.lineWrap = true
        detail.wrapStyleWord = true
        detail.rows = 5
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
        val first = city == null
        val oldId = selectedId
        val oldCell = selectedCell
        city = value
        homes = value?.mobility?.homes.orEmpty()
        selectedCell = preferredHome ?: selectedCell ?: value?.households?.firstOrNull()?.cell ?: homes.firstOrNull()?.cell
        people =
            value
                ?.households
                ?.firstOrNull { it.cell == selectedCell }
                ?.people
                .orEmpty()
        val scroll = listScroll.verticalScrollBar.value
        residentList.setListData(people.toTypedArray())
        val selected = people.indexOfFirst { it.id == oldId }
        residentList.selectedIndex =
            if (selected >= 0) {
                selected
            } else if (people.isNotEmpty()) {
                0
            } else {
                -1
            }
        selectedId = people.getOrNull(residentList.selectedIndex)?.id
        listScroll.verticalScrollBar.value = scroll
        indexTitle.text =
            if (value != null && selectedCell != null) {
                "Case ${selectedCell!! % value.width}, ${selectedCell!! / value.width} · ${people.size} habitants" +
                    if (people.isEmpty()) " · Aucun habitant à inspecter" else ""
            } else {
                "Aucun habitant à inspecter"
            }
        if (first) weekday.selectedIndex = Math.floorMod(value?.mobility?.day ?: 0, 7)
        if (preferredHome != null || oldCell != selectedCell || selected < 0) showIndex()
        refresh()
    }

    internal fun openResident(index: Int) {
        val person = people.getOrNull(index) ?: return
        residentList.selectedIndex = index
        selectedId = person.id
        showingDetails = true
        cards.show(pages, "details")
        refresh()
        back.requestFocusInWindow()
    }

    private fun showIndex() {
        showingDetails = false
        cards.show(pages, "index")
        show(null, null, -1)
        residentList.requestFocusInWindow()
    }

    private fun selectedHome() = homes.firstOrNull { it.cell == selectedCell }

    private fun selectedPerson() = people.firstOrNull { it.id == selectedId }

    private fun refresh() {
        val selected = table.selectedRow.coerceAtLeast(0)
        refreshing = true
        model.rowCount = 0
        val c = city
        val h = selectedHome()
        val day = h?.days?.firstOrNull { it.citizenId == selectedId }
        if (c != null && h != null && day != null) {
            for (activity in day.activities) {
                val path = activity.route?.let { h?.paths?.getOrNull(it) }
                val leg = day?.legs?.firstOrNull { activity.matches(it) && !it.returning }
                val status = (if (activity.appointment != null) day.dateOutcome?.status?.label else null) ?: leg?.status ?: if (activity.scheduled && activity.route != null) "Non effectué" else activity.reason
                model.addRow(arrayOf(activity.appointment?.kind?.label ?: activity.need.label, if (activity.scheduled) time(activity.departure) else "—", path?.destination?.let { "${it % c.width}, ${it / c.width}" } ?: "—", path?.let { "${it.duration} min" } ?: "—", status))
            }
            if (model.rowCount > 0) table.setRowSelectionInterval(selected.coerceAtMost(model.rowCount - 1), selected.coerceAtMost(model.rowCount - 1))
        }
        refreshing = false
        refreshMemories()
    }

    private fun refreshMemories() {
        val h = selectedHome()
        val person = selectedPerson()
        val need =
            h
                ?.days
                ?.firstOrNull { it.citizenId == selectedId }
                ?.activities
                ?.getOrNull(table.selectedRow)
                ?.need
        memories =
            person
                ?.routes
                .orEmpty()
                .filter { h != null && it.path in h.paths.indices && it.weekday == weekday.selectedIndex && it.need == need && it.returning == returning.isSelected }
                .sortedByDescending { it.score }
        refreshing = true
        remembered.removeAllItems()
        memories.forEachIndexed { i, memory -> remembered.addItem("Itinéraire ${i + 1} · score ${memory.score} · ${h!!.paths[memory.path].duration} min") }
        remembered.isEnabled = view.selectedIndex == 2 && memories.isNotEmpty()
        weekday.isEnabled = view.selectedIndex == 2
        memoryControls.isVisible = view.selectedIndex == 2
        refreshing = false
        display()
    }

    private fun display() {
        val c = city
        val mobility = c?.mobility
        val h = selectedHome()
        val day = h?.days?.firstOrNull { it.citizenId == selectedId }
        val person = selectedPerson()
        residentPortrait.show(person)
        val minute = hour.value
        heading.text = person?.name ?: "Aucun habitant à inspecter"
        identity.text = person?.let { "${it.age} ans · ${residentSex(it)} · ${residentOccupation(it)}" } ?: ""
        happiness.value = person?.wellbeing?.satisfaction?.toInt() ?: 0
        happiness.string = person?.let { "Bonheur : %.1f %%".format(it.wellbeing.satisfaction) } ?: "Bonheur inconnu"
        summary.text = ""
        biography.text = ""
        statistics.text = ""
        if (c == null || person == null) {
            detail.text = "Aucun habitant à inspecter."
            show(null, null, -1)
            return
        }
        val activity = day?.activities?.getOrNull(table.selectedRow)
        val leg = day?.legs?.firstOrNull { activity?.matches(it) == true && it.returning == returning.isSelected }
        val path =
            when (view.selectedIndex) {
                1 -> leg?.trace
                2 -> memories.getOrNull(remembered.selectedIndex)?.let { h?.paths?.getOrNull(it.path) }
                else -> if (returning.isSelected) activity?.returnRoute?.let { h?.paths?.getOrNull(it) } else activity?.route?.let { h?.paths?.getOrNull(it) }
            }
        val position = day?.positionAt(minute)?.div(TRAVEL_MODE_COUNT) ?: person.position / TRAVEL_MODE_COUNT
        if (cohortHomes !== c.households) {
            cohortHomes = c.households
            cohorts = c.educationByAge()
        }
        val lifeText = StringBuilder()
        val summaryText = StringBuilder()
        val cityText = StringBuilder()
        detail.text =
            buildString {
                with(lifeText) {
                    person.life?.let { life ->
                        append("${life.stage.label} · Connaissances (NI) %.1f / 150 · Capacité d’apprentissage (CA) %.2f · naissance ${epoch.plusDays(life.birthDay.toLong())}\n".format(life.knowledge, life.learningAbility))
                        append("Diplôme : ${life.diploma?.label ?: "aucun"} · revenu individuel : %.1f $ / jour\n".format(person.dailyIncome))
                        life.study?.let { study -> append("${study.course.label} : année ${study.year}/${study.course.years}, %.1f / %.0f points, ${study.attendedDays}/${study.scheduledDays} présences, ${if (study.repeated) "redoublement" else "première inscription"}.\n".format(study.points, LifeRules.ANNUAL_PASS_POINTS)) }
                        if (life.lastExam.isNotEmpty()) append("${life.lastExam}\n")
                        append("Partenaire : ${life.partner?.let { "#$it" } ?: "aucun"} · parents : ${life.parents.joinToString { "#$it" }.ifEmpty { "non renseignés" }}\n")
                        life.studentRelationship?.let { relation ->
                            append("Relation étudiante : #${relation.partner}, depuis ${epoch.plusDays(relation.started.toLong())} ; solidité %.1f / 100 ; rupture hebdomadaire %.1f %%\n".format(relation.strength, StudentRomanceRules.breakupChance(relation.strength) * 100))
                        }
                        life.pregnancyDue?.let { append("Naissance prévue : ${epoch.plusDays(it.toLong())}\n") }
                    }
                }
                day?.dateOutcome?.let { outcome -> append("${outcome.status.label} : ${outcome.togetherMinutes} min ensemble ; bonheur %+.2f ; solidité %+.1f.\n".format(outcome.happiness, outcome.strengthChange)) }
                activity?.appointment?.let { date -> append("Rendez-vous avec #${date.partner} à ${time(date.meeting)}, fin ${time(date.end)}.\n") }
                with(summaryText) {
                    person.wellbeing.let { w ->
                        append("Satisfaction : %.1f %% (%+.2f / jour)\nInsatisfaction : %d jours · ancienneté : %d jours.\n\n".format(w.satisfaction, w.trend, w.unhappyDays, w.residenceDays))
                        append("Emploi : ${w.workplace?.let { "${it % c.width}, ${it / c.width}" } ?: "aucun"}\n\nBUDGET DU FOYER · PAR PERSONNE\nRevenus : %.1f $ / jour\nDépenses : %.1f $ / jour\n\n".format(w.income, w.costs))
                        append("DÉPLACEMENTS\nDurée : ${w.travelMinutes} min · distance : ${w.distance} cases\nRetard moyen : %.1f min\nBesoins non satisfaits : ${w.unmet}\n".format(w.averageDelay))
                        append("Délai moyen de satisfaction des activités accomplies : %.1f min (activité comprise).\n\n".format(w.averageFulfillmentMinutes))
                        val factors =
                            OpinionFactor.entries
                                .map { it to w.factors.getOrElse(it.ordinal) { 0.0 } }
                                .filter { it.second > .05 }
                                .sortedByDescending { it.second }
                        append(if (factors.isEmpty()) "Aucun facteur négatif.\n" else factors.joinToString(" ; ") { (factor, value) -> "${factor.label} : −%.1f".format(value) } + "\n")
                    }
                }
                person.bicycle?.let { bike ->
                    val date = c.mobility?.day ?: c.nextDay()
                    append("Vélo : ${bike.cell?.let { "à ${it % c.width}, ${it / c.width}" } ?: "aucun"}. Réticence : %.1f / 100.\n".format(bike.reluctance(date)))
                    if (bike.parkedSince != null) append(if (bike.protectedParking) "Stationné sur une place vélo.\n" else "Stationné sans place vélo protégée.\n")
                    bike.lastTheftDay?.let { append("Dernier vol : ${epoch.plusDays(it.toLong())} ; malus de bonheur restant : %.1f.\n".format(bike.unhappiness(date))) }
                }
                append("Position réelle : ${position % c.width}, ${position / c.width} (point blanc). Plan du matin, parcours réalisé et carnet sont distincts.\n")
                if (leg != null) {
                    append("${leg.status} ; départ ${time(leg.departure)}, arrivée ${leg.arrival?.let(::time) ?: "non atteinte"}. Attente : ${leg.waitMinutes} min.\n")
                    append(leg.decisions.joinToString(" ; ") { "${time(it.minute)} : ${it.action}" })
                } else {
                    append(activity?.reason ?: "Sélectionnez un besoin.")
                }
                append("\nCarnet : choisissez un jour et un itinéraire pour consulter son score et son tracé, aller ou retour.\n")
                with(cityText) {
                    append("Instruction par âge (effectif, NI moyen) : " + cohorts.entries.joinToString(" ; ") { (age, values) -> "$age ans : ${values.first}, %.1f".format(values.second) } + "\n")
                    if (socialMobility !== mobility) {
                        socialMobility = mobility
                        socialStatistics = mobility?.studentDateStatistics(c.width)
                    }
                    val social = socialStatistics
                    if (social != null) {
                        append("Rendez-vous étudiants : ${social.appointments}, dont ${social.missed} manqués ; ${social.completedTrips}/${social.trips} trajets accomplis, ${social.distance} cases parcourues.\n")
                        append(
                            "Départs des rendez-vous : " +
                                social.departureHours
                                    .withIndex()
                                    .filter { it.value > 0 }
                                    .joinToString(" ; ") { "${it.index} h : ${it.value}" }
                                    .ifEmpty { "aucun" } + "\n",
                        )
                    }
                    append("Ville : ${mobility?.completedNeeds ?: 0} besoins satisfaits, ${mobility?.unmetNeeds ?: 0} non satisfaits. Les voitures illustrent les passages effectivement admis.")
                }
            }
        summary.text = summaryText.toString()
        biography.text = lifeText.toString().ifEmpty { "Données biographiques non disponibles." }
        statistics.text = cityText.toString()
        if (day == null) detail.text = "Les déplacements seront disponibles après la prochaine journée de simulation."
        if (showingDetails && day != null) show(path, position, minute / 60) else show(null, null, -1)
    }

    private fun time(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
}

private fun textArea() =
    JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
    }
