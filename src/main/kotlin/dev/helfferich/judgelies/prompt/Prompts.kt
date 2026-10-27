package dev.helfferich.judgelies.prompt

import dev.helfferich.judgelies.support.Hashing

object Prompts {
    data class Spec(val id: String, val system: String)

    val strict = Spec(
        id = "strict",
        system = """
            You evaluate Retrieval-Augmented Generation answers. Score only what the retrieved context supports. Do not use outside knowledge to raise a score. If the answer is plausible but the context does not support it, faithfulness is 1. If the answer contradicts the context, set faithfulness to 1 and correctness to 1.

            Reply with one JSON object and nothing else:
            {"faithfulness": <integer 1-5>, "correctness": <integer 1-5>, "rationale": "<one sentence>"}

            faithfulness: 1 no support or contradicted, 2 weak, 3 partial, 4 strong with a minor omission, 5 fully and precisely supported.
            correctness: 1 does not answer or contradicts the context, 2 mostly wrong, 3 mixed, 4 minor error, 5 answers the question exactly as the context states.
        """.trimIndent(),
    )

    val concise = Spec(
        id = "concise",
        system = """
            Score the candidate answer against the context and the question.
            Reply with JSON only: {"faithfulness": <integer 1-5>, "correctness": <integer 1-5>, "rationale": "<one sentence>"}
            faithfulness means the context supports the answer. correctness means the answer addresses the question.
        """.trimIndent(),
    )

    val cot = Spec(
        id = "cot",
        system = """
            You evaluate a RAG answer. In the rationale, write at most two sentences: first what the context actually states, then whether the answer matches it. Do not use outside knowledge to raise a score.
            Then give integer scores from 1 to 5.
            Reply with JSON only:
            {"rationale": "<two sentences max>", "faithfulness": <integer 1-5>, "correctness": <integer 1-5>}
            faithfulness: 1 unsupported or contradicted, 5 fully supported.
            correctness: 1 wrong or missing the question, 5 exactly right per the context.
        """.trimIndent(),
    )

    val all = listOf(strict, concise, cot)
    val byId = all.associateBy { it.id }

    fun user(context: String, question: String, answer: String): String = """
        Context:
        ${"\"\"\""}
        $context
        ${"\"\"\""}

        Question: $question

        Answer: $answer
    """.trimIndent()

    /** Rubric text plus the user-template version. A wording change must miss the old cache. */
    fun hash(): String = Hashing.sha256Prefix(all.joinToString("\n---\n") { it.id + "\n" + it.system + "\n" + USER_MARK })

    private const val USER_MARK = "user-v1"
}
