package dev.helfferich.judgelies.analysis

import kotlinx.serialization.Serializable

import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.dataset.DisputedItem

@Serializable
data class ScoreRow(
    val itemId: String,
    val title: String,
    val variant: String,
    val subtleKind: String,
    val judge: String,
    val label: String,
    val prompt: String,
    val repeat: Int,
    val faithfulness: Int?,
    val correctness: Int?,
    val parsed: Boolean,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val estimatedCostUsd: Double,
    val billedCostUsd: Double?,
    val mode: String,
    val rationale: String? = null,
    val parseFailure: String? = null,
    val partialFaithfulness: Int? = null,
)

@Serializable
data class SpreadStats(
    val pctNotIdentical: Double,
    val meanRange: Double,
    val rangeHistogram: List<Int>,
)

@Serializable
data class JudgeConsistency(
    val judge: String,
    val label: String,
    val nGroups: Int,
    val faithfulness: SpreadStats,
    val correctness: SpreadStats,
)

@Serializable
data class SelfConsistency(
    val prompt: String,
    val judges: List<JudgeConsistency>,
)

@Serializable
data class PromptShift(
    val judge: String,
    val label: String,
    val n: Int,
    val meanDeltaFaithfulness: Double,
    val meanDeltaCorrectness: Double,
    val pctFaithfulnessChanged: Double,
    val pctFaithfulnessShiftAtLeast2: Double,
    val pctCorrectnessChanged: Double,
    val pctCorrectnessShiftAtLeast2: Double,
)

@Serializable
data class PromptComparison(
    val prompt: String,
    val judges: List<PromptShift>,
)

@Serializable
data class PromptSensitivity(
    val baseline: String,
    val comparisons: List<PromptComparison>,
)

@Serializable
data class AgreementMatrices(
    val exactAgreement: List<List<Double>>,
    val meanAbsDiff: List<List<Double>>,
    val quadraticKappa: List<List<Double>>,
)

@Serializable
data class InterJudge(
    val prompt: String,
    val repeat: Int,
    val nItems: Int,
    val judges: List<String>,
    val labels: List<String>,
    val faithfulness: AgreementMatrices,
    val correctness: AgreementMatrices,
)

@Serializable
data class PlantedJudge(
    val judge: String,
    val label: String,
    val correctN: Int,
    val subtleN: Int,
    val unsupportedN: Int,
    val unparsed: Int,
    val subtleMissRate: Double?,
    /** Same subtle slice, but only a perfect correctness of 5 counts as a miss. */
    val subtleMissRateAt5: Double? = null,
    /** Subtle-error rows in this slice that did not parse (not counted in any subtle rate). */
    val subtleUnparsed: Int = 0,
    /** Of those, rows that gave a faithfulness score but no correctness. */
    val subtleMissingCorrectness: Int = 0,
    val unsupportedMissRate: Double?,
    val correctFalseRejectRate: Double?,
    val subtleMissRateByKind: Map<String, Double>,
    val meanFaithfulness: Map<String, Double>,
    val meanCorrectness: Map<String, Double>,
)

@Serializable
data class PlantedErrors(
    val prompt: String,
    val repeat: Int,
    val missThreshold: Int,
    val falseRejectAtOrBelow: Int,
    val subtleMiss: String,
    val unsupportedMiss: String,
    val correctFalseReject: String,
    val judges: List<PlantedJudge>,
    val subtleMissAt5: String = "",
    val disputed: List<DisputedItem> = emptyList(),
)

@Serializable
data class Headlines(
    val meanFaithfulnessSelfDisagreement: Double?,
    val meanPairwiseFaithfulnessKappa: Double?,
    val meanSubtleMissRate: Double?,
    val meanSubtleMissRateAt5: Double? = null,
    val meanUnsupportedMissRate: Double?,
    val meanCorrectFalseRejectRate: Double?,
)

@Serializable
data class MetricsMeta(
    val schemaVersion: Int = 1,
    val mode: String,
    val simulated: Boolean,
    val startedAt: String,
    val finishedAt: String?,
    val analyzedAt: String,
    val dataset: String,
    val datasetLicense: String,
    val nItems: Int,
    val nTrialsPlanned: Int,
    val nTrialsCompleted: Int,
    val nParsed: Int,
    val temperature: Double,
    val seedSent: Boolean,
    val primaryPrompt: String,
    val promptOrder: List<String>,
    val repeats: Map<String, Int>,
    val missThreshold: Int,
    val falseRejectAtOrBelow: Int,
    val judges: List<JudgeModel>,
    val capEur: Double,
    val safetyMargin: Double,
    val eurPerUsd: Double,
    val fxAsOf: String,
    val pricingAsOf: String,
    val estimatedCostUsd: Double,
    val estimatedCostEur: Double,
    val estimatedCostEurWithMargin: Double,
    val billedCostUsd: Double,
    val stoppedEarly: Boolean,
    val promptHash: String,
)

@Serializable
data class Metrics(
    val meta: MetricsMeta,
    val headlines: Headlines,
    val selfConsistency: SelfConsistency,
    val promptSensitivity: PromptSensitivity,
    val interJudge: InterJudge,
    val plantedErrors: PlantedErrors,
    val parseFailures: List<ParseFailureSummary> = emptyList(),
)

@Serializable
data class ParseFailureSummary(
    val judge: String,
    val label: String,
    val calls: Int,
    val unparsed: Int,
    val byReason: Map<String, Int>,
    val byPrompt: Map<String, Int>,
    val byVariant: Map<String, Int>,
    /** For missing_correctness rows: how often each faithfulness value was given. */
    val missingCorrectnessFaithfulness: Map<String, Int>,
)

/** One planted case a judge let through: a subtle error scored correct, or an unsupported answer scored faithful. */
@Serializable
data class MissRow(
    val itemId: String,
    val title: String,
    val question: String,
    val goldAnswer: String,
    val variant: String,
    val subtleKind: String,
    val shownAnswer: String,
    val donorTitle: String?,
    val judge: String,
    val label: String,
    val prompt: String,
    val repeat: Int,
    val faithfulness: Int,
    val correctness: Int,
    val missedAt4: Boolean,
    val missedAt5: Boolean,
    val rationale: String?,
)

@Serializable
data class MissFile(
    val schemaVersion: Int = 1,
    val simulated: Boolean,
    val missThreshold: Int,
    val definition: String,
    val disputed: List<DisputedItem>,
    val rows: List<MissRow>,
)

@Serializable
data class ExampleScore(
    val judge: String,
    val label: String,
    val variant: String,
    val faithfulness: Int?,
    val correctness: Int?,
)

@Serializable
data class ExampleCase(
    val itemId: String,
    val title: String,
    val question: String,
    val goldAnswer: String,
    val subtleAnswer: String,
    val subtleKind: String,
    val sourceContext: String,
    val donorTitle: String,
    val donorContext: String,
    val scores: List<ExampleScore>,
)

@Serializable
data class ExampleFile(
    val schemaVersion: Int = 1,
    val simulated: Boolean,
    val cases: List<ExampleCase>,
)

data class Analysis(
    val metrics: Metrics,
    val examples: ExampleFile,
    val rows: List<ScoreRow>,
    val misses: MissFile,
)
