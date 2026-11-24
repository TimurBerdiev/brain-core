package dev.braincore.boss

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OverviewHttpTest {
    private fun commit(n: Int) = JSONObject().put("sha", "$n").put("html_url", "https://github.com/r/one/commit/$n")
        .put("commit", JSONObject().put("message", "Change $n").put("committer", JSONObject().put("date", "2026-11-14T17:00:00Z")))

    @Test fun paginatesCommitsAndKeepsThemWhenEventAccessIsDenied() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer token", request.header("Authorization"))
            paths += request.url.encodedPath
            val events = request.url.encodedPath.endsWith("/events")
            val page = request.url.queryParameter("page")
            val body = when {
                events -> """{"message":"Denied"}"""
                page == "1" -> JSONArray((1..100).map(::commit)).toString()
                else -> JSONArray(listOf(commit(101))).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (events) 403 else 200)
                .message(if (events) "Forbidden" else "OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val (items, warnings) = GitHub("token", "r/one", client = client).activity("2026-11-14T00:00:00Z")
        assertEquals(101, items.size)
        assertEquals(3, paths.size)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("r/one task events"))
        assertTrue(items.all { it.repo == "r/one" })
    }
}
