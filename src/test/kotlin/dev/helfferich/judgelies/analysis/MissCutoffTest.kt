package dev.helfferich.judgelies.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.dataset.AnswerVariant
import dev.helfferich.judgelies.dataset.DisputedItem
import dev.helfferich.judgelies.dataset.EvalItem
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.scoring.ParseFailure

class MissCutoffTest {
    private val judge = JudgeModel(
        id = "j/one", label = "One", vendor = "V",
        promptUsdPerMillion = 1.0, completionUsdPerMillion = 1.0,
        supportsTemperature = true, supportsReasoning = false,
    )

    private fun row(item: String, variant: String, faithfulness: Int?, correctness: Int?, failure: ParseFailure? = null) = ScoreRow(
        itemId = item, title = item, variant = variant, subtleKind = "year", judge = judge.id, label = judge.label,
        prompt = "strict", repeat = 0, faithfulness = faithfulness, correctness = correctness,
        parsed = failure == null, promptTokens = null, completionTokens = null, estimatedCostUsd = 0.0,
        billedCostUsd = null, mode = "test", parseFailure = failure?.wire,
        partialFaithfulness = if (failure != null) faithfulness else null,
    )

    @Test
    fun `should apply the cutoffs, disputed items, and unparsed rows`() {
        val rows = listOf(
            row("a", "subtle_error", 2, 3),
            row("b", "subtle_error", 4, 4),
            row("c", "subtle_error", 5, 5),
            row("d", "subtle_error", 5, 5),
            row("e", "subtle_error", null, null, ParseFailure.MISSING_CORRECTNESS),
            row("a", "correct", 5, 5),
        )
        val disputed = listOf(DisputedItem("d", "acceptable"))
        val planted = Analyzer.planted(rows, listOf(judge), "strict", missAt = 4, rejectAt = 2, disputed = disputed)
        val j = planted.judges.single()
        assertEquals(4, j.subtleN)
        assertEquals(3.0 / 4, j.subtleMissRate)
        assertEquals(2.0 / 4, j.subtleMissRateAt5)
        assertEquals(1, j.subtleUnparsed)
        assertEquals(1, j.subtleMissingCorrectness)
        assertNull(Analyzer.subtleMissRate(rows.filter { !it.parsed }, 4))
    }

    @Test
    fun `should list only planted cases that meet the miss threshold`() {
        val item = EvalItem(
            id = "a", title = "A", question = "Q?", goldAnswer = "1712", subtleKind = "year", unsupportedDonorTitle = "B",
            variants = listOf(
                AnswerVariant(VariantKind.CORRECT, "1712", "ctx"),
                AnswerVariant(VariantKind.SUBTLE_ERROR, "1713", "ctx"),
                AnswerVariant(VariantKind.UNSUPPORTED, "1712", "other"),
            ),
        )
        val rows = listOf(
            row("a", "subtle_error", 4, 4),
            row("a", "subtle_error", 2, 3),
            row("a", "unsupported", 5, 5),
            row("a", "correct", 5, 5),
        )
        val misses = Analyzer.misses(listOf(item), rows, missAt = 4, simulated = false, disputed = listOf(DisputedItem("a", "x")))
        assertEquals(2, misses.rows.size)
        val subtle = misses.rows.single { it.variant == "subtle_error" }
        assertEquals("1713", subtle.shownAnswer)
        assertTrue(subtle.missedAt4)
        assertFalse(subtle.missedAt5)
        assertTrue(misses.rows.single { it.variant == "unsupported" }.missedAt5)
    }
}
