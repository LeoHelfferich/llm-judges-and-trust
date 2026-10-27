package dev.helfferich.judgelies

import java.nio.file.Path

import kotlin.io.path.readText

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import dev.helfferich.judgelies.analysis.Analyzer
import dev.helfferich.judgelies.analysis.Metrics
import dev.helfferich.judgelies.config.ExperimentConfig
import dev.helfferich.judgelies.cost.CostModel
import dev.helfferich.judgelies.dataset.DatasetFile
import dev.helfferich.judgelies.dataset.DisputedFile
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.dataset.VariantRules
import dev.helfferich.judgelies.planning.Planner
import dev.helfferich.judgelies.run.ResponseCache
import dev.helfferich.judgelies.run.RunManifest
import dev.helfferich.judgelies.support.Jsons

class CheckedInExperimentTest {
    @Test
    fun `should keep the checked-in dataset invariants and license`() {
        val file = Jsons.pretty.decodeFromString(DatasetFile.serializer(), Path.of("data/items.json").readText())
        assertEquals("CC BY-SA 4.0", file.dataset.license)
        assertEquals(50, file.items.size)
        assertEquals(32, file.items.count { it.subtleKind != "entity_swap" })
        assertEquals(18, file.items.count { it.subtleKind == "entity_swap" })
        assertEquals(file.items.size, file.items.map { it.id }.distinct().size)
        file.items.forEach { item ->
            val correct = item.variant(VariantKind.CORRECT)
            val subtle = item.variant(VariantKind.SUBTLE_ERROR)
            val unsupported = item.variant(VariantKind.UNSUPPORTED)
            assertEquals(item.goldAnswer, correct.answer)
            assertTrue(correct.context.contains(correct.answer))
            assertEquals(correct.context, subtle.context)
            assertNotEquals(correct.answer, subtle.answer)
            assertEquals(correct.answer, unsupported.answer)
            assertNotEquals(item.title, item.unsupportedDonorTitle)
            assertFalse(unsupported.context.contains(item.goldAnswer, ignoreCase = true))
            VariantRules.salientNumbers(item.goldAnswer).forEach { number ->
                assertFalse(VariantRules.containsBounded(unsupported.context, number), item.id)
            }
            if (item.subtleKind == "entity_swap") {
                assertTrue(subtle.context.contains(subtle.answer))
            } else {
                assertFalse(subtle.context.contains(subtle.answer, ignoreCase = true))
            }
        }
    }

    @Test
    fun `should match the published metrics to the cached responses`() {
        val published = Jsons.pretty.decodeFromString(Metrics.serializer(), Path.of("docs/data/metrics.json").readText())
        // docs/data is either the simulator output or a paid run under results/runs/<startedAt stamp>.
        val runDir = if (published.meta.simulated) {
            Path.of("results/dry-run")
        } else {
            Path.of("results/runs").toFile().listFiles()!!.map { it.toPath() }
                .single { dir ->
                    Jsons.pretty.decodeFromString(RunManifest.serializer(), dir.resolve("manifest.json").readText())
                        .startedAt == published.meta.startedAt
                }
        }
        val manifest = Jsons.pretty.decodeFromString(RunManifest.serializer(), runDir.resolve("manifest.json").readText())
        assertEquals(published.meta.simulated, manifest.simulated)
        val items = Jsons.pretty.decodeFromString(DatasetFile.serializer(), Path.of("data/items.json").readText()).items
        val cache = ResponseCache(runDir.resolve("responses.jsonl")).also { it.load() }
        val disputed = Jsons.pretty.decodeFromString(DisputedFile.serializer(), Path.of("data/disputed.json").readText()).items
        val fresh = Analyzer.analyze(items, manifest, cache.rows(), "2026-01-01T00:00:00Z", disputed)
        // The one excluded subtle variant (data/disputed.json) drops 5 prompt-repeats x 5 judges.
        assertEquals(50 * 3 * 5 * 5 - disputed.size * 5 * 5, published.meta.nTrialsCompleted)
        assertEquals(fresh.misses.rows.size, Path.of("docs/data/misses.csv").readText().lines().count { it.isNotBlank() } - 1)
        assertEquals(fresh.metrics.headlines, published.headlines)
        assertEquals(
            fresh.metrics.plantedErrors.judges.map { it.subtleMissRate },
            published.plantedErrors.judges.map { it.subtleMissRate },
        )
    }

    @Test
    fun `should keep the full run under the ten euro cap`() {
        val config = Jsons.pretty.decodeFromString(ExperimentConfig.serializer(), Path.of("config/experiment.json").readText())
        config.validate()
        val items = Jsons.pretty.decodeFromString(DatasetFile.serializer(), Path.of("data/items.json").readText()).items
        val estimate = CostModel.estimate(config, Planner.plan(config, items).map { it.call })
        assertEquals(
            listOf(
                "x-ai/grok-4.3",
                "openai/gpt-5.4-mini",
                "anthropic/claude-haiku-4.5",
                "google/gemini-2.5-flash",
                "meta-llama/llama-3.3-70b-instruct",
            ),
            config.judges.map { it.id },
        )
        val mini = config.judges.first { it.id == "openai/gpt-5.4-mini" }
        assertFalse(mini.supportsTemperature)
        assertTrue(mini.supportsReasoning)
        val llama = config.judges.first { it.id == "meta-llama/llama-3.3-70b-instruct" }
        assertEquals("deepinfra", llama.providerOnly)
        assertTrue(llama.supportsTemperature)
        assertEquals(50 * 3 * 5 * 5, estimate.trials)
        assertTrue(estimate.allowed, CostModel.format(estimate))
        assertTrue(estimate.withMarginEur < 10.0)
        assertTrue(estimate.totalEur > 0.05)
        println(CostModel.format(estimate))
    }
}

