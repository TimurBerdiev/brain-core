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

class SearchTest {
    private fun issue(n: Int, title: String, body: String = "", state: String = "open", reason: String = "") =
        Issue(n, title, body, emptyList(), state, "2026-11-08T10:00:00Z", "", stateReason = reason)

    private val tasks = listOf(
        issue(70, "Export analytics"),
        issue(170, "Fix STORAGE", "Export retry after a network failure", "closed", "completed"),
        issue(7, "Old export experiment", state = "closed", reason = "not_planned"),
        issue(80, "Other task", "The answer is 70"),
    )

    @Test fun substringIgnoresCaseAndIncludesEveryStatus() {
        assertEquals(setOf(70, 170, 7), searchIssues(tasks, "  ExPoRt  ").map { it.number }.toSet())
        assertEquals(listOf(170), searchIssues(tasks, "network failure").map { it.number })
        assertEquals(listOf(170), searchIssues(tasks, "storage").map { it.number })
        assertTrue(searchIssues(tasks, "does not exist").isEmpty())
    }

    @Test fun numberSearchRanksExactNumberFirstAndSupportsHashPrefix() {
        assertEquals(listOf(70, 170, 80), searchIssues(tasks, "70").map { it.number })
        assertEquals(listOf(70, 170), searchIssues(tasks, "#70").map { it.number })
        assertEquals(7, searchIssues(tasks, "7").first().number)
    }

    @Test fun qualifiedSearchSelectsTheRepoWhenNumbersCoincide() {
        val home = issue(70, "Mentions studio#70")
        val studio = issue(70, "Fix export", state = "closed", reason = "completed")
            .copy(repo = "alex/studio", repoAlias = "studio")
        val tasks = listOf(home, studio, studio.copy(number = 701))
        assertEquals(listOf(studio), searchIssues(tasks, "studio#70"))
        assertEquals(listOf(studio), searchIssues(tasks, " ALEX/STUDIO#70 "))
        assertTrue(searchIssues(tasks, "unknown#70").isEmpty())
        assertEquals(setOf(home, studio), searchIssues(tasks, "#70").take(2).toSet())
    }

    @Test fun openingAndAnsweringAQualifiedTaskUsesTheFullRepositoryName() = runBlocking {
        val home = issue(70, "Home task")
        val remote = issue(70, "Fix export").copy(repo = "alex/studio", repoAlias = "studio")
        val (repo, number) = splitKey(searchIssues(listOf(home, remote), "studio#70").single().key)
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(GitHub.toJson(remote.copy(repo = "", repoAlias = "")).toString().toResponseBody()).build()
        }.build()
        val github = GitHub("token", repo, tag = repo, repoAlias = "studio", client = client)
        val opened = github.issue(number)
        github.comment(number, answerComment("Continue?", "Yes", ""))
        github.setStatus(opened, "ready")

        assertEquals(remote, opened)
        assertEquals(listOf("GET", "POST", "PATCH"), requests.map { it.method })
        assertEquals(listOf(
            "/repos/alex/studio/issues/70",
            "/repos/alex/studio/issues/70/comments",
            "/repos/alex/studio/issues/70",
        ), requests.map { it.url.encodedPath })
    }

    @Test fun emptyQueryShowsAllIssuesNewestFirstWithoutDuplicates() {
        val latest = tasks[0].copy(updatedAt = "2026-11-09T10:00:00Z")
        assertEquals(listOf(70, 170, 80, 7), searchIssues(listOf(latest) + tasks, " ").map { it.number })
    }

    @Test fun githubSearchCollectionIncludesDroppedAndCompletedButNotPullRequests() {
        fun json(n: Int, state: String, reason: String) = JSONObject()
            .put("number", n).put("title", "Task $n").put("state", state).put("state_reason", reason)
        val parsed = GitHub.allIssues(listOf(
            json(1, "open", ""), json(2, "closed", "completed"), json(3, "closed", "not_planned"),
            json(4, "closed", "completed").put("pull_request", JSONObject()),
        ))
        assertEquals(listOf(1, 2, 3), parsed.map { it.number })
        assertEquals(listOf("ready", "done", "dropped"), parsed.map { it.status })
    }
}
