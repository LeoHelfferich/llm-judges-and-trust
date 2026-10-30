package dev.helfferich.judgelies.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HashingTest {
    @Test
    fun `should match the known FNV-1a vector`() {
        assertEquals(1_335_831_723, Hashing.fnv1a("hello"))
    }
}

