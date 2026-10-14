package com.danielealbano.androidremotecontrolmcp.mcp.pairing

/** In-memory fixed-window limiter for pairing-claim attempts, keyed by the transport peer address. */
class PairingRateLimiter(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val attempts = HashMap<String, Window>()

    /** Records an attempt for [key]; returns false when the window is exhausted. */
    @Synchronized
    fun allow(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        prune(nowMs)
        val current = attempts[key]
        val allowed = current == null || current.count < maxAttempts
        if (allowed) {
            attempts[key] =
                if (current == null) {
                    Window(startMs = nowMs, count = 1)
                } else {
                    current.copy(count = current.count + 1)
                }
        }
        return allowed
    }

    /** Number of tracked windows (test visibility for eviction). */
    @Synchronized
    fun size(nowMs: Long = System.currentTimeMillis()): Int {
        prune(nowMs)
        return attempts.size
    }

    /** Drops windows whose period has elapsed; MUST be called with the monitor held. */
    private fun prune(nowMs: Long) {
        attempts.values.removeAll { nowMs - it.startMs >= windowMs }
    }

    private data class Window(
        val startMs: Long,
        val count: Int,
    )

    private companion object {
        const val MAX_ATTEMPTS = 20
        const val WINDOW_MS = 60 * 1000L
    }
}
