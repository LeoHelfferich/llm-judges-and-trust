package dev.helfferich.judgelies.run

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

import kotlin.io.path.exists
import kotlin.io.path.readText

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import dev.helfferich.judgelies.analysis.Analyzer
import dev.helfferich.judgelies.cli.Cli
import dev.helfferich.judgelies.config.ExperimentConfig
import dev.helfferich.judgelies.dataset.DatasetFile
import dev.helfferich.judgelies.judging.JudgeClient
import dev.helfferich.judgelies.judging.SimulatedJudge
import dev.helfferich.judgelies.report.Report
import dev.helfferich.judgelies.support.Jsons
import dev.helfferich.judgelies.support.dataset
import dev.helfferich.judgelies.support.sampleConfig

class PipelineTest {
    @Test
    fun `should cache a dry run and analyze it without a key`(@TempDir dir: Path) {
        val config = sampleConfig(capEur = 100.0)
        val calls = mutableListOf<Int>()
        val judge = JudgeClient { request ->
            calls += 1
            SimulatedJudge.complete(request)
        }
        val clock = { Instant.parse("2026-01-01T00:00:00Z") }
        val runner = ExperimentRunner(clock)
        val first = runner.run(config, dataset(), dir, judge, "dry-run", fresh = true, gitCommit = "abc")
        val called = calls.size
        val second = runner.run(config, dataset(), dir, judge, "dry-run", fresh = false, gitCommit = "abc")
        assertEquals(first.manifest.plannedTrials, called)
        assertEquals(called, second.manifest.cacheHits)
        assertEquals(called, calls.size)
        assertEquals(0.0, second.manifest.spentUsd)
        val cache = ResponseCache(dir.resolve("responses.jsonl")).also { it.load() }
        val analysis = Analyzer.analyze(dataset().items, second.manifest, cache.rows(), "2026-01-01T00:00:00Z")
        assertEquals(called, analysis.metrics.meta.nParsed)
        assertEquals(1.0, analysis.metrics.interJudge.faithfulness.quadraticKappa[0][0])
        val docs = dir.resolve("docs")
        Report.write(dir, docs, analysis)
        assertTrue(docs.resolve("metrics.json").readText().contains("\"schemaVersion\": 1"))
        assertTrue(Report.toCsv(analysis.rows).contains("\"Comma, title\""))
    }

    @Test
    fun `should run the dry-run CLI without a key`(@TempDir dir: Path) {
        val configPath = dir.resolve("experiment.json")
        val dataPath = dir.resolve("items.json")
        val config = sampleConfig(capEur = 100.0).copy(dataset = dataPath.toString())
        Files.writeString(configPath, Jsons.pretty.encodeToString(ExperimentConfig.serializer(), config))
        Files.writeString(dataPath, Jsons.pretty.encodeToString(DatasetFile.serializer(), dataset()))
        val code = Cli.run(
            arrayOf("run", "--dry-run", "--fresh", "--config", configPath.toString(), "--out", dir.resolve("out").toString()),
            env = emptyMap(),
            clock = { Instant.parse("2026-01-01T00:00:00Z") },
        )
        assertEquals(0, code)
        assertTrue(dir.resolve("out/responses.jsonl").exists())
    }

    @Test
    fun `should refuse a paid run without a key`(@TempDir dir: Path) {
        val configPath = dir.resolve("experiment.json")
        val dataPath = dir.resolve("items.json")
        Files.writeString(configPath, Jsons.pretty.encodeToString(ExperimentConfig.serializer(), sampleConfig(100.0).copy(dataset = dataPath.toString())))
        Files.writeString(dataPath, Jsons.pretty.encodeToString(DatasetFile.serializer(), dataset()))
        val code = Cli.run(
            arrayOf("run", "--allow-stale-prices", "--config", configPath.toString(), "--out", dir.resolve("paid").toString()),
            env = emptyMap(),
        )
        assertEquals(1, code)
        assertFalse(dir.resolve("paid/responses.jsonl").exists())
    }
}

