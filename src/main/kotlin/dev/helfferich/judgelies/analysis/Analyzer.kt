package dev.helfferich.judgelies.analysis

import dev.helfferich.judgelies.config.JudgeModel
import dev.helfferich.judgelies.dataset.DisputedItem
import dev.helfferich.judgelies.dataset.EvalItem
import dev.helfferich.judgelies.dataset.VariantKind
import dev.helfferich.judgelies.dataset.wire
import dev.helfferich.judgelies.run.CachedTrial
import dev.helfferich.judgelies.run.RunManifest
import dev.helfferich.judgelies.scoring.Agreement
import dev.helfferich.judgelies.scoring.ParseFailure
import dev.helfferich.judgelies.scoring.ScoreParser

/**
 * Turns a cached run into the numbers the pages draw.
 * Agreement, consistency, and planted-error rates use the strict rubric's first parsed repeat.
 * Replies that do not parse are left out of those rates and counted beside them.
 */
object Analyzer {
    fun scoreRows(items: List<EvalItem>, manifest: RunManifest, cached: List<CachedTrial>): List<ScoreRow> {
        val byId = items.associateBy { it.id }
        val labels = manifest.judges.associate { it.id to it.label }
        return cached.map { row ->
            val item = byId[row.itemId]
            val parsed = ScoreParser.parse(row.content)
            ScoreRow(
                itemId = row.itemId,
                title = item?.title ?: "",
                variant = row.variant,
                subtleKind = item?.subtleKind ?: "",
                judge = row.model,
                label = labels[row.model] ?: row.model,
                prompt = row.promptId,
                repeat = row.repeat,
                faithfulness = parsed.faithfulness,
                correctness = parsed.correctness,
                parsed = parsed.parsed,
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                estimatedCostUsd = row.estimatedCostUsd,
                billedCostUsd = row.billedCostUsd,
                mode = row.mode,
                rationale = parsed.rationale,
                parseFailure = parsed.failure?.wire,
                partialFaithfulness = parsed.partialFaithfulness,
            )
        }
    }

    fun analyze(
        items: List<EvalItem>,
        manifest: RunManifest,
        cached: List<CachedTrial>,
        analyzedAt: String,
        disputed: List<DisputedItem> = emptyList(),
    ): Analysis {
        // Excluded planted variants (data/disputed.json) are not actually wrong, so they are dropped before any metric.
        val excluded = disputed.map { it.itemId }.toSet()
        val rows = scoreRows(items, manifest, cached).filterNot { it.itemId in excluded && it.variant == "subtle_error" }
        val judges = manifest.judges
        val primary = manifest.primaryPrompt
        val consistency = selfConsistency(rows, judges, primary)
        val sensitivity = promptSensitivity(rows, judges, primary, manifest.promptOrder)
        val inter = interJudge(rows, judges, primary)
        val planted = planted(rows, judges, primary, manifest.missThreshold, manifest.falseRejectAtOrBelow, disputed)
        val headlines = Headlines(
            meanFaithfulnessSelfDisagreement = meanOrNull(consistency.judges.map { it.faithfulness.pctNotIdentical }),
            meanPairwiseFaithfulnessKappa = meanOffDiagonal(inter.faithfulness.quadraticKappa),
            meanSubtleMissRate = meanOrNull(planted.judges.mapNotNull { it.subtleMissRate }),
            meanSubtleMissRateAt5 = meanOrNull(planted.judges.mapNotNull { it.subtleMissRateAt5 }),
            meanUnsupportedMissRate = meanOrNull(planted.judges.mapNotNull { it.unsupportedMissRate }),
            meanCorrectFalseRejectRate = meanOrNull(planted.judges.mapNotNull { it.correctFalseRejectRate }),
        )
        val metrics = Metrics(
            meta = MetricsMeta(
                mode = manifest.mode,
                simulated = manifest.simulated,
                startedAt = manifest.startedAt,
                finishedAt = manifest.finishedAt,
                analyzedAt = analyzedAt,
                dataset = manifest.datasetName,
                datasetLicense = manifest.datasetLicense,
                nItems = items.size,
                nTrialsPlanned = manifest.plannedTrials,
                nTrialsCompleted = rows.size,
                nParsed = rows.count { it.parsed },
                temperature = manifest.temperature,
                seedSent = manifest.seedSent,
                primaryPrompt = primary,
                promptOrder = manifest.promptOrder,
                repeats = manifest.repeats,
                missThreshold = manifest.missThreshold,
                falseRejectAtOrBelow = manifest.falseRejectAtOrBelow,
                judges = judges,
                capEur = manifest.capEur,
                safetyMargin = manifest.safetyMargin,
                eurPerUsd = manifest.eurPerUsd,
                fxAsOf = manifest.fxAsOf,
                pricingAsOf = manifest.pricingAsOf,
                estimatedCostUsd = manifest.estimate.totalUsd,
                estimatedCostEur = manifest.estimate.totalEur,
                estimatedCostEurWithMargin = manifest.estimate.withMarginEur,
                billedCostUsd = manifest.spentUsd,
                stoppedEarly = manifest.stoppedEarly,
                promptHash = manifest.promptHash,
            ),
            headlines = headlines,
            selfConsistency = consistency,
            promptSensitivity = sensitivity,
            interJudge = inter,
            plantedErrors = planted,
            parseFailures = parseFailures(rows, judges),
        )
        return Analysis(
            metrics,
            examples(items, rows, judges, manifest.simulated, primary),
            rows,
            misses(items, rows, manifest.missThreshold, manifest.simulated, disputed),
        )
    }

    fun selfConsistency(rows: List<ScoreRow>, judges: List<JudgeModel>, prompt: String): SelfConsistency {
        val relevant = rows.filter { it.prompt == prompt && it.parsed }
        val perJudge = judges.map { judge ->
            val groups = relevant.filter { it.judge == judge.id }.groupBy { it.itemId to it.variant }
                .values.filter { it.size >= 2 }
            JudgeConsistency(
                judge = judge.id,
                label = judge.label,
                nGroups = groups.size,
                faithfulness = spread(groups.map { g -> g.mapNotNull { it.faithfulness } }),
                correctness = spread(groups.map { g -> g.mapNotNull { it.correctness } }),
            )
        }
        return SelfConsistency(prompt, perJudge)
    }

    fun promptSensitivity(
        rows: List<ScoreRow>,
        judges: List<JudgeModel>,
        baseline: String,
        promptOrder: List<String>,
    ): PromptSensitivity {
        val comparisons = promptOrder.filter { it != baseline }.map { prompt ->
            PromptComparison(
                prompt = prompt,
                judges = judges.map { judge -> shift(rows, judge, baseline, prompt) },
            )
        }
        return PromptSensitivity(baseline, comparisons)
    }

    fun interJudge(rows: List<ScoreRow>, judges: List<JudgeModel>, prompt: String): InterJudge {
        val slice = rows.filter { it.prompt == prompt && it.repeat == 0 && it.parsed }
        val keys = slice.map { it.itemId to it.variant }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        val usable = keys.filter { key ->
            judges.all { judge -> slice.any { it.judge == judge.id && it.itemId == key.first && it.variant == key.second } }
        }
        fun vector(judge: String, score: (ScoreRow) -> Int): IntArray = usable.map { key ->
            val row = slice.first { it.judge == judge && it.itemId == key.first && it.variant == key.second }
            score(row)
        }.toIntArray()
        return InterJudge(
            prompt = prompt,
            repeat = 0,
            nItems = usable.size,
            judges = judges.map { it.id },
            labels = judges.map { it.label },
            faithfulness = matrices(judges, usable.size) { judge -> vector(judge) { checkNotNull(it.faithfulness) } },
            correctness = matrices(judges, usable.size) { judge -> vector(judge) { checkNotNull(it.correctness) } },
        )
    }

    fun planted(
        rows: List<ScoreRow>,
        judges: List<JudgeModel>,
        prompt: String,
        missAt: Int,
        rejectAt: Int,
        disputed: List<DisputedItem> = emptyList(),
    ): PlantedErrors {
        val slice = rows.filter { it.prompt == prompt && it.repeat == 0 }
        val perJudge = judges.map { judge ->
            val mine = slice.filter { it.judge == judge.id }
            fun ofKind(kind: String) = mine.filter { it.variant == kind && it.parsed }
            val correct = ofKind("correct")
            val subtle = ofKind("subtle_error")
            val unsupported = ofKind("unsupported")
            val byKind = subtle.groupBy { it.subtleKind }.mapValues { (_, group) ->
                subtleMissRate(group, missAt)!!
            }
            val subtleFailed = mine.filter { it.variant == "subtle_error" && !it.parsed }
            PlantedJudge(
                judge = judge.id,
                label = judge.label,
                correctN = correct.size,
                subtleN = subtle.size,
                unsupportedN = unsupported.size,
                unparsed = mine.count { !it.parsed },
                subtleMissRate = subtleMissRate(subtle, missAt),
                subtleMissRateAt5 = subtleMissRate(subtle, 5),
                subtleUnparsed = subtleFailed.size,
                subtleMissingCorrectness = subtleFailed.count { it.parseFailure == ParseFailure.MISSING_CORRECTNESS.wire },
                unsupportedMissRate = if (unsupported.isEmpty()) null else rate(
                    unsupported.count { (it.faithfulness ?: 0) >= missAt },
                    unsupported.size,
                ),
                correctFalseRejectRate = if (correct.isEmpty()) null else rate(
                    correct.count { (it.faithfulness ?: 5) <= rejectAt || (it.correctness ?: 5) <= rejectAt },
                    correct.size,
                ),
                subtleMissRateByKind = byKind,
                meanFaithfulness = meanByVariant(mine),
                meanCorrectness = meanByVariant(mine, faithfulness = false),
            )
        }
        return PlantedErrors(
            prompt = prompt,
            repeat = 0,
            missThreshold = missAt,
            falseRejectAtOrBelow = rejectAt,
            subtleMiss = "correctness >= $missAt on a subtly wrong answer",
            unsupportedMiss = "faithfulness >= $missAt on a correct answer paired with an unrelated passage",
            correctFalseReject = "faithfulness or correctness <= $rejectAt on the gold answer in its own passage",
            judges = perJudge,
            subtleMissAt5 = "correctness == 5 on a subtly wrong answer",
            disputed = disputed,
        )
    }

    /**
     * Share of parsed subtle-error rows whose correctness is at or above [cutoff].
     * The headline uses the config's missThreshold (4). A cutoff of 5 counts only a perfect score as a miss.
     * Unparsed rows are never counted, in the numerator or the denominator. Null when nothing is left.
     */
    fun subtleMissRate(subtleRows: List<ScoreRow>, cutoff: Int): Double? {
        val usable = subtleRows.filter { it.parsed && it.variant == "subtle_error" && it.correctness != null }
        if (usable.isEmpty()) return null
        return rate(usable.count { it.correctness!! >= cutoff }, usable.size)
    }

    fun parseFailures(rows: List<ScoreRow>, judges: List<JudgeModel>): List<ParseFailureSummary> = judges.map { judge ->
        val mine = rows.filter { it.judge == judge.id }
        val failed = mine.filter { !it.parsed }
        ParseFailureSummary(
            judge = judge.id,
            label = judge.label,
            calls = mine.size,
            unparsed = failed.size,
            byReason = failed.groupingBy { it.parseFailure ?: "unknown" }.eachCount().toSortedMap(),
            byPrompt = failed.groupingBy { it.prompt }.eachCount().toSortedMap(),
            byVariant = failed.groupingBy { it.variant }.eachCount().toSortedMap(),
            missingCorrectnessFaithfulness = failed
                .filter { it.parseFailure == ParseFailure.MISSING_CORRECTNESS.wire }
                .groupingBy { it.partialFaithfulness?.toString() ?: "?" }.eachCount().toSortedMap(),
        )
    }

    /** Every planted case a judge let through, across all prompts and repeats. */
    fun misses(
        items: List<EvalItem>,
        rows: List<ScoreRow>,
        missAt: Int,
        simulated: Boolean,
        disputed: List<DisputedItem> = emptyList(),
    ): MissFile {
        val byId = items.associateBy { it.id }
        val missed = rows.filter { row ->
            row.parsed && when (row.variant) {
                "subtle_error" -> row.correctness!! >= missAt
                "unsupported" -> row.faithfulness!! >= missAt
                else -> false
            }
        }.mapNotNull { row ->
            val item = byId[row.itemId] ?: return@mapNotNull null
            val subtle = row.variant == "subtle_error"
            val score = if (subtle) row.correctness!! else row.faithfulness!!
            MissRow(
                itemId = row.itemId,
                title = item.title,
                question = item.question,
                goldAnswer = item.goldAnswer,
                variant = row.variant,
                subtleKind = if (subtle) item.subtleKind else "",
                shownAnswer = if (subtle) item.variant(VariantKind.SUBTLE_ERROR).answer else item.variant(VariantKind.UNSUPPORTED).answer,
                donorTitle = if (subtle) null else item.unsupportedDonorTitle,
                judge = row.judge,
                label = row.label,
                prompt = row.prompt,
                repeat = row.repeat,
                faithfulness = row.faithfulness!!,
                correctness = row.correctness!!,
                missedAt4 = score >= missAt,
                missedAt5 = score >= 5,
                rationale = row.rationale,
            )
        }.sortedWith(compareBy({ it.variant }, { it.title }, { it.itemId }, { it.label }, { it.prompt }, { it.repeat }))
        return MissFile(
            simulated = simulated,
            missThreshold = missAt,
            definition = "subtle_error: correctness >= $missAt. unsupported: faithfulness >= $missAt. All prompts and repeats.",
            disputed = disputed,
            rows = missed,
        )
    }

    private fun examples(
        items: List<EvalItem>,
        rows: List<ScoreRow>,
        judges: List<JudgeModel>,
        simulated: Boolean,
        prompt: String,
    ): ExampleFile {
        val numeric = items.firstOrNull { it.subtleKind != "entity_swap" }
        val entity = items.firstOrNull { it.subtleKind == "entity_swap" && it.title != numeric?.title }
            ?: items.firstOrNull { it.subtleKind == "entity_swap" }
        val slice = rows.filter { it.prompt == prompt && it.repeat == 0 && it.parsed && it.variant == "subtle_error" }
        val ranked = items.map { item ->
            val scores = judges.mapNotNull { judge ->
                slice.firstOrNull { it.itemId == item.id && it.judge == judge.id }?.correctness
            }
            val range = if (scores.isEmpty()) 0 else scores.max() - scores.min()
            item to range
        }.sortedByDescending { it.second }
        val picked = mutableListOf<EvalItem>()
        listOfNotNull(numeric, entity).forEach { if (it !in picked) picked += it }
        ranked.forEach { (item, _) ->
            if (picked.size < 3 && item !in picked) picked += item
        }
        val cases = picked.map { item ->
            val source = item.variant(VariantKind.CORRECT)
            val subtle = item.variant(VariantKind.SUBTLE_ERROR)
            val donor = item.variant(VariantKind.UNSUPPORTED)
            val scores = judges.flatMap { judge ->
                VariantKind.entries.mapNotNull { kind ->
                    val row = rows.firstOrNull {
                        it.itemId == item.id && it.judge == judge.id && it.prompt == prompt &&
                            it.repeat == 0 && it.variant == kind.wire()
                    } ?: return@mapNotNull null
                    ExampleScore(judge.id, judge.label, kind.wire(), row.faithfulness, row.correctness)
                }
            }
            ExampleCase(
                itemId = item.id,
                title = item.title,
                question = item.question,
                goldAnswer = item.goldAnswer,
                subtleAnswer = subtle.answer,
                subtleKind = item.subtleKind,
                sourceContext = source.context,
                donorTitle = item.unsupportedDonorTitle,
                donorContext = donor.context,
                scores = scores,
            )
        }
        return ExampleFile(simulated = simulated, cases = cases)
    }

    private fun spread(groups: List<List<Int>>): SpreadStats {
        if (groups.isEmpty()) return SpreadStats(0.0, 0.0, List(5) { 0 })
        val ranges = groups.map { values ->
            if (values.isEmpty()) 0 else values.max() - values.min()
        }
        val histogram = MutableList(5) { 0 }
        ranges.forEach { histogram[it.coerceIn(0, 4)]++ }
        return SpreadStats(
            pctNotIdentical = ranges.count { it > 0 } / ranges.size.toDouble(),
            meanRange = ranges.average(),
            rangeHistogram = histogram,
        )
    }

    private fun shift(rows: List<ScoreRow>, judge: JudgeModel, baseline: String, prompt: String): PromptShift {
        val base = rows.filter { it.judge == judge.id && it.prompt == baseline && it.repeat == 0 && it.parsed }
            .associateBy { it.itemId to it.variant }
        val other = rows.filter { it.judge == judge.id && it.prompt == prompt && it.repeat == 0 && it.parsed }
        val paired = other.mapNotNull { row -> base[row.itemId to row.variant]?.let { row to it } }
        fun deltas(select: (ScoreRow) -> Int?): List<Int> = paired.mapNotNull { (next, prev) ->
            val a = select(next)
            val b = select(prev)
            if (a == null || b == null) null else a - b
        }
        val faith = deltas { it.faithfulness }
        val corr = deltas { it.correctness }
        return PromptShift(
            judge = judge.id,
            label = judge.label,
            n = paired.size,
            meanDeltaFaithfulness = faith.averageOrZero(),
            meanDeltaCorrectness = corr.averageOrZero(),
            pctFaithfulnessChanged = faith.changed(),
            pctFaithfulnessShiftAtLeast2 = faith.big(),
            pctCorrectnessChanged = corr.changed(),
            pctCorrectnessShiftAtLeast2 = corr.big(),
        )
    }

    private fun matrices(
        judges: List<JudgeModel>,
        n: Int,
        vector: (String) -> IntArray,
    ): AgreementMatrices {
        val exact = MutableList(judges.size) { MutableList(judges.size) { 0.0 } }
        val mad = MutableList(judges.size) { MutableList(judges.size) { 0.0 } }
        val kappa = MutableList(judges.size) { MutableList(judges.size) { 0.0 } }
        for (i in judges.indices) {
            for (j in judges.indices) {
                if (n == 0) continue
                val left = vector(judges[i].id)
                val right = vector(judges[j].id)
                var same = 0
                var abs = 0
                for (k in left.indices) {
                    if (left[k] == right[k]) same++
                    abs += kotlin.math.abs(left[k] - right[k])
                }
                exact[i][j] = same / n.toDouble()
                mad[i][j] = abs / n.toDouble()
                kappa[i][j] = Agreement.quadraticWeightedKappa(left, right)
            }
        }
        return AgreementMatrices(exact, mad, kappa)
    }

    private fun meanByVariant(rows: List<ScoreRow>, faithfulness: Boolean = true): Map<String, Double> =
        rows.filter { it.parsed }.groupBy { it.variant }.mapValues { (_, group) ->
            val values = group.mapNotNull { if (faithfulness) it.faithfulness else it.correctness }
            values.averageOrZero()
        }

    private fun rate(hits: Int, n: Int): Double = hits / n.toDouble()

    private fun meanOrNull(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    private fun meanOffDiagonal(matrix: List<List<Double>>): Double? {
        val values = mutableListOf<Double>()
        for (i in matrix.indices) {
            for (j in i + 1 until matrix[i].size) values += matrix[i][j]
        }
        return meanOrNull(values)
    }

    private fun List<Int>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun List<Int>.changed(): Double = if (isEmpty()) 0.0 else count { it != 0 } / size.toDouble()

    private fun List<Int>.big(): Double = if (isEmpty()) 0.0 else count { kotlin.math.abs(it) >= 2 } / size.toDouble()
}
