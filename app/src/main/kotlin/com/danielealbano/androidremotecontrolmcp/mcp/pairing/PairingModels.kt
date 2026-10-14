package com.danielealbano.androidremotecontrolmcp.mcp.pairing

import kotlinx.serialization.Serializable

/** A short single-use code a client spends to obtain a per-device bearer token. */
data class PairingCode(
    val code: String,
    val expiresAtMs: Long,
)

/** A client that has claimed a pairing code; holds only the SHA-256 of its bearer token. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String? = null,
    val tokenSha256: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
)

/** Result of a successful claim: the persisted record plus the plaintext token (returned once, never stored). */
data class ClaimedDevice(
    val device: PairedDevice,
    val token: String,
)
