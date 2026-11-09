package city.client

import city.core.Zone
import city.core.standardBuildings
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Export the real vector icons as a labelled contact sheet and reusable transparent PNGs. */
fun exportToolIcons() {
    val tools =
        listOf("inspect", "bulldoze") + Zone.entries.drop(1).map { "zone:${it.name}" } +
            listOf("road", "rail", "power", "pipe", "subway", "clear_underground", "raise", "lower", "level", "water", "trees") +
            standardBuildings().map { it.id } + "plugin-building"
    val directory = Path.of("build/previews/icons")
    Files.createDirectories(directory)
    val sheet = BufferedImage(800, ((tools.size + 7) / 8) * 112 + 48, BufferedImage.TYPE_INT_RGB)
    val g = sheet.createGraphics()
    g.color = Color(0x1b2a35)
    g.fillRect(0, 0, sheet.width, sheet.height)
    g.color = Color(0xe7d5a5)
    g.font = Font("Dialog", Font.BOLD, 18)
    g.drawString("SHARED CITY / 2000 — OUTILS", 18, 29)
    tools.forEachIndexed { index, id ->
        val icon = ToolIcon(id)
        val png = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val graphics = png.createGraphics()
        graphics.scale(2.0, 2.0)
        icon.paintIcon(null, graphics, 0, 0)
        graphics.dispose()
        ImageIO.write(png, "png", directory.resolve("${id.replace(':', '_')}.png").toFile())
        val x = (index % 8) * 100
        val y = (index / 8) * 112 + 48
        g.drawImage(png, x + 18, y, null)
        g.color = Color(0xe2e9e6)
        g.font = Font("Dialog", Font.PLAIN, 10)
        val name = id.removePrefix("zone:").lowercase()
        val parts = name.split('_')
        parts.forEachIndexed { row, part ->
            g.drawString(part, x + (100 - g.fontMetrics.stringWidth(part)) / 2, y + 78 + row * 11)
        }
    }
    g.dispose()
    ImageIO.write(sheet, "png", Path.of("build/previews/tool-icons.png").toFile())
}
