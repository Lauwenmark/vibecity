package city.example

import city.core.*

/** Demonstrates both a new building and an inhabitant/development rule. */
class GardenPlugin : CityPlugin {
    override val id = "example.community-gardens"

    override fun configure(context: PluginContext) {
        context.catalog.register(
            BuildingDef(
                "community_garden",
                "Jardin communautaire",
                75,
                2,
                color = 0x74b95b,
                service = "park",
                radius = 6,
            ),
        )
        context.rules.add(
            object : TileRule {
                override val id = "example.garden-attraction"

                override fun apply(
                    context: TileContext,
                    proposed: Tile,
                ): Tile {
                    val nearGarden = context.city.neighbors(context.index).any { context.city.tiles[it].building == "community_garden" }
                    return if (nearGarden && proposed.zone.residential && proposed.powered && proposed.occupants > 0) {
                        proposed.copy(occupants = (proposed.occupants + 1).coerceAtMost(proposed.zone.capacity), landValue = (proposed.landValue + 5).coerceAtMost(100))
                    } else {
                        proposed
                    }
                }
            },
        )
    }
}
