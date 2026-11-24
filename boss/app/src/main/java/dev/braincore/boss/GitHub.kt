package dev.braincore.boss

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** HTTP failures are distinct from transport failures, so a bad token is never retried as a network outage. */
internal class GitHubHttpException(val code: Int, message: String, val rateLimited: Boolean = false) : IOException(message)

data class RepoFile(val text: String, val sha: String)

/**
 * GitHub REST for one repo, with a fine-grained token (Issues: read/write; Contents: read, write for the dispatcher filter).
 * [tag] is what its issues carry as [Issue.repo]: empty for the home repo, owner/name for another task repo.
 * [repoAlias] is the name used in task references and workstream filters.
 */
class GitHub(
    private val token: String,
    val repo: String,
    private val tag: String = "",
    private val repoAlias: String = "",
    private val client: OkHttpClient = http,
) {
    private fun tagged(i: Issue) = i.copy(repo = tag, repoAlias = repoAlias)

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(if (path.startsWith("https://")) path else "https://api.github.com/repos/$repo/$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                val hasRateLimitSignal = r.header("X-RateLimit-Remaining") == "0" ||
                    r.header("Retry-After") != null || msg.contains("rate limit", ignoreCase = true)
                val rateLimited = r.code == 429 || (r.code == 403 && hasRateLimitSignal)
                throw GitHubHttpException(r.code, "GitHub ${r.code}${if (msg.isNotEmpty()) ": $msg" else ""}", rateLimited)
            }
            text
        }
    }

    private suspend fun pages(path: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val sep = if ('?' in path) '&' else '?'
            val arr = JSONArray(call("GET", "$path${sep}per_page=100&page=$page"))
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            if (arr.length() < 100) return out
            page++
        }
    }

    suspend fun check() { call("GET", "issues?per_page=1") }

    /** A read check that also works when the repository has no claim or card branches yet. */
    internal suspend fun checkContents() { call("GET", "branches?per_page=1") }

    suspend fun openIssues(): List<Issue> =
        pages("issues?state=open").filter { !it.has("pull_request") }.map { tagged(issue(it)) }

    /** Search loads all pages and every closure reason, including dropped tasks. */
    suspend fun allIssues(): List<Issue> = allIssues(pages("issues?state=all&sort=updated&direction=desc")).map(::tagged)

    /** Loaded only when the completed section is opened, independently of the widget's open-task sync. */
    suspend fun completedIssues(): List<Issue> =
        completedIssues(pages("issues?state=closed&sort=updated&direction=desc")).map(::tagged)

    /** Fresh claims on the given issues: number -> holder. Two calls per claimed issue, so pass only the ready ones. */
    suspend fun claims(numbers: Collection<Int>): Map<Int, String> {
        val refs = JSONArray(call("GET", "git/matching-refs/heads/claim"))
        val now = System.currentTimeMillis()
        val out = mutableMapOf<Int, String>()
        for (i in 0 until refs.length()) {
            val ref = refs.getJSONObject(i)
            val n = ref.getString("ref").substringAfterLast('/').toIntOrNull() ?: continue
            if (n !in numbers) continue
            val commit = JSONObject(call("GET", "git/commits/${ref.getJSONObject("object").getString("sha")}"))
            val date = runCatching { java.time.Instant.parse(commit.getJSONObject("committer").getString("date")).toEpochMilli() }.getOrDefault(0L)
            claimHolder(commit.optString("message"), (now - date) / 3_600_000.0).takeIf { it.isNotEmpty() }?.let { out[n] = it }
        }
        return out
    }

    suspend fun issue(n: Int): Issue = tagged(issue(JSONObject(call("GET", "issues/$n"))))

    suspend fun comments(n: Int): List<Comment> = pages("issues/$n/comments").map {
        Comment(
            id = it.getLong("id"),
            author = it.optJSONObject("user")?.optString("login").orEmpty(),
            body = it.str("body"),
            createdAt = it.str("created_at"),
            url = it.str("html_url"),
        )
    }

    suspend fun comment(n: Int, body: String) {
        call("POST", "issues/$n/comments", JSONObject().put("body", body))
    }

    /** Same effect as `scripts/task.sh set <n> <status>`: swap the label, close/reopen, rewrite the Waiting on lines. */
    suspend fun setStatus(issue: Issue, status: String, waitingOn: String? = null, checkAfter: String? = null) {
        val change = statusChange(issue, status, waitingOn, checkAfter)
        val patch = JSONObject().put("body", change.body).put("labels", JSONArray(change.labels))
        change.state?.let { patch.put("state", it) }
        change.stateReason?.let { patch.put("state_reason", it) }
        call("PATCH", "issues/${issue.number}", patch)
    }

    /** A file on the default branch, and the blob sha a later [putFile] must name. */
    suspend fun file(path: String, ref: String? = null): RepoFile {
        val o = JSONObject(call("GET", "contents/$path${ref?.let { "?ref=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()}"))
        val text = String(Base64.getMimeDecoder().decode(o.getString("content")), Charsets.UTF_8)
        return RepoFile(text, o.getString("sha"))
    }

    /** Main-branch changes plus task/agent events. Event retention and latency are GitHub's. */
    internal suspend fun activity(since: String): Pair<List<ActivityItem>, List<String>> {
        val result = mutableListOf<ActivityItem>()
        val warnings = mutableListOf<String>()
        for (page in 1..10) {
            val commits = JSONArray(call("GET", "commits?since=$since&per_page=100&page=$page")).objects()
            result += commits.map { commitActivity(repo, it) }
            if (commits.size < 100) break
            if (page == 10) warnings += "$repo: showing the latest 1,000 commits"
        }
        try {
            for (page in 1..3) {
                val events = JSONArray(call("GET", "events?per_page=100&page=$page")).objects()
                result += events.filter { millis(it.optString("created_at")) >= millis(since) }.mapNotNull { eventActivity(repo, it) }
                if (events.size < 100 || events.any { millis(it.optString("created_at")) < millis(since) }) break
                if (page == 3) warnings += "$repo: GitHub's 300-event history may not cover the whole period"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { warnings += "$repo task events: ${Store.describe(e)}" }
        return result to warnings
    }

    /** Commit [text] to [path] on the default branch; GitHub refuses (409) if the file changed since [sha] was read. */
    suspend fun putFile(path: String, text: String, sha: String, message: String) {
        val body = JSONObject().put("message", message).put("sha", sha)
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        call("PUT", "contents/$path", body)
    }

    companion object {
        internal fun allIssues(items: List<JSONObject>): List<Issue> = items
            .filter { !it.has("pull_request") }.map(::issue)

        internal fun completedIssues(items: List<JSONObject>): List<Issue> = items
            .filter { !it.has("pull_request") && it.optString("state") == "closed" && it.optString("state_reason") == "completed" }
            .map(::issue)

        /** Shared client. Requests to GitHub hosts that don't carry a token yet (images) get [tokenFor]'s. */
        var tokenFor: () -> String = { "" }

        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(Interceptor { chain ->
                    var req = chain.request()
                    val url = req.url.toString()
                    apiImageUrl(url)?.let { api ->
                        req = req.newBuilder().url(api).header("Accept", "application/vnd.github.raw").build()
                    }
                    val token = tokenFor()
                    if (req.header("Authorization") == null && token.isNotEmpty() && req.url.host == "api.github.com") {
                        req = req.newBuilder().header("Authorization", "Bearer $token").build()
                    }
                    chain.proceed(req)
                })
                .build()
        }

        private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

        fun issue(o: JSONObject): Issue {
            val labels = o.optJSONArray("labels") ?: JSONArray()
            val assignees = o.optJSONArray("assignees") ?: JSONArray()
            return Issue(
                number = o.getInt("number"),
                title = o.str("title"),
                body = o.str("body"),
                labels = (0 until labels.length()).map { labels.getJSONObject(it).getString("name") },
                state = o.str("state"),
                updatedAt = o.str("updated_at"),
                url = o.str("html_url"),
                claim = o.str("x_claim"),
                stateReason = o.str("state_reason"),
                repo = o.str("x_repo"),
                repoAlias = o.str("x_repo_alias"),
                assignees = (0 until assignees.length()).mapNotNull { assignees.optJSONObject(it)?.optString("login")?.takeIf(String::isNotEmpty) },
            )
        }

        fun toJson(i: Issue): JSONObject = JSONObject()
            .put("number", i.number).put("title", i.title).put("body", i.body).put("state", i.state)
            .put("updated_at", i.updatedAt).put("html_url", i.url).put("x_claim", i.claim)
            .put("state_reason", i.stateReason).put("x_repo", i.repo).put("x_repo_alias", i.repoAlias)
            .put("labels", JSONArray(i.labels.map { JSONObject().put("name", it) }))
            .put("assignees", JSONArray(i.assignees.map { JSONObject().put("login", it) }))
    }
}
