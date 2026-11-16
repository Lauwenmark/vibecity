package city.client

import city.core.*
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ResidentPortraitTest {
    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    @Test fun `every family sex age and variant has a complete compatible binary partition`() {
        val masks = mutableMapOf<PortraitPart, BooleanArray>()
        for (family in 0 until PortraitCodes.FAMILIES) {
            for (sex in listOf("female", "male")) {
                for (age in PortraitAge.entries) {
                    for (variant in 0 until PortraitCodes.VARIANTS) {
                        for (part in PortraitPart.entries) {
                            val path = "/city/client/portraits/bank-v1/family-$family/$sex/${age.resource}/${part.resource}-$variant.png"
                            val image = requireNotNull(javaClass.getResourceAsStream(path)) { path }.use { ImageIO.read(it) }
                            assertEquals(256, image.width, path)
                            assertEquals(256, image.height, path)
                            val argb = pixels(image)
                            assertTrue(argb.all { it ushr 24 in listOf(0, 255) }, path)
                            val mask = BooleanArray(argb.size) { argb[it] ushr 24 == 255 }
                            assertTrue(mask.any { it }, path)
                            val previous = masks.putIfAbsent(part, mask)
                            if (previous != null) assertContentEquals(previous, mask, path)
                        }
                    }
                }
            }
        }
        for (pixel in 0 until 256 * 256) assertEquals(1, masks.values.count { it[pixel] }, "Coverage at $pixel")
    }

    @Test fun `same identity is deterministic at every age and age changes the actual pixels`() {
        for (sex in Sex.entries) {
            val code = PortraitCodes.create(82, sex)
            val images = PortraitAge.entries.map { ResidentPortraits.image(ResidentPortraits.Key(code, it)) }
            for ((index, image) in images.withIndex()) {
                assertTrue(pixels(image).all { it ushr 24 == 255 })
                assertContentEquals(pixels(image), pixels(ResidentPortraits.image(ResidentPortraits.Key(code, PortraitAge.entries[index]))))
            }
            assertFalse(pixels(images.first()).contentEquals(pixels(images.last())))
        }
    }

    @Test fun `changing one trait changes only its own pixels and keeps skin family`() {
        val code = PortraitCodes.create(17, Sex.MALE)
        val before = pixels(ResidentPortraits.image(ResidentPortraits.Key(code, PortraitAge.ADULT)))
        val afterCode = PortraitCodes.withVariant(code, PortraitPart.NOSE, (PortraitCodes.variant(code, PortraitPart.NOSE) + 1) % PortraitCodes.VARIANTS)
        val after = pixels(ResidentPortraits.image(ResidentPortraits.Key(afterCode, PortraitAge.ADULT)))
        assertEquals(PortraitCodes.family(code), PortraitCodes.family(afterCode))
        var differences = 0
        for (i in before.indices) {
            if (before[i] != after[i]) {
                differences++
                assertTrue(i % 256 in 110 until 146 && i / 256 in 132 until 155)
            }
        }
        assertTrue(differences > 0)
    }

    @Test fun `render runtime compositions for every family and sex through six age bands`() {
        val sheet = BufferedImage(1536, 1536, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        val labelled = BufferedImage(1624, 1944, BufferedImage.TYPE_INT_RGB)
        val labels = labelled.createGraphics()
        val recipes = mutableListOf("family,sex,citizenId,portraitCode,frame,face,ears,brows,eyes,nose,mouth")
        labels.color = Color(25, 33, 45)
        labels.fillRect(0, 0, labelled.width, labelled.height)
        labels.color = Color.WHITE
        labels.font = Font(Font.SANS_SERIF, Font.BOLD, 25)
        labels.drawString("Portraits composés par le jeu", 16, 32)
        labels.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        labels.drawString("6 identités · 3 familles de peau · mêmes codes à tous les âges · portraits natifs 256 × 256", 16, 59)
        val ages = listOf(8, 16, 30, 55, 70, 85)
        for ((column, age) in ages.withIndex()) labels.drawString("$age ans", 16 + column * 268, 88)
        try {
            for (family in 0 until PortraitCodes.FAMILIES) {
                for (sex in Sex.entries) {
                    val firstId = 101L + sex.ordinal * 1000
                    val id = (firstId..firstId + 100).first { PortraitCodes.family(PortraitCodes.create(it, sex)) == family }
                    val code = PortraitCodes.create(id, sex)
                    val row = family * 2 + sex.ordinal
                    labels.color = Color(198, 213, 232)
                    labels.drawString("Famille ${family + 1} · ${if (sex == Sex.FEMALE) "Féminin" else "Masculin"} · Habitant $id · Code ${code.toString(16)}", 16, 120 + row * 300)
                    recipes.add((listOf(family, sex, id, code) + PortraitPart.entries.map { PortraitCodes.variant(code, it) }).joinToString(","))
                    for ((column, age) in PortraitAge.entries.withIndex()) {
                        val person = Citizen(id, "Habitant $id", ages[column], position = 0, portraitCode = code)
                        val image = ResidentPortraits.image(ResidentPortraits.key(person))
                        assertEquals(age, ResidentPortraits.key(person).age)
                        g.drawImage(image, column * 256, row * 256, null)
                        labels.drawImage(image, 16 + column * 268, 130 + row * 300, null)
                    }
                }
            }
        } finally {
            g.dispose()
            labels.dispose()
        }
        val file = File("build/reports/portraits/runtime-aging.png")
        file.parentFile.mkdirs()
        ImageIO.write(sheet, "png", file)
        ImageIO.write(labelled, "png", File(file.parentFile, "game-test-sheet.png"))
        File(file.parentFile, "game-test-recipes.csv").writeText(recipes.joinToString("\n", postfix = "\n"))
    }
}
