package dev.helfferich.judgelies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ReasoningControl(val effort: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int,
    val reasoning: ReasoningControl? = null,
)

data class JudgeRaw(
    val content: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val costUsd: Double?,
    val rawJson: String,
)

fun buildChatRequest(model: JudgeModel, temperature: Double?, maxTokens: Int, system: String, user: String): String {
    val request = ChatRequest(
        model = model.id,
        messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
        temperature = temperature?.takeIf { model.supportsTemperature },
        maxTokens = maxTokens,
        reasoning = if (model.supportsReasoning) ReasoningControl("none") else null,
    )
    return Jsons.compact.encodeToString(ChatRequest.serializer(), request)
}

fun parseCompletion(body: String): JudgeRaw {
    val root = Jsons.compact.parseToJsonElement(body).jsonObject
    val error = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    if (error != null && root["choices"] == null) {
        throw OpenRouterException("OpenRouter error: $error")
    }
    val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("message")?.jsonObject
        ?: throw OpenRouterException("OpenRouter response has no choices[0].message")
    val content = readMessageContent(message)
        ?: throw OpenRouterException("OpenRouter response has no message content")
    val usage = root["usage"]?.jsonObject
    return JudgeRaw(
        content = content,
        promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
        completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
        costUsd = usage?.get("cost")?.jsonPrimitive?.doubleOrNull,
        rawJson = body,
    )
}

class OpenRouterException(message: String) : RuntimeException(message)

@Serializable
data class CatalogFile(val data: List<CatalogModel> = emptyList())

@Serializable
data class CatalogModel(
    val id: String,
    val pricing: CatalogPricing = CatalogPricing(),
    @SerialName("supported_parameters") val supportedParameters: List<String> = emptyList(),
)

@Serializable
data class CatalogPricing(
    val prompt: String = "0",
    val completion: String = "0",
)

fun reconcilePrices(pinned: List<JudgeModel>, catalogJson: String): List<JudgeModel> {
    val catalog = Jsons.compact.decodeFromString(CatalogFile.serializer(), catalogJson)
    val byId = catalog.data.associateBy { it.id }
    return pinned.map { judge ->
        val live = byId[judge.id] ?: throw IllegalArgumentException(
            "OpenRouter model list has no '${judge.id}'. Update config/experiment.json.",
        )
        val livePrompt = live.pricing.prompt.toDouble() * 1_000_000.0
        val liveCompletion = live.pricing.completion.toDouble() * 1_000_000.0
        val params = live.supportedParameters
        judge.copy(
            promptUsdPerMillion = maxOf(judge.promptUsdPerMillion, livePrompt),
            completionUsdPerMillion = maxOf(judge.completionUsdPerMillion, liveCompletion),
            supportsTemperature = if (params.isEmpty()) judge.supportsTemperature else "temperature" in params,
            supportsReasoning = if (params.isEmpty()) judge.supportsReasoning else "reasoning" in params,
        )
    }
}
