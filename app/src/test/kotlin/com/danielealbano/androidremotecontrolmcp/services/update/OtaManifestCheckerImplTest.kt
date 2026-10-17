package com.danielealbano.androidremotecontrolmcp.services.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OtaManifestCheckerImpl")
class OtaManifestCheckerImplTest {
    private fun checkerWith(handler: () -> HttpClient): OtaManifestCheckerImpl =
        OtaManifestCheckerImpl(UnconfinedTestDispatcher()).apply { clientProvider = handler }

    private fun jsonClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): () -> HttpClient =
        {
            HttpClient(MockEngine) {
                engine { addHandler { respondJson(body, status) } }
            }
        }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val validBody = """{"versionName":"1.11.0","versionCode":2011000,"url":"https://x/a.apk"}"""

    @Test
    fun `returns the manifest on success`() =
        runTest {
            val result = checkerWith(jsonClient(validBody)).fetchLatestManifest()

            assertEquals("1.11.0", result?.versionName)
            assertEquals(2011000, result?.versionCode)
            assertEquals("https://x/a.apk", result?.downloadUrl)
        }

    @Test
    fun `ignores the sha256 and mandatory fields`() =
        runTest {
            val body =
                """{"versionName":"1.11.0","versionCode":2011000,"url":"https://x/a.apk",""" +
                    """"sha256":"deadbeef","mandatory":true}"""

            assertEquals(2011000, checkerWith(jsonClient(body)).fetchLatestManifest()?.versionCode)
        }

    @Test
    fun `requests the release manifest for the selected flavor`() =
        runTest {
            var requestedUrl: String? = null
            val checker =
                checkerWith {
                    HttpClient(MockEngine) {
                        engine {
                            addHandler { request ->
                                requestedUrl = request.url.toString()
                                respondJson(validBody)
                            }
                        }
                    }
                }.apply { flavor = "foss" }

            checker.fetchLatestManifest()

            assertEquals(
                "https://s3.bigt.ai/apps/android-remote-control-mcp/apk/mobile-agent-foss-release-latest.json",
                requestedUrl,
            )
        }

    @Test
    fun `manifestUrl is the release manifest under the bucket base`() {
        assertEquals(
            "https://s3.bigt.ai/apps/android-remote-control-mcp/apk/mobile-agent-gms-release-latest.json",
            OtaManifestCheckerImpl.manifestUrl("gms"),
        )
    }

    @Test
    fun `returns null on non-2xx`() =
        runTest {
            val checker = checkerWith(jsonClient("""{"message":"Not Found"}""", HttpStatusCode.NotFound))
            assertNull(checker.fetchLatestManifest())
        }

    @Test
    fun `returns null on malformed body`() =
        runTest {
            assertNull(checkerWith(jsonClient("this is not json")).fetchLatestManifest())
        }

    @Test
    fun `returns null when versionCode is missing`() =
        runTest {
            val body = """{"versionName":"1.11.0","url":"https://x/a.apk"}"""
            assertNull(checkerWith(jsonClient(body)).fetchLatestManifest())
        }

    @Test
    fun `returns null when versionName is missing`() =
        runTest {
            val body = """{"versionCode":2011000,"url":"https://x/a.apk"}"""
            assertNull(checkerWith(jsonClient(body)).fetchLatestManifest())
        }

    @Test
    fun `returns null when versionName is blank`() =
        runTest {
            val body = """{"versionName":"   ","versionCode":2011000,"url":"https://x/a.apk"}"""
            assertNull(checkerWith(jsonClient(body)).fetchLatestManifest())
        }

    @Test
    fun `returns null when url is missing`() =
        runTest {
            val body = """{"versionName":"1.11.0","versionCode":2011000}"""
            assertNull(checkerWith(jsonClient(body)).fetchLatestManifest())
        }

    @Test
    fun `rejects a non-http(s) url scheme`() =
        runTest {
            val body = """{"versionName":"1.11.0","versionCode":2011000,"url":"javascript:alert(1)"}"""
            assertNull(checkerWith(jsonClient(body)).fetchLatestManifest())
        }

    @Test
    fun `returns null when the request throws`() =
        runTest {
            val checker =
                checkerWith {
                    HttpClient(MockEngine) {
                        engine { addHandler { throw IOException("offline") } }
                    }
                }
            assertNull(checker.fetchLatestManifest())
        }

    @Test
    fun `sends a User-Agent header`() =
        runTest {
            var userAgent: String? = null
            val checker =
                checkerWith {
                    HttpClient(MockEngine) {
                        engine {
                            addHandler { request ->
                                userAgent = request.headers[HttpHeaders.UserAgent]
                                respondJson(validBody)
                            }
                        }
                    }
                }

            checker.fetchLatestManifest()

            assertTrue(userAgent?.isNotBlank() == true)
        }
}
