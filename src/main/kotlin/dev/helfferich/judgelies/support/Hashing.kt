package dev.helfferich.judgelies.support

import java.security.MessageDigest

object Hashing {
    /**
     * FNV-1a 32-bit, masked to a non-negative Int.
     * UInt multiply wraps the way the 32-bit definition does, so donor offsets stay stable across JVMs.
     */
    fun fnv1a(text: String): Int {
        var hash = 2166136261u
        for (byte in text.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toUInt()
            hash *= 16777619u
        }
        return (hash and 0x7fff_ffffu).toInt()
    }

    /** Leading hex characters of SHA-256. The prompt cache uses this so a rubric edit cannot collide with an old key. */
    fun sha256Prefix(text: String, hexChars: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.take(hexChars)
    }
}
