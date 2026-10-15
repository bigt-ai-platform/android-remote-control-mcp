package com.danielealbano.androidremotecontrolmcp.services.update

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The latest published build described by the OTA manifest, as written by `deploy.apk.sh`:
 * `{"versionName":…, "versionCode":…, "url":…, "sha256":…, "mandatory":…}`.
 *
 * @property versionName The release's `versionName` (for display; e.g. `1.11.0`).
 * @property versionCode The monotonic release `versionCode` used to decide if the build is newer.
 * @property downloadUrl The public APK download URL opened when the user accepts the update.
 */
data class OtaManifest(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
)

/** Fetches the latest published build's OTA manifest from the project's public object-storage bucket. */
interface OtaManifestChecker {
    /**
     * Returns the latest published manifest, or null on ANY failure (offline, non-2xx, malformed body,
     * missing fields). This call NEVER throws — a failed check must never surface to the user.
     */
    suspend fun fetchLatestManifest(): OtaManifest?
}

@Singleton
class OtaManifestCheckerImpl
    @Inject
    constructor(
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : OtaManifestChecker {
        // One OkHttp-backed client reused across checks (this is a @Singleton). Lazy so unit tests, which
        // override clientProvider before any call, never build a real engine. Overridable for tests.
        // Intentionally not closed: it lives for the process lifetime, like the singleton that owns it.
        private val sharedClient: HttpClient by lazy { buildClient() }
        internal var clientProvider: () -> HttpClient = { sharedClient }

        // The distribution flavor selects which manifest to read (`gms` vs `foss`). Overridable for tests.
        internal var flavor: String = BuildConfig.FLAVOR

        override suspend fun fetchLatestManifest(): OtaManifest? =
            withContext(ioDispatcher) {
                try {
                    val response =
                        clientProvider().get(manifestUrl(flavor)) {
                            header(HttpHeaders.UserAgent, USER_AGENT)
                        }
                    if (!response.status.isSuccess()) {
                        Logger.w(TAG, "Update check returned HTTP ${response.status.value}")
                        return@withContext null
                    }
                    val dto = json.decodeFromString(OtaManifestDto.serializer(), response.bodyAsText())
                    val versionName = dto.versionName?.takeIf { it.isNotBlank() } ?: return@withContext null
                    val versionCode = dto.versionCode ?: return@withContext null
                    // The URL is a network-derived value later handed to ACTION_VIEW — accept only http(s)
                    // so a tampered/unexpected payload can never smuggle in another scheme (intent:, javascript:).
                    val url = dto.url?.takeIf { isWebUrl(it) } ?: return@withContext null
                    OtaManifest(versionName, versionCode, url)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Message only (no response body) — the manifest payload is not sensitive, but keeping
                    // logs terse avoids noise; the check simply fails closed.
                    Logger.w(TAG, "Update check failed: ${e.message}")
                    null
                }
            }

        private fun isWebUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

        private fun buildClient(): HttpClient =
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                }
            }

        @Serializable
        private data class OtaManifestDto(
            val versionName: String? = null,
            val versionCode: Int? = null,
            val url: String? = null,
        )

        companion object {
            private const val TAG = "MCP:UpdateChecker"
            private const val BASE_URL = "https://minio-s1001.bigt.ai/mobile-agent"
            private const val USER_AGENT = "android-remote-control-mcp"
            private const val REQUEST_TIMEOUT_MS = 15_000L
            private const val CONNECT_TIMEOUT_MS = 10_000L
            private val json = Json { ignoreUnknownKeys = true }

            /** The OTA manifest object name for a distribution flavor (the `deploy.apk.sh` release manifest). */
            internal fun manifestUrl(flavor: String): String = "$BASE_URL/mobile-agent-$flavor-release-latest.json"
        }
    }
