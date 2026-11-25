package dev.braincore.boss

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class GitHubAppRegistration(val clientId: String, slug: String) {
    val configured = clientId.isNotBlank() && slug.matches(Regex("[a-z0-9-]+"))
    val installUrl = "https://github.com/apps/$slug/installations/new"

    companion object {
        val current = GitHubAppRegistration(BuildConfig.GITHUB_APP_CLIENT_ID, BuildConfig.GITHUB_APP_SLUG)
    }
}

internal class DeviceCode(
    val userCode: String,
    val deviceCode: String,
    val intervalMillis: Long,
    val deadlineMillis: Long,
)

internal class GitHubAppTokens(
    val access: String,
    val refresh: String,
    val expiresAt: Long,
) {
    fun needsRefresh(now: Long) = expiresAt - now <= 60_000L

    fun toJson() = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt)

    companion object {
        fun fromJson(json: JSONObject) =
            GitHubAppTokens(json.getString("access"), json.getString("refresh"), json.getLong("expiresAt"))
    }
}

internal class GitHubAuthException(message: String, val reconnect: Boolean = false) : IOException(message)

internal class GitHubDeviceAuth(
    private val registration: GitHubAppRegistration,
    private val http: OkHttpClient = client,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val now: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun start(): DeviceCode {
        val result = post("device/code", "scope" to "offline_access")
        result.authError()?.let { throw authFailure(it) }
        return DeviceCode(
            result.requiredString("user_code"), result.requiredString("device_code"),
            result.seconds("interval"), elapsed() + result.seconds("expires_in"),
        )
    }

    suspend fun authorize(code: DeviceCode): GitHubAppTokens {
        var interval = code.intervalMillis
        while (true) {
            val remaining = code.deadlineMillis - elapsed()
            if (remaining <= 0) throw authFailure("expired_token")
            wait(minOf(interval, remaining))
            if (elapsed() >= code.deadlineMillis) throw authFailure("expired_token")
            val result = post(
                "oauth/access_token", "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )
            when (val error = result.authError()) {
                null -> return result.tokens()
                "authorization_pending" -> Unit
                "slow_down" -> interval = maxOf(interval + 5_000L, result.optLong("interval") * 1_000L)
                else -> throw authFailure(error)
            }
        }
    }

    suspend fun refresh(refresh: String): GitHubAppTokens {
        val result = post("oauth/access_token", "grant_type" to "refresh_token", "refresh_token" to refresh)
        result.authError()?.let { throw authFailure(it) }
        return result.tokens()
    }

    private fun JSONObject.tokens() =
        GitHubAppTokens(requiredString("access_token"), requiredString("refresh_token"), now() + seconds("expires_in"))

    private fun JSONObject.seconds(key: String): Long = optLong(key).takeIf { it in 1..31_536_000L }?.times(1_000L)
        ?: throw GitHubAuthException("GitHub returned an invalid sign-in response. Try again.")

    private fun JSONObject.requiredString(key: String): String = optString(key).takeUnless { isNull(key) || it.isBlank() }
        ?: throw GitHubAuthException("GitHub returned an incomplete sign-in response. Try again.")

    private fun JSONObject.authError() = optString("error").ifBlank { null }

    private fun authFailure(error: String): GitHubAuthException = when (error) {
        "access_denied" -> GitHubAuthException("GitHub authorization was declined. Try connecting again.")
        "expired_token", "token_expired", "bad_verification_code", "incorrect_device_code" ->
            GitHubAuthException("The sign-in code expired. Connect with GitHub to get a new code.")
        "bad_refresh_token" -> GitHubAuthException("GitHub sign-in expired. Reconnect in Settings.", reconnect = true)
        "device_flow_disabled", "incorrect_client_credentials" ->
            GitHubAuthException("This build's GitHub App isn't configured for device sign-in.")
        "unverified_user_email" -> GitHubAuthException("Verify your primary email on GitHub, then try again.")
        else -> GitHubAuthException("GitHub couldn't authorize Boss. Try connecting again.")
    }

    private suspend fun post(path: String, vararg parameters: Pair<String, String>): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val body = FormBody.Builder().add("client_id", registration.clientId).apply {
                parameters.forEach { (key, value) -> add(key, value) }
            }.build()
            val request = Request.Builder().url("https://github.com/login/$path")
                .header("Accept", "application/json").post(body).build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                            if (json == null || (!it.isSuccessful && json.authError() == null)) {
                                throw GitHubAuthException("GitHub sign-in is unavailable (${it.code}). Try again.")
                            }
                            json
                        }
                    }
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }

    companion object {
        const val VERIFICATION_URL = "https://github.com/login/device"
        private val client = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
}
