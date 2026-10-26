package dev.helfferich.judgelies

import java.nio.file.Files
import java.nio.file.Path

object Report {
    fun write(runDir: Path, docsData: Path?, analysis: Analysis) {
        Files.createDirectories(runDir)
        val metrics = Jsons.pretty.encodeToString(Metrics.serializer(), analysis.metrics)
        val examples = Jsons.pretty.encodeToString(ExampleFile.serializer(), analysis.examples)
        val csv = toCsv(analysis.rows)
        val missesJson = Jsons.pretty.encodeToString(MissFile.serializer(), analysis.misses)
        val missesCsv = missesToCsv(analysis.misses.rows)
        val outputs = mapOf(
            "metrics.json" to metrics,
            "examples.json" to examples,
            "scores.csv" to csv,
            "misses.json" to missesJson,
            "misses.csv" to missesCsv,
        )
        outputs.forEach { (name, body) -> Files.writeString(runDir.resolve(name), body) }
        if (docsData != null) {
            Files.createDirectories(docsData)
            outputs.forEach { (name, body) -> Files.writeString(docsData.resolve(name), body) }
        }
    }

    fun missesToCsv(rows: List<MissRow>): String {
        val header = listOf(
            "item_id", "title", "question", "gold_answer", "variant", "subtle_kind", "shown_answer", "donor_title",
            "disputed", "judge", "label", "prompt", "repeat", "faithfulness", "correctness",
            "missed_at_4", "missed_at_5", "rationale",
        )
        return buildString {
            appendLine(header.joinToString(",") { csv(it) })
            rows.forEach { row ->
                appendLine(
                    listOf(
                        row.itemId, row.title, row.question, row.goldAnswer, row.variant, row.subtleKind,
                        row.shownAnswer, row.donorTitle ?: "", row.disputed.toString(), row.judge, row.label,
                        row.prompt, row.repeat.toString(), row.faithfulness.toString(), row.correctness.toString(),
                        row.missedAt4.toString(), row.missedAt5.toString(), row.rationale ?: "",
                    ).joinToString(",") { csv(it) },
                )
            }
        }
    }

    fun toCsv(rows: List<ScoreRow>): String {
        val header = listOf(
            "item_id", "title", "variant", "subtle_kind", "judge", "label", "prompt", "repeat",
            "faithfulness", "correctness", "parsed", "prompt_tokens", "completion_tokens",
            "estimated_cost_usd", "billed_cost_usd", "mode", "parse_failure",
        )
        return buildString {
            appendLine(header.joinToString(",") { csv(it) })
            rows.forEach { row ->
                appendLine(
                    listOf(
                        row.itemId,
                        row.title,
                        row.variant,
                        row.subtleKind,
                        row.judge,
                        row.label,
                        row.prompt,
                        row.repeat.toString(),
                        row.faithfulness?.toString() ?: "",
                        row.correctness?.toString() ?: "",
                        row.parsed.toString(),
                        row.promptTokens?.toString() ?: "",
                        row.completionTokens?.toString() ?: "",
                        row.estimatedCostUsd.toString(),
                        row.billedCostUsd?.toString() ?: "",
                        row.mode,
                        row.parseFailure ?: "",
                    ).joinToString(",") { csv(it) },
                )
            }
        }
    }

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }
}
