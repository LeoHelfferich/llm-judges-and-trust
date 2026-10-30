package dev.helfferich.judgelies.config

import java.util.Locale

import kotlinx.serialization.Serializable

import dev.helfferich.judgelies.prompt.Prompts

@Serializable
data class JudgeModel(
    val id: String,
    val label: String,
    val vendor: String,
    val promptUsdPerMillion: Double,
    val completionUsdPerMillion: Double,
    val supportsTemperature: Boolean,
    val supportsReasoning: Boolean,
    /** OpenRouter provider slug. When set, the request allows that provider only. */
    val providerOnly: String? = null,
) {
    fun promptUsdPerToken(): Double = promptUsdPerMillion / 1_000_000.0

    fun completionUsdPerToken(): Double = completionUsdPerMillion / 1_000_000.0
}

@Serializable
/**
 * The checked-in experiment: judges, prices, repeats, and the euro cap.
 * [validate] is the set of invariants a paid run relies on before it spends anything.
 */
data class ExperimentConfig(
    val dataset: String,
    val temperature: Double,
    val missThreshold: Int,
    val falseRejectAtOrBelow: Int = 2,
    val primaryPrompt: String,
    val promptOrder: List<String>,
    val safetyMargin: Double,
    val capEur: Double,
    val eurPerUsd: Double,
    val fxAsOf: String,
    val fxSource: String,
    val pricingAsOf: String,
    val pricingSource: String,
    val expectedOutputTokens: Map<String, Int>,
    val maxOutputTokens: Map<String, Int>,
    val repeats: Map<String, Int>,
    val judges: List<JudgeModel>,
    val schemaVersion: Int = 1,
) {
    fun validate() {
        require(dataset.isNotBlank()) { "dataset path is blank" }
        require(temperature in 0.0..2.0) { "temperature must be between 0 and 2" }
        require(missThreshold in 1..5) { "missThreshold must be 1..5" }
        require(falseRejectAtOrBelow in 1..4) { "falseRejectAtOrBelow must be 1..4" }
        require(safetyMargin >= 1.0) { "safetyMargin must be >= 1" }
        require(capEur > 0.0) { "capEur must be positive" }
        require(eurPerUsd > 0.0) { "eurPerUsd must be positive" }
        require(judges.isNotEmpty()) { "at least one judge is required" }
        require(judges.map { it.id }.distinct().size == judges.size) { "judge ids must be unique" }
        require(judges.map { it.vendor }.distinct().size == judges.size) { "pick judges from different vendors" }
        judges.forEach { judge ->
            require(judge.promptUsdPerMillion > 0.0 && judge.completionUsdPerMillion > 0.0) {
                "prices for ${judge.id} must be positive"
            }
            judge.providerOnly?.let { slug ->
                require(slug.isNotBlank() && slug == slug.lowercase(Locale.ROOT) && slug.none { it.isWhitespace() }) {
                    "providerOnly for ${judge.id} must be an OpenRouter provider slug"
                }
            }
        }
        require(promptOrder.isNotEmpty()) { "promptOrder is empty" }
        require(promptOrder.distinct().size == promptOrder.size) { "promptOrder has duplicates" }
        require(primaryPrompt in promptOrder) { "primaryPrompt $primaryPrompt is not in promptOrder" }
        promptOrder.forEach { id ->
            require(id in Prompts.byId) { "unknown prompt '$id'" }
            require((repeats[id] ?: 0) >= 1) { "repeats for $id must be >= 1" }
            require((expectedOutputTokens[id] ?: 0) >= 1) { "expectedOutputTokens for $id must be >= 1" }
            require((maxOutputTokens[id] ?: 0) >= expectedOutputTokens.getValue(id)) {
                "maxOutputTokens for $id must be >= expectedOutputTokens"
            }
        }
        require((repeats[primaryPrompt] ?: 0) >= 2) {
            "primary prompt needs at least 2 repeats so self-consistency is measurable"
        }
    }

    fun capUsd(): Double = capEur / eurPerUsd
}

