package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.mcp.pairing.ClaimedDevice
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairedDevice
import kotlinx.coroutines.flow.Flow

/** Persisted registry of clients that have paired, keyed by the SHA-256 of their bearer token. */
interface PairedDeviceRepository {
    fun observeDevices(): Flow<List<PairedDevice>>

    suspend fun getDevices(): List<PairedDevice>

    /** Mints a token, stores its hash, and returns the plaintext token (shown once). */
    suspend fun claim(
        name: String?,
        nowMs: Long,
    ): ClaimedDevice

    /** True when [token] matches a paired device; touches `lastUsedAtMs` (debounced). */
    suspend fun validate(
        token: String,
        nowMs: Long,
    ): Boolean

    suspend fun revoke(deviceId: String)

    suspend fun revokeAll()

    companion object {
        /** Maximum retained pairings; the least-recently-used is evicted beyond this. */
        const val MAX_PAIRED_DEVICES = 20
    }
}
