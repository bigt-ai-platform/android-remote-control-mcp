package com.danielealbano.androidremotecontrolmcp.mcp.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PairingCodeRegistryImplTest {
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Test
    @DisplayName("issue returns an 8-char code from the unambiguous alphabet")
    fun issueReturnsCode() {
        val registry = PairingCodeRegistryImpl()

        val code = registry.issue(nowMs = 1_000L)

        assertEquals(PairingCodeRegistryImpl.CODE_CHARS, code.code.length)
        assertTrue(code.code.all { it in alphabet })
        assertEquals(1_000L + PairingCodeRegistryImpl.CODE_TTL_MS, code.expiresAtMs)
    }

    @Test
    @DisplayName("issue voids the previous code")
    fun issueVoidsPrevious() {
        val registry = PairingCodeRegistryImpl()
        val first = registry.issue(nowMs = 0L)
        val second = registry.issue(nowMs = 0L)

        assertFalse(registry.consume(first.code, nowMs = 0L))
        assertTrue(registry.consume(second.code, nowMs = 0L))
    }

    @Test
    @DisplayName("current returns null after the ttl")
    fun currentExpires() {
        val registry = PairingCodeRegistryImpl()
        registry.issue(nowMs = 0L)

        assertNull(registry.current(nowMs = PairingCodeRegistryImpl.CODE_TTL_MS))
        assertNull(registry.code.value)
    }

    @Test
    @DisplayName("consume accepts a normalized code once")
    fun consumeNormalized() {
        val registry = PairingCodeRegistryImpl()
        val issued = registry.issue(nowMs = 0L)
        val messy =
            issued.code
                .lowercase()
                .chunked(4)
                .joinToString("-")

        assertTrue(registry.consume(messy, nowMs = 0L))
        assertFalse(registry.consume(issued.code, nowMs = 0L))
    }

    @Test
    @DisplayName("consume rejects a wrong code and keeps the active code")
    fun consumeRejectsWrong() {
        val registry = PairingCodeRegistryImpl()
        val issued = registry.issue(nowMs = 0L)

        assertFalse(registry.consume("WRONGXYZ", nowMs = 0L))
        assertTrue(registry.consume(issued.code, nowMs = 0L))
    }

    @Test
    @DisplayName("clear drops the active code")
    fun clearDropsCode() {
        val registry = PairingCodeRegistryImpl()
        registry.issue(nowMs = 0L)

        registry.clear()

        assertNull(registry.current(nowMs = 0L))
    }
}
