package city.client

import city.core.*
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** Local inspection of the authoritative snapshot; no simulation runs on the Swing thread. */
internal class MobilityPanel(
    private val show: (TravelPath?, Int?, Int) -> Unit,
) : JPanel(BorderLayout(8, 8)) {
    private var city: City? = null
    private var homes = emptyList<MobilityHome>()
    private var refreshing = false
    private val home = JComboBox<String>()
    private val resident = JSpinner(SpinnerNumberModel(1, 1, 1, 1))
    private val hour = JSlider(0, 1439, 8 * 60)
    private val heading = JLabel()
    private val detail = JTextArea()
    private val model =
        object : DefaultTableModel(arrayOf("Besoin", "Départ", "Destination", "Aller", "État"), 0) {
            override fun isCellEditable(
                row: Int,
                column: Int,
            ) = false
        }
    internal val table = JTable(model)

    init {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val controls = JPanel(GridLayout(0, 1, 4, 4))
        controls.add(JLabel("Domicile (x, y) puis numéro de l’habitant"))
        controls.add(home)
        controls.add(resident)
        controls.add(heading)
        controls.add(JLabel("Heure observée · journée représentative du mois"))
        hour.majorTickSpacing = 360
        hour.paintTicks = true
        controls.add(hour)
        add(controls, BorderLayout.NORTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = 26
        add(JScrollPane(table), BorderLayout.CENTER)
        detail.isEditable = false
        detail.lineWrap = true
        detail.wrapStyleWord = true
        detail.rows = 6
        add(detail, BorderLayout.SOUTH)
        home.addActionListener {
            if (!refreshing) {
                updateResidentLimit()
                refresh()
            }
        }
        resident.addChangeListener { if (!refreshing) refresh() }
        hour.addChangeListener { if (!refreshing) display() }
        table.selectionModel.addListSelectionListener { if (!refreshing && !it.valueIsAdjusting) display() }
    }

    fun updateSnapshot(
        value: City?,
        preferredHome: Int? = null,
    ) {
        val old = preferredHome ?: homes.getOrNull(home.selectedIndex)?.cell
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
        val mobility = c?.mobility
        if (c != null && h != null && mobility != null) {
            val person = Resident(h.cell, (resident.value as Int) - 1, c.seed)
            for (trip in mobility.itinerary(person, h)) {
                val path = trip.path?.let { h.paths[it] }
                val destination = path?.destination ?: if (trip.need == Need.REST) h.cell else null
                model.addRow(arrayOf(trip.need.label, if (trip.scheduled) time(trip.departure) else "—", destination?.let { "${it % c.width}, ${it / c.width}" } ?: "—", path?.let { "${it.duration} min" } ?: "—", trip.status))
            }
            table.setRowSelectionInterval(selected.coerceAtMost(model.rowCount - 1), selected.coerceAtMost(model.rowCount - 1))
        }
        refreshing = false
        display()
    }

    private fun display() {
        val c = city
        val mobility = c?.mobility
        val h = homes.getOrNull(home.selectedIndex)
        val minute = hour.value
        if (c == null || mobility == null || h == null) {
            heading.text = "Aucun habitant à inspecter"
            detail.text = "Les déplacements seront calculés au prochain mois de simulation. Après une construction, relancez le temps pour actualiser les itinéraires."
            show(null, null, minute / 60)
            return
        }
        val person = Resident(h.cell, (resident.value as Int) - 1, c.seed)
        val trips = mobility.itinerary(person, h)
        val trip = trips.getOrNull(table.selectedRow)
        val path = trip?.path?.let { h.paths[it] }
        val position = mobility.position(person, h, minute)
        heading.text = "${person.label} · ${time(minute)} · position ${position % c.width}, ${position / c.width}"
        detail.text =
            buildString {
                append("Sélectionnez un besoin pour afficher le trajet envisagé, même s’il n’est pas prévu aujourd’hui.\n")
                if (trip != null) {
                    append("${trip.need.label} : ${trip.status}. ")
                    if (path != null) {
                        append("Modes : ${path.nodes.map { TravelMode.entries[it % 4].label }.distinct().joinToString(" → ")}. ")
                        if (trip.fulfilled) append("Retour à ${time(trip.returnDeparture)}, domicile à ${time(trip.arrivalHome)}.")
                    }
                }
                append("\nPoint blanc : position à l’heure observée. Marche : jaune ; route : orange ; train : bleu ; métro : violet. Retour par le même chemin. Calque Trafic : charge à l’heure choisie (100 % = capacité horaire).\n")
                append("Ville : ${mobility.completedNeeds} besoins satisfaits, ${mobility.unmetNeeds} non satisfaits sur cette journée.")
            }
        show(path, position, minute / 60)
    }

    private fun time(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
}
