package dev.helfferich.judgelies.planning

import dev.helfferich.judgelies.config.ExperimentConfig
import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.cost.CostModel
import dev.helfferich.judgelies.cost.PricedCall
import dev.helfferich.judgelies.dataset.EvalItem
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.dataset.wire
import dev.helfferich.judgelies.prompt.Prompts

data class PlannedTrial(
    val itemId: String,
    val title: String,
    val variant: VariantKind,
    val subtleKind: String,
    val question: String,
    val answer: String,
    val context: String,
    val judge: JudgeModel,
    val promptId: String,
    val repeat: Int,
    val system: String,
    val user: String,
    val call: PricedCall,
) {
    /**
     * Identity of one judge call.
     * Temperature is recorded as `provider-default` when the model does not accept the parameter,
     * so a later catalog that grows a temperature knob does not reuse those rows.
     * The provider slug is part of the key so a route change misses the cache.
     */
    fun cacheKey(promptHash: String, temperature: Double): String = listOf(
        judge.id,
        promptId,
        promptHash,
        itemId,
        variant.wire(),
        repeat.toString(),
        if (judge.supportsTemperature) temperature.toString() else "provider-default",
        judge.providerOnly ?: "any-provider",
        call.maxOutputTokens.toString(),
    ).joinToString("|")
}

/**
 * Expands the config into one trial per item, variant, judge, prompt, and repeat.
 * The order is part of the experiment: file order, then correct / subtle_error / unsupported, then judges, prompts, repeats.
 */
object Planner {
    fun plan(config: ExperimentConfig, items: List<EvalItem>): List<PlannedTrial> {
        config.validate()
        val trials = mutableListOf<PlannedTrial>()
        for (item in items) {
            for (variant in item.variants) {
                for (judge in config.judges) {
                    for (promptId in config.promptOrder) {
                        val spec = Prompts.byId.getValue(promptId)
                        val user = Prompts.user(variant.context, item.question, variant.answer)
                        val call = PricedCall(
                            model = judge,
                            promptId = promptId,
                            inputTokens = CostModel.estimateTokens(spec.system + "\n" + user),
                            expectedOutputTokens = config.expectedOutputTokens.getValue(promptId),
                            maxOutputTokens = config.maxOutputTokens.getValue(promptId),
                        )
                        val repeats = config.repeats.getValue(promptId)
                        for (repeat in 0 until repeats) {
                            trials += PlannedTrial(
                                itemId = item.id,
                                title = item.title,
                                variant = variant.kind,
                                subtleKind = item.subtleKind,
                                question = item.question,
                                answer = variant.answer,
                                context = variant.context,
                                judge = judge,
                                promptId = promptId,
                                repeat = repeat,
                                system = spec.system,
                                user = user,
                                call = call,
                            )
                        }
                    }
                }
            }
        }
        return trials
    }
}
