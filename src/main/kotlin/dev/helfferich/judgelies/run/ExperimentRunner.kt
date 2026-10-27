package dev.helfferich.judgelies.run

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

import kotlin.io.path.deleteIfExists
import kotlinx.serialization.Serializable

import dev.helfferich.judgelies.config.ExperimentConfig
import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.cost.BudgetGuard
import dev.helfferich.judgelies.cost.BudgetRefused
import dev.helfferich.judgelies.cost.CostEstimate
import dev.helfferich.judgelies.cost.CostModel
import dev.helfferich.judgelies.cost.formatEur
import dev.helfferich.judgelies.dataset.DatasetFile
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.dataset.wire
import dev.helfferich.judgelies.judging.JudgeClient
import dev.helfferich.judgelies.judging.JudgeRequest
import dev.helfferich.judgelies.openrouter.OpenRouterException
import dev.helfferich.judgelies.planning.PlannedTrial
import dev.helfferich.judgelies.planning.Planner
import dev.helfferich.judgelies.prompt.Prompts
import dev.helfferich.judgelies.support.Jsons

@Serializable
data class PromptDump(val system: String)

@Serializable
data class RunManifest(
    val schemaVersion: Int = 1,
    val mode: String,
    val simulated: Boolean,
    val startedAt: String,
    val finishedAt: String?,
    val datasetPath: String,
    val datasetName: String,
    val datasetLicense: String,
    val datasetItems: Int,
    val temperature: Double,
    val seedSent: Boolean,
    val promptHash: String,
    val prompts: Map<String, PromptDump>,
    val judges: List<JudgeModel>,
    val promptOrder: List<String>,
    val primaryPrompt: String,
    val repeats: Map<String, Int>,
    val missThreshold: Int,
    val falseRejectAtOrBelow: Int,
    val pricingAsOf: String,
    val pricingSource: String,
    val fxAsOf: String,
    val fxSource: String,
    val eurPerUsd: Double,
    val capEur: Double,
    val safetyMargin: Double,
    val estimate: CostEstimate,
    val plannedTrials: Int,
    val completedTrials: Int,
    val cacheHits: Int,
    val stoppedEarly: Boolean,
    val spentUsd: Double,
    val stopReason: String?,
    val gitCommit: String?,
)

data class RunResult(
    val manifest: RunManifest,
    val outputDir: Path,
)

/**
 * Walks the plan once and appends every raw reply to the cache.
 * A later run with the same cache skips calls that are already stored, and those skips cost nothing.
 */
class ExperimentRunner(
    private val clock: () -> Instant = Instant::now,
) {
    fun run(
        config: ExperimentConfig,
        dataset: DatasetFile,
        outputDir: Path,
        judge: JudgeClient,
        mode: String,
        fresh: Boolean,
        gitCommit: String?,
    ): RunResult {
        val trials = Planner.plan(config, dataset.items)
        val estimate = CostModel.estimate(config, trials.map { it.call })
        if (!estimate.allowed) throw BudgetRefused(estimate)

        Files.createDirectories(outputDir)
        val cacheFile = outputDir.resolve("responses.jsonl")
        if (fresh) {
            cacheFile.deleteIfExists()
            outputDir.resolve("manifest.json").deleteIfExists()
            outputDir.resolve("metrics.json").deleteIfExists()
            outputDir.resolve("scores.csv").deleteIfExists()
        }
        val cache = ResponseCache(cacheFile)
        cache.load()
        val promptHash = Prompts.hash()
        val budget = BudgetGuard(config.capUsd())
        val started = clock()
        var cacheHits = 0
        var completed = 0
        var stoppedEarly = false
        var stopReason: String? = null
        var consecutiveFailures = 0

        fun writeManifest(finished: Instant?, spent: Double) {
            val manifest = manifest(
                config, dataset, mode, started, finished, promptHash, estimate,
                trials.size, completed, cacheHits, stoppedEarly, spent, stopReason, gitCommit,
            )
            Files.writeString(outputDir.resolve("manifest.json"), Jsons.pretty.encodeToString(RunManifest.serializer(), manifest))
        }
        writeManifest(null, 0.0)

        for (trial in trials) {
            val key = trial.cacheKey(promptHash, config.temperature)
            if (cache.get(key) != null) {
                cacheHits++
                completed++
                continue
            }
            // Stop before sending. The only overshoot left is the single call already in flight.
            if (!budget.canAfford(trial.call.ceilingUsd())) {
                stoppedEarly = true
                stopReason = "Next call's max_tokens ceiling would exceed the ${config.capEur} EUR cap."
                break
            }
            val temperature = config.temperature.takeIf { trial.judge.supportsTemperature }
            val request = JudgeRequest(
                model = trial.judge,
                temperature = temperature,
                maxTokens = trial.call.maxOutputTokens,
                system = trial.system,
                user = trial.user,
                promptId = trial.promptId,
                itemId = trial.itemId,
                variant = trial.variant,
                repeat = trial.repeat,
            )
            val raw = try {
                judge.complete(request)
            } catch (ex: OpenRouterException) {
                consecutiveFailures++
                cache.put(errorRow(trial, key, promptHash, temperature, ex.message ?: "OpenRouter error", mode))
                completed++
                if (consecutiveFailures >= 5 || ex.message?.contains("API key") == true) {
                    stoppedEarly = true
                    stopReason = ex.message
                    break
                }
                continue
            }
            consecutiveFailures = 0
            val charged = raw.costUsd ?: trial.call.expectedUsd()
            val costSource = when {
                mode == "dry-run" -> "dry-run"
                raw.costUsd != null -> "usage"
                else -> "estimate"
            }
            if (mode != "dry-run") budget.commit(charged)
            cache.put(
                CachedTrial(
                    key = key,
                    model = trial.judge.id,
                    promptId = trial.promptId,
                    promptHash = promptHash,
                    itemId = trial.itemId,
                    variant = trial.variant.wire(),
                    repeat = trial.repeat,
                    temperature = temperature,
                    content = raw.content,
                    promptTokens = raw.promptTokens,
                    completionTokens = raw.completionTokens,
                    estimatedCostUsd = trial.call.expectedUsd(),
                    billedCostUsd = if (mode == "dry-run") null else charged,
                    costSource = costSource,
                    mode = mode,
                    createdAt = clock().toString(),
                ),
            )
            completed++
            if (completed % 250 == 0) {
                val progress = if (mode == "dry-run") {
                    "  $completed / ${trials.size}"
                } else {
                    "  $completed / ${trials.size}  spent ${formatEur(budget.spentUsd * config.eurPerUsd)} EUR"
                }
                println(progress)
            }
        }
        val finished = clock()
        val spent = if (mode == "dry-run") 0.0 else budget.spentUsd
        writeManifest(finished, spent)
        val manifest = Jsons.pretty.decodeFromString(
            RunManifest.serializer(),
            Files.readString(outputDir.resolve("manifest.json")),
        )
        return RunResult(manifest, outputDir)
    }

    private fun errorRow(
        trial: PlannedTrial,
        key: String,
        promptHash: String,
        temperature: Double?,
        message: String,
        mode: String,
    ) = CachedTrial(
        key = key,
        model = trial.judge.id,
        promptId = trial.promptId,
        promptHash = promptHash,
        itemId = trial.itemId,
        variant = variantName(trial.variant),
        repeat = trial.repeat,
        temperature = temperature,
        content = "ERROR: $message",
        promptTokens = null,
        completionTokens = null,
        estimatedCostUsd = 0.0,
        billedCostUsd = null,
        costSource = "error",
        mode = mode,
        createdAt = clock().toString(),
    )
}

private fun variantName(kind: VariantKind): String = when (kind) {
    VariantKind.CORRECT -> "correct"
    VariantKind.SUBTLE_ERROR -> "subtle_error"
    VariantKind.UNSUPPORTED -> "unsupported"
}

private fun manifest(
    config: ExperimentConfig,
    dataset: DatasetFile,
    mode: String,
    started: Instant,
    finished: Instant?,
    promptHash: String,
    estimate: CostEstimate,
    planned: Int,
    completed: Int,
    cacheHits: Int,
    stoppedEarly: Boolean,
    spent: Double,
    stopReason: String?,
    gitCommit: String?,
) = RunManifest(
    mode = mode,
    simulated = mode == "dry-run",
    startedAt = started.toString(),
    finishedAt = finished?.toString(),
    datasetPath = config.dataset,
    datasetName = dataset.dataset.name,
    datasetLicense = dataset.dataset.license,
    datasetItems = dataset.items.size,
    temperature = config.temperature,
    seedSent = false,
    promptHash = promptHash,
    prompts = Prompts.all.associate { it.id to PromptDump(it.system) },
    judges = config.judges,
    promptOrder = config.promptOrder,
    primaryPrompt = config.primaryPrompt,
    repeats = config.repeats,
    missThreshold = config.missThreshold,
    falseRejectAtOrBelow = config.falseRejectAtOrBelow,
    pricingAsOf = config.pricingAsOf,
    pricingSource = config.pricingSource,
    fxAsOf = config.fxAsOf,
    fxSource = config.fxSource,
    eurPerUsd = config.eurPerUsd,
    capEur = config.capEur,
    safetyMargin = config.safetyMargin,
    estimate = estimate,
    plannedTrials = planned,
    completedTrials = completed,
    cacheHits = cacheHits,
    stoppedEarly = stoppedEarly,
    spentUsd = spent,
    stopReason = stopReason,
    gitCommit = gitCommit,
)
