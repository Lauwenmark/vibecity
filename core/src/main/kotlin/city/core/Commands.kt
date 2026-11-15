package city.core

/** Pure transaction; a validated no-op returns the original snapshot without invalidating simulation. */
class Commands(
    private val catalog: Catalog,
    private val ignoreConstructionYears: Boolean = false,
) {
    fun apply(
        city: City,
        request: Request,
    ): City {
        val updated =
            when (request.type) {
                "BUILD" -> {
                    build(city, request)
                }

                "TAX" -> {
                    require(request.value in 0..20)
                    city.copy(rules = city.rules.copy(taxRate = request.value))
                }

                "FUNDING" -> {
                    require(request.value in 0..100)
                    city.copy(rules = city.rules.copy(serviceFunding = request.value))
                }

                "SPEED" -> {
                    require(request.value in 0..3)
                    city.copy(rules = city.rules.copy(speed = request.value))
                }

                "DISASTERS" -> {
                    require(request.value in 0..1)
                    city.copy(rules = city.rules.copy(disasters = request.value == 1))
                }

                "BOND" -> {
                    require(city.debt < 100_000) { "Plafond de dette atteint" }
                    city.copy(treasury = city.treasury + 10_000, debt = city.debt + 10_000)
                }

                "REPAY" -> {
                    require(city.debt >= 10_000 && city.treasury >= 10_000) { "Remboursement impossible" }
                    city.copy(treasury = city.treasury - 10_000, debt = city.debt - 10_000)
                }

                else -> {
                    error("Commande inconnue : ${request.type}")
                }
            }
        if (updated == city) return city
        return updated.copy(
            revision = city.revision + 1,
            mobility = if (request.type in listOf("BUILD", "FUNDING")) null else city.mobility,
            tiles = if (request.type in listOf("BUILD", "FUNDING")) updated.tiles.map { it.copy(traffic = 0) } else updated.tiles,
        )
    }

    private fun build(
        city: City,
        r: Request,
    ): City {
        val i = city.index(r.x, r.y)
        val tile = city.tiles[i]
        val tiles = city.tiles.toMutableList()
        var demolished = emptySet<Int>()
        var cost = 0
        when {
            r.tool == "bulldoze" -> {
                val anchor = if (tile.anchor >= 0) tile.anchor else i
                val targets = if (tile.building != null) city.tiles.indices.filter { it == anchor || city.tiles[it].anchor == anchor } else listOf(i)
                demolished = targets.toSet()
                cost = 2 * targets.size
                targets.forEach { n ->
                    tiles[n] =
                        tiles[n].copy(
                            zone = Zone.NONE,
                            road = false,
                            rail = false,
                            powerLine = false,
                            building = null,
                            residentialOccupancy = null,
                            housingCapacity = 0,
                            construction = null,
                            constructionBlocked = false,
                            anchor = -1,
                            occupants = 0,
                            trees = false,
                            fire = 0,
                        )
                }
            }

            r.tool == "clear_underground" -> {
                cost = 1
                tiles[i] = tile.copy(pipe = false, subway = false)
            }

            r.tool in listOf("raise", "lower", "level") -> {
                return city.terraform(r.x, r.y, r.tool, r.value)
            }

            r.tool == "water" -> {
                require(!tile.protectedTerrain()) { "Dégagez le terrain" }
                require(city.surface(i).flat || city.surface(i).ramp) { "L’eau de surface exige un terrain plat ou une rampe simple" }
                cost = if (tile.water) 0 else 5
                tiles[i] = tile.copy(water = true, trees = false)
            }

            r.tool == "pipe" -> {
                cost = if (tile.pipe) 0 else 3
                tiles[i] = tile.copy(pipe = true)
            }

            r.tool == "subway" -> {
                cost = if (tile.subway) 0 else 20
                tiles[i] = tile.copy(subway = true)
            }

            r.tool == "trees" -> {
                require(!tile.water && tile.building == null && tile.zone == Zone.NONE && !tile.road && !tile.rail)
                cost = if (tile.trees) 0 else 3
                tiles[i] = tile.copy(trees = true)
            }

            r.tool in listOf("road", "rail", "power") -> {
                require(tile.building == null && tile.zone == Zone.NONE) { "Emplacement occupé" }
                if (r.tool != "power") require(city.surface(i).flat || city.surface(i).ramp) { "Route ou rail : aplanissez cette pente irrégulière" }
                cost =
                    when (r.tool) {
                        "road" -> if (tile.road) 0 else 10
                        "rail" -> if (tile.rail) 0 else 20
                        else -> if (tile.powerLine) 0 else 2
                    }
                if (tile.water) cost *= 5
                tiles[i] =
                    tile.copy(
                        road = tile.road || r.tool == "road",
                        rail = tile.rail || r.tool == "rail",
                        powerLine = tile.powerLine || r.tool == "power",
                        trees = false,
                    )
            }

            r.tool.startsWith("zone:") -> {
                val zone = Zone.valueOf(r.tool.substringAfter(':'))
                require(zone != Zone.NONE)
                require(!tile.water && tile.building == null && !tile.road && !tile.rail) { "Terrain non disponible" }
                require(tile.zone == Zone.NONE || tile.zone == zone) { "Démolissez avant de changer la zone" }
                cost =
                    if (tile.zone == zone) {
                        0
                    } else if (zone.name.endsWith("DENSE")) {
                        10
                    } else {
                        5
                    }
                tiles[i] = tile.copy(zone = zone, trees = false)
            }

            else -> {
                val def = catalog[r.tool]
                require(ignoreConstructionYears || 1900 + city.month / 12 >= def.year) { "Disponible en ${def.year}" }
                require(r.x + def.size <= city.width && r.y + def.size <= city.height) { "Bâtiment hors carte" }
                val footprint = (r.y until r.y + def.size).flatMap { y -> (r.x until r.x + def.size).map { x -> city.index(x, y) } }
                require(footprint.all { n -> city.tiles[n].let { it.building == null && it.zone == Zone.NONE && !it.road && !it.rail && !it.powerLine && (if (def.id == "hydro") it.water else !it.water) } }) { "Emplacement occupé ou terrain incompatible" }
                if (def.id == "hydro") {
                    require(city.surface(i).ramp) { "Le barrage exige de l’eau sur une rampe" }
                } else {
                    require(footprint.all { city.surface(it).flat && city.surface(it).minimum == city.surface(i).minimum }) { "Aplanissez toute l’emprise du bâtiment" }
                }
                if (def.id == "seaport" || def.id == "desalination") require(footprint.any { n -> city.neighbors(n).any { city.tiles[it].water } }) { "Nécessite une rive" }
                cost = def.cost
                footprint.forEach { n -> tiles[n] = tiles[n].copy(building = def.id, anchor = i, trees = false) }
            }
        }
        require(city.treasury >= cost) { "Trésorerie insuffisante" }
        val updated = city.copy(tiles = tiles, treasury = city.treasury - cost)
        return if (demolished.isEmpty()) updated else evictHouseholds(updated, demolished)
    }
}
