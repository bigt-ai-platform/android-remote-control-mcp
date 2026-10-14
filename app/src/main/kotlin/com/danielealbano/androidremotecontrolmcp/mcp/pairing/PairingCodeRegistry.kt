package com.danielealbano.androidremotecontrolmcp.mcp.pairing

import kotlinx.coroutines.flow.StateFlow

/** Holds the single active pairing code shown by the app and spent at `POST /pair/claim`. */
interface PairingCodeRegistry {
    val code: StateFlow<PairingCode?>

    /** Mints a fresh code, voiding any previous one. */
    fun issue(nowMs: Long = System.currentTimeMillis()): PairingCode

    /** The active code, or null when none/expired. */
    fun current(nowMs: Long = System.currentTimeMillis()): PairingCode?

    /** Single-use: true exactly once for a matching (normalized) code, then clears it. */
    fun consume(
        input: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean

    /** Drops the code (server stopped). */
    fun clear()
}
