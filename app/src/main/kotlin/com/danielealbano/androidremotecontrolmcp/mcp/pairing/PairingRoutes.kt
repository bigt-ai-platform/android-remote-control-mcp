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
data class PairClaimRequest(
    val code: String = "",
    val name: String? = null,
)

/** Success body of `POST /pair/claim` (token shown once). */
@Serializable
data class PairClaimResponse(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    val name: String? = null,
)

/** Error body of `POST /pair/claim`. */
@Serializable
data class PairErrorResponse(
    val error: String,
    val message: String,
)

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
        val address = clientKey(call)
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

/**
 * The rate-limit identity for a claim attempt. Behind a local tunnel/proxy (cloudflared/ngrok)
 * every client arrives from loopback, so keying on the peer address would collapse all clients
 * into one bucket; use the proxy-set client IP instead. Direct (non-local) peers are keyed by
 * their transport address; `X-Forwarded-For` is only trusted from a loopback peer, where the
 * tunnel — not the client — supplies it.
 */
private fun clientKey(call: ApplicationCall): String {
    val peer = call.request.local.remoteAddress
    if (peer != "127.0.0.1" && peer != "::1") return peer
    val forwarded =
        call.request.headers["CF-Connecting-IP"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: call.request.headers["X-Forwarded-For"]
                ?.split(",")
                ?.lastOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    return forwarded ?: peer
}

private const val NAME_MAX = 64

private const val MAX_BODY_BYTES = 4096L
