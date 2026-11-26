package dev.braincore.boss

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class RepositoryAccessTest {
    private val home = "alex/brain"
    private val project = "alex/studio"
    private val registry = registryResponse("""
        repos:
          studio:
            remote: https://github.com/alex/studio.git
            tasks: assigned
          archived: {remote: 'https://github.com/alex/archived.git', tasks: false}
    """.trimIndent())

    private fun registryResponse(yaml: String) = JSONObject()
        .put("content", Base64.getEncoder().encodeToString(yaml.toByteArray()))
        .put("sha", "abc").toString()

    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test response").body(body.toResponseBody())

    @Test fun checksRegistryAndEachPermissionWithTheSameTokenUsingOnlyReads() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            when (request.url.encodedPath) {
                "/repos/$home/contents/repos.yaml" -> response(request, 200, registry)
                "/repos/$project/branches" -> response(request, 403, """{"message":"Resource not accessible"}""")
                else -> response(request, 200, "[]")
            }.build()
        }.build()
        val report = checkRepositoryAccess(GitHub("draft-token", home, client = client), emptyList()) {
            GitHub("draft-token", it, client = client)
        }

        assertNull(report.registryProblem)
        val (workspace, taskRepo) = report.repositories
        assertEquals(listOf(home, project), report.repositories.map { it.repo.fullName })
        assertEquals("studio", taskRepo.repo.name)
        assertEquals(ReadAccess(), workspace.issues)
        assertEquals(ReadAccess(), workspace.contents)
        assertEquals(ReadAccess(), taskRepo.issues)
        assertTrue(taskRepo.contents!!.denied)
        assertTrue(taskRepo.contents.problem!!.contains("403"))
        assertEquals(5, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.header("Authorization") == "Bearer draft-token" })
        assertEquals(listOf(
            "/repos/$home/contents/repos.yaml", "/repos/$home/issues", "/repos/$home/branches",
            "/repos/$project/issues", "/repos/$project/branches",
        ), requests.map { it.url.encodedPath })
    }

    @Test fun fallsBackToCachedReposAndDistinguishesAnOutageFromDeniedAccess() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/repos/$home/contents/repos.yaml" -> response(request, 404, "{}").build()
                "/repos/$project/issues" -> throw IOException("network unavailable")
                "/repos/$project/branches" -> response(request, 403, "{}").header("X-RateLimit-Remaining", "0").build()
                else -> response(request, 200, "[]").build()
            }
        }.build()
        val cached = TaskRepo("studio", project)
        val report = checkRepositoryAccess(GitHub("token", home, client = client), listOf(cached)) {
            GitHub("token", it, client = client)
        }

        assertTrue(report.registryProblem!!.contains("repos.yaml"))
        val taskRepo = report.repositories.last()
        assertEquals(cached, taskRepo.repo)
        assertFalse(taskRepo.issues!!.denied)
        assertTrue(taskRepo.issues.problem!!.contains("connection"))
        assertFalse(taskRepo.contents!!.denied)
        assertTrue(taskRepo.contents.problem!!.contains("rate limit"))
    }

    @Test fun appGrantMustIncludeTheRepositoryEvenIfItsPublicIssuesCanBeRead() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer refreshed-token", request.header("Authorization"))
            val path = request.url.encodedPath
            requests += path
            val body = when (path) {
                "/user/installations" -> """{"installations":[{"id":1,"account":{"login":"another"}},{"id":2,"account":{"login":"ALEX"}}]}"""
                "/user/installations/2/repositories" -> """{"repositories":[{"full_name":"alex/STUDIO"}]}"""
                else -> "[]"
            }
            response(request, 200, body).build()
        }.build()
        val brain = GitHub("", home, client = client, tokenProvider = { "refreshed-token" })
        brain.check()
        assertFalse(brain.hasAppAccess())
        assertTrue(GitHub("", project, client = client, tokenProvider = { "refreshed-token" }).hasAppAccess())
        assertFalse("/user/installations/1/repositories" in requests)
    }
}
