package dev.braincore.boss

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Agent runs (release, deep test, ...) as an agent run recorder publishes them: one JSON per
// run plus a newest-first runs/index.json on the `runs` branch of the repo each run belongs to.
// Format: Brain Core's boss/README.md, "Run records".

internal const val RUNS_BRANCH = "runs"

internal data class RunCheck(val name: String, val result: String, val note: String = "")
internal data class RunLink(val label: String, val url: String)

internal data class RunEntry(
    val repo: String, val id: String, val kind: String, val status: String, val started: String,
    val ended: String = "", val deadline: String = "", val summary: String = "",
    val ref: String = "", val sha: String = "", val env: String = "", val engine: String = "",
    val checks: List<RunCheck> = emptyList(), val links: List<RunLink> = emptyList(), val issues: List<String> = emptyList(),
) {
    /** A run still `running` ten minutes after its deadline died without reporting back. */
    fun shownStatus(now: Long): String =
        if (status == "running" && millis(deadline) > 0 && now > millis(deadline) + 600_000) "lost" else status

    /** The tag it tested, else the short commit, plus where. */
    fun target(): String = listOf(ref.ifBlank { sha.take(7) }, env).filter { it.isNotBlank() }.joinToString(" · ")

    fun json(): JSONObject = JSONObject().put("repo", repo).put("id", id).put("kind", kind).put("status", status)
        .put("started", started).put("ended", ended).put("deadline", deadline).put("summary", summary)
        .put("engine", engine).put("target", JSONObject().put("ref", ref).put("sha", sha).put("env", env))
}

internal fun readRun(o: JSONObject, repo: String): RunEntry {
    val t = o.optJSONObject("target") ?: JSONObject()
    return RunEntry(
        repo, o.optString("id"), o.optString("kind"), o.optString("status"), o.optString("started"),
        o.optString("ended"), o.optString("deadline"), o.optString("summary"),
        t.optString("ref"), t.optString("sha"), t.optString("env"), o.optString("engine"),
        o.optJSONArray("checks")?.objects().orEmpty().map { RunCheck(it.optString("name"), it.optString("result"), it.optString("note")) },
        o.optJSONArray("links")?.objects().orEmpty().map { RunLink(it.optString("label"), it.optString("url")) }
            .filter { it.url.startsWith("https://") },
        o.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
    )
}

/** runs/index.json of one repo. */
internal fun readRunIndex(text: String, repo: String): List<RunEntry> {
    val root = JSONObject(text)
    require(root.optInt("v") == 1) { "Unsupported runs data version" }
    return root.optJSONArray("runs")?.objects().orEmpty().map { readRun(it, repo) }.filter { it.id.isNotBlank() }
}

internal fun mergeRuns(byRepo: Map<String, List<RunEntry>>): List<RunEntry> =
    byRepo.values.flatten().sortedByDescending { millis(it.started) }

internal fun runStatusLabel(status: String): String = when (status) {
    "passed" -> "Passed"; "failed" -> "Failed"; "blocked" -> "Blocked"; "skipped" -> "Nothing to do"
    "done" -> "Finished"; "error" -> "Error"; "timeout" -> "Timed out"; "stopped" -> "Stopped"
    "running" -> "Running"; "lost" -> "Lost"; else -> status.replaceFirstChar { it.uppercase() }
}

internal fun runIsBad(status: String) = status in setOf("failed", "error", "timeout", "lost")

private fun checkMark(result: String) = when (result) { "pass" -> "✓"; "fail" -> "✗"; "blocked" -> "!"; else -> "–" }

internal fun runDuration(run: RunEntry, now: Long): String {
    val start = millis(run.started).takeIf { it > 0 } ?: return ""
    val end = millis(run.ended).takeIf { it > 0 }
    val minutes = (((end ?: now) - start) / 60_000).coerceAtLeast(0)
    val text = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    return when {
        end != null -> "Took $text"
        run.shownStatus(now) == "running" -> "Running for $text"
        else -> "No report for $text"
    }
}

private fun runAgo(iso: String, now: Long): String = millis(iso).takeIf { it > 0 }?.let {
    android.text.format.DateUtils.getRelativeTimeSpanString(it, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
} ?: "Unknown time"

@Composable
internal fun RunsScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var runs by remember { mutableStateOf(Store.runs(ctx)) }
    var details by remember { mutableStateOf(mapOf<String, RunEntry>()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    fun refresh() = scope.launch {
        if (loading) return@launch
        loading = true; error = ""
        try {
            val (fresh, warning) = Store.syncRuns(ctx)
            runs = fresh; error = warning; details = emptyMap(); now = System.currentTimeMillis()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = Store.describe(e)
        } finally { loading = false }
    }
    fun toggle(run: RunEntry) {
        val key = run.repo + ":" + run.id
        if (key in expanded) { expanded = expanded - key; return }
        expanded = expanded + key
        if (key !in details) scope.launch {
            try { details = details + (key to Store.runDetail(ctx, run)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "${run.id}: ${Store.describe(e)}" }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    RunsContent(runs, details.filterKeys { it in expanded }, expanded, now, loading, error, { refresh() }, onSettings,
        onToggle = { toggle(it) }, onOpen = { uri.openUri(it) }, homeRepo = Store.repo(ctx))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunsContent(
    runs: List<RunEntry>, details: Map<String, RunEntry>, expanded: Set<String>, now: Long, loading: Boolean, error: String,
    onRefresh: () -> Unit, onSettings: () -> Unit, onToggle: (RunEntry) -> Unit, onOpen: (String) -> Unit, homeRepo: String = "",
) {
    Scaffold(topBar = { OverviewBar("Runs", loading, onRefresh, onSettings, AiReference.screen("Runs", homeRepo)) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Agent runs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Releases, test runs and other agent jobs, newest first. Tap one for its checks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error.isNotBlank()) item { ErrorBanner(error) }
            if (runs.isEmpty() && !loading) item { Panel { Text("No runs recorded yet. Agents list a run by writing it to the runs branch of its repo (boss/README.md, \"Run records\").", Modifier.padding(20.dp)) } }
            items(runs, key = { it.repo + ":" + it.id }) { run ->
                val key = run.repo + ":" + run.id
                val status = run.shownStatus(now)
                val color: Color = when {
                    runIsBad(status) -> MaterialTheme.colorScheme.error
                    status == "passed" -> MaterialTheme.colorScheme.primary
                    status == "running" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Panel(onClick = { onToggle(run) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${run.repo.substringAfter('/')} · ${run.kind} · ${runAgo(run.started, now)}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(listOf(runStatusLabel(status), run.target()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                        if (run.summary.isNotBlank()) Text(run.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(listOf(runDuration(run, now), run.engine).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (key in expanded) {
                            val d = details[key]
                            if (d == null) Text("Loading checks…", style = MaterialTheme.typography.bodySmall)
                            else {
                                if (d.checks.isEmpty()) Text("No checks reported.", style = MaterialTheme.typography.bodySmall)
                                d.checks.forEach { c ->
                                    Text("${checkMark(c.result)} ${c.name}" + (if (c.note.isNotBlank()) " — ${c.note}" else ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (c.result == "fail") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                }
                                if (d.issues.isNotEmpty()) Text("Issues: ${d.issues.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    d.links.take(3).forEach { l -> TextButton(onClick = { onOpen(l.url) }) { Text(l.label) } }
                                    TextButton(onClick = { onOpen("https://github.com/${run.repo}/blob/$RUNS_BRANCH/runs/${run.id}.json") }) { Text("Record") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun runsJson(runs: List<RunEntry>): String = JSONArray(runs.map { it.json() }).toString()
internal fun runsFromJson(text: String): List<RunEntry> =
    JSONArray(text).objects().map { readRun(it, it.optString("repo")) }
