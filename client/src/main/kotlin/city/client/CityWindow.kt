package city.client

import city.core.*
import city.server.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.*
import java.nio.file.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.*

private val ink = Color(0xe2e9e6)
private val panel = Color(0x1b2a35)
private val accent = Color(0xd8bb7d)

/** Swing components and view state are accessed exclusively from the EDT. */
class CityWindow : JFrame("Shared City 2000 · atelier municipal") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: Connection? = null
    private var localServer: CityServer? = null
    private var startupJob: Job? = null
    private var starting = false
    private var closing = false
    private var pluginLoader: PluginLoader? = null
    private var current: City? = null
    private var definitions = emptyList<BuildingDef>()
    private var selectedTool = "inspect"
    private val heading = JLabel("SHARED CITY / 2000")
    private val status = JLabel("Bienvenue — ouvrez une ville locale ou rejoignez un serveur.")
    private val metrics = JLabel("UNE VILLE. PLUSIEURS MAIRES.")
    private val inspector = JTextArea()
    private val notices = JTextArea()
    private val players = JLabel("Hors ligne")
    private val disasters = JCheckBox("Incendies aléatoires")
    private val terrainAltitude = JSpinner(SpinnerNumberModel(2, 0, Terrain.MAX_HEIGHT, 1))
    private val tools = JPanel()
    private val activeTool = JLabel("Inspection")
    private val chart = HistoryChart()
    private val minimap = MiniMap()
    private val canvas = CityCanvas(::inspect, ::build, ::inspectResidents)
    private val scroll = JScrollPane(canvas)
    private val buttons = ButtonGroup()
    private var mobilityDialog: JDialog? = null
    private var mobilityPanel: MobilityPanel? = null
    private var inspectedCell: Int? = null

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(1100, 720)
        size = Dimension(1450, 900)
        layout = BorderLayout(0, 0)
        contentPane.background = panel
        val header = JPanel(BorderLayout(12, 8))
        header.background = panel
        header.border = EmptyBorder(16, 20, 14, 20)
        heading.font = Font("Dialog", Font.BOLD, 23)
        heading.foreground = accent
        metrics.foreground = ink
        metrics.font = Font("Monospaced", Font.PLAIN, 13)
        val brand = JPanel(GridLayout(2, 1, 0, 8))
        brand.isOpaque = false
        brand.add(heading)
        brand.add(metrics)
        header.add(brand, BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        actions.isOpaque = false
        actions.add(button("Ville locale") { startLocal() })
        actions.add(button("Rejoindre…") { joinDialog() })
        actions.add(button("Sauvegarder") { send(Request("SAVE")) })
        actions.add(button("Guide") { JOptionPane.showMessageDialog(this, "1. Choisissez une centrale et une emprise plane ; utilisez Aplanir avec son altitude cible si nécessaire.\n2. Reliez les zones résidentielles, commerciales et industrielles à l’électricité.\n3. Placez des routes à trois cases au plus des zones.\n4. Ajoutez une pompe alimentée et des conduites sous les zones.\n5. Surveillez demandes R/C/I, budget, pollution et services.\n\nClic / glisser : construire. Survol : inspecter. Molette : zoom (jusqu’à ×16). Boutons ↶ / ↷ : rotation de 90°.\nLes constructions, impôts et vitesses sont partagés entre tous les joueurs.\nLa ville locale contient un quartier de départ ; le serveur autonome commence vide.", "Guide du maire", JOptionPane.INFORMATION_MESSAGE) })
        header.add(actions, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
        tools.layout = BoxLayout(tools, BoxLayout.Y_AXIS)
        tools.background = panel
        tools.border = EmptyBorder(8, 10, 12, 10)
        val toolScroll = JScrollPane(tools)
        toolScroll.preferredSize = Dimension(242, 0)
        toolScroll.border = null
        toolScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        toolScroll.verticalScrollBar.unitIncrement = 24
        val palette = JPanel(BorderLayout())
        palette.background = panel
        palette.add(toolScroll, BorderLayout.CENTER)
        activeTool.foreground = ink
        activeTool.border = EmptyBorder(8, 10, 8, 10)
        activeTool.preferredSize = Dimension(242, 64)
        palette.add(activeTool, BorderLayout.SOUTH)
        add(palette, BorderLayout.WEST)
        addTools()
        val center = JPanel(BorderLayout())
        center.background = panel
        val viewBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8))
        viewBar.background = Color(0x243743)
        viewBar.add(JLabel("CALQUE").apply { foreground = accent })
        val layers = JComboBox(Layer.entries.map { it.label }.toTypedArray())
        layers.addActionListener { canvas.layer = Layer.entries[layers.selectedIndex] }
        viewBar.add(layers)
        viewBar.add(
            JCheckBox("Voitures", true).apply {
                isOpaque = false
                foreground = ink
                toolTipText = "Densité routière animée : heure de l’inspecteur, sinon pointe journalière. Animation figée en pause."
                addActionListener { canvas.animatedTraffic = isSelected }
            },
        )
        viewBar.add(
            JCheckBox("Satisfaction", true).apply {
                isOpaque = false
                foreground = ink
                toolTipText = "Satisfaction moyenne par habitation : vert ≥ 70 %, jaune ≥ 40 %, rouge < 40 %."
                addActionListener { canvas.showSatisfaction = isSelected }
            },
        )
        viewBar.add(button("Habitants / trajets…") { inspectResidents(inspectedCell ?: 0) })
        viewBar.add(button("−") { canvas.zoom -= .15 })
        viewBar.add(button("+") { canvas.zoom += .15 })
        viewBar.add(button("↶ 90°") { canvas.rotation -= 1 })
        viewBar.add(button("↷ 90°") { canvas.rotation += 1 })
        viewBar.add(JLabel("   TEMPS").apply { foreground = accent })
        listOf("Ⅱ", "▶", "▶▶", "▶▶▶").forEachIndexed { i, label -> viewBar.add(button(label) { send(Request("SPEED", value = i)) }) }
        center.add(viewBar, BorderLayout.NORTH)
        scroll.border = null
        scroll.viewport.background = canvas.background
        scroll.horizontalScrollBar.unitIncrement = 32
        scroll.verticalScrollBar.unitIncrement = 24
        center.add(scroll, BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)
        val sidebar = JPanel()
        sidebar.layout = BoxLayout(sidebar, BoxLayout.Y_AXIS)
        sidebar.background = panel
        sidebar.border = EmptyBorder(14, 14, 14, 14)
        sidebar.preferredSize = Dimension(258, 0)
        sidebar.add(label("OBSERVATOIRE"))
        sidebar.add(Box.createVerticalStrut(10))
        minimap.preferredSize = Dimension(220, 160)
        minimap.maximumSize = Dimension(230, 160)
        sidebar.add(minimap)
        sidebar.add(Box.createVerticalStrut(12))
        sidebar.add(label("ÉVOLUTION · 120 MOIS"))
        chart.preferredSize = Dimension(220, 100)
        chart.maximumSize = Dimension(230, 100)
        sidebar.add(chart)
        sidebar.add(Box.createVerticalStrut(12))
        sidebar.add(label("INSPECTION"))
        for (area in listOf(inspector, notices)) {
            area.isEditable = false
            area.lineWrap = true
            area.wrapStyleWord = true
            area.background = panel
            area.foreground = ink
            area.font = Font("Dialog", Font.PLAIN, 12)
            area.border = EmptyBorder(8, 0, 8, 0)
        }
        inspector.text = "Survolez une case pour afficher ses informations."
        inspector.maximumSize = Dimension(230, 180)
        sidebar.add(inspector)
        sidebar.add(label("CONSEIL MUNICIPAL"))
        notices.text = "Votre ville vous attend."
        notices.maximumSize = Dimension(230, 150)
        sidebar.add(notices)
        val budget = JPanel(GridLayout(0, 2, 5, 5))
        budget.isOpaque = false
        budget.maximumSize = Dimension(230, 100)
        budget.add(button("Impôts…") { numberDialog("Taux d’imposition (0–20 %)", current?.rules?.taxRate ?: 7, 0, 20) { send(Request("TAX", value = it)) } })
        budget.add(button("Services…") { numberDialog("Financement des services (0–100 %)", current?.rules?.serviceFunding ?: 100, 0, 100) { send(Request("FUNDING", value = it)) } })
        budget.add(button("Emprunter") { send(Request("BOND")) })
        budget.add(button("Rembourser") { send(Request("REPAY")) })
        sidebar.add(budget)
        disasters.background = panel
        disasters.foreground = ink
        disasters.addActionListener { send(Request("DISASTERS", value = if (disasters.isSelected) 1 else 0)) }
        sidebar.add(disasters)
        sidebar.add(Box.createVerticalGlue())
        players.foreground = accent
        sidebar.add(players)
        sidebar.components.filterIsInstance<JComponent>().forEach { it.alignmentX = Component.LEFT_ALIGNMENT }
        add(sidebar, BorderLayout.EAST)
        status.foreground = ink
        status.background = Color(0x263b46)
        status.isOpaque = true
        status.border = EmptyBorder(10, 16, 10, 16)
        add(status, BorderLayout.SOUTH)
        addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) {
                    shutdown()
                }
            },
        )
        setLocationRelativeTo(null)
    }

    private fun label(text: String) =
        JLabel(text).apply {
            foreground = accent
            font = Font("Dialog", Font.BOLD, 11)
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun button(
        text: String,
        action: () -> Unit,
    ) = JButton(text).apply {
        addActionListener { action() }
        isFocusPainted = false
    }

    private fun numberDialog(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        action: (Int) -> Unit,
    ) {
        val spinner = JSpinner(SpinnerNumberModel(value, min, max, 1))
        if (JOptionPane.showConfirmDialog(this, spinner, title, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) action(spinner.value as Int)
    }

    private fun addTools() {
        tools.removeAll()
        buttons.elements.toList().forEach { buttons.remove(it) }

        var grid = JPanel()
        var count = 0

        fun section(title: String) {
            tools.add(Box.createVerticalStrut(14))
            tools.add(label(title))
            tools.add(Box.createVerticalStrut(6))
            grid = JPanel(GridLayout(0, 4, 6, 6))
            grid.isOpaque = false
            grid.alignmentX = Component.LEFT_ALIGNMENT
            count = 0
            tools.add(grid)
        }

        fun tool(
            id: String,
            name: String,
            cost: Int? = null,
        ) {
            val description = if (cost == null) name else "$name · $$cost"
            val b = JToggleButton(ToolIcon(id))
            b.selectedIcon = ToolIcon(id, selected = true)
            b.name = "tool:$id"
            b.accessibleContext.accessibleName = name
            b.accessibleContext.accessibleDescription = description
            b.preferredSize = Dimension(44, 44)
            b.margin = Insets(4, 4, 4, 4)
            b.background = Color(0x293e4b)
            b.border = BorderFactory.createLineBorder(Color(0x597180), 2)
            b.addItemListener {
                b.border = BorderFactory.createLineBorder(if (b.isSelected) accent else Color(0x597180), 2)
            }
            b.toolTipText = description
            if (id == selectedTool) activeTool.text = "<html>$description</html>"
            b.isSelected = id == selectedTool
            b.addActionListener {
                selectedTool = id
                canvas.tool = id
                activeTool.text = "<html>$description</html>"
                status.text = "Outil : $name — clic ou glisser sur la carte"
            }
            buttons.add(b)
            grid.add(b)
            count++
            val gridSize = Dimension(194, ((count + 3) / 4) * 50 - 6)
            grid.preferredSize = gridSize
            grid.maximumSize = gridSize
        }
        section("OUTILS DU MAIRE")
        tool("inspect", "Inspection")
        tool("bulldoze", "Démolition", 2)
        section("ZONES")
        listOf("Résidentiel léger", "Résidentiel dense", "Commercial léger", "Commercial dense", "Industriel léger", "Industriel dense").zip(Zone.entries.drop(1)).forEach { (name, z) -> tool("zone:${z.name}", name, if (z.name.endsWith("DENSE")) 10 else 5) }
        section("RÉSEAUX")
        listOf(Triple("road", "Route / pont", 10), Triple("rail", "Rail / pont", 20), Triple("power", "Ligne électrique", 2), Triple("pipe", "Conduite d’eau", 3), Triple("subway", "Tunnel de métro", 20), Triple("clear_underground", "Retirer le sous-sol", 1)).forEach { tool(it.first, it.second, it.third) }
        section("TERRAIN")
        tool("raise", "Élever (5 $ / sommet)")
        tool("lower", "Abaisser (5 $ / sommet)")
        tool("level", "Aplanir à l’altitude choisie")
        tool("water", "Eau / cascade", 5)
        tool("trees", "Planter des arbres", 3)
        tools.add(Box.createVerticalStrut(6))
        tools.add(label("ALTITUDE CIBLE (0–12)"))
        terrainAltitude.maximumSize = Dimension(194, 28)
        terrainAltitude.alignmentX = Component.LEFT_ALIGNMENT
        terrainAltitude.toolTipText = "Altitude de l’outil Aplanir. Le terrassement coûte 5 $ par sommet modifié."
        tools.add(terrainAltitude)
        section("BÂTIMENTS & SERVICES")
        definitions.sortedWith(compareBy({ it.year }, { it.name })).forEach { tool(it.id, "${it.name}${if (it.year > 1900) " (${it.year})" else ""}", it.cost) }
        tools.preferredSize = Dimension(220, tools.layout.preferredLayoutSize(tools).height)
        tools.revalidate()
        tools.repaint()
    }

    private fun build(i: Int) {
        val c = current ?: return
        if (selectedTool == "level") {
            try {
                terrainAltitude.commitEdit()
            } catch (_: java.text.ParseException) {
                status.text = "Indiquez une altitude entière entre 0 et ${Terrain.MAX_HEIGHT}."
                return
            }
        }
        send(Request("BUILD", tool = selectedTool, x = i % c.width, y = i / c.width, value = if (selectedTool == "level") terrainAltitude.value as Int else 0))
    }

    private fun send(r: Request) {
        if (connection == null) status.text = "Connectez-vous à une ville d’abord." else connection?.send(r)
    }

    private fun inspectResidents(cell: Int) {
        val c = current ?: return
        val anchor =
            c.tiles
                .getOrNull(cell)
                ?.anchor
                ?.takeIf { it >= 0 } ?: cell
        if (mobilityDialog == null) {
            val content =
                MobilityPanel { path, position, hour ->
                    val origin = path?.nodes?.firstOrNull()?.div(4) ?: position
                    if (origin != null && origin !=
                        canvas.itinerary
                            ?.nodes
                            ?.firstOrNull()
                            ?.div(4)
                    ) {
                        canvas.focusCell(origin)
                    }
                    canvas.itinerary = path
                    canvas.residentPosition = position
                    canvas.trafficHour = hour
                }
            mobilityPanel = content
            mobilityDialog =
                JDialog(this, "Habitants · besoins et déplacements", false).apply {
                    contentPane = content
                    size = Dimension(900, 900)
                    minimumSize = Dimension(640, 480)
                    setLocationRelativeTo(this@CityWindow)
                    defaultCloseOperation = DISPOSE_ON_CLOSE
                    addWindowListener(
                        object : WindowAdapter() {
                            override fun windowClosed(e: WindowEvent) {
                                canvas.itinerary = null
                                canvas.residentPosition = null
                                canvas.trafficHour = -1
                                mobilityPanel = null
                                mobilityDialog = null
                            }
                        },
                    )
                }
        }
        mobilityPanel?.updateSnapshot(c, anchor)
        mobilityDialog?.isVisible = true
    }

    private fun inspect(i: Int) {
        inspectedCell = i
        val c = current ?: return
        val t = c.tiles.getOrNull(i) ?: return
        val surface = c.surface(i)
        val slope =
            if (surface.flat) {
                "plat"
            } else if (surface.ramp) {
                "rampe"
            } else {
                "pente composée"
            }
        val text = "Case ${i % c.width}, ${i / c.width} · altitude ${surface.minimum}–${surface.maximum} ($slope)\n${definitions.find { it.id == t.building }?.name ?: t.zone.name}\n${if (t.zone.commercial || t.zone.industrial) "Emplois" else "Habitants"} : ${if (t.zone.residential || t.building == null) {
            t.occupants
        } else {
            c.households
                .firstOrNull { it.cell == i }
                ?.people
                ?.size ?: 0
        }}\nÉlectricité : ${if (t.powered) "oui" else "non"} · Eau : ${if (t.watered) "oui" else "non"}\nPollution ${t.pollution} · Crime ${t.crime}\nValeur ${t.landValue} · Charge de pointe ${t.traffic} %"
        val household = c.households.firstOrNull { it.cell == i }
        val opinion =
            household
                ?.takeIf { it.people.isNotEmpty() }
                ?.let {
                    "\nSatisfaction moyenne : %.1f %% (%+.2f / jour)\nRisque de déménagement : %.2f %% / jour".format(it.satisfaction, it.people.map { p -> p.wellbeing.trend }.average(), it.moveProbability() * 100)
                }.orEmpty()
        val station =
            if (t.building == "metro") {
                c.mobility?.let { mobility ->
                    val passengers = mobility.metroStationPassengers[i] ?: StationPassengers()
                    "\nDernier jour simulé :\nEmbarquements : ${passengers.boardings} / jour\nDébarquements : ${passengers.alightings} / jour"
                } ?: "\nVoyageurs : en attente de simulation"
            } else {
                ""
            }
        val details = text + opinion + station
        if (inspector.text != details) inspector.text = details
    }

    fun connect(
        host: String,
        port: Int,
        name: String,
        token: String = "",
    ) {
        connection?.close()
        current = null
        canvas.city = null
        mobilityPanel?.updateSnapshot(null)
        status.text = "Connexion à $host:$port…"
        connection =
            Connection(host, port, name, token, { response ->
                when (response.type) {
                    "WELCOME" -> {
                        definitions = response.catalog
                        canvas.catalog = definitions.associateBy { it.id }
                        addTools()
                        status.text = "Connecté à $host:$port"
                    }

                    "STATE" -> {
                        response.city?.let { city ->
                            val first = current == null
                            current = city
                            canvas.city = city
                            mobilityPanel?.updateSnapshot(city)
                            if (canvas.selected >= 0) inspect(canvas.selected)
                            chart.city = city
                            minimap.city = city
                            disasters.isSelected = city.rules.disasters
                            val s = city.stats
                            metrics.text = "${epoch.plusDays((city.mobility?.day ?: city.nextDay()).toLong())}   |   ${s.population} HAB.   |   ${city.treasury} $   |   R ${s.residentialDemand}  C ${s.commercialDemand}  I ${s.industrialDemand}"
                            heading.text = "SHARED CITY / ${if (city.rules.speed == 0) "PAUSE" else "2000"}"
                            notices.text = "Recettes : ${s.income} $ / mois\nDépenses : ${s.expenses} $ / mois\nDette : ${city.debt} $\nImpôts : ${city.rules.taxRate} %\n" + city.notices.joinToString("\n")
                            players.text = "${response.players.size} maire(s) · rév. ${city.revision}"
                            players.toolTipText = response.players.joinToString(", ")
                            if (first) SwingUtilities.invokeLater { scroll.viewport.viewPosition = Point(max(0, (canvas.preferredSize.width - scroll.viewport.width) / 2), max(0, (canvas.preferredSize.height - scroll.viewport.height) / 2)) }
                        }
                    }

                    "ERROR" -> {
                        status.text = "Refus : ${response.message}"
                        Toolkit.getDefaultToolkit().beep()
                    }

                    "ACK" -> {
                        status.text = response.message
                    }
                }
            }, { message -> status.text = "Connexion : $message" })
        connection!!.start()
    }

    fun startLocal() {
        if (localServer != null) {
            connect("127.0.0.1", localServer!!.port, "Maire local")
            return
        }
        if (starting || closing) return
        starting = true
        status.text = "Ouverture de la ville locale…"
        startupJob =
            scope.launch {
                try {
                    val catalog = Catalog()
                    val loader = PluginLoader(Path.of("plugins"), catalog)
                    val path = Path.of("saves/local-city.json")
                    val city = if (Files.exists(path)) Persistence.load(path, catalog) else demoCity()
                    val server = CityServer(city, catalog, loader.rules, port = 0, savePath = path, systems = loader.systems)
                    SwingUtilities.invokeAndWait {
                        pluginLoader = loader
                        localServer = server
                        starting = false
                        if (!closing) connect("127.0.0.1", server.port, "Maire local")
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        starting = false
                        status.text = "Impossible d’ouvrir la ville : ${e.message}"
                    }
                }
            }
    }

    private fun joinDialog() {
        val host = JTextField("127.0.0.1")
        val port = JTextField("2000")
        val name = JTextField("Maire")
        val token = JPasswordField()
        val form = JPanel(GridLayout(0, 2, 8, 8))
        listOf("Serveur" to host, "Port" to port, "Nom" to name, "Jeton" to token).forEach {
            form.add(JLabel(it.first))
            form.add(it.second)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Rejoindre une ville", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            val p = port.text.toIntOrNull()
            if (p == null || p !in 1..65535) {
                status.text = "Port invalide"
                return
            }
            connect(host.text, p, name.text, String(token.password))
        }
    }

    private fun shutdown() {
        if (closing) return
        closing = true
        isEnabled = false
        status.text = "Sauvegarde et arrêt…"
        connection?.close()
        scope.launch {
            try {
                startupJob?.join()
                localServer?.shutdown()
                pluginLoader?.close()
                SwingUtilities.invokeLater { dispose() }
                scope.cancel()
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    closing = false
                    isEnabled = true
                    status.text = "Erreur de sauvegarde : ${e.message}"
                }
            }
        }
    }
}

private class HistoryChart : JPanel() {
    var city: City? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        background = panel
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val history = city?.history ?: return
        g.color = Color(0x3b4e56)
        g.drawLine(0, height - 12, width, height - 12)
        if (history.size < 2) return
        val maximum = max(10, history.maxOf { max(it.population, it.jobs) })
        listOf(Color(0x86bd8b) to { s: Statistics -> s.population }, Color(0x74b7d5) to { s: Statistics -> s.jobs }).forEach { (color, value) ->
            g.color = color
            for (i in 1 until history.size) g.drawLine((i - 1) * width / (history.size - 1), height - 12 - value(history[i - 1]) * (height - 34) / maximum, i * width / (history.size - 1), height - 12 - value(history[i]) * (height - 34) / maximum)
        }
        g.color = ink
        g.font = Font("Dialog", Font.PLAIN, 10)
        g.drawString("Population / emplois", 2, 12)
    }
}

private class MiniMap : JPanel() {
    var city: City? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        background = panel
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val c = city ?: return
        c.tiles.forEachIndexed { i, t ->
            g.color =
                when {
                    t.water -> Color(0x4486a6)
                    t.building != null -> accent
                    t.road -> Color(0x9dada8)
                    t.zone.residential -> Color(0x85c583)
                    t.zone.commercial -> Color(0x72b9d7)
                    t.zone.industrial -> Color(0xd3bb72)
                    else -> Color(0x3c6049)
                }
            val x = i % c.width
            val y = i / c.width
            g.fillRect(x * width / c.width, y * height / c.height, ceil(width.toDouble() / c.width).toInt(), ceil(height.toDouble() / c.height).toInt())
        }
    }
}
