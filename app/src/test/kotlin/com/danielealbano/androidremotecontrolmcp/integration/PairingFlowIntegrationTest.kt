package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PairingFlowIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    private fun claimBody(code: String): String = """{"code":"$code"}"""

    private val initializeBody =
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-06-18","capabilities":{},
        "clientInfo":{"name":"pairing-test","version":"1.0"}}}
        """.trimIndent()

    private suspend fun claimToken(
        context: McpIntegrationTestHelper.PairingTestContext,
        code: String,
    ): String {
        val response =
            context.httpClient.post("/pair/claim") {
                contentType(ContentType.Application.Json)
                setBody(claimBody(code))
            }
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!
            .jsonPrimitive.content
    }

    private suspend fun mcpInitialize(
        context: McpIntegrationTestHelper.PairingTestContext,
        bearer: String,
    ) = context.httpClient.post("/mcp") {
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $bearer")
        header("Accept", "application/json, text/event-stream")
        setBody(initializeBody)
    }

    @Test
    @DisplayName("claim with a valid code returns a token and pairs a device")
    fun claimValidCode() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                val issued = context.codeRegistry.issue()

                val response =
                    context.httpClient.post("/pair/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimBody(issued.code))
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(64, body["token"]!!.jsonPrimitive.content.length)
                assertEquals("pd-", body["device_id"]!!.jsonPrimitive.content.take(3))
                assertEquals(1, context.deviceRepository.getDevices().size)
            }
        }

    @Test
    @DisplayName("claim with an invalid code is 404")
    fun claimInvalidCode() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                val response =
                    context.httpClient.post("/pair/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimBody("BADCODE1"))
                    }

                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals(0, context.deviceRepository.getDevices().size)
            }
        }

    @Test
    @DisplayName("claim without a code is 400")
    fun claimWithoutCode() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                val response =
                    context.httpClient.post("/pair/claim") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

    @Test
    @DisplayName("a code cannot be spent twice")
    fun codeSingleUse() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                val issued = context.codeRegistry.issue()

                val first =
                    context.httpClient.post("/pair/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimBody(issued.code))
                    }
                val second =
                    context.httpClient.post("/pair/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimBody(issued.code))
                    }

                assertEquals(HttpStatusCode.OK, first.status)
                assertEquals(HttpStatusCode.NotFound, second.status)
                assertEquals(1, context.deviceRepository.getDevices().size)
            }
        }

    @Test
    @DisplayName("a claimed token authorizes an /mcp initialize")
    fun claimedTokenAuthorizesMcp() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                val issued = context.codeRegistry.issue()
                val token = claimToken(context, issued.code)

                assertNotEquals(HttpStatusCode.Unauthorized, mcpInitialize(context, token).status)
            }
        }

    @Test
    @DisplayName("a bogus token is refused on /mcp")
    fun bogusTokenRefused() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                assertEquals(HttpStatusCode.Unauthorized, mcpInitialize(context, "not-a-real-token").status)
            }
        }

    @Test
    @DisplayName("revoked token is refused")
    fun revokedTokenRefused() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                val issued = context.codeRegistry.issue()
                val response =
                    context.httpClient.post("/pair/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimBody(issued.code))
                    }
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val token = body["token"]!!.jsonPrimitive.content
                val deviceId = body["device_id"]!!.jsonPrimitive.content

                context.deviceRepository.revoke(deviceId)

                assertEquals(HttpStatusCode.Unauthorized, mcpInitialize(context, token).status)
            }
        }

    @Test
    @DisplayName("claim is rate limited")
    fun claimRateLimited() =
        runTest {
            McpIntegrationTestHelper.withPairingTestApplication { context ->
                var lastStatus = HttpStatusCode.OK
                repeat(21) {
                    lastStatus =
                        context.httpClient
                            .post("/pair/claim") {
                                contentType(ContentType.Application.Json)
                                setBody(claimBody("WRONGXYZ"))
                            }.status
                }

                assertEquals(HttpStatusCode.TooManyRequests, lastStatus)
            }
        }
}
