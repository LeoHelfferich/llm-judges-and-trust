package dev.helfferich.judgelies.scoring

object Agreement {
    /**
     * Quadratic weighted Cohen's kappa. Categories are the integers from [min] to [max].
     * Returns 1 when both raters are constant and identical (the weight sum is zero).
     */
    fun quadraticWeightedKappa(left: IntArray, right: IntArray, min: Int = 1, max: Int = 5): Double {
        require(left.size == right.size && left.isNotEmpty()) { "kappa needs paired non-empty ratings" }
        val span = max - min
        require(span > 0)
        val nCat = span + 1
        val observed = Array(nCat) { DoubleArray(nCat) }
        left.indices.forEach { i ->
            val a = left[i]
            val b = right[i]
            require(a in min..max && b in min..max) { "rating outside $min..$max" }
            observed[a - min][b - min] += 1.0
        }
        val n = left.size.toDouble()
        val row = DoubleArray(nCat) { r -> observed[r].sum() }
        val col = DoubleArray(nCat) { c -> (0 until nCat).sumOf { r -> observed[r][c] } }
        var weightedObserved = 0.0
        var weightedExpected = 0.0
        for (r in 0 until nCat) {
            for (c in 0 until nCat) {
                val weight = (r - c) * (r - c) / (span.toDouble() * span)
                weightedObserved += weight * observed[r][c]
                weightedExpected += weight * (row[r] * col[c] / n)
            }
        }
        // Both raters constant and equal: every weight is 0, so kappa is 1.
        // Constant and different: observed weight is positive while expected is 0, so kappa is 0.
        if (weightedExpected == 0.0) return if (weightedObserved == 0.0) 1.0 else 0.0
        return 1.0 - weightedObserved / weightedExpected
    }
}
