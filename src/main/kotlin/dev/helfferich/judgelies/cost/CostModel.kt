package dev.helfferich.judgelies.cost

import java.util.Locale

import kotlin.math.ceil
import kotlinx.serialization.Serializable

import dev.helfferich.judgelies.config.ExperimentConfig
import dev.helfferich.judgelies.config.JudgeModel

@Serializable
data class ModelCost(
    val id: String,
    val label: String,
    val trials: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val usd: Double,
)

@Serializable
data class CostEstimate(
    val trials: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val perModel: List<ModelCost>,
    val totalUsd: Double,
    val totalEur: Double,
    val withMarginUsd: Double,
    val withMarginEur: Double,
    val ceilingUsd: Double,
    val ceilingEur: Double,
    val stressExtraReasoningTokens: Int,
    val stressUsd: Double,
    val stressEur: Double,
    val capEur: Double,
    val safetyMargin: Double,
    val eurPerUsd: Double,
    val allowed: Boolean,
)

data class PricedCall(
    val model: JudgeModel,
    val promptId: String,
    val inputTokens: Int,
    val expectedOutputTokens: Int,
    val maxOutputTokens: Int,
) {
    fun expectedUsd(): Double =
        inputTokens * model.promptUsdPerToken() + expectedOutputTokens * model.completionUsdPerToken()

    fun ceilingUsd(): Double =
        inputTokens * model.promptUsdPerToken() + maxOutputTokens * model.completionUsdPerToken()
}

/**
 * Pre-run estimate and the figures printed next to it.
 * The gate is expected cost times the safety margin. The max-token ceiling is what stops a run between calls.
 */
object CostModel {
    const val STRESS_EXTRA_OUTPUT_TOKENS = 1000

    /** Character count over four, rounded up. The safety margin exists because this is a rough count. */
    fun estimateTokens(text: String): Int = kotlin.math.max(1, (text.length + 3) / 4)

    fun estimate(config: ExperimentConfig, calls: List<PricedCall>): CostEstimate {
        val perModel = config.judges.map { judge ->
            val mine = calls.filter { it.model.id == judge.id }
            ModelCost(
                id = judge.id,
                label = judge.label,
                trials = mine.size,
                inputTokens = mine.sumOf { it.inputTokens.toLong() },
                outputTokens = mine.sumOf { it.expectedOutputTokens.toLong() },
                usd = mine.sumOf { it.expectedUsd() },
            )
        }
        val totalUsd = perModel.sumOf { it.usd }
        val ceilingUsd = calls.sumOf { it.ceilingUsd() }
        // Not the gate. This is the bill if reasoning.effort=none is ignored and each reasoning call grows by 1000 output tokens.
        val stressUsd = calls.sumOf { call ->
            val extra = if (call.model.supportsReasoning) STRESS_EXTRA_OUTPUT_TOKENS else 0
            call.ceilingUsd() + extra * call.model.completionUsdPerToken()
        }
        val margin = config.safetyMargin
        val rate = config.eurPerUsd
        val withMarginUsd = totalUsd * margin
        return CostEstimate(
            trials = calls.size,
            inputTokens = perModel.sumOf { it.inputTokens },
            outputTokens = perModel.sumOf { it.outputTokens },
            perModel = perModel,
            totalUsd = totalUsd,
            totalEur = totalUsd * rate,
            withMarginUsd = withMarginUsd,
            withMarginEur = withMarginUsd * rate,
            ceilingUsd = ceilingUsd,
            ceilingEur = ceilingUsd * rate,
            stressExtraReasoningTokens = STRESS_EXTRA_OUTPUT_TOKENS,
            stressUsd = stressUsd,
            stressEur = stressUsd * rate,
            capEur = config.capEur,
            safetyMargin = margin,
            eurPerUsd = rate,
            // Enforced line. Ceiling and stress are printed so a reader can see them; they do not block the start.
            allowed = withMarginUsd * rate <= config.capEur,
        )
    }

    fun format(estimate: CostEstimate): String = buildString {
        appendLine("Trials: ${estimate.trials}")
        appendLine(
            "Input tokens (chars/4): ${grouped(estimate.inputTokens)}    " +
                "expected output tokens: ${grouped(estimate.outputTokens)}",
        )
        appendLine("Prices are USD per 1M tokens. EUR uses ${estimate.eurPerUsd} EUR per USD.")
        appendLine()
        estimate.perModel.forEach { model ->
            appendLine(
                "${model.label.padEnd(28)} ${model.id.padEnd(36)} " +
                    "${model.trials.toString().padStart(5)} calls   ${money(model.usd)} USD",
            )
        }
        appendLine()
        appendLine("Expected:                 ${money(estimate.totalUsd)} USD   ${money(estimate.totalEur)} EUR")
        appendLine(
            "Expected × ${trim(estimate.safetyMargin)} margin:  " +
                "${money(estimate.withMarginUsd)} USD   ${money(estimate.withMarginEur)} EUR",
        )
        appendLine("Ceiling at max_tokens:    ${money(estimate.ceilingUsd)} USD   ${money(estimate.ceilingEur)} EUR")
        appendLine(
            "Stress (+${estimate.stressExtraReasoningTokens} reasoning tokens on models that support them): " +
                "${money(estimate.stressUsd)} USD   ${money(estimate.stressEur)} EUR",
        )
        appendLine("Cap:                      ${money(estimate.capEur)} EUR")
        appendLine(if (estimate.allowed) "Decision: allowed" else "Decision: REFUSED, over cap")
    }

    private fun money(value: Double): String = String.format(Locale.US, "%.4f", value)

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)
}

/** Running spend. [canAfford] looks at the next call's max-token ceiling, which is the worst bill that call can post. */
class BudgetGuard(val capUsd: Double) {
    var spentUsd: Double = 0.0
        private set

    fun canAfford(nextUsd: Double): Boolean = spentUsd + nextUsd <= capUsd + 1e-9

    fun commit(actualUsd: Double) {
        require(actualUsd >= 0.0)
        spentUsd += actualUsd
    }
}

class BudgetRefused(val estimate: CostEstimate) : RuntimeException(
    "Refusing to run: estimated cost with margin is ${formatEur(estimate.withMarginEur)} EUR, cap is ${formatEur(estimate.capEur)} EUR.",
)

fun formatEur(value: Double): String = String.format(Locale.US, "%.4f", value)

/** chars/4 rounded up, exposed for tests that want the same ceiling rule. */
fun ceilDiv4(chars: Int): Int = ceil(chars / 4.0).toInt()
