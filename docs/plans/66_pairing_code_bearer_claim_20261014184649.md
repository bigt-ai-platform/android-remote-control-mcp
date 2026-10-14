<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 66 — Short-code pairing: the phone prints a code, clients spend it for a per-device token

On `main` an MCP client pairs by copying the app's long static **bearer token** by hand (or completing the OAuth
flow). This plan adds a **short single-use pairing code** (the analogue of the `dsh` relay pairing in
`../dai/packages/dsh-tunnel/src/pairing.ts`): the phone shows an 8-character code; a client spends it at
`POST /pair/claim` and receives a **per-device bearer token** (shown once, stored hashed on the phone). The token is
accepted by the existing `/mcp` auth as a **third** credential path (alongside the static bearer and OAuth access
tokens) and is **revocable individually** from a new "Paired devices" screen — like the existing OAuth "Connected
clients" list. Manual token entry and OAuth remain unchanged.

**Why a per-device token and not the static bearer:** the static token is device-wide and rotating it drops every
client. A code exchanges for its own random token, so revocation is per pairing and the long-lived secret is never
typed by the user (only the short, single-use, expiring code is).

**Scope boundary — files this plan may touch:**

Part A (this repo; `.../` = `com/danielealbano/androidremotecontrolmcp`):
- `app/src/main/kotlin/.../mcp/pairing/PairingModels.kt` (create)
- `app/src/main/kotlin/.../mcp/pairing/PairingCodeRegistry.kt` (create)
- `app/src/main/kotlin/.../mcp/pairing/PairingCodeRegistryImpl.kt` (create)
- `app/src/main/kotlin/.../mcp/pairing/PairingRateLimiter.kt` (create)
- `app/src/main/kotlin/.../mcp/pairing/PairingRoutes.kt` (create)
- `app/src/main/kotlin/.../mcp/pairing/PairingServerDeps.kt` (create)
- `app/src/main/kotlin/.../mcp/McpServerDeps.kt` (create)
- `app/src/main/kotlin/.../data/repository/PairedDeviceRepository.kt` (create)
- `app/src/main/kotlin/.../data/repository/PairedDeviceRepositoryImpl.kt` (create)
- `app/src/main/kotlin/.../mcp/auth/BearerTokenAuth.kt` (modify)
- `app/src/main/kotlin/.../mcp/McpServer.kt` (modify)
- `app/src/main/kotlin/.../di/AppModule.kt` (modify)
- `app/src/main/kotlin/.../services/mcp/McpServerService.kt` (modify)
- `app/src/main/kotlin/.../ui/components/PairingCodeCard.kt` (create)
- `app/src/main/kotlin/.../ui/viewmodels/PairingViewModel.kt` (create)
- `app/src/main/kotlin/.../ui/viewmodels/PairedDevicesViewModel.kt` (create)
- `app/src/main/kotlin/.../ui/screens/settings/PairedDevicesScreen.kt` (create)
- `app/src/main/kotlin/.../ui/screens/settings/AccessSettingsScreen.kt` (modify)
- `app/src/main/kotlin/.../ui/screens/ServerScreen.kt` (modify)
- `app/src/main/kotlin/.../ui/screens/SettingsScreen.kt` (modify)
- `app/src/main/kotlin/.../ui/navigation/Routes.kt` (modify)
- `app/src/main/res/values/strings.xml` (modify)
- `app/src/test/kotlin/.../mcp/pairing/PairingCodeRegistryImplTest.kt` (create)
- `app/src/test/kotlin/.../mcp/pairing/PairingRateLimiterTest.kt` (create)
- `app/src/test/kotlin/.../data/repository/PairedDeviceRepositoryImplTest.kt` (create)
- `app/src/test/kotlin/.../mcp/auth/BearerTokenAuthTest.kt` (modify)
- `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt` (modify — add `withPairingTestApplication`)
- `app/src/test/kotlin/.../integration/PairingFlowIntegrationTest.kt` (create)

Part B (cross-repo clients — **separate branches/PRs in those repos**; no commit in this repo touches them):
- `../wallet/packages/mobile-control/src/android-client.ts`, `src/index.ts`
- `../wallet/packages/mobile-control/test/android-client.test.ts`
- `../wallet/expo-app/sources/screens/device/DeviceScreen.tsx`, `sources/lib/i18n.ts`, `docs/p2p.md`
- `../dai/services/dai/src/mobile.ts`, `src/server.ts`, `test/mobile.test.ts`
- `../dai/apps/web/src/lib/api.ts`, `src/components/MobileScreen.tsx`, `src/lib/i18n.ts`

**Pre-flight constraints (verified during planning):** detekt `2.0.0-alpha.5` defaults are active with no override
config, so `McpServer` MUST keep 6 constructor parameters (`LongParameterList.allowedConstructorParameters = 6`) and
`PairedDeviceRepositoryImpl` MUST keep ≤ 11 class functions (`TooManyFunctions`). Both are satisfied below by (a)
grouping collaborators into `McpServerDeps` and (b) moving pure helpers to top-level file functions.

---

## Protocol contract (frozen — both clients and the phone MUST match exactly)

- **Display:** the app shows an 8-char code from the alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no `I`/`O`/`0`/`1`),
  valid 10 minutes, exactly one active code at a time.
- **Spend:** `POST {base}/pair/claim`, `Content-Type: application/json`, body `{"code":"<code>","name":"<label?>"}`.
  `code` is normalized (uppercase, non-alphanumerics stripped) before comparison.
- **200:** `{"token":"<64 hex>","device_id":"pd-<uuid>","name":"<label|null>"}` — `token` is shown **once**; the phone
  stores only its SHA-256.
- **400** `{"error":"invalid_request","message":...}` (missing code), **404**
  `{"error":"invalid_code","message":"invalid or expired pairing code"}`, **429**
  `{"error":"rate_limited","message":...}`.
- **Use:** the token is a bearer credential on `POST {base}/mcp` (`Authorization: Bearer <token>`); the endpoint is
  the same one the app already shows. Revoking the paired device on the phone invalidates it immediately.
- **Endpoint path is `/pair/claim`** (not `/pair`) so future `/pair/*` additions do not collide.

---

## User Story 1 — Pairing-code registry (short single-use code)

**Why:** the code is the bearer secret of the pairing (only someone reading the phone's screen can bind); it must be
short-lived and single-use so a leaked code is worthless after one successful claim or 10 minutes.

**Acceptance criteria:**
- [ ] `issue` mints an 8-char code over the unambiguous alphabet and voids any prior code (one active code).
- [ ] `current` returns `null` once the code's TTL has elapsed and clears it.
- [ ] `consume` returns `true` exactly once for a matching code (normalized) and clears it; a wrong code returns
      `false` and leaves the active code intact.
- [ ] `code` is a `StateFlow<PairingCode?>` observable by the UI.
- [ ] `clear` drops the code (server stopped).

### Task 1.1 — Models

**Action — create** `app/src/main/kotlin/.../mcp/pairing/PairingModels.kt`:

```kotlin
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
```

### Task 1.2 — Registry interface

**Action — create** `app/src/main/kotlin/.../mcp/pairing/PairingCodeRegistry.kt`:

```kotlin
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
    fun consume(input: String, nowMs: Long = System.currentTimeMillis()): Boolean

    /** Drops the code (server stopped). */
    fun clear()
}
```

### Task 1.3 — Registry implementation

**Action — create** `app/src/main/kotlin/.../mcp/pairing/PairingCodeRegistryImpl.kt`:

```kotlin
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
            if (active.expiresAtMs <= nowMs) {
                _code.value = null
                return null
            }
            return active
        }

        @Synchronized
        override fun consume(input: String, nowMs: Long): Boolean {
            val active = current(nowMs) ?: return false
            if (normalizePairingCode(input) != active.code) return false
            _code.value = null
            return true
        }

        @Synchronized
        override fun clear() {
            _code.value = null
        }

        private fun randomCode(): String {
            val bytes = ByteArray(CODE_CHARS).also { secureRandom.nextBytes(it) }
            return buildString { for (byte in bytes) append(ALPHABET[(byte.toInt() and 0xff) % ALPHABET.length]) }
        }

        companion object {
            /** Code length in characters. */
            const val CODE_CHARS = 8

            /** Code time-to-live (10 minutes), matching the dsh pairing code. */
            const val CODE_TTL_MS = 10 * 60 * 1000L

            /** Unambiguous alphabet (no I/O/0/1). */
            private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        }
    }

/** Uppercase and strip separators/ambiguity so `abcd-efgh` == `ABCDEFGH`. */
fun normalizePairingCode(input: String): String = input.uppercase().replace(Regex("[^A-Z0-9]"), "")
```

**Definition of Done:**
- [ ] Constants `CODE_CHARS`, `CODE_TTL_MS` and top-level `normalizePairingCode` are public (used by tests and routes).
- [ ] No `Log`/Android dependency (unit-testable).
- [ ] `issue` replaces any existing code (single active code).

---

## User Story 2 — Paired-device token repository (persisted, revocable)

**Why:** a claimed token must survive process death (the client keeps using it) and be individually revocable, so the
phone persists one record per pairing with only the token's hash — mirroring the OAuth client repository under
`data/repository/`.

**Acceptance criteria:**
- [ ] `claim` mints a 32-byte hex token, stores only its SHA-256, and returns the plaintext once.
- [ ] `validate` returns true only for a token whose SHA-256 is a stored record; it updates `lastUsedAtMs` at most
      once per minute (debounced).
- [ ] `revoke(id)` / `revokeAll()` remove records; a revoked token stops validating.
- [ ] The list is observable and persisted in a dedicated DataStore, seeded once (same shape as `OAuthClientRepositoryImpl`).
- [ ] At most `MAX_PAIRED_DEVICES` records; on overflow the single least-recently-used record is removed (ties broken
      by oldest `createdAtMs`).

### Task 2.1 — Repository interface

**Action — create** `app/src/main/kotlin/.../data/repository/PairedDeviceRepository.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.mcp.pairing.ClaimedDevice
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairedDevice
import kotlinx.coroutines.flow.Flow

/** Persisted registry of clients that have paired, keyed by the SHA-256 of their bearer token. */
interface PairedDeviceRepository {
    fun observeDevices(): Flow<List<PairedDevice>>

    suspend fun getDevices(): List<PairedDevice>

    /** Mints a token, stores its hash, and returns the plaintext token (shown once). */
    suspend fun claim(name: String?, nowMs: Long): ClaimedDevice

    /** True when [token] matches a paired device; touches `lastUsedAtMs` (debounced). */
    suspend fun validate(token: String, nowMs: Long): Boolean

    suspend fun revoke(deviceId: String)

    suspend fun revokeAll()

    companion object {
        /** Maximum retained pairings; the least-recently-used is evicted beyond this. */
        const val MAX_PAIRED_DEVICES = 20
    }
}
```

### Task 2.2 — Repository implementation

**Action — create** `app/src/main/kotlin/.../data/repository/PairedDeviceRepositoryImpl.kt`.
Pure token/hash helpers are **top-level** file functions so the class stays under detekt's `TooManyFunctions` (11):

```kotlin
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

        override suspend fun claim(name: String?, nowMs: Long): ClaimedDevice =
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
                    if (combined.size > MAX_PAIRED_DEVICES) {
                        val victim = combined.minWith(compareBy({ it.lastUsedAtMs }, { it.createdAtMs }))
                        combined - victim
                    } else {
                        combined
                    }
                persist(kept)
                ClaimedDevice(device = device, token = token)
            }

        override suspend fun validate(token: String, nowMs: Long): Boolean {
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

private fun decodePairedDevices(json: Json, stored: String?): List<PairedDevice> {
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

private fun hashesEqual(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

private const val TOKEN_BYTES = 32
```

**Definition of Done:**
- [ ] `token` plaintext is returned only from `claim`; never persisted or logged.
- [ ] Comparison is constant-time over the hash; `validate("")` is `false` without touching disk.
- [ ] On overflow exactly the least-recently-used (then oldest-created) record is removed.
- [ ] `PairedDeviceRepositoryImpl` has ≤ 11 functions (helpers are top-level).

### Task 2.3 — Tests

**File**: `app/src/test/kotlin/.../mcp/pairing/PairingCodeRegistryImplTest.kt`

**Setup**: `PairingCodeRegistryImpl()`; no mocks. Pass explicit `nowMs`.

| Test | Verifies |
|------|----------|
| `issue returns an 8-char code from the unambiguous alphabet` | Length 8; every char in `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` |
| `issue voids the previous code` | Issue twice → first code no longer `consume`s |
| `current returns null after the ttl` | `issue(t)`, `current(t + CODE_TTL_MS)` → null; `code.value` null |
| `consume accepts a normalized code once` | `issue` → `consume(" abcd-efgh ")` equals the code; second consume false |
| `consume rejects a wrong code and keeps the active code` | Wrong code false; correct code still consumes |
| `clear drops the active code` | `clear()` → `current` null |

**File**: `app/src/test/kotlin/.../mcp/pairing/PairingRateLimiterTest.kt`

**Setup**: `PairingRateLimiter(maxAttempts = 3, windowMs = 1000)`; no mocks.

| Test | Verifies |
|------|----------|
| `allows up to maxAttempts in a window` | First 3 `allow(key, t)` true; 4th false |
| `resets after the window` | 4th at `t + windowMs` true |
| `keys are independent` | Exhausting `a` does not affect `b` |
| `expired windows are evicted` | After advancing past the window, `size(t + windowMs)` is 0 |

**File**: `app/src/test/kotlin/.../data/repository/PairedDeviceRepositoryImplTest.kt`

**Setup**: `PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "paired.preferences_pb") })` in a temp dir
per test; `mockk<ServerLogRepository>(relaxed = true)`; `runTest`. Pass explicit `nowMs`.

| Test | Verifies |
|------|----------|
| `claim persists a hashed record and returns the token once` | `claim("wallet", t)` → token 64 hex; `getDevices()` has one record whose `tokenSha256` != token |
| `validate accepts a claimed token and rejects others` | `validate(token, t)` true; `validate("deadbeef", t)` false |
| `validate debounces lastUsed updates` | Two validates 1s apart → `lastUsedAtMs` unchanged; after `TOUCH_INTERVAL_MS` it advances |
| `revoke invalidates the token` | `revoke(id)` → `validate(token)` false; device absent |
| `revokeAll clears every device` | Two claims → `revokeAll()` → `getDevices()` empty |
| `claim evicts the least-recently-used beyond MAX_PAIRED_DEVICES` | Claim 21 with increasing `lastUsedAtMs` → 20 kept, the smallest is gone |
| `observeDevices emits persisted state` | After `claim`, collect first emission contains the record |

---

## User Story 3 — `/pair/claim` route + paired-token auth path

**Why:** the claim endpoint is the spend point; it is unauthenticated (the code is the secret) so it must validate
and fail closed itself, and the resulting token must be accepted by the same `/mcp` auth plugin.

**Acceptance criteria:**
- [ ] `POST /pair/claim` consumes a valid code, returns `{token, device_id, name}`, and mints one device.
- [ ] Missing code → 400; invalid/expired code → 404; a consumed code never works twice; an oversized body → 413.
- [ ] Per-client-address rate limiting rejects more than 20 attempts/minute with 429.
- [ ] `/mcp` accepts `Authorization: Bearer <paired token>`; a bogus or revoked token is rejected (401).
- [ ] `POST /pair/claim` is added to the auth exclusion list in BOTH `McpServer.kt` and the integration helper.

### Task 3.1 — Rate limiter

**Action — create** `app/src/main/kotlin/.../mcp/pairing/PairingRateLimiter.kt`. Keys on the **transport peer
address** (not the spoofable `X-Forwarded-For`) and opportunistically evicts expired windows so an attacker cannot
grow the map without bound:

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp.pairing

/** In-memory fixed-window limiter for pairing-claim attempts, keyed by the transport peer address. */
class PairingRateLimiter(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val attempts = HashMap<String, Window>()

    /** Records an attempt for [key]; returns false when the window is exhausted. */
    @Synchronized
    fun allow(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        val current = attempts[key]
        if (current == null) {
            attempts[key] = Window(startMs = nowMs, count = 1)
            return true
        }
        if (current.count >= maxAttempts) return false
        attempts[key] = current.copy(count = current.count + 1)
        return true
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

    private data class Window(val startMs: Long, val count: Int)

    private companion object {
        const val MAX_ATTEMPTS = 20
        const val WINDOW_MS = 60 * 1000L
    }
}
```

### Task 3.2 — Routes

**Action — create** `app/src/main/kotlin/.../mcp/pairing/PairingRoutes.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp.pairing

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.PairedDeviceRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body of `POST /pair/claim`. */
@Serializable
data class PairClaimRequest(val code: String = "", val name: String? = null)

/** Success body of `POST /pair/claim` (token shown once). */
@Serializable
data class PairClaimResponse(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    val name: String? = null,
)

/** Error body of `POST /pair/claim`. */
@Serializable
data class PairErrorResponse(val error: String, val message: String)

/** Collaborators for the pairing route. */
class PairingRouteDeps(
    val codeRegistry: PairingCodeRegistry,
    val deviceRepository: PairedDeviceRepository,
    val serverLog: ServerLogRepository,
    val rateLimiter: PairingRateLimiter = PairingRateLimiter(),
)

/**
 * Mounts the unauthenticated (code-is-the-secret) pairing-claim route. The auth plugin excludes
 * `/pair/claim`, so this handler is the fail-closed boundary: it consumes a valid single-use code or
 * refuses, and is rate-limited per transport peer address.
 */
fun Route.installPairingRoutes(deps: PairingRouteDeps) {
    post("/pair/claim") {
        val address = call.request.local.remoteAddress
        if (!deps.rateLimiter.allow(address)) {
            call.respondPairError(HttpStatusCode.TooManyRequests, "rate_limited", "too many attempts")
            return@post
        }
        if ((call.request.headers["Content-Length"]?.toLongOrNull() ?: 0L) > MAX_BODY_BYTES) {
            call.respondPairError(HttpStatusCode.PayloadTooLarge, "invalid_request", "body too large")
            return@post
        }
        val body = runCatching { call.receive<PairClaimRequest>() }.getOrNull()
        val code = body?.code?.trim().orEmpty()
        if (code.isEmpty()) {
            call.respondPairError(HttpStatusCode.BadRequest, "invalid_request", "code required")
            return@post
        }
        if (!deps.codeRegistry.consume(code)) {
            deps.serverLog.log(ServerLogEntry.Type.AUTH, "Pairing claim failed from $address")
            call.respondPairError(HttpStatusCode.NotFound, "invalid_code", "invalid or expired pairing code")
            return@post
        }
        val claimed = deps.deviceRepository.claim(body?.name?.take(NAME_MAX), System.currentTimeMillis())
        deps.serverLog.log(ServerLogEntry.Type.AUTH, "Device paired: ${claimed.device.name ?: claimed.device.id}")
        call.respondText(
            McpJson.encodeToString(
                PairClaimResponse.serializer(),
                PairClaimResponse(
                    token = claimed.token,
                    deviceId = claimed.device.id,
                    name = claimed.device.name,
                ),
            ),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

private suspend fun ApplicationCall.respondPairError(
    status: HttpStatusCode,
    error: String,
    message: String,
) {
    respondText(
        McpJson.encodeToString(PairErrorResponse.serializer(), PairErrorResponse(error = error, message = message)),
        ContentType.Application.Json,
        status,
    )
}

private const val NAME_MAX = 64

private const val MAX_BODY_BYTES = 4096L
```

### Task 3.3 — Auth plugin accepts paired tokens

**Action — modify** `app/src/main/kotlin/.../mcp/auth/BearerTokenAuth.kt`:

1. Add to `McpAuthConfig` (after `validateOAuthToken`):

```kotlin
    /** Validates a per-device paired token (null when pairing is unavailable). */
    var validatePairedToken: (suspend (String) -> Boolean)? = null
```

2. In `McpAuthPlugin`, capture the config field beside the others:

```kotlin
        val validatePairedToken = pluginConfig.validatePairedToken
```

3. In the intercept body, immediately AFTER the static bearer path and BEFORE the OAuth path, insert:

```kotlin
            // Paired-device token path (short-code pairing).
            if (validatePairedToken != null && providedToken.isNotEmpty() && validatePairedToken(providedToken)) {
                return@intercept
            }
```

**Definition of Done:**
- [ ] KDoc of `McpAuthConfig`/`McpAuthPlugin` updated to say "static bearer OR paired-device token OR OAuth access token".
- [ ] The open-server early return (`!bearerTokenEnabled && !oauthEnabled`) is unchanged, and no change is made to the
      failure branch or `WWW-Authenticate` behavior. (Production always has at least one of bearer/OAuth enabled when
      pairing is offered — Task 3.4 mounts the route under the same condition.)

### Task 3.4 — Server wiring

**Action — create** `app/src/main/kotlin/.../mcp/pairing/PairingServerDeps.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp.pairing

/** Groups the pairing collaborators used by the server (kept out of [com.danielealbano.androidremotecontrolmcp.mcp.McpServer]). */
class PairingServerDeps(
    val codeRegistry: PairingCodeRegistry,
    val deviceRepository: com.danielealbano.androidremotecontrolmcp.data.repository.PairedDeviceRepository,
)
```

**Action — create** `app/src/main/kotlin/.../mcp/McpServerDeps.kt` (keeps `McpServer` at 6 constructor params):

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthServerDeps
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingServerDeps

/** Groups the `oauth` and `pairing` collaborator bundles passed into [McpServer] (the other four
 *  collaborators stay as direct parameters) so its constructor stays within detekt's LongParameterList. */
class McpServerDeps(
    val oauth: OAuthServerDeps,
    val pairing: PairingServerDeps,
)
```

**Action — modify** `app/src/main/kotlin/.../mcp/McpServer.kt`:

1. Replace the constructor parameter `private val oauth: OAuthServerDeps,` with:

```kotlin
    private val deps: McpServerDeps,
```

`McpServerDeps` is in the same package (no import). Remove the now-unused `OAuthServerDeps` import and add imports
`com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingRouteDeps` and
`com.danielealbano.androidremotecontrolmcp.mcp.pairing.installPairingRoutes`.

2. Update the class KDoc: the `@param oauth` line becomes `@param deps The OAuth authorization-server and pairing collaborators.`

3. `accessValidator` becomes:

```kotlin
    private val accessValidator =
        OAuthAccessValidator(deps.oauth.jwtTokenService, deps.oauth.oauthClientRepository, serverLog)
```

4. In `installMcpBasePlugins { }`, add:

```kotlin
            validatePairedToken = { token -> deps.pairing.deviceRepository.validate(token, System.currentTimeMillis()) }
```

and extend the exclusion set to:

```kotlin
            excludedPaths = setOf("/health", "/register", "/token", "/authorize", "/authorize/status", "/pair/claim")
```

5. The OAuth install call becomes `OAuthRouteDeps(oauth = deps.oauth, publicUrlOverride = ..., serverLog = serverLog)`.

6. In `routing { }`, after the `if (config.oauthEnabled) { installOAuthRoutes(...) }` block, add:

```kotlin
            // Pairing-claim route — mounted only when an auth method is enabled (an open server needs no pairing).
            if (config.bearerTokenEnabled || config.oauthEnabled) {
                installPairingRoutes(
                    PairingRouteDeps(
                        codeRegistry = deps.pairing.codeRegistry,
                        deviceRepository = deps.pairing.deviceRepository,
                        serverLog = serverLog,
                    ),
                )
            }
```

### Task 3.5 — DI

**Action — modify** `app/src/main/kotlin/.../di/AppModule.kt`:

1. Add qualifier + extension property near `OAuthClientsDataStore`:

```kotlin
/** Qualifier for the dedicated paired-devices Preferences DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PairedDevicesDataStore

/** Extension property for the dedicated paired-devices Preferences DataStore on [Context]. */
private val Context.pairedDevicesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "paired_devices",
)
```

2. Add `@Provides` in `AppModule` beside `provideOAuthClientsDataStore`:

```kotlin
    /**
     * Provides the dedicated application-scoped [DataStore] for the paired-device token registry.
     */
    @Provides
    @Singleton
    @PairedDevicesDataStore
    fun providePairedDevicesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.pairedDevicesDataStore
```

3. Bind in `RepositoryModule` (after `bindOAuthClientRepository`):

```kotlin
    /** Binds the persisted paired-device token registry. */
    @Binds
    @Singleton
    abstract fun bindPairedDeviceRepository(impl: PairedDeviceRepositoryImpl): PairedDeviceRepository
```

4. Bind in `ServiceModule` (after `bindAuthorizationCodeStore`):

```kotlin
    @Binds
    @Singleton
    abstract fun bindPairingCodeRegistry(impl: PairingCodeRegistryImpl): PairingCodeRegistry
```

Add imports: `com.danielealbano.androidremotecontrolmcp.data.repository.PairedDeviceRepository`,
`PairedDeviceRepositoryImpl`, `com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingCodeRegistry`,
`PairingCodeRegistryImpl`.

### Task 3.6 — Service issues/clears the code

**Action — modify** `app/src/main/kotlin/.../services/mcp/McpServerService.kt`:

1. Add injected fields beside the OAuth collaborators:

```kotlin
    @Inject lateinit var pairingCodeRegistry: PairingCodeRegistry

    @Inject lateinit var pairedDeviceRepository: PairedDeviceRepository
```

2. Pass a combined `McpServerDeps` to `McpServer(...)` (replacing the `oauth = OAuthServerDeps(...)` argument; the
   `McpServer` constructor arg name is now `deps`):

```kotlin
                    deps =
                        McpServerDeps(
                            oauth =
                                OAuthServerDeps(
                                    jwtTokenService = jwtTokenService,
                                    oauthClientRepository = oauthClientRepository,
                                    authorizationCodeStore = authorizationCodeStore,
                                    approvalCoordinator = approvalCoordinator,
                                    geoIpResolver = geoIpResolver,
                                ),
                            pairing =
                                PairingServerDeps(
                                    codeRegistry = pairingCodeRegistry,
                                    deviceRepository = pairedDeviceRepository,
                                ),
                        ),
```

3. After `mcpServer?.start()`, issue a code **only when an auth method is enabled**:

```kotlin
            if (config.bearerTokenEnabled || config.oauthEnabled) {
                pairingCodeRegistry.issue()
            }
```

4. Guarantee the clear on stop regardless of whether `mcpServer?.stop(...)` throws — wrap the stop call in
   `try/finally` so `pairingCodeRegistry.clear()` runs in the `finally`.

Add imports for the pairing and `McpServerDeps` types.

**Definition of Done:**
- [ ] The code exists only while the server runs and only when an auth method is enabled.
- [ ] `McpServer` still has exactly 6 constructor parameters.

### Task 3.7 — Integration test helper (`withPairingTestApplication`)

**Action — modify** `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt`:

1. In `withOAuthTestApplication`, extend `excludedPaths` (line ~554) to include `"/pair/claim"` (keeps test and
   production exclusion sets identical).
2. Add (mirroring `withOAuthTestApplication`). **The defaults enable the static-bearer auth method with an empty
   token**, so the auth plugin does NOT take the open-server early return and the paired-token path is actually
   enforced (a both-disabled server would accept any request):

```kotlin
    /** Full production wiring WITH pairing routes, a real paired-device DataStore, and paired-token auth.
     *  bearerTokenEnabled defaults TRUE with an empty token (fail-closed), so the paired-token path is enforced. */
    suspend fun withPairingTestApplication(
        deps: MockDependencies = createMockDependencies(),
        bearerTokenEnabled: Boolean = true,
        bearerToken: String = "",
        testBlock: suspend io.ktor.server.testing.ApplicationTestBuilder.(PairingTestContext) -> Unit,
    ) {
        val sdkServer = createSdkServer(deps)
        val tempDir = java.nio.file.Files.createTempDirectory("pairing_helper").toFile()
        val devicesDataStore =
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { java.io.File(tempDir, "paired_devices.preferences_pb") },
            )
        val deviceRepository = PairedDeviceRepositoryImpl(devicesDataStore, deps.serverLog)
        val codeRegistry = PairingCodeRegistryImpl()

        testApplication {
            application {
                installMcpBasePlugins {
                    this.bearerTokenEnabled = bearerTokenEnabled
                    expectedToken = bearerToken
                    oauthEnabled = false
                    validatePairedToken = { token -> deviceRepository.validate(token, System.currentTimeMillis()) }
                    excludedPaths =
                        setOf("/health", "/register", "/token", "/authorize", "/authorize/status", "/pair/claim")
                    excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX, "/.well-known/")
                    onAuthFailure = { deps.serverLog.log(ServerLogEntry.Type.AUTH, "Authentication failed from $it") }
                }
                routing {
                    installPairingRoutes(
                        PairingRouteDeps(
                            codeRegistry = codeRegistry,
                            deviceRepository = deviceRepository,
                            serverLog = deps.serverLog,
                        ),
                    )
                }
                installMcpStatelessTransport { sdkServer }
            }
            val httpClient =
                createClient {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(McpJson) }
                    install(io.ktor.client.plugins.sse.SSE)
                }
            testBlock(PairingTestContext(httpClient, codeRegistry, deviceRepository))
        }
    }
```

Add the context holder beside `OAuthTestContext` (uses the fully-qualified `HttpClient`, as the file has no such
import):

```kotlin
/** Collaborators exposed to pairing integration tests. */
class PairingTestContext(
    val httpClient: io.ktor.client.HttpClient,
    val codeRegistry: PairingCodeRegistry,
    val deviceRepository: PairedDeviceRepository,
)
```

Add imports: `com.danielealbano.androidremotecontrolmcp.data.repository.PairedDeviceRepository`,
`PairedDeviceRepositoryImpl`, `com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingCodeRegistry`,
`PairingCodeRegistryImpl`, `PairingRouteDeps`, `installPairingRoutes`.

**Definition of Done:**
- [ ] `withPairingTestApplication` uses the SAME `installMcpBasePlugins`/`installMcpStatelessTransport` as production.
- [ ] Its defaults produce a fail-closed server (auth enforced) so 401 tests are meaningful.

### Task 3.8 — Claim + auth integration tests

**File**: `app/src/test/kotlin/.../integration/PairingFlowIntegrationTest.kt`

**Setup**: `McpIntegrationTestHelper.mockAndroidLog()` / `unmockAndroidLog()`; `withPairingTestApplication { ctx -> ... }`
(default fail-closed config).

| Test | Verifies |
|------|----------|
| `claim with a valid code returns a token and pairs a device` | `ctx.codeRegistry.issue()` → POST `/pair/claim` `{"code":...}` → 200 with 64-hex `token`; `ctx.deviceRepository.getDevices()` size 1 |
| `claim with an invalid code is 404` | POST `{"code":"BADCODE1"}` → 404; no device persisted |
| `claim without a code is 400` | POST `{}` → 400 |
| `a code cannot be spent twice` | First claim 200; identical second claim 404 |
| `a claimed token authorizes an /mcp initialize` | Claim → POST `/mcp` with `Authorization: Bearer <token>` → not 401 |
| `a bogus token is refused on /mcp` | POST `/mcp` `Authorization: Bearer nope` → 401 |
| `revoked token is refused` | Claim, revoke via `ctx.deviceRepository.revoke(id)`, POST `/mcp` with token → 401 |
| `claim is rate limited` | Issue a code, POST 21 times with distinct codes → the last is 429 (uses the limiter's 20/min default) |

### Task 3.9 — Auth-plugin unit tests

**File**: `app/src/test/kotlin/.../mcp/auth/BearerTokenAuthTest.kt` (extend)

**Setup**: existing `mockkStatic(android.util.Log::class)` pattern; drive the plugin via `testApplication` with
`installMcpBasePlugins` (add `import com.danielealbano.androidremotecontrolmcp.mcp.installMcpBasePlugins`).

| Test | Verifies |
|------|----------|
| `paired token accepted when validator returns true` | `validatePairedToken = { true }` (bearer/oauth off would open the server, so set `bearerTokenEnabled = true`, empty expected token) → a request carrying a token is not 401 |
| `paired token refused when validator returns false` | `validatePairedToken = { false }` → 401 |
| `paired validator not consulted for OPTIONS or excluded paths` | OPTIONS `/mcp` and `GET /health` reach the handler (validator spy not invoked) |

---

## User Story 4 — Phone UI: pairing-code card + paired-devices screen

**Why:** the code must be shown where the user already reads the connection details, and pairings must be
revocable from Settings — reusing the card/list idioms already in the app.

**Acceptance criteria:**
- [ ] The Server screen shows the pairing code (and Copy/New-code), only while the server is Running and an auth
      method is enabled; the card disappears once the code expires.
- [ ] Settings → Access has a "Paired devices" entry (shown when an auth method is enabled) opening a list.
- [ ] Each paired device shows name, created/last-used, and a revoke action with a confirm dialog.
- [ ] No stored setting is modified by viewing/revoking.

### Task 4.1 — Strings

**Action — modify** `app/src/main/res/values/strings.xml`, add:

```xml
    <string name="pairing_code_title">Pair with a code</string>
    <string name="pairing_code_desc">Enter this code in the client (wallet Device screen, or dai Mobile page) to pair — no token to copy.</string>
    <string name="pairing_code_expires">Expires %1$s</string>
    <string name="pairing_code_copy">Copy code</string>
    <string name="pairing_code_refresh">New code</string>
    <string name="paired_devices">Paired devices</string>
    <string name="paired_devices_title">Paired devices</string>
    <string name="paired_devices_empty">No paired devices. A client appears here after it spends a pairing code.</string>
    <string name="paired_devices_created">Paired %1$s</string>
    <string name="paired_devices_last_used">Last used %1$s</string>
    <string name="paired_devices_revoke">Revoke</string>
    <string name="paired_devices_revoke_dialog_title">Revoke device?</string>
    <string name="paired_devices_revoke_dialog_body">Revoking immediately invalidates this device\'s pairing token. It must pair again with a new code.</string>
    <string name="paired_devices_revoke_confirm">Revoke</string>
    <string name="paired_devices_revoke_cancel">Cancel</string>
    <string name="access_paired_devices_supporting">Clients that paired with a code</string>
```

**Definition of Done:**
- [ ] Every key is unique in the file; no existing string text is changed.

### Task 4.2 — ViewModels

**Action — create** `app/src/main/kotlin/.../ui/viewmodels/PairingViewModel.kt`. The exposed code **is cleared when it
expires** (the registry only nulls lazily on `current()`/`consume()`, which the UI never calls), so the card hides
promptly:

```kotlin
package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingCode
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingCodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PairingViewModel
    @Inject
    constructor(
        private val pairingCodeRegistry: PairingCodeRegistry,
    ) : ViewModel() {
        val pairingCode: StateFlow<PairingCode?> =
            pairingCodeRegistry.code
                .transformLatest { current ->
                    val remaining = current?.expiresAtMs?.minus(System.currentTimeMillis()) ?: 0L
                    if (current == null || remaining <= 0) {
                        emit(null)
                    } else {
                        emit(current)
                        delay(remaining)
                        emit(null)
                    }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), null)

        /** Mints a fresh code (the previous one stops working). */
        fun refresh() {
            pairingCodeRegistry.issue()
        }

        private companion object {
            const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
```

**Action — create** `app/src/main/kotlin/.../ui/viewmodels/PairedDevicesViewModel.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.PairedDeviceRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PairedDevicesViewModel
    @Inject
    constructor(
        private val repository: PairedDeviceRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val devices: StateFlow<List<PairedDevice>> =
            repository
                .observeDevices()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), emptyList())

        fun revoke(deviceId: String) {
            viewModelScope.launch(ioDispatcher) { repository.revoke(deviceId) }
        }

        private companion object {
            const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
```

### Task 4.3 — PairingCodeCard

**Action — create** `app/src/main/kotlin/.../ui/components/PairingCodeCard.kt`: an `ElevatedCard` with the title
(`R.string.pairing_code_title`), the description (`pairing_code_desc`), the code in
`MaterialTheme.typography.headlineSmall` with `letterSpacing = 4.sp`, the expiry (`pairing_code_expires` formatted
with `DateFormat.getTimeInstance(DateFormat.SHORT)`), and two `TextButton`s: Copy (`pairing_code_copy`, calls
`onCopy(code)`) and New code (`pairing_code_refresh`, calls `onRefresh`). Signature:

```kotlin
@Composable
fun PairingCodeCard(
    code: String,
    expiresAtMs: Long,
    onCopy: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Definition of Done:**
- [ ] Purely presentational (no ViewModel); code rendered as selectable `Text`.

### Task 4.4 — PairedDevicesScreen

**Action — create** `app/src/main/kotlin/.../ui/screens/settings/PairedDevicesScreen.kt`, mirroring
`OAuthClientsScreen.kt` (TopAppBar `R.string.paired_devices_title`; empty state `paired_devices_empty`; a
`LazyColumn` of rows `key = { it.id }`, each row: name (`it.name ?: it.id`), `paired_devices_created` /
`paired_devices_last_used` with `DateFormat.getDateInstance`, a Delete `IconButton` opening the revoke confirm
dialog using the `paired_devices_revoke_*` strings). ViewModel via `hiltViewModel<PairedDevicesViewModel>()`.

### Task 4.5 — Access screen entry + route

**Action — modify** `app/src/main/kotlin/.../ui/screens/settings/AccessSettingsScreen.kt`:

1. Add parameter `onNavigatePairedDevices: () -> Unit,` after `onNavigateClients`.
2. Add the new block **after** the `if (serverConfig.oauthEnabled) { ... }` block that contains the "Connected
   clients" `ListItem` (not inside it), with its own auth guard:

```kotlin
            if (serverConfig.oauthEnabled || serverConfig.bearerTokenEnabled) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.paired_devices)) },
                    supportingContent = { Text(stringResource(R.string.access_paired_devices_supporting)) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onNavigatePairedDevices),
                )
            }
```

**Action — modify** `app/src/main/kotlin/.../ui/navigation/Routes.kt`: add

```kotlin
    data object PairedDevices : SettingsRoute("settings/access/paired")
```

**Action — modify** `app/src/main/kotlin/.../ui/screens/SettingsScreen.kt`:

1. Pass `onNavigatePairedDevices = { navController.navigate(SettingsRoute.PairedDevices.route) }` to
   `AccessSettingsScreen`.
2. Add:

```kotlin
        composable(SettingsRoute.PairedDevices.route) {
            PairedDevicesScreen(onBack = { navController.popBackStack() })
        }
```

Add import for `PairedDevicesScreen`.

### Task 4.6 — Server screen card

**Action — modify** `app/src/main/kotlin/.../ui/screens/ServerScreen.kt`:

1. Add `pairingViewModel: PairingViewModel = hiltViewModel(),` to the composable parameters.
2. Collect:

```kotlin
    val pairingCode by pairingViewModel.pairingCode.collectAsStateWithLifecycle()
```

3. Immediately after the `ConnectionInfoCard(...)` call, add:

```kotlin
            val activePairingCode = pairingCode
            if (serverStatus is ServerStatus.Running &&
                (serverConfig.bearerTokenEnabled || serverConfig.oauthEnabled) &&
                activePairingCode != null
            ) {
                Spacer(Modifier.height(16.dp))
                PairingCodeCard(
                    code = activePairingCode.code,
                    expiresAtMs = activePairingCode.expiresAtMs,
                    onCopy = { value ->
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("pairing code", value))
                        android.widget.Toast
                            .makeText(context, R.string.copied_to_clipboard, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    },
                    onRefresh = { pairingViewModel.refresh() },
                )
            }
```

Add imports: `PairingCodeCard`, `PairingViewModel`. (`ServerStatus`, `context` are already available in the file.)

**Definition of Done:**
- [ ] Card hidden when the server is not Running, when both auth methods are off, when no code is active, or when the
      code has expired (ViewModel clears it).

---

## User Story 5 — Quality gates & final ground-up double-check (LAST)

**Why:** bring Part A to green and re-read everything from scratch.

### Task 5.1 — Quality gates

- [ ] `make lint` clean (ktlint + detekt), no suppressions added.
- [ ] Full unit + integration suite green, output tee'd to `/tmp/p66-*.log` (never re-run to re-grep).
- [ ] `./gradlew build` succeeds for both `gms` and `foss` flavors; no NEW warnings beyond the baseline on `main`.
- [ ] Manual QA (labelled; not a substitute for automated tests; requires a device/emulator):
  - Start the server with bearer enabled → a code appears on the Server screen; copy/New-code work; the code
    disappears after 10 minutes.
  - `curl -s -X POST http://127.0.0.1:8080/pair/claim -H 'content-type: application/json' -d '{"code":"<shown>"}'`
    returns a token; the code then disappears and a second claim 404s.
  - `POST /mcp` with the returned token succeeds; after revoking the device in Settings → Access → Paired devices,
    the same token 401s.

### Task 5.2 — Plan-compliance code review

- [ ] Spawn `code-reviewer` in plan-compliance mode; fix ALL findings; re-run until clean.

### Task 5.3 — Final ground-up double-check (LAST ITEM)

- [ ] Re-read each created/modified Part A file and confirm it matches its task and the final state is coherent.
- [ ] Confirm the frozen protocol contract matches `PairingRoutes.kt` exactly (`/pair/claim`, field names
      `code`/`name` → `token`/`device_id`/`name`).
- [ ] Confirm `/pair/claim` is in `excludedPaths` in BOTH `McpServer.kt` and `McpIntegrationTestHelper.kt`.
- [ ] Confirm no plaintext token is persisted or logged; only SHA-256.
- [ ] Confirm `McpServer` has exactly 6 constructor params and `PairedDeviceRepositoryImpl` ≤ 11 functions.
- [ ] Confirm no file outside the scope boundary was altered.

---

## Part B — client integration (separate repos: `../wallet`, `../dai`)

> Implement as **separate branches/PRs in each repo**, authored with each repo's own conventions and quality gates.
> The frozen protocol contract above is the interface; do not re-open it here. The steps below are the exact changes.

## User Story B1 — wallet Device screen spends the code

**Files:** `../wallet/packages/mobile-control/src/android-client.ts` (modify), `.../src/index.ts` (already
`export *`, verify), `.../test/android-client.test.ts` (modify);
`../wallet/expo-app/sources/screens/device/DeviceScreen.tsx` (modify),
`../wallet/expo-app/sources/lib/i18n.ts` (modify all 12 locales), `../wallet/docs/p2p.md` (modify §12).

**Action** — add to `android-client.ts`:

```ts
export interface ClaimedPairing {
  baseUrl: string;
  token: string;
  deviceId?: string;
}

/** Spend a short single-use pairing code for a per-device bearer token (android-remote-control-mcp). */
export async function claimPairing(
  baseUrl: string,
  code: string,
  opts: { name?: string; timeoutMs?: number } = {},
): Promise<ClaimedPairing> {
  const base = baseUrl.trim().replace(/\/+$/, "");
  const res = await fetch(`${base}/pair/claim`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: code.trim(), ...(opts.name ? { name: opts.name } : {}) }),
    signal: AbortSignal.timeout(opts.timeoutMs ?? 30_000),
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => "");
    throw new Error(`pair/claim: ${res.status}${detail ? ` — ${detail.slice(0, 160)}` : ""}`);
  }
  const out = (await res.json()) as { token?: string; device_id?: string; name?: string };
  if (!out.token) throw new Error("pair/claim: no token in response");
  return { baseUrl: base, token: out.token, ...(out.device_id ? { deviceId: out.device_id } : {}) };
}
```

**Action** — `DeviceScreen.tsx`: add a `code` state + a "Pairing code" `TextInput`
(`testID="device-pair-code"`). In `connect`, when `code.trim()` is non-empty, call
`claimPairing(endpoint.trim(), code.trim(), { name: 'wallet' })`, store the returned token via `deviceStore.set` and
`setToken`, **and use the returned token through a local variable** (not the `token` state, which is stale inside the
existing `connect` closure) for the `AndroidMcpClient.connect` call on that pass. Clear the code field on success.
Keep the manual token field as the fallback. Add i18n keys `device.pairCode`, `device.pairCodeHint`,
`device.pairedCode`, `device.pairCodeFailed` to **all 12** locale dicts (the `i18n.test.ts` key-parity test enforces
this).

**Action** — update `docs/p2p.md` §12 so the handover text is "the app shows a short single-use pairing code; the
wallet spends it (`claimPairing`) for a per-device token", and reconcile the "neither is a credential the engine
could use elsewhere… dies when the token is rotated" sentence with per-device revocation.

**Tests**: `packages/mobile-control/test/android-client.test.ts` — stub global `fetch`; assert `claimPairing` POSTs
`{base}/pair/claim` with `{code, name}`, returns the token, and rejects on non-2xx / missing token.

## User Story B2 — dai Mobile page spends the code

**Files:** `../dai/services/dai/src/mobile.ts` (modify), `src/server.ts` (modify),
`test/mobile.test.ts` (modify); `../dai/apps/web/src/lib/api.ts` (modify),
`src/components/MobileScreen.tsx` (modify), `src/lib/i18n.ts` (modify all 12 locales).

**Action** — add to `mobile.ts`:

```ts
export interface ClaimedMobilePairing {
  token: string;
  deviceId?: string;
}

/** Spend a short single-use pairing code for a per-device bearer token. */
export async function claimMobilePairing(
  baseUrl: string,
  code: string,
  opts: { name?: string; timeoutMs?: number } = {},
): Promise<ClaimedMobilePairing> {
  const base = trimBase(baseUrl);
  const res = await fetch(`${base}/pair/claim`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: code.trim(), ...(opts.name ? { name: opts.name } : {}) }),
    signal: AbortSignal.timeout(opts.timeoutMs ?? 30_000),
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => "");
    throw new Error(`pair/claim: ${res.status}${detail ? ` — ${detail.slice(0, 160)}` : ""}`);
  }
  const out = (await res.json()) as { token?: string; device_id?: string };
  if (!out.token) throw new Error("pair/claim: no token in response");
  return { token: out.token, ...(out.device_id ? { deviceId: out.device_id } : {}) };
}
```

**Action** — `server.ts`: add `POST /mobile/pair` in the M9 block (after `PUT /mobile/config`): body
`{ did, base_url, code, device?, dialect?, tool_prefix? }`; call `claimMobilePairing(base_url, code)`; build the
`MobileConfig` (token = claimed token, dialect default `android-mcp`), verify with `mobile.mobileListDevices(cfg)`,
then `creds.set(did, "mobile", JSON.stringify(cfg))`; return `{ configured, base_url, device, devices }` (same
shape as `PUT /mobile/config`). 400 when `did`/`base_url`/`code` missing; 404 passthrough for an invalid code.

**Action** — `apps/web/src/lib/api.ts`: add `pairMobile(did, base_url, code)` next to `saveMobileConfig`
(`POST ${DAI_URL}/mobile/pair`). `MobileScreen.tsx`: add a pairing-code input (`data-testid="mobile-pair-code"`);
`pair()` calls `pairMobile` when the code is non-empty, else the existing `saveMobileConfig`. **Relax the existing
early-return guard** so a code-only submission is allowed: it currently requires `baseUrl` AND (`token` OR signer);
it must also accept a non-empty `code` (e.g. `if (!baseUrl.trim() || (!code.trim() && !token.trim() && !status.keys?.MOBILEMCP_SIGNER)) return;`).
Add `mob.pairCode`, `mob.pairCodeHint`, `mob.pairedCode`, `mob.pairCodeFailed` to **all 12** i18n dicts.

**Tests**: `services/dai/test/mobile.test.ts` — new `describe("mobile pairing claim")` stubbing `fetch`; assert
`POST {base}/pair/claim` with `{code}`, returned token, and non-2xx rejection.

---

## Definition of Done (whole plan)
- [ ] Part A: every task and acceptance criterion met; gates green; code review clean; final double-check complete.
- [ ] Part B: each repo's branch merged with its own gates green.
- [ ] The frozen protocol contract is matched byte-for-byte by the phone, the wallet client, and the dai client.
