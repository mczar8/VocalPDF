package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun validatesPinFormat() {
        assertTrue(PinHasher.isValidPin("0429"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin("12345"))
    }

    @Test
    fun hashIsSaltedAndVerifiable() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertNotEquals("1234", hash)
        assertTrue(PinHasher.matches("1234", salt, hash))
        assertFalse(PinHasher.matches("1235", salt, hash))
        assertNotEquals(hash, PinHasher.hash("1234", PinHasher.newSalt()))
    }

    @Test
    fun lockoutGrowsAfterRepeatedFailures() {
        assertEquals(0L, LockoutPolicy.delayAfterFailures(4))
        assertEquals(30_000L, LockoutPolicy.delayAfterFailures(5))
        assertEquals(60_000L, LockoutPolicy.delayAfterFailures(6))
        assertEquals(300_000L, LockoutPolicy.delayAfterFailures(20))
    }
}
