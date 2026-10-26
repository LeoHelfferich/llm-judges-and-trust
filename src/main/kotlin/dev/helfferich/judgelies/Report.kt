package dev.helfferich.judgelies

import java.nio.file.Files
import java.nio.file.Path

object Report {
    fun write(runDir: Path, docsData: Path?, analysis: Analysis) {
        Files.createDirectories(runDir)
        val metrics = Jsons.pretty.encodeToString(Metrics.serializer(), analysis.metrics)
        val examples = Jsons.pretty.encodeToString(ExampleFile.serializer(), analysis.examples)
        val csv = toCsv(analysis.rows)
        Files.writeString(runDir.resolve("metrics.json"), metrics)
        Files.writeString(runDir.resolve("examples.json"), examples)
        Files.writeString(runDir.resolve("scores.csv"), csv)
        if (docsData != null) {
            Files.createDirectories(docsData)
            Files.writeString(docsData.resolve("metrics.json"), metrics)
            Files.writeString(docsData.resolve("examples.json"), examples)
            Files.writeString(docsData.resolve("scores.csv"), csv)
        }
    }

    fun toCsv(rows: List<ScoreRow>): String {
        val header = listOf(
            "item_id", "title", "variant", "subtle_kind", "judge", "label", "prompt", "repeat",
            "faithfulness", "correctness", "parsed", "prompt_tokens", "completion_tokens",
            "estimated_cost_usd", "billed_cost_usd", "mode",
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
