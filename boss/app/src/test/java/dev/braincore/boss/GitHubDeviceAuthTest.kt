package dev.braincore.boss

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GitHubDeviceAuthTest {
    private val registration = GitHubAppRegistration("Iv23Example", "boss-example")
    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private val waits = mutableListOf<Long>()
    private var time = 0L

    private fun auth(vararg responses: String, wait: suspend (Long) -> Unit = { waits += it; time += it }): GitHubDeviceAuth {
        val queue = ArrayDeque(responses.toList())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val body = req.body as FormBody
            assertEquals("github.com", req.url.host)
            assertEquals("POST", req.method)
            assertEquals("application/json", req.header("Accept"))
            requests += req.url.encodedPath to (0 until body.size).associate { body.name(it) to body.value(it) }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(queue.removeFirst().toResponseBody()).build()
        }.build()
        return GitHubDeviceAuth(registration, http, elapsed = { time }, now = { 1_000 }, wait = wait)
    }

    private fun deviceResponse(expires: Int = 900) =
        """{"user_code":"ABCD-1234","device_code":"device","expires_in":$expires,"interval":5}"""

    private val tokenResponse = """{"access_token":"ghu_access","refresh_token":"ghr_next","expires_in":28800}"""

    @Test fun waitsForApprovalAndBumpsTheIntervalWhenGitHubAsks() = runBlocking {
        val auth = auth(deviceResponse(), """{"error":"authorization_pending"}""",
            """{"error":"slow_down","interval":12}""", tokenResponse)
        val code = auth.start()
        assertEquals("ABCD-1234", code.userCode)
        val tokens = auth.authorize(code)
        assertEquals(listOf(5_000L, 5_000L, 12_000L), waits)
        assertEquals("ghu_access", tokens.access)
        assertEquals("ghr_next", tokens.refresh)
        assertEquals(28_801_000L, tokens.expiresAt)
        assertEquals("/login/device/code" to mapOf("client_id" to "Iv23Example", "scope" to "offline_access"), requests.first())
        assertTrue(requests.drop(1).all {
            it.first == "/login/oauth/access_token" && it.second == mapOf(
                "client_id" to "Iv23Example", "device_code" to "device", "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )
        })
    }

    @Test fun stopsPollingAtTheDeadline() = runBlocking {
        val auth = auth(deviceResponse(expires = 7), """{"error":"authorization_pending"}""")
        val code = auth.start()
        val error = runCatching { auth.authorize(code) }.exceptionOrNull()
        assertTrue(error is GitHubAuthException && error.message!!.contains("expired"))
        assertEquals(listOf(5_000L, 2_000L), waits)
        assertEquals(2, requests.size)
    }

    @Test fun denialStopsPolling() = runBlocking {
        val auth = auth(deviceResponse(), """{"error":"access_denied"}""")
        val code = auth.start()
        val error = runCatching { auth.authorize(code) }.exceptionOrNull()
        assertTrue(error is GitHubAuthException && error.message!!.contains("declined"))
        assertEquals(2, requests.size)
    }

    @Test fun cancellingAbortsPendingPolling() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val auth = auth(deviceResponse(), wait = { waiting.complete(Unit); delay(Long.MAX_VALUE) })
        val code = auth.start()
        val polling = async { auth.authorize(code) }
        waiting.await()
        polling.cancelAndJoin()
        assertEquals(1, requests.size)
    }

    @Test fun rotatesWithoutClientSecretAndTellsUserWhenReauthorizationIsNeeded() = runBlocking {
        val auth = auth(tokenResponse, """{"error":"bad_refresh_token","error_description":"Not for UI"}""")
        assertEquals("ghr_next", auth.refresh("ghr_previous").refresh)
        assertEquals("/login/oauth/access_token" to mapOf(
            "client_id" to "Iv23Example", "grant_type" to "refresh_token", "refresh_token" to "ghr_previous",
        ), requests.first())
        val error = runCatching { auth.refresh("ghr_next") }.exceptionOrNull()
        assertTrue(error is GitHubAuthException && error.reconnect && error.message!!.contains("Reconnect"))
        assertFalse(error!!.message!!.contains("Not for UI"))
    }
}
