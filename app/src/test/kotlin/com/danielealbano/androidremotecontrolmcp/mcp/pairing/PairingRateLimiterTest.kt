package com.danielealbano.androidremotecontrolmcp.mcp.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PairingRateLimiterTest {
    @Test
    @DisplayName("allows up to maxAttempts in a window")
    fun allowsUpToMax() {
        val limiter = PairingRateLimiter(maxAttempts = 3, windowMs = 1_000L)

        assertTrue(limiter.allow("a", nowMs = 0L))
        assertTrue(limiter.allow("a", nowMs = 0L))
        assertTrue(limiter.allow("a", nowMs = 0L))
        assertFalse(limiter.allow("a", nowMs = 0L))
    }

    @Test
    @DisplayName("resets after the window")
    fun resetsAfterWindow() {
        val limiter = PairingRateLimiter(maxAttempts = 1, windowMs = 1_000L)
        assertTrue(limiter.allow("a", nowMs = 0L))
        assertFalse(limiter.allow("a", nowMs = 0L))

        assertTrue(limiter.allow("a", nowMs = 1_000L))
    }

    @Test
    @DisplayName("keys are independent")
    fun keysIndependent() {
        val limiter = PairingRateLimiter(maxAttempts = 1, windowMs = 1_000L)
        assertTrue(limiter.allow("a", nowMs = 0L))

        assertTrue(limiter.allow("b", nowMs = 0L))
    }

    @Test
    @DisplayName("expired windows are evicted")
    fun expiredWindowsEvicted() {
        val limiter = PairingRateLimiter(maxAttempts = 1, windowMs = 1_000L)
        limiter.allow("a", nowMs = 0L)
        assertEquals(1, limiter.size(nowMs = 0L))

        assertEquals(0, limiter.size(nowMs = 1_000L))
    }
}
