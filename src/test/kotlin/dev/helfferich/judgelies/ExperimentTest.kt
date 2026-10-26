package dev.helfferich.judgelies

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

class HashingTest {
    @Test
    fun fnv1aMatchesTheKnownVector() {
        assertEquals(1_335_831_723, Hashing.fnv1a("hello"))
    }
}

class VariantRulesTest {
    @Test
    fun yearMovesByOneWhenTheNeighbourIsAbsent() {
        val (text, kind) = VariantRules.numericVariant("1884", "Nikola Tesla emigrated in 1884.")!!
        assertEquals("1885", text)
        assertEquals("year", kind)
    }

    @Test
    fun yearSkipsANumberThatAlreadyAppears() {
        val (text, _) = VariantRules.numericVariant("1884", "In 1884 and again in 1885 he moved.")!!
        assertEquals("1883", text)
    }

    @Test
    fun decadeStepsByTen() {
        val (text, kind) = VariantRules.numericVariant("the 1960s", "It was discovered in the 1960s.")!!
        assertEquals("the 1970s", text)
        assertEquals("decade", kind)
    }

    @Test
    fun ordinalKeepsARealSuffix() {
        val (text, kind) = VariantRules.numericVariant("17th century", "Overcome in the 17th century.")!!
        assertEquals("18th century", text)
        assertEquals("ordinal", kind)
    }

    @Test
    fun duplicatedOrdinalIsRejected() {
        val (text, _) = VariantRules.numericVariant("10th and 11th centuries", "During the 10th and 11th centuries.")!!
        assertEquals("9th and 11th centuries", text)
    }

    @Test
    fun decimalAndParenthetical() {
        assertEquals("20.9%", VariantRules.numericVariant("20.8%", "Oxygen is 20.8% of air.")!!.first)
        assertEquals(
            "1.6 m (4 ft 11 in)",
            VariantRules.numericVariant("1.5 m (4 ft 11 in)", "They grow to 1.5 m (4 ft 11 in).")!!.first,
        )
    }

    @Test
    fun commaNumberIncrements() {
        assertEquals("1,345,597", VariantRules.numericVariant("1,345,596", "The population was 1,345,596.")!!.first)
    }

    @Test
    fun verseReferencesAreNotPerturbed() {
        assertNull(VariantRules.numericVariant("(Exodus 1: 15-19)", "See (Exodus 1: 15-19) for the story."))
    }

    @Test
    fun entitySwapPicksAnotherNameAndSkipsGenericPhrases() {
        val context = "Tesla worked for Thomas Edison before licensing patents to George Westinghouse in the United States."
        assertEquals("George Westinghouse", VariantRules.entityVariant("Thomas Edison", context))
        assertNull(VariantRules.entityVariant("Thomas Edison", "Thomas Edison and the United States patent office."))
    }

    @Test
    fun donorSkipsPassagesThatContainTheAnswer() {
        val dump = squad(
            article("Tesla", "Nikola Tesla emigrated in 1884 and worked in the city.", "1884"),
            article("Ships", "The harbour ledger listed 1884 vessels and repeated 1884 in the margin.", "1884"),
            article("Bread", "Bakers sold rye loaves and kept no record of inventors or departure years.", "rye loaves"),
            article("Workshop", "Augusta Lovelace wrote notes after meeting Charles Babbage at the workshop.", "Augusta Lovelace"),
        )
        val dataset = VariantRules.buildDataset(dump, numericTarget = 1, entityTarget = 1)
        assertEquals(2, dataset.items.size)
        val numeric = dataset.items.first { it.subtleKind != "entity_swap" }
        assertNotEquals(numeric.title, numeric.unsupportedDonorTitle)
        assertFalse(numeric.variant(VariantKind.UNSUPPORTED).context.contains("1884"))
        assertEquals(numeric.goldAnswer, numeric.variant(VariantKind.UNSUPPORTED).answer)
        assertNotEquals(numeric.goldAnswer, numeric.variant(VariantKind.SUBTLE_ERROR).answer)
        assertTrue(numeric.variant(VariantKind.CORRECT).context.contains(numeric.goldAnswer))
        val entity = dataset.items.first { it.subtleKind == "entity_swap" }
        assertEquals("Charles Babbage", entity.variant(VariantKind.SUBTLE_ERROR).answer)
    }

    private fun article(title: String, context: String, answer: String): SquadArticle {
        val filler = "Local trade continued without dates or titles. "
        var padded = context
        while (padded.length < 480) padded += filler
        return SquadArticle(
            title = title,
            paragraphs = listOf(
                SquadParagraph(
                    context = padded.take(1400),
                    qas = listOf(SquadQa(title.lowercase(), "What is recorded?", listOf(SquadAnswer(answer)))),
                ),
            ),
        )
    }

    private fun squad(vararg articles: SquadArticle) = SquadDump(version = "1.1", data = articles.toList())
}

class ScoreParserTest {
    @Test
    fun parsesBareJson() {
        val parsed = ScoreParser.parse("""{"faithfulness": 4, "correctness": 2, "rationale": "The year is wrong."}""")
        assertEquals(4, parsed.faithfulness)
        assertEquals(2, parsed.correctness)
        assertTrue(parsed.parsed)
    }

    @Test
    fun parsesFencedJsonAndStringScores() {
        val parsed = ScoreParser.parse(
            """
            Sure.
            ```json
            {"faithfulness": "5", "correctness": "1", "rationale": "Quoted."}
            ```
            """.trimIndent(),
        )
        assertEquals(5, parsed.faithfulness)
        assertEquals(1, parsed.correctness)
    }

    @Test
    fun rejectsOutOfRangeAndMissingFields() {
        assertFalse(ScoreParser.parse("""{"faithfulness": 6, "correctness": 2}""").parsed)
        assertFalse(ScoreParser.parse("""{"correctness": 2}""").parsed)
        assertFalse(ScoreParser.parse("no json here").parsed)
    }

    @Test
    fun ignoresBracesInsideTheRationaleString() {
        val parsed = ScoreParser.parse("""{"faithfulness": 3, "correctness": 3, "rationale": "He said {not this} was it."}""")
        assertEquals(3, parsed.faithfulness)
    }
}

class AgreementTest {
    @Test
    fun identicalRatingsHaveKappaOne() {
        val scores = intArrayOf(1, 2, 3, 4, 5)
        assertEquals(1.0, Agreement.quadraticWeightedKappa(scores, scores), 1e-9)
    }

    @Test
    fun handComputedTwoCategoryCase() {
        val kappa = Agreement.quadraticWeightedKappa(intArrayOf(1, 1, 2, 2), intArrayOf(1, 2, 2, 2), min = 1, max = 2)
        assertEquals(0.5, kappa, 1e-9)
    }
}

class OpenRouterContractTest {
    @Test
    fun requestOmitsTemperatureWhenUnsupportedAndDisablesReasoningWhenSupported() {
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
    fun parsesUsageCostAndListContent() {
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
    fun reconcileTakesTheHigherPriceAndLiveParameterSupport() {
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

class CostAndBudgetTest {
    @Test
    fun tokenEstimateIsCeilOfCharsOverFour() {
        assertEquals(1, CostModel.estimateTokens("abcd"))
        assertEquals(2, CostModel.estimateTokens("abcde"))
    }

    @Test
    fun guardStopsWhenTheNextChargeWouldCrossTheCap() {
        val guard = BudgetGuard(0.05)
        assertTrue(guard.canAfford(0.05))
        guard.commit(0.04)
        assertFalse(guard.canAfford(0.02))
        assertTrue(guard.canAfford(0.01))
    }

    @Test
    fun runnerRefusesBeforeAnyCallWhenTheMarginExceedsTheCap(@TempDir dir: Path) {
        val config = sampleConfig(capEur = 0.0000001)
        val calls = mutableListOf<String>()
        val judge = countingJudge(calls)
        val failed = runCatching {
            ExperimentRunner { Instant.parse("2026-01-01T00:00:00Z") }.run(
                config, dataset(), dir, judge, "openrouter", fresh = true, gitCommit = null,
            )
        }
        assertTrue(failed.exceptionOrNull() is BudgetRefused)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun runnerStopsOnceActualSpendCannotFitAnotherCeiling(@TempDir dir: Path) {
        val loose = sampleConfig(capEur = 1_000.0, completionUsdPerMillion = 1_000.0, maxTokens = 5_000, expectedTokens = 10)
        val ceiling = Planner.plan(loose, dataset().items).first().call.ceilingUsd()
        val config = loose.copy(capEur = ceiling * 1.2)
        val calls = mutableListOf<String>()
        val judge = JudgeClient { request ->
            calls += request.itemId
            JudgeRaw("""{"faithfulness":5,"correctness":5,"rationale":"ok"}""", 10, 10, ceiling, "{}")
        }
        val result = ExperimentRunner { Instant.parse("2026-01-01T00:00:00Z") }.run(
            config, dataset(), dir, judge, "openrouter", fresh = true, gitCommit = null,
        )
        assertEquals(1, calls.size)
        assertTrue(result.manifest.stoppedEarly)
        assertTrue(result.manifest.spentUsd <= config.capUsd() + ceiling)
    }
}

class PipelineTest {
    @Test
    fun dryRunIsCachedAndAnalyzedWithoutAKey(@TempDir dir: Path) {
        val config = sampleConfig(capEur = 100.0)
        val calls = mutableListOf<Int>()
        val judge = JudgeClient { request ->
            calls += 1
            SimulatedJudge.complete(request)
        }
        val clock = { Instant.parse("2026-01-01T00:00:00Z") }
        val runner = ExperimentRunner(clock)
        val first = runner.run(config, dataset(), dir, judge, "dry-run", fresh = true, gitCommit = "abc")
        val called = calls.size
        val second = runner.run(config, dataset(), dir, judge, "dry-run", fresh = false, gitCommit = "abc")
        assertEquals(first.manifest.plannedTrials, called)
        assertEquals(called, second.manifest.cacheHits)
        assertEquals(called, calls.size)
        assertEquals(0.0, second.manifest.spentUsd)
        val cache = ResponseCache(dir.resolve("responses.jsonl")).also { it.load() }
        val analysis = Analyzer.analyze(dataset().items, second.manifest, cache.rows(), "2026-01-01T00:00:00Z")
        assertEquals(called, analysis.metrics.meta.nParsed)
        assertEquals(1.0, analysis.metrics.interJudge.faithfulness.quadraticKappa[0][0])
        val docs = dir.resolve("docs")
        Report.write(dir, docs, analysis)
        assertTrue(docs.resolve("metrics.json").readText().contains("\"schemaVersion\": 1"))
        assertTrue(Report.toCsv(analysis.rows).contains("\"Comma, title\""))
    }

    @Test
    fun cliDryRunDoesNotNeedAKey(@TempDir dir: Path) {
        val configPath = dir.resolve("experiment.json")
        val dataPath = dir.resolve("items.json")
        val config = sampleConfig(capEur = 100.0).copy(dataset = dataPath.toString())
        Files.writeString(configPath, Jsons.pretty.encodeToString(ExperimentConfig.serializer(), config))
        Files.writeString(dataPath, Jsons.pretty.encodeToString(DatasetFile.serializer(), dataset()))
        val code = Cli.run(
            arrayOf("run", "--dry-run", "--fresh", "--config", configPath.toString(), "--out", dir.resolve("out").toString()),
            env = emptyMap(),
            clock = { Instant.parse("2026-01-01T00:00:00Z") },
        )
        assertEquals(0, code)
        assertTrue(dir.resolve("out/responses.jsonl").exists())
    }

    @Test
    fun paidRunRefusesWithoutAKey(@TempDir dir: Path) {
        val configPath = dir.resolve("experiment.json")
        val dataPath = dir.resolve("items.json")
        Files.writeString(configPath, Jsons.pretty.encodeToString(ExperimentConfig.serializer(), sampleConfig(100.0).copy(dataset = dataPath.toString())))
        Files.writeString(dataPath, Jsons.pretty.encodeToString(DatasetFile.serializer(), dataset()))
        val code = Cli.run(
            arrayOf("run", "--allow-stale-prices", "--config", configPath.toString(), "--out", dir.resolve("paid").toString()),
            env = emptyMap(),
        )
        assertEquals(1, code)
        assertFalse(dir.resolve("paid/responses.jsonl").exists())
    }
}

class CheckedInExperimentTest {
    @Test
    fun datasetInvariantsAndLicense() {
        val file = Jsons.pretty.decodeFromString(DatasetFile.serializer(), Path.of("data/items.json").readText())
        assertEquals("CC BY-SA 4.0", file.dataset.license)
        assertEquals(50, file.items.size)
        assertEquals(32, file.items.count { it.subtleKind != "entity_swap" })
        assertEquals(18, file.items.count { it.subtleKind == "entity_swap" })
        assertEquals(file.items.size, file.items.map { it.id }.distinct().size)
        file.items.forEach { item ->
            val correct = item.variant(VariantKind.CORRECT)
            val subtle = item.variant(VariantKind.SUBTLE_ERROR)
            val unsupported = item.variant(VariantKind.UNSUPPORTED)
            assertEquals(item.goldAnswer, correct.answer)
            assertTrue(correct.context.contains(correct.answer))
            assertEquals(correct.context, subtle.context)
            assertNotEquals(correct.answer, subtle.answer)
            assertEquals(correct.answer, unsupported.answer)
            assertNotEquals(item.title, item.unsupportedDonorTitle)
            assertFalse(unsupported.context.contains(item.goldAnswer, ignoreCase = true))
            VariantRules.salientNumbers(item.goldAnswer).forEach { number ->
                assertFalse(VariantRules.containsBounded(unsupported.context, number), item.id)
            }
            if (item.subtleKind == "entity_swap") {
                assertTrue(subtle.context.contains(subtle.answer))
            } else {
                assertFalse(subtle.context.contains(subtle.answer, ignoreCase = true))
            }
        }
    }

    @Test
    fun publishedDryRunMatchesTheCachedResponses() {
        val manifest = Jsons.pretty.decodeFromString(
            RunManifest.serializer(),
            Path.of("results/dry-run/manifest.json").readText(),
        )
        val items = Jsons.pretty.decodeFromString(DatasetFile.serializer(), Path.of("data/items.json").readText()).items
        val cache = ResponseCache(Path.of("results/dry-run/responses.jsonl")).also { it.load() }
        val fresh = Analyzer.analyze(items, manifest, cache.rows(), "2026-01-01T00:00:00Z")
        val published = Jsons.pretty.decodeFromString(Metrics.serializer(), Path.of("docs/data/metrics.json").readText())
        assertEquals(50 * 3 * 5 * 5, published.meta.nTrialsCompleted)
        assertTrue(published.meta.simulated)
        assertEquals(fresh.metrics.headlines, published.headlines)
        assertEquals(
            fresh.metrics.plantedErrors.judges.map { it.subtleMissRate },
            published.plantedErrors.judges.map { it.subtleMissRate },
        )
    }

    @Test
    fun fullRunStaysUnderTheTenEuroCap() {
        val config = Jsons.pretty.decodeFromString(ExperimentConfig.serializer(), Path.of("config/experiment.json").readText())
        config.validate()
        val items = Jsons.pretty.decodeFromString(DatasetFile.serializer(), Path.of("data/items.json").readText()).items
        val estimate = CostModel.estimate(config, Planner.plan(config, items).map { it.call })
        assertEquals(
            listOf(
                "x-ai/grok-4.3",
                "openai/gpt-5.4-mini",
                "anthropic/claude-haiku-4.5",
                "google/gemini-2.5-flash",
                "meta-llama/llama-3.3-70b-instruct",
            ),
            config.judges.map { it.id },
        )
        val mini = config.judges.first { it.id == "openai/gpt-5.4-mini" }
        assertFalse(mini.supportsTemperature)
        assertTrue(mini.supportsReasoning)
        val llama = config.judges.first { it.id == "meta-llama/llama-3.3-70b-instruct" }
        assertEquals("deepinfra", llama.providerOnly)
        assertTrue(llama.supportsTemperature)
        assertEquals(50 * 3 * 5 * 5, estimate.trials)
        assertTrue(estimate.allowed, CostModel.format(estimate))
        assertTrue(estimate.withMarginEur < 10.0)
        assertTrue(estimate.totalEur > 0.05)
        println(CostModel.format(estimate))
    }
}

private fun sampleConfig(
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

private fun dataset(): DatasetFile {
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

private fun countingJudge(calls: MutableList<String>) = JudgeClient { request ->
    calls += request.itemId
    JudgeRaw("""{"faithfulness":5,"correctness":5,"rationale":"ok"}""", 10, 10, 0.0, "{}")
}

private fun kotlinx.serialization.json.JsonElement.jsonObject() = this as kotlinx.serialization.json.JsonObject
private fun kotlinx.serialization.json.JsonElement.jsonArray() = this as kotlinx.serialization.json.JsonArray
private fun kotlinx.serialization.json.JsonElement.jsonPrimitive() = this as kotlinx.serialization.json.JsonPrimitive
