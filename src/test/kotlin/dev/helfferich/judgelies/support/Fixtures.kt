package dev.helfferich.judgelies.support

import dev.helfferich.judgelies.config.ExperimentConfig
import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.dataset.AnswerVariant
import dev.helfferich.judgelies.dataset.DatasetFile
import dev.helfferich.judgelies.dataset.DatasetInfo
import dev.helfferich.judgelies.dataset.EvalItem
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.judging.JudgeClient
import dev.helfferich.judgelies.openrouter.JudgeRaw

internal fun sampleConfig(
    capEur: Double,
    completionUsdPerMillion: Double = 1.0,
    maxTokens: Int = 80,
    expectedTokens: Int = 20,
) = ExperimentConfig(
    dataset = "data/items.json",
    temperature = 0.0,
    missThreshold = 4,
    falseRejectAtOrBelow = 2,
    primaryPrompt = "strict",
    promptOrder = listOf("strict"),
    safetyMargin = 1.0,
    capEur = capEur,
    eurPerUsd = 1.0,
    fxAsOf = "2026-10-23",
    fxSource = "test",
    pricingAsOf = "2026-10-26",
    pricingSource = "test",
    expectedOutputTokens = mapOf("strict" to expectedTokens),
    maxOutputTokens = mapOf("strict" to maxTokens),
    repeats = mapOf("strict" to 2),
    judges = listOf(
        JudgeModel("x-ai/grok-4.3", "Grok 4.3", "xAI", 1.0, completionUsdPerMillion, true, true),
    ),
)

internal fun dataset(): DatasetFile {
    val context = "Nikola Tesla emigrated in 1884 and later worked in New York."
    return DatasetFile(
        dataset = DatasetInfo(
            name = "fixture",
            license = "CC BY-SA 4.0",
            sourceUrl = "https://example.test",
            paper = "https://example.test",
            citation = "fixture",
            wikipediaLicense = "CC BY-SA",
            selection = "fixture",
            squadVersion = "1.1",
        ),
        items = listOf(
            EvalItem(
                id = "q1",
                title = "Comma, title",
                question = "When did Nikola Tesla emigrate?",
                goldAnswer = "1884",
                subtleKind = "year",
                unsupportedDonorTitle = "Ships",
                variants = listOf(
                    AnswerVariant(VariantKind.CORRECT, "1884", context),
                    AnswerVariant(VariantKind.SUBTLE_ERROR, "1885", context),
                    AnswerVariant(VariantKind.UNSUPPORTED, "1884", "The harbour sold bread and kept no inventor."),
                ),
            ),
        ),
    )
}

internal fun countingJudge(calls: MutableList<String>) = JudgeClient { request ->
    calls += request.itemId
    JudgeRaw("""{"faithfulness":5,"correctness":5,"rationale":"ok"}""", 10, 10, 0.0, "{}")
}

internal fun kotlinx.serialization.json.JsonElement.jsonObject() = this as kotlinx.serialization.json.JsonObject
internal fun kotlinx.serialization.json.JsonElement.jsonArray() = this as kotlinx.serialization.json.JsonArray
internal fun kotlinx.serialization.json.JsonElement.jsonPrimitive() = this as kotlinx.serialization.json.JsonPrimitive

