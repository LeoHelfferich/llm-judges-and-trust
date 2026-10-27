package dev.helfferich.judgelies.scoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgreementTest {
    @Test
    fun `should give kappa 1 for identical ratings`() {
        val scores = intArrayOf(1, 2, 3, 4, 5)
        assertEquals(1.0, Agreement.quadraticWeightedKappa(scores, scores), 1e-9)
    }

    @Test
    fun `should match the hand-computed two-category kappa`() {
        val kappa = Agreement.quadraticWeightedKappa(intArrayOf(1, 1, 2, 2), intArrayOf(1, 2, 2, 2), min = 1, max = 2)
        assertEquals(0.5, kappa, 1e-9)
    }
}

