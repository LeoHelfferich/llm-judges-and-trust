package dev.helfferich.judgelies.judging

import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.openrouter.JudgeRaw

data class JudgeRequest(
    val model: JudgeModel,
    val temperature: Double?,
    val maxTokens: Int,
    val system: String,
    val user: String,
    val promptId: String,
    val itemId: String,
    val variant: VariantKind,
    val repeat: Int,
)

/** What a run calls for each trial. The dry-run uses [SimulatedJudge]. A paid run uses [OpenRouterJudge]. */
fun interface JudgeClient {
    fun complete(request: JudgeRequest): JudgeRaw
}
