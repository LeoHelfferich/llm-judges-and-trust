package dev.helfferich.judgelies.cost

import java.nio.file.Path
import java.time.Instant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import dev.helfferich.judgelies.judging.JudgeClient
import dev.helfferich.judgelies.openrouter.JudgeRaw
import dev.helfferich.judgelies.planning.Planner
import dev.helfferich.judgelies.run.ExperimentRunner
import dev.helfferich.judgelies.support.countingJudge
import dev.helfferich.judgelies.support.dataset
import dev.helfferich.judgelies.support.sampleConfig

class CostAndBudgetTest {
    @Test
    fun `should estimate tokens as ceil of characters over four`() {
        assertEquals(1, CostModel.estimateTokens("abcd"))
        assertEquals(2, CostModel.estimateTokens("abcde"))
    }

    @Test
    fun `should stop the guard when the next charge would cross the cap`() {
        val guard = BudgetGuard(0.05)
        assertTrue(guard.canAfford(0.05))
        guard.commit(0.04)
        assertFalse(guard.canAfford(0.02))
        assertTrue(guard.canAfford(0.01))
    }

    @Test
    fun `should refuse the run before any call when the margin exceeds the cap`(@TempDir dir: Path) {
        val config = sampleConfig(capEur = 0.0000001)
        val calls = mutableListOf<String>()
        val judge = countingJudge(calls)
        val failed = runCatching {
            ExperimentRunner { Instant.parse("2026-01-01T00:00:00Z") }.run(
                config, dataset(), dir, judge, "openrouter", fresh = true, gitCommit = null,
            )
        }
        assertTrue(failed.exceptionOrNull() is BudgetRefused)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `should stop once actual spend cannot fit another ceiling`(@TempDir dir: Path) {
        val loose = sampleConfig(capEur = 1_000.0, completionUsdPerMillion = 1_000.0, maxTokens = 5_000, expectedTokens = 10)
        val ceiling = Planner.plan(loose, dataset().items).first().call.ceilingUsd()
        val config = loose.copy(capEur = ceiling * 1.2)
        val calls = mutableListOf<String>()
        val judge = JudgeClient { request ->
            calls += request.itemId
            JudgeRaw("""{"faithfulness":5,"correctness":5,"rationale":"ok"}""", 10, 10, ceiling, "{}")
        }
        val result = ExperimentRunner { Instant.parse("2026-01-01T00:00:00Z") }.run(
            config, dataset(), dir, judge, "openrouter", fresh = true, gitCommit = null,
        )
        assertEquals(1, calls.size)
        assertTrue(result.manifest.stoppedEarly)
        assertTrue(result.manifest.spentUsd <= config.capUsd() + ceiling)
    }
}

