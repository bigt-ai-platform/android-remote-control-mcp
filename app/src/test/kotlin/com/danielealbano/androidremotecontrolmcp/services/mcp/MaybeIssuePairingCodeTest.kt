package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingCodeRegistryImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("maybeIssuePairingCode")
class MaybeIssuePairingCodeTest {
    @Test
    fun `issues a code when an auth method is enabled and none is active`() {
        val registry = PairingCodeRegistryImpl()

        maybeIssuePairingCode(registry, ServerConfig(bearerTokenEnabled = true))

        assertNotNull(registry.current())
    }

    @Test
    fun `keeps a still-valid code instead of rotating it on a second start`() {
        val registry = PairingCodeRegistryImpl()
        maybeIssuePairingCode(registry, ServerConfig(bearerTokenEnabled = true))
        val first = registry.current()

        // A second start (e.g. the package-replaced restart racing the trampoline start) must NOT
        // rotate the code a client may already have read off the screen.
        maybeIssuePairingCode(registry, ServerConfig(bearerTokenEnabled = true))

        assertEquals(first, registry.current())
    }

    @Test
    fun `does not issue a code when both auth methods are disabled`() {
        val registry = PairingCodeRegistryImpl()

        maybeIssuePairingCode(registry, ServerConfig(bearerTokenEnabled = false, oauthEnabled = false))

        assertNull(registry.current())
    }

    @Test
    fun `oauth-only still issues a code`() {
        val registry = PairingCodeRegistryImpl()

        maybeIssuePairingCode(registry, ServerConfig(bearerTokenEnabled = false, oauthEnabled = true))

        assertNotNull(registry.current())
    }
}
