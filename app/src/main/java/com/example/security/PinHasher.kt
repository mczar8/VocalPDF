package com.example.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Hachage du code PIN : le code n'est jamais stocké en clair.
 * SHA-256 itéré avec un sel aléatoire (Kotlin pur, compatible API 24).
 */
object PinHasher {

    const val PIN_LENGTH = 4
    private const val ITERATIONS = 10_000

    fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val saltBytes = salt.toByteArray(Charsets.UTF_8)
        var data = "$salt:$pin".toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) {
            digest.reset()
            digest.update(saltBytes)
            data = digest.digest(data)
        }
        return data.toHex()
    }

    fun matches(pin: String, salt: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expectedHash.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Délai imposé après des erreurs répétées : 5 erreurs -> 30 s, puis doublement, max 5 min. */
object LockoutPolicy {
    const val FREE_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 300_000L

    fun delayAfterFailures(failedAttempts: Int): Long {
        if (failedAttempts < FREE_ATTEMPTS) return 0L
        val exponent = (failedAttempts - FREE_ATTEMPTS).coerceAtMost(10)
        return (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
    }
}
