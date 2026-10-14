package com.danielealbano.androidremotecontrolmcp.mcp.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory [PairingCodeRegistry]. Exactly one active code; single-use; expires after [CODE_TTL_MS].
 *  Accessed from the Ktor request thread and the UI thread, so every mutation is `@Synchronized`. */
@Singleton
class PairingCodeRegistryImpl
    @Inject
    constructor() : PairingCodeRegistry {
        private val _code = MutableStateFlow<PairingCode?>(null)
        override val code: StateFlow<PairingCode?> = _code.asStateFlow()
        private val secureRandom = SecureRandom()

        @Synchronized
        override fun issue(nowMs: Long): PairingCode {
            val issued = PairingCode(code = randomCode(), expiresAtMs = nowMs + CODE_TTL_MS)
            _code.value = issued
            return issued
        }

        @Synchronized
        override fun current(nowMs: Long): PairingCode? {
            val active = _code.value ?: return null
            return if (active.expiresAtMs <= nowMs) {
                _code.value = null
                null
            } else {
                active
            }
        }

        @Synchronized
        override fun consume(
            input: String,
            nowMs: Long,
        ): Boolean {
            val active = current(nowMs) ?: return false
            val matched = normalizePairingCode(input) == active.code
            if (matched) {
                _code.value = null
            }
            return matched
        }

        @Synchronized
        override fun clear() {
            _code.value = null
        }

        private fun randomCode(): String {
            val bytes = ByteArray(CODE_CHARS).also { secureRandom.nextBytes(it) }
            return buildString { for (byte in bytes) append(ALPHABET[(byte.toInt() and BYTE_MASK) % ALPHABET.length]) }
        }

        companion object {
            /** Code length in characters. */
            const val CODE_CHARS = 8

            /** Code time-to-live (10 minutes), matching the dsh pairing code. */
            const val CODE_TTL_MS = 10 * 60 * 1000L

            /** Unambiguous alphabet (no I/O/0/1). */
            private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

            /** Mask to treat a signed byte as an unsigned index base. */
            private const val BYTE_MASK = 0xff
        }
    }

/** Uppercase and strip separators/ambiguity so `abcd-efgh` == `ABCDEFGH`. */
fun normalizePairingCode(input: String): String = input.uppercase().replace(Regex("[^A-Z0-9]"), "")
