package dev.helfferich.judgelies.openrouter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.support.Jsons
import dev.helfferich.judgelies.support.jsonArray
import dev.helfferich.judgelies.support.jsonObject
import dev.helfferich.judgelies.support.jsonPrimitive

class OpenRouterContractTest {
    @Test
    fun `should omit temperature when unsupported and disable reasoning when supported`() {
        val grok = judge("x-ai/grok-4.3", temperature = true, reasoning = true)
        val plain = judge("example/plain", temperature = true, reasoning = false)
        val mini = judge("openai/gpt-5.4-mini", temperature = false, reasoning = true)
        val llama = JudgeModel(
            "meta-llama/llama-3.3-70b-instruct",
            "Llama 3.3 70B",
            "Meta",
            0.1,
            0.32,
            true,
            false,
            providerOnly = "deepinfra",
        )
        val grokJson = Jsons.compact.parseToJsonElement(buildChatRequest(grok, 0.0, 160, "sys", "user")).jsonObject()
        val plainJson = Jsons.compact.parseToJsonElement(buildChatRequest(plain, 0.0, 160, "sys", "user")).jsonObject()
        val miniJson = Jsons.compact.parseToJsonElement(buildChatRequest(mini, 0.0, 160, "sys", "user")).jsonObject()
        val llamaJson = Jsons.compact.parseToJsonElement(buildChatRequest(llama, 0.0, 160, "sys", "user")).jsonObject()
        assertEquals("none", grokJson["reasoning"]!!.jsonObject()["effort"]!!.jsonPrimitive().content)
        assertEquals(0.0, grokJson["temperature"]!!.jsonPrimitive().content.toDouble())
        assertFalse(grokJson.containsKey("provider"))
        assertFalse(plainJson.containsKey("reasoning"))
        assertTrue(plainJson.containsKey("temperature"))
        assertEquals("none", miniJson["reasoning"]!!.jsonObject()["effort"]!!.jsonPrimitive().content)
        assertFalse(miniJson.containsKey("temperature"))
        assertEquals(0.0, llamaJson["temperature"]!!.jsonPrimitive().content.toDouble())
        assertFalse(llamaJson.containsKey("reasoning"))
        val pin = llamaJson["provider"]!!.jsonObject()
        assertEquals("deepinfra", pin["only"]!!.jsonArray()[0].jsonPrimitive().content)
        assertEquals(false, pin["allow_fallbacks"]!!.jsonPrimitive().content.toBoolean())
    }

    @Test
    fun `should parse usage cost and list content`() {
        val raw = parseCompletion(
            """
            {"choices":[{"message":{"content":[{"type":"text","text":"{\"faithfulness\":5,\"correctness\":4,\"rationale\":\"ok\"}"}]}}],
             "usage":{"prompt_tokens":11,"completion_tokens":7,"cost":0.00021}}
            """.trimIndent(),
        )
        assertEquals(11, raw.promptTokens)
        assertEquals(7, raw.completionTokens)
        assertEquals(0.00021, raw.costUsd)
        assertTrue(raw.content.contains("faithfulness"))
    }

    @Test
    fun `should take the higher price and the live parameter support`() {
        val pinned = listOf(judge("x-ai/grok-4.3", temperature = true, reasoning = true, prompt = 1.0, completion = 2.0))
        val catalog = """
            {"data":[{"id":"x-ai/grok-4.3","pricing":{"prompt":"0.00000125","completion":"0.000002"},"supported_parameters":["temperature","max_tokens"]}]}
        """.trimIndent()
        val live = reconcilePrices(pinned, catalog).single()
        assertEquals(1.25, live.promptUsdPerMillion, 1e-9)
        assertEquals(2.0, live.completionUsdPerMillion, 1e-9)
        assertTrue(live.supportsTemperature)
        assertFalse(live.supportsReasoning)
    }

    private fun judge(
        id: String,
        temperature: Boolean,
        reasoning: Boolean,
        prompt: Double = 1.0,
        completion: Double = 1.0,
    ) = JudgeModel(id, id, "Vendor", prompt, completion, temperature, reasoning)
}

