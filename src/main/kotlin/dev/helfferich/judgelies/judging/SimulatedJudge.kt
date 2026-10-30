package dev.helfferich.judgelies.judging

import dev.helfferich.judgelies.cost.CostModel
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.openrouter.JudgeRaw
import dev.helfferich.judgelies.support.Hashing
import dev.helfferich.judgelies.support.Jsons

/**
 * Deterministic stand-in used by `--dry-run`. The rates are not predictions of
 * the named models. They exist so the charts have a shape before any API call.
 */
object SimulatedJudge : JudgeClient {
    data class Profile(
        val subtleMissPercent: Int,
        val unsupportedMissPercent: Int,
        val driftPercent: Int,
        val falseRejectPercent: Int,
    )

    val profiles: Map<String, Profile> = mapOf(
        "x-ai/grok-4.3" to Profile(8, 6, 6, 2),
        "openai/gpt-5.4-mini" to Profile(26, 18, 16, 4),
        "anthropic/claude-haiku-4.5" to Profile(16, 30, 12, 3),
        "google/gemini-2.5-flash" to Profile(22, 36, 15, 5),
        "meta-llama/llama-3.3-70b-instruct" to Profile(14, 22, 11, 3),
    )

    override fun complete(request: JudgeRequest): JudgeRaw {
        val (faithfulness, correctness) = score(request)
        val body = Jsons.compact.encodeToString(
            SimulatedBody.serializer(),
            SimulatedBody(faithfulness, correctness, "Simulated dry-run score.", simulated = true),
        )
        return JudgeRaw(
            content = body,
            promptTokens = CostModel.estimateTokens(request.system + "\n" + request.user),
            completionTokens = CostModel.estimateTokens(body),
            costUsd = null,
            rawJson = body,
        )
    }

    fun score(request: JudgeRequest): Pair<Int, Int> {
        val profile = profiles[request.model.id] ?: Profile(20, 20, 10, 4)
        val nudge = when (request.promptId) {
            "concise" -> 10
            "cot" -> -8
            else -> 0
        }
        var faithfulness: Int
        var correctness: Int
        when (request.variant) {
            VariantKind.CORRECT -> if (hit(request, "reject", profile.falseRejectPercent + nudge)) {
                faithfulness = 2
                correctness = 3
            } else {
                faithfulness = 5
                correctness = 5
            }
            VariantKind.SUBTLE_ERROR -> if (hit(request, "subtle", profile.subtleMissPercent + nudge)) {
                faithfulness = 3
                correctness = 4
            } else {
                faithfulness = 2
                correctness = 2
            }
            VariantKind.UNSUPPORTED -> if (hit(request, "support", profile.unsupportedMissPercent + nudge)) {
                faithfulness = 5
                correctness = 5
            } else {
                faithfulness = 1
                correctness = 4
            }
        }
        if (request.repeat > 0 && bucket(request, "drift", includeRepeat = true) < profile.driftPercent) {
            val delta = if (bucket(request, "sign", includeRepeat = true) % 2 == 0) 1 else -1
            if (bucket(request, "which", includeRepeat = true) % 2 == 0) faithfulness += delta else correctness += delta
        }
        if (request.promptId == "concise" && bucket(request, "bump") < 40) faithfulness += 1
        if (request.promptId == "cot" && request.variant == VariantKind.UNSUPPORTED && bucket(request, "cot") < 50) {
            faithfulness -= 1
        }
        return faithfulness.coerceIn(1, 5) to correctness.coerceIn(1, 5)
    }

    private fun hit(request: JudgeRequest, kind: String, percent: Int): Boolean =
        bucket(request, kind) < percent.coerceIn(0, 95)

    private fun bucket(request: JudgeRequest, kind: String, includeRepeat: Boolean = false): Int {
        // The base hit/miss ignores the repeat, so the three strict calls share a score and only drift moves them.
        // Drift includes the repeat and the caller only applies it when repeat > 0, so repeat 0 stays the anchor.
        val repeat = if (includeRepeat) "|${request.repeat}" else ""
        val key = "${request.model.id}|${request.itemId}|${request.variant}|${request.promptId}$repeat|$kind"
        return Hashing.fnv1a(key) % 100
    }
}

@kotlinx.serialization.Serializable
private data class SimulatedBody(
    val faithfulness: Int,
    val correctness: Int,
    val rationale: String,
    val simulated: Boolean,
)
