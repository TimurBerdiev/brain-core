package dev.braincore.boss

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant

internal fun millis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal data class ActivityItem(
    val id: String, val repo: String, val title: String, val detail: String,
    val at: String, val url: String, val kind: String,
) {
    fun json() = JSONObject().put("id", id).put("repo", repo).put("title", title).put("detail", detail)
        .put("at", at).put("url", url).put("kind", kind)
    companion object {
        fun read(o: JSONObject) = ActivityItem(o.getString("id"), o.getString("repo"), o.optString("title"),
            o.optString("detail"), o.optString("at"), o.optString("url"), o.optString("kind"))
    }
}

internal fun activitySince(items: List<ActivityItem>, hours: Int, now: Long): List<ActivityItem> = items
    .filter { millis(it.at) in (now - hours * 3_600_000L)..now }
    .distinctBy { it.repo.lowercase() to it.id }.sortedByDescending { millis(it.at) }

internal fun commitActivity(repo: String, o: JSONObject): ActivityItem {
    val commit = o.getJSONObject("commit")
    val message = commit.optString("message")
    val by = o.optJSONObject("author")?.optString("login").orEmpty()
        .ifBlank { commit.optJSONObject("author")?.optString("name").orEmpty() }
    val coauthor = Regex("(?im)^Co-Authored-By: ([^<\\n]+)").findAll(message)
        .map { it.groupValues[1].trim() }.toList()
    return ActivityItem("commit:${o.getString("sha")}", repo, message.lineSequence().firstOrNull().orEmpty(),
        (listOf(by) + coauthor).filter(String::isNotBlank).distinct().joinToString(" · "),
        commit.optJSONObject("committer")?.optString("date").orEmpty(), o.optString("html_url"), "Commit")
}

internal fun eventActivity(repo: String, o: JSONObject): ActivityItem? {
    val p = o.optJSONObject("payload") ?: return null
    val type = o.optString("type")
    val issue = p.optJSONObject("issue") ?: p.optJSONObject("pull_request")
    val comment = p.optJSONObject("comment")
    val number = issue?.optInt("number") ?: p.optInt("number")
    val action = p.optString("action")
    val claim = p.optString("ref").takeIf { it.startsWith("refs/heads/claim/") }
    val title = when (type) {
        "IssuesEvent", "PullRequestEvent" -> "#$number $action · ${issue?.optString("title").orEmpty()}"
        "IssueCommentEvent" -> "#$number · ${issue?.optString("title").orEmpty()}"
        "PushEvent" -> if (claim != null) "Task #${claim.substringAfterLast('/')} · agent claim updated" else return null
        else -> return null
    }
    val body = comment?.optString("body").orEmpty().lineSequence()
        .filterNot { it.trim().startsWith("<!--") }.firstOrNull { it.isNotBlank() }.orEmpty().take(180)
    return ActivityItem("event:${o.getString("id")}", repo, title,
        listOf(o.optJSONObject("actor")?.optString("login").orEmpty(), body).filter(String::isNotBlank).joinToString(" · "),
        o.optString("created_at"), comment?.optString("html_url") ?: issue?.optString("html_url")
            ?: "https://github.com/$repo/commits/${claim?.removePrefix("refs/heads/").orEmpty()}",
        when (type) { "IssueCommentEvent" -> "Comment"; "PushEvent" -> "Agent"; "PullRequestEvent" -> "Pull request"; else -> "Task" })
}

/** All registered repos are watch candidates; this does not grant the token access to any of them. */
internal fun activityReposOf(yaml: String): List<String> {
    val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) as? Map<*, *> ?: return emptyList()
    val entries = root["repos"] as? Map<*, *> ?: return emptyList()
    val remote = Regex("github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?/?$")
    return entries.values.mapNotNull { value ->
        ((value as? Map<*, *>)?.get("remote") as? String)?.let { remote.find(it)?.groupValues?.get(1) }
    }.distinct().sorted()
}

internal data class UsageWindow(val name: String, val used: Double?, val minutes: Int?, val resetsAt: String)
internal data class ProviderUsage(
    val key: String, val name: String, val observedAt: String = "", val source: String = "",
    val auth: String = "unknown", val error: String = "", val windows: List<UsageWindow> = emptyList(),
    val credits: String = "",
    val quotaReached: Boolean = false,
) {
    fun fresh(now: Long) = error.isBlank() && auth == "authenticated" && millis(observedAt) > 0 &&
        now - millis(observedAt) in 0..1_800_000 && windows.isNotEmpty() &&
        windows.all { it.used != null && (it.resetsAt.isBlank() || millis(it.resetsAt) > now) }
}

internal val usageProviders = listOf("antigravity" to "Antigravity", "claude-code" to "Claude Code", "codex" to "Codex")

internal fun readUsage(text: String): List<ProviderUsage> {
    if (text.isBlank()) return usageProviders.map { ProviderUsage(it.first, it.second) }
    val root = JSONObject(text)
    require(root.optInt("schema_version") == 1) { "Unsupported usage data version" }
    val byKey = root.optJSONArray("providers")?.objects().orEmpty().associateBy { it.optString("key") }
    return usageProviders.map { (key, name) ->
        val o = byKey[key] ?: return@map ProviderUsage(key, name)
        ProviderUsage(key, name, o.optString("observed_at"), o.optString("source"), o.optString("auth_state", "unknown"),
            o.optString("error"), o.optJSONArray("windows")?.objects().orEmpty().map { w ->
                val used = if (w.isNull("used_percent")) null else w.optDouble("used_percent").takeIf { it.isFinite() && it in 0.0..100.0 }
                UsageWindow(w.optString("name"), used, w.optInt("window_minutes").takeIf { it > 0 }, w.optString("resets_at"))
            }, if (o.isNull("credits")) "" else o.optString("credits"), o.optBoolean("quota_reached"))
    }
}
