package city.core

/** A small serviced starter district, built through the same transactions as human players. */
fun demoCity(): City {
    var city = City.generate()
    city = city.copy(tiles = city.tiles.mapIndexed { i, t -> if (i % 64 in 24..49 && i / 64 in 18..40) Tile(elevation = 2) else t })
    val commands = Commands(Catalog())

    fun place(
        tool: String,
        x: Int,
        y: Int,
    ) {
        city = commands.apply(city, Request("BUILD", tool = tool, x = x, y = y))
    }
    place("coal", 28, 22)
    for (x in 32..44) place("power", x, 25)
    place("pump", 33, 24)
    for (y in 24..32) {
        place("pipe", 33, y)
        place("pipe", 34, y)
        place("power", 34, y)
    }
    for (x in 26..46) place("road", x, 28)
    for (y in 24..34) place("road", 45, y)
    for (y in 25..27) {
        for (x in 35..42) {
            place("zone:RESIDENTIAL_LIGHT", x, y)
            place("pipe", x, y)
        }
    }
    for (y in 29..31) {
        for (x in 35..39) {
            place("zone:INDUSTRIAL_LIGHT", x, y)
            place("pipe", x, y)
        }
    }
    for (y in 29..31) {
        for (x in 40..43) {
            place("zone:COMMERCIAL_LIGHT", x, y)
            place("pipe", x, y)
        }
    }
    place("park", 43, 26)
    return city
}
