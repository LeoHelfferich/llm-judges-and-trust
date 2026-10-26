package dev.helfferich.judgelies

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ParsedScore(
    val faithfulness: Int?,
    val correctness: Int?,
    val rationale: String?,
    val parsed: Boolean,
    /** Why a reply did not parse. Null when [parsed] is true. */
    val failure: ParseFailure? = null,
    /** A valid faithfulness score found in a reply that still failed, e.g. one that left out correctness. */
    val partialFaithfulness: Int? = null,
) {
    companion object {
        fun failed(reason: ParseFailure, partialFaithfulness: Int? = null, rationale: String? = null) =
            ParsedScore(null, null, rationale, false, reason, partialFaithfulness)
    }
}

enum class ParseFailure(val wire: String) {
    NO_JSON("no_json"),
    UNTERMINATED_JSON("unterminated_json"),
    INVALID_JSON("invalid_json"),
    MISSING_FAITHFULNESS("missing_faithfulness"),
    MISSING_CORRECTNESS("missing_correctness"),
}

object ScoreParser {
    fun parse(text: String): ParsedScore {
        val json = extractObject(text)
            ?: return ParsedScore.failed(if (text.contains('{')) ParseFailure.UNTERMINATED_JSON else ParseFailure.NO_JSON)
        val obj = try {
            Jsons.compact.parseToJsonElement(json) as? JsonObject ?: return ParsedScore.failed(ParseFailure.INVALID_JSON)
        } catch (_: Exception) {
            return ParsedScore.failed(ParseFailure.INVALID_JSON)
        }
        val rationale = obj["rationale"]?.let { it as? JsonPrimitive }?.contentOrNull?.trim()?.take(500)
        val faithfulness = readScore(obj, "faithfulness")
            ?: return ParsedScore.failed(ParseFailure.MISSING_FAITHFULNESS, rationale = rationale)
        val correctness = readScore(obj, "correctness")
            ?: return ParsedScore.failed(ParseFailure.MISSING_CORRECTNESS, faithfulness, rationale)
        return ParsedScore(faithfulness, correctness, rationale, true)
    }

    private fun readScore(obj: JsonObject, key: String): Int? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        val value = primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
        return value?.takeIf { it in 1..5 }
    }

    fun extractObject(text: String): String? {
        val stripped = text.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = stripped.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until stripped.length) {
            val char = stripped[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

object Agreement {
    /**
     * Quadratic weighted Cohen's kappa. Categories are the integers from [min] to [max].
     * Returns 1 when both raters are constant and identical (the weight sum is zero).
     */
    fun quadraticWeightedKappa(left: IntArray, right: IntArray, min: Int = 1, max: Int = 5): Double {
        require(left.size == right.size && left.isNotEmpty()) { "kappa needs paired non-empty ratings" }
        val span = max - min
        require(span > 0)
        val nCat = span + 1
        val observed = Array(nCat) { DoubleArray(nCat) }
        left.indices.forEach { i ->
            val a = left[i]
            val b = right[i]
            require(a in min..max && b in min..max) { "rating outside $min..$max" }
            observed[a - min][b - min] += 1.0
        }
        val n = left.size.toDouble()
        val row = DoubleArray(nCat) { r -> observed[r].sum() }
        val col = DoubleArray(nCat) { c -> (0 until nCat).sumOf { r -> observed[r][c] } }
        var weightedObserved = 0.0
        var weightedExpected = 0.0
        for (r in 0 until nCat) {
            for (c in 0 until nCat) {
                val weight = (r - c) * (r - c) / (span.toDouble() * span)
                weightedObserved += weight * observed[r][c]
                weightedExpected += weight * (row[r] * col[c] / n)
            }
        }
        if (weightedExpected == 0.0) return if (weightedObserved == 0.0) 1.0 else 0.0
        return 1.0 - weightedObserved / weightedExpected
    }
}

/** Reads message content that is either a string or a list of text parts. */
fun readMessageContent(message: JsonObject): String? {
    val content = message["content"] ?: return null
    return when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.joinToString("").ifBlank { null }
        else -> null
    }
}
