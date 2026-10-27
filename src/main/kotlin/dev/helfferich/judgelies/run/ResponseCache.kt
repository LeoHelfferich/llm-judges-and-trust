package dev.helfferich.judgelies.run

import java.nio.file.Files
import java.nio.file.Path

import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlinx.serialization.Serializable

import dev.helfferich.judgelies.support.Jsons

@Serializable
data class CachedTrial(
    val key: String,
    val model: String,
    val promptId: String,
    val promptHash: String,
    val itemId: String,
    val variant: String,
    val repeat: Int,
    val temperature: Double?,
    val content: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val estimatedCostUsd: Double,
    val billedCostUsd: Double?,
    val costSource: String,
    val mode: String,
    val createdAt: String,
)

/** Append-only JSONL of raw replies. A key that is already stored is not written again. */
class ResponseCache(private val file: Path) {
    private val byKey = linkedMapOf<String, CachedTrial>()

    fun load() {
        if (!file.exists()) return
        file.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val row = Jsons.compact.decodeFromString(CachedTrial.serializer(), line)
            byKey[row.key] = row
        }
    }

    fun get(key: String): CachedTrial? = byKey[key]

    fun put(row: CachedTrial) {
        if (row.key in byKey) return
        byKey[row.key] = row
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            Jsons.compact.encodeToString(CachedTrial.serializer(), row) + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    fun rows(): List<CachedTrial> = byKey.values.toList()
}
