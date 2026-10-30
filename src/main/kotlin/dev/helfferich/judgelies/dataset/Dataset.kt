package dev.helfferich.judgelies.dataset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VariantKind {
    @SerialName("correct")
    CORRECT,

    @SerialName("subtle_error")
    SUBTLE_ERROR,

    @SerialName("unsupported")
    UNSUPPORTED,
}

@Serializable
data class AnswerVariant(
    val kind: VariantKind,
    val answer: String,
    val context: String,
)

/** One question, its gold answer, and the three passages the judge is shown. */
@Serializable
data class EvalItem(
    val id: String,
    val title: String,
    val question: String,
    val goldAnswer: String,
    val subtleKind: String,
    val unsupportedDonorTitle: String,
    val variants: List<AnswerVariant>,
) {
    fun variant(kind: VariantKind): AnswerVariant =
        variants.first { it.kind == kind }
}

fun VariantKind.wire(): String = when (this) {
    VariantKind.CORRECT -> "correct"
    VariantKind.SUBTLE_ERROR -> "subtle_error"
    VariantKind.UNSUPPORTED -> "unsupported"
}

fun variantKind(wire: String): VariantKind = when (wire) {
    "correct", "CORRECT" -> VariantKind.CORRECT
    "subtle_error", "SUBTLE_ERROR" -> VariantKind.SUBTLE_ERROR
    "unsupported", "UNSUPPORTED" -> VariantKind.UNSUPPORTED
    else -> error("Unknown variant '$wire'")
}

@Serializable
data class DatasetInfo(
    val name: String,
    val license: String,
    val sourceUrl: String,
    val paper: String,
    val citation: String,
    val wikipediaLicense: String,
    val selection: String,
    val squadVersion: String,
    val schemaVersion: Int = 1,
)

@Serializable
data class DatasetFile(
    val dataset: DatasetInfo,
    val items: List<EvalItem>,
)

@Serializable
data class SquadDump(
    val version: String? = null,
    val data: List<SquadArticle>,
)

@Serializable
data class SquadArticle(
    val title: String,
    val paragraphs: List<SquadParagraph>,
)

@Serializable
data class SquadParagraph(
    val context: String,
    val qas: List<SquadQa> = emptyList(),
)

@Serializable
data class SquadQa(
    val id: String,
    val question: String,
    val answers: List<SquadAnswer> = emptyList(),
)

@Serializable
data class SquadAnswer(
    val text: String,
    @SerialName("answer_start") val answerStart: Int = 0,
)

/** A planted case whose "wrong" answer is arguably acceptable. Kept in the dataset file and excluded by the analyzer. */
@Serializable
data class DisputedItem(
    val itemId: String,
    val reason: String,
)

@Serializable
data class DisputedFile(
    val schemaVersion: Int = 1,
    val items: List<DisputedItem>,
)
