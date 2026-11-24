package dev.braincore.boss

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal enum class SyncProblem { ACCESS, TEMPORARY, OTHER }

internal fun classifySyncError(error: Throwable): SyncProblem = when (error) {
    is GitHubHttpException -> when {
        error.rateLimited || error.code in 500..599 || error.code in listOf(408, 429) -> SyncProblem.TEMPORARY
        error.code in listOf(401, 403, 404, 410) -> SyncProblem.ACCESS
        else -> SyncProblem.OTHER
    }
    is IOException -> SyncProblem.TEMPORARY
    else -> SyncProblem.OTHER
}

/** Three attempts for a manual refresh; periodic work keeps its normal backoff for temporary failures. */
internal fun shouldRetrySync(error: Throwable, attempt: Int, manual: Boolean): Boolean =
    classifySyncError(error) == SyncProblem.TEMPORARY && (!manual || attempt < 2)

internal data class QueueSyncResult(val issues: List<Issue>, val temporaryFailure: Exception?)

/** Settings and the last fetched issue list (the widget draws from it). */
object Store {
    /** Where every Brain Core user gets the app; Settings links here. */
    const val RELEASES_URL = "https://github.com/rawmahn/brain-core/releases/latest"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("boss", Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0L)
    private val syncMutex = Mutex()
    private val completedMutex = Mutex()
    private val searchMutex = Mutex()

    /**
     * Bumped whenever something the widget shows changes. A running Glance session doesn't call provideGlance
     * again on update, so the widget re-reads the Store when this ticks instead of keeping what it read first.
     */
    val changes: StateFlow<Long> = _changes
    private fun changed() = _changes.update { it + 1 }

    fun token(ctx: Context): String = prefs(ctx).getString("token", "").orEmpty()
    /** The owner's Brain (workspace) repo, "owner/name"; empty until Settings saves one. */
    fun repo(ctx: Context): String = prefs(ctx).getString("repo", "").orEmpty().trim()
    fun configured(ctx: Context) = token(ctx).isNotBlank() && repo(ctx).isNotBlank()
    fun save(ctx: Context, repo: String, token: String) =
        prefs(ctx).edit().putString("repo", repo.trim()).putString("token", token.trim())
            .remove("snapshot").remove("task_repos").remove("owner").remove("completed_snapshot").remove("search_snapshot").remove("synced").remove("error")
            .remove("sync_problem").remove("refreshing")
            .remove("activity").remove("activity_synced").remove("activity_candidates").remove("activity_watched").remove("usage").apply()

    internal fun activity(ctx: Context): List<ActivityItem> = runCatching {
        JSONArray(prefs(ctx).getString("activity", "[]")).objects().map(ActivityItem::read)
    }.getOrDefault(emptyList())

    internal fun activitySynced(ctx: Context) = prefs(ctx).getLong("activity_synced", 0)
    internal fun watchedRepos(ctx: Context): Set<String> = prefs(ctx).getStringSet("activity_watched", null)?.toSet()
        ?: (listOf(repo(ctx)) + taskRepos(ctx).map { it.fullName }).toSet()
    internal fun watchRepos(ctx: Context, repos: Set<String>) { prefs(ctx).edit().putStringSet("activity_watched", repos).apply() }
    internal fun activityCandidates(ctx: Context): List<String> =
        (prefs(ctx).getStringSet("activity_candidates", emptySet()).orEmpty() + watchedRepos(ctx)).sorted()
    internal suspend fun refreshActivityCandidates(ctx: Context): List<String> {
        val repos = (activityReposOf(github(ctx).file("repos.yaml").text) + repo(ctx)).distinct().sorted()
        prefs(ctx).edit().putStringSet("activity_candidates", repos.toSet()).apply()
        return repos
    }

    private val activityMutex = Mutex()
    internal suspend fun syncActivity(
        ctx: Context,
        fetch: suspend (GitHub, String) -> Pair<List<ActivityItem>, List<String>> = { gh, since -> gh.activity(since) },
    ): Pair<List<ActivityItem>, String> = activityMutex.withLock {
        val config = repo(ctx) to token(ctx)
        val watched = watchedRepos(ctx)
        val now = System.currentTimeMillis()
        val since = java.time.Instant.ofEpochMilli(now - 72 * 3_600_000L).toString()
        val items = mutableListOf<ActivityItem>()
        val warnings = mutableListOf<String>()
        for (repo in watched.sorted()) {
            try {
                val (fresh, notes) = fetch(github(ctx, repo), since)
                items += fresh
                if (notes.any { it.startsWith("$repo task events:") }) {
                    items += activity(ctx).filter { it.repo == repo && it.kind != "Commit" }
                }
                warnings += notes
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                items += activity(ctx).filter { it.repo == repo }
                warnings += "$repo: ${describe(e)} (saved activity, may be out of date)"
            }
        }
        if (config != (repo(ctx) to token(ctx)) || watched != watchedRepos(ctx)) throw CancellationException("Activity configuration changed")
        val completedAt = System.currentTimeMillis()
        val sorted = activitySince(items, 72, completedAt)
        prefs(ctx).edit().putString("activity", JSONArray(sorted.map { it.json() }).toString())
            .putLong("activity_synced", completedAt).apply()
        sorted to warnings.joinToString("\n")
    }

    internal fun usage(ctx: Context): String = prefs(ctx).getString("usage", "").orEmpty()
    internal suspend fun syncUsage(ctx: Context): String {
        val config = repo(ctx) to token(ctx)
        val text = github(ctx).file("usage.json", "agent-state").text
        readUsage(text)
        if (config != (repo(ctx) to token(ctx))) throw CancellationException("Usage configuration changed")
        prefs(ctx).edit().putString("usage", text).apply()
        return text
    }

    /** Agent runs from the `runs` branch of home and every task repo, as last read (Runs.kt). */
    internal fun runs(ctx: Context): List<RunEntry> = runCatching { runsFromJson(prefs(ctx).getString("runs", "[]").orEmpty()) }
        .getOrDefault(emptyList())

    /** Re-read each repo's runs/index.json; a repo without a runs branch has no runs, one that fails keeps its saved runs. */
    internal suspend fun syncRuns(ctx: Context): Pair<List<RunEntry>, String> {
        val config = repo(ctx) to token(ctx)
        val saved = runs(ctx).groupBy { it.repo }
        val warnings = mutableListOf<String>()
        val byRepo = (listOf(repo(ctx)) + taskRepos(ctx).map { it.fullName }).distinct().associateWith { r ->
            try {
                readRunIndex(github(ctx, r).file("runs/index.json", RUNS_BRANCH).text, r)
            } catch (e: CancellationException) { throw e
            } catch (e: GitHubHttpException) {
                if (e.code == 404) emptyList() else { warnings += "$r: ${describe(e)}"; saved[r].orEmpty() }
            } catch (e: Exception) { warnings += "$r: ${describe(e)}"; saved[r].orEmpty() }
        }
        if (config != (repo(ctx) to token(ctx))) throw CancellationException("Runs configuration changed")
        val merged = mergeRuns(byRepo)
        prefs(ctx).edit().putString("runs", runsJson(merged)).apply()
        return merged to warnings.joinToString("\n")
    }

    /** One run's full record (checks, links, issues). */
    internal suspend fun runDetail(ctx: Context, run: RunEntry): RunEntry {
        require(Regex("[a-z0-9-]+").matches(run.id)) { "Unexpected run id" }
        return readRun(JSONObject(github(ctx, run.repo).file("runs/${run.id}.json", RUNS_BRANCH).text), run.repo)
    }

    fun filter(ctx: Context): Set<String> = prefs(ctx).getStringSet("filter", null)?.toSet().orEmpty()

    /** Save the workstream filter and redraw the widget, which uses the same filter. */
    suspend fun setFilter(ctx: Context, filter: Set<String>) {
        prefs(ctx).edit().putStringSet("filter", filter).apply()
        changed()
        BossWidget().updateAll(ctx)
    }

    /** Include collaborators' tasks in the queue and widget; existing installs default to owner/unassigned. */
    fun showEveryone(ctx: Context): Boolean = prefs(ctx).getBoolean("show_everyone", false)

    suspend fun setShowEveryone(ctx: Context, show: Boolean) {
        prefs(ctx).edit().putBoolean("show_everyone", show).apply()
        changed()
        BossWidget().updateAll(ctx)
    }

    /** The home repo's client, or another task repo's (owner/name). */
    fun github(ctx: Context, repo: String = ""): GitHub {
        if (repo.isEmpty()) return GitHub(token(ctx), repo(ctx))
        val alias = taskRepos(ctx).firstOrNull { it.fullName.equals(repo, ignoreCase = true) }?.name.orEmpty()
        return GitHub(token(ctx), repo, tag = repo, repoAlias = alias)
    }

    /** The owner's GitHub login, from home's workspace.yaml (`github:`), as last read; "" if unknown. */
    fun owner(ctx: Context): String = prefs(ctx).getString("owner", "").orEmpty()

    /** Task repos besides home, from home's repos.yaml (`tasks: true` / `assigned`), as last read. */
    internal fun taskRepos(ctx: Context): List<TaskRepo> {
        val saved = prefs(ctx).getString("task_repos", "").orEmpty()
        if (saved.startsWith('{')) {
            val json = JSONObject(saved)
            return json.keys().asSequence().sorted().map { TaskRepo(it, json.getString(it)) }.toList()
        }
        // The previous version cached only full names.
        return saved.split(',').filter { it.isNotBlank() }.map { TaskRepo(it.substringAfter('/'), it) }
    }

    /** Re-read repos.yaml; on failure keep the last list, so a hiccup never hides a repo's tasks. */
    private suspend fun refreshTaskRepos(ctx: Context) {
        val repos = try {
            readTaskRepoRegistry(github(ctx))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        prefs(ctx).edit().putString("task_repos", JSONObject(repos).toString()).apply()
        try {
            prefs(ctx).edit().putString("owner", ownerOf(github(ctx).file("workspace.yaml").text)).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** The dispatcher's filter lives in the repo's workers.yaml (`filter:`), not in the app: agents read it from there. */
    const val WORKERS_FILE = "workers.yaml"

    suspend fun dispatcherFilter(ctx: Context): Pair<Set<String>, RepoFile> {
        val file = github(ctx).file(WORKERS_FILE)
        return (dispatcherFilter(file.text) ?: throw IOException("$WORKERS_FILE has no filter: line")) to file
    }

    /** Commit the new filter; returns the file as now saved, so another Save names the right version. */
    suspend fun setDispatcherFilter(ctx: Context, file: RepoFile, filter: Set<String>): RepoFile {
        val text = withDispatcherFilter(file.text, filter) ?: throw IOException("Can't write this filter to $WORKERS_FILE")
        github(ctx).putFile(WORKERS_FILE, text, file.sha, "Dispatcher filter: ${describeFilter(filter)} (Boss app)")
        return github(ctx).file(WORKERS_FILE)
    }

    fun issues(ctx: Context): List<Issue> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("snapshot", "[]"))
        (0 until arr.length()).map { GitHub.issue(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun completed(ctx: Context): List<Issue> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("completed_snapshot", "[]"))
        (0 until arr.length()).map { GitHub.issue(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun completedLoaded(ctx: Context): Boolean = prefs(ctx).contains("completed_snapshot")

    /** Search has its own complete history; before the first fetch, offer the known cached tasks. */
    fun search(ctx: Context): List<Issue> = if (!prefs(ctx).contains("search_snapshot")) {
        (issues(ctx) + completed(ctx)).distinctBy { it.key }
    } else runCatching {
        val arr = JSONArray(prefs(ctx).getString("search_snapshot", "[]"))
        (0 until arr.length()).map { GitHub.issue(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    suspend fun syncSearch(ctx: Context): List<Issue> = syncSearch(ctx) { eachRepo(ctx) { it.allIssues() } }

    /** Fetch from every task repo. Home must answer. Callers can recover saved issues from another repo. */
    private suspend fun eachRepo(
        ctx: Context, recover: (TaskRepo, Exception) -> List<Issue> = { _, _ -> emptyList() },
        fetch: suspend (GitHub) -> List<Issue>,
    ): List<Issue> =
        buildList {
            val token = token(ctx)
            addAll(fetch(GitHub(token, repo(ctx))))
            val repos = taskRepos(ctx).distinctBy { it.fullName.lowercase() }
            for (taskRepo in repos) {
                val (alias, remote) = taskRepo
                try {
                    addAll(fetch(GitHub(token, remote, tag = remote, repoAlias = alias)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addAll(recover(taskRepo, e))
                }
            }
        }

    /** Fetch once on opening/refresh, never per keystroke; don't affect queue/widget sync state. */
    internal suspend fun syncSearch(ctx: Context, fetch: suspend () -> List<Issue>): List<Issue> = searchMutex.withLock {
        val config = repo(ctx) to token(ctx)
        val issues = fetch()
        if (config != (repo(ctx) to token(ctx))) throw CancellationException("Search configuration changed")
        prefs(ctx).edit().putString("search_snapshot", JSONArray(issues.map(GitHub::toJson)).toString()).apply()
        issues
    }

    /** History has its own cache and errors; it must never make the widget appear to have failed. */
    suspend fun syncCompleted(ctx: Context): List<Issue> = completedMutex.withLock {
        val issues = eachRepo(ctx) { it.completedIssues() }
        prefs(ctx).edit().putString("completed_snapshot", JSONArray(issues.map(GitHub::toJson)).toString()).apply()
        issues
    }

    fun syncedAt(ctx: Context): Long = prefs(ctx).getLong("synced", 0)
    fun error(ctx: Context): String = prefs(ctx).getString("error", "").orEmpty()
    internal fun syncProblem(ctx: Context): SyncProblem? {
        val saved = prefs(ctx).getString("sync_problem", null)
        return SyncProblem.entries.firstOrNull { it.name == saved } ?: when (error(ctx)) {
            "" -> null
            "offline" -> SyncProblem.TEMPORARY
            else -> SyncProblem.OTHER
        }
    }
    fun refreshing(ctx: Context): Boolean = prefs(ctx).getBoolean("refreshing", false)

    /** A short reason for a failed sync: "offline" for network trouble rather than a raw "Unable to resolve host …". */
    fun describe(e: Throwable): String = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is InterruptedIOException -> "offline"
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun readQueue(gh: GitHub): List<Issue> {
        val open = gh.openIssues()
        // Losing claim markers is not a list-sync failure, but cancellation must still stop this sync.
        val claims = try {
            gh.claims(open.filter { it.status == "ready" }.map { it.number })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        return open.map { it.copy(claim = claims[it.number].orEmpty()) }
    }

    suspend fun sync(ctx: Context): List<Issue> = syncQueue(ctx).issues

    internal suspend fun syncQueue(
        ctx: Context,
        prepare: suspend () -> Unit = { refreshTaskRepos(ctx) },
        fetch: suspend (GitHub) -> List<Issue> = ::readQueue,
        redraw: suspend () -> Unit = { BossWidget().updateAll(ctx) },
    ): QueueSyncResult {
        val failures = mutableMapOf<String, Exception>()
        val updated = sync(ctx, fetch = {
            prepare()
            val saved = issues(ctx)
            eachRepo(ctx, recover = { taskRepo, error ->
                failures[taskRepo.name] = error
                saved.filter { it.repo.equals(taskRepo.fullName, ignoreCase = true) }
                    .map { it.copy(repoAlias = taskRepo.name) }
            }, fetch = fetch)
        }, redraw = redraw, failures = { failures })
        return QueueSyncResult(updated, failures.values.firstOrNull { classifySyncError(it) == SyncProblem.TEMPORARY })
    }

    /** One sync at a time. A partial fetch updates the reachable repos and keeps the last full-sync time. */
    internal suspend fun sync(
        ctx: Context, fetch: suspend () -> List<Issue>, redraw: suspend () -> Unit,
        failures: () -> Map<String, Exception> = { emptyMap() },
    ): List<Issue> = syncMutex.withLock {
        try {
            val issues = fetch()
            val missed = failures()
            prefs(ctx).edit()
                .putString("snapshot", JSONArray(issues.map(GitHub::toJson)).toString())
                .apply {
                    if (missed.isEmpty()) {
                        putLong("synced", System.currentTimeMillis()).remove("error").remove("sync_problem")
                    } else {
                        val problem = missed.values.map(::classifySyncError).minBy { it.ordinal }
                        putString("sync_problem", problem.name)
                        putString("error", missed.entries.joinToString("; ") { (repo, error) -> "$repo: ${describe(error)}" })
                    }
                }.apply()
            issues
        } catch (e: CancellationException) {
            // WorkManager can stop work when constraints change. That is not a failed network request.
            throw e
        } catch (e: Exception) {
            prefs(ctx).edit().putString("error", describe(e)).putString("sync_problem", classifySyncError(e).name).apply()
            throw e
        } finally {
            withContext(NonCancellable) {
                prefs(ctx).edit().remove("refreshing").apply()
                changed()
                // Data was already saved. A launcher redraw failure must not turn a successful fetch into an error.
                try { redraw() } catch (_: Exception) { }
            }
        }
    }

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Refresh from the widget's ↻. The sync runs as a WorkManager job, not in the click's broadcast: a backgrounded
     * app has no network under Doze or Battery Saver (that is the "Unable to resolve host"), a running job does.
     * The widget acknowledges the request while work is queued or running.
     */
    suspend fun refresh(ctx: Context) {
        prefs(ctx).edit().putBoolean("refreshing", true).apply()
        changed()
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(SyncWorker.MANUAL)
            // Expedited work on Android < 12 runs as a foreground service, which needs a notification; skip it there.
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("refresh", ExistingWorkPolicy.KEEP, work)
        // Enqueue first, so a launcher redraw problem cannot prevent the requested refresh from running.
        try { BossWidget().updateAll(ctx) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    fun schedule(ctx: Context) {
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, work)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Store.configured(applicationContext)) return Result.success()
        val manual = MANUAL in tags
        return try {
            val failure = Store.syncQueue(applicationContext).temporaryFailure
            if (failure != null && shouldRetrySync(failure, runAttemptCount, manual)) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (shouldRetrySync(e, runAttemptCount, manual)) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val MANUAL = "manual"
    }
}
