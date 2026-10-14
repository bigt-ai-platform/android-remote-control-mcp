package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.di.PairedDevicesDataStore
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.ClaimedDevice
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** [PairedDeviceRepository] backed by a dedicated Preferences DataStore (`paired_devices`); same
 *  seeded-snapshot + write-through shape as `OAuthClientRepositoryImpl`. Stores token hashes only. */
@Singleton
class PairedDeviceRepositoryImpl
    @Inject
    constructor(
        @PairedDevicesDataStore private val dataStore: DataStore<Preferences>,
        private val serverLog: ServerLogRepository,
    ) : PairedDeviceRepository {
        private val json = Json { ignoreUnknownKeys = true }
        private val snapshot = MutableStateFlow<List<PairedDevice>>(emptyList())
        private val mutex = Mutex()
        private val secureRandom = SecureRandom()
        private var seeded = false

        private suspend fun seedLocked() {
            if (seeded) return
            snapshot.value = decodePairedDevices(json, dataStore.data.first()[PAIRED_DEVICES_KEY])
            seeded = true
        }

        private suspend fun persist(list: List<PairedDevice>) {
            snapshot.value = list
            dataStore.edit { it[PAIRED_DEVICES_KEY] = json.encodeToString(list) }
        }

        override fun observeDevices(): Flow<List<PairedDevice>> =
            flow {
                mutex.withLock { seedLocked() }
                emitAll(snapshot)
            }

        override suspend fun getDevices(): List<PairedDevice> =
            mutex.withLock {
                seedLocked()
                snapshot.value
            }

        override suspend fun claim(
            name: String?,
            nowMs: Long,
        ): ClaimedDevice =
            mutex.withLock {
                seedLocked()
                val token = randomToken(secureRandom)
                val device =
                    PairedDevice(
                        id = "pd-${UUID.randomUUID()}",
                        name = name,
                        tokenSha256 = sha256Hex(token),
                        createdAtMs = nowMs,
                        lastUsedAtMs = nowMs,
                    )
                val combined = snapshot.value + device
                val kept =
                    if (combined.size > PairedDeviceRepository.MAX_PAIRED_DEVICES) {
                        val victim = combined.minWith(compareBy({ it.lastUsedAtMs }, { it.createdAtMs }))
                        combined - victim
                    } else {
                        combined
                    }
                persist(kept)
                ClaimedDevice(device = device, token = token)
            }

        override suspend fun validate(
            token: String,
            nowMs: Long,
        ): Boolean {
            if (token.isEmpty()) return false
            val provided = sha256Hex(token)
            return mutex.withLock {
                seedLocked()
                val match =
                    snapshot.value.firstOrNull { hashesEqual(it.tokenSha256, provided) }
                        ?: return@withLock false
                if (nowMs - match.lastUsedAtMs >= TOUCH_INTERVAL_MS) {
                    persist(snapshot.value.map { if (it.id == match.id) it.copy(lastUsedAtMs = nowMs) else it })
                }
                true
            }
        }

        override suspend fun revoke(deviceId: String) {
            mutex.withLock {
                seedLocked()
                val removed = snapshot.value.firstOrNull { it.id == deviceId }
                val updated = snapshot.value.filterNot { it.id == deviceId }
                if (updated.size != snapshot.value.size) {
                    persist(updated)
                    serverLog.log(ServerLogEntry.Type.AUTH, "Paired device revoked: ${removed?.name ?: deviceId}")
                }
            }
        }

        override suspend fun revokeAll() {
            mutex.withLock {
                seedLocked()
                if (snapshot.value.isNotEmpty()) {
                    persist(emptyList())
                    serverLog.log(ServerLogEntry.Type.AUTH, "All paired devices revoked")
                }
            }
        }

        private companion object {
            val PAIRED_DEVICES_KEY = stringPreferencesKey("paired_devices")
            const val TOUCH_INTERVAL_MS = 60 * 1000L
        }
    }

private fun decodePairedDevices(
    json: Json,
    stored: String?,
): List<PairedDevice> {
    if (stored.isNullOrEmpty()) return emptyList()
    return runCatching { json.decodeFromString<List<PairedDevice>>(stored) }.getOrDefault(emptyList())
}

private fun randomToken(secureRandom: SecureRandom): String {
    val bytes = ByteArray(TOKEN_BYTES).also { secureRandom.nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun hashesEqual(
    a: String,
    b: String,
): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

private const val TOKEN_BYTES = 32
