package dev.helfferich.judgelies.dataset

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VariantRulesTest {
    @Test
    fun `should move a year by one when the neighbour is absent`() {
        val (text, kind) = VariantRules.numericVariant("1884", "Nikola Tesla emigrated in 1884.")!!
        assertEquals("1885", text)
        assertEquals("year", kind)
    }

    @Test
    fun `should skip a year that already appears in the passage`() {
        val (text, _) = VariantRules.numericVariant("1884", "In 1884 and again in 1885 he moved.")!!
        assertEquals("1883", text)
    }

    @Test
    fun `should step a decade by ten`() {
        val (text, kind) = VariantRules.numericVariant("the 1960s", "It was discovered in the 1960s.")!!
        assertEquals("the 1970s", text)
        assertEquals("decade", kind)
    }

    @Test
    fun `should keep a real ordinal suffix`() {
        val (text, kind) = VariantRules.numericVariant("17th century", "Overcome in the 17th century.")!!
        assertEquals("18th century", text)
        assertEquals("ordinal", kind)
    }

    @Test
    fun `should reject an ordinal edit that would duplicate a number`() {
        val (text, _) = VariantRules.numericVariant("10th and 11th centuries", "During the 10th and 11th centuries.")!!
        assertEquals("9th and 11th centuries", text)
    }

    @Test
    fun `should edit a decimal and leave a parenthetical number alone`() {
        assertEquals("20.9%", VariantRules.numericVariant("20.8%", "Oxygen is 20.8% of air.")!!.first)
        assertEquals(
            "1.6 m (4 ft 11 in)",
            VariantRules.numericVariant("1.5 m (4 ft 11 in)", "They grow to 1.5 m (4 ft 11 in).")!!.first,
        )
    }

    @Test
    fun `should increment a comma-formatted integer`() {
        assertEquals("1,345,597", VariantRules.numericVariant("1,345,596", "The population was 1,345,596.")!!.first)
    }

    @Test
    fun `should leave verse references alone`() {
        assertNull(VariantRules.numericVariant("(Exodus 1: 15-19)", "See (Exodus 1: 15-19) for the story."))
    }

    @Test
    fun `should swap a proper name and skip a generic phrase`() {
        val context = "Tesla worked for Thomas Edison before licensing patents to George Westinghouse in the United States."
        assertEquals("George Westinghouse", VariantRules.entityVariant("Thomas Edison", context))
        assertNull(VariantRules.entityVariant("Thomas Edison", "Thomas Edison and the United States patent office."))
    }

    @Test
    fun `should pick an unsupported donor that does not contain the answer`() {
        val dump = squad(
            article("Tesla", "Nikola Tesla emigrated in 1884 and worked in the city.", "1884"),
            article("Ships", "The harbour ledger listed 1884 vessels and repeated 1884 in the margin.", "1884"),
            article("Bread", "Bakers sold rye loaves and kept no record of inventors or departure years.", "rye loaves"),
            article("Workshop", "Augusta Lovelace wrote notes after meeting Charles Babbage at the workshop.", "Augusta Lovelace"),
        )
        val dataset = VariantRules.buildDataset(dump, numericTarget = 1, entityTarget = 1)
        assertEquals(2, dataset.items.size)
        val numeric = dataset.items.first { it.subtleKind != "entity_swap" }
        assertNotEquals(numeric.title, numeric.unsupportedDonorTitle)
        assertFalse(numeric.variant(VariantKind.UNSUPPORTED).context.contains("1884"))
        assertEquals(numeric.goldAnswer, numeric.variant(VariantKind.UNSUPPORTED).answer)
        assertNotEquals(numeric.goldAnswer, numeric.variant(VariantKind.SUBTLE_ERROR).answer)
        assertTrue(numeric.variant(VariantKind.CORRECT).context.contains(numeric.goldAnswer))
        val entity = dataset.items.first { it.subtleKind == "entity_swap" }
        assertEquals("Charles Babbage", entity.variant(VariantKind.SUBTLE_ERROR).answer)
    }

    private fun article(title: String, context: String, answer: String): SquadArticle {
        val filler = "Local trade continued without dates or titles. "
        var padded = context
        while (padded.length < 480) padded += filler
        return SquadArticle(
            title = title,
            paragraphs = listOf(
                SquadParagraph(
                    context = padded.take(1400),
                    qas = listOf(SquadQa(title.lowercase(), "What is recorded?", listOf(SquadAnswer(answer)))),
                ),
            ),
        )
    }

    private fun squad(vararg articles: SquadArticle) = SquadDump(version = "1.1", data = articles.toList())
}

