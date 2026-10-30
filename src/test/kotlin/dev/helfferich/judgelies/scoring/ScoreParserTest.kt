package dev.helfferich.judgelies.scoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoreParserTest {
    @Test
    fun `should parse bare JSON scores`() {
        val parsed = ScoreParser.parse("""{"faithfulness": 4, "correctness": 2, "rationale": "The year is wrong."}""")
        assertEquals(4, parsed.faithfulness)
        assertEquals(2, parsed.correctness)
        assertTrue(parsed.parsed)
    }

    @Test
    fun `should parse fenced JSON and string scores`() {
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
    fun `should reject out-of-range and missing scores`() {
        assertFalse(ScoreParser.parse("""{"faithfulness": 6, "correctness": 2}""").parsed)
        assertFalse(ScoreParser.parse("""{"correctness": 2}""").parsed)
        assertFalse(ScoreParser.parse("no json here").parsed)
    }

    @Test
    fun `should name why a reply failed`() {
        val claude = ScoreParser.parse("""{"faithfulness": 2, "rationale": "The context says 220, not 221."}""")
        assertFalse(claude.parsed)
        assertEquals(ParseFailure.MISSING_CORRECTNESS, claude.failure)
        assertEquals(2, claude.partialFaithfulness)
        assertNull(claude.faithfulness)
        assertEquals(ParseFailure.UNTERMINATED_JSON, ScoreParser.parse("""{"faithfulness": 5, "correctness": 5, "rationale": "cut"""").failure)
        assertEquals(ParseFailure.NO_JSON, ScoreParser.parse("no json here").failure)
        assertEquals(ParseFailure.MISSING_FAITHFULNESS, ScoreParser.parse("""{"correctness": 2}""").failure)
        assertNull(ScoreParser.parse("""{"faithfulness": 4, "correctness": 2}""").failure)
    }

    @Test
    fun `should ignore braces inside the rationale string`() {
        val parsed = ScoreParser.parse("""{"faithfulness": 3, "correctness": 3, "rationale": "He said {not this} was it."}""")
        assertEquals(3, parsed.faithfulness)
    }
}

