package dev.braincore.boss

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val issue = Issue(7, "Fresh task", "", listOf("ready"), "open", "", "")

    @Before fun clear() { ctx.getSharedPreferences("boss", Context.MODE_PRIVATE).edit().clear().putString("repo", "alex/brain").commit() }

    @Test fun notConfiguredUntilBothRepoAndTokenAreSaved() {
        ctx.getSharedPreferences("boss", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals("", Store.repo(ctx))
        assertFalse(Store.configured(ctx))
        ctx.getSharedPreferences("boss", Context.MODE_PRIVATE).edit().putString("token", "t").commit()
        assertFalse(Store.configured(ctx))
        Store.save(ctx, "alex/brain", "t")
        assertTrue(Store.configured(ctx))
    }

    @Test fun everyoneToggleDefaultsOffPersistsAndNotifiesTheWidget() = runBlocking {
        assertFalse(Store.showEveryone(ctx))
        val before = Store.changes.value
        Store.setShowEveryone(ctx, true)
        assertTrue(Store.showEveryone(ctx))
        assertTrue(ctx.getSharedPreferences("boss", Context.MODE_PRIVATE).getBoolean("show_everyone", false))
        assertTrue(Store.changes.value > before)
        Store.setShowEveryone(ctx, false)
        assertFalse(Store.showEveryone(ctx))
        assertFalse(ctx.getSharedPreferences("boss", Context.MODE_PRIVATE).getBoolean("show_everyone", true))
    }

    @Test fun overviewWatchSelectionAndCachesDoNotCrossAccountChanges() {
        val prefs = ctx.getSharedPreferences("boss", Context.MODE_PRIVATE)
        prefs.edit().putString("task_repos", """{"studio":"alex/studio"}""").commit()
        assertEquals(setOf("alex/brain", "alex/studio"), Store.watchedRepos(ctx))
        Store.watchRepos(ctx, setOf("alex/app"))
        assertEquals(setOf("alex/app"), Store.watchedRepos(ctx))
        prefs.edit().putString("activity", """[{"id":"1","repo":"alex/app","title":"Private change"}]""")
            .putString("usage", "private snapshot").commit()
        assertEquals(1, Store.activity(ctx).size)
        Store.save(ctx, "other/workspace", "different-token")
        assertTrue(Store.activity(ctx).isEmpty())
        assertEquals("", Store.usage(ctx))
        assertEquals(setOf("other/workspace"), Store.watchedRepos(ctx))
    }

    @Test fun activityPartialFailuresKeepSavedRowsWithoutBlockingOtherRepos() = runBlocking {
        Store.watchRepos(ctx, setOf("r/one", "r/two"))
        fun row(repo: String) = ActivityItem(repo, repo, "Saved change", "", java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 1000).toString(), "", "Commit")
        Store.syncActivity(ctx) { gh, _ -> listOf(row(gh.repo)) to emptyList() }
        val (items, warning) = Store.syncActivity(ctx) { gh, _ ->
            if (gh.repo == "r/one") throw GitHubHttpException(403, "Denied")
            listOf(row(gh.repo).copy(title = "Fresh change")) to emptyList()
        }
        assertEquals(2, items.size)
        assertEquals("Saved change", items.first { it.repo == "r/one" }.title)
        assertEquals("Fresh change", items.first { it.repo == "r/two" }.title)
        assertTrue(warning.contains("r/one"))
        assertEquals(items, Store.activity(ctx))
    }

    @Test fun activityCannotPublishIntoNewAccountAfterConfigurationChanges() = runBlocking {
        val result = runCatching { Store.syncActivity(ctx) { _, _ ->
            Store.save(ctx, "other/home", "new-token")
            emptyList<ActivityItem>() to emptyList<String>()
        } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(Store.activity(ctx).isEmpty())
        assertEquals(0L, Store.activitySynced(ctx))
    }

    @Test fun taskRepositoryCacheSupportsOldAndNewValues() {
        val prefs = ctx.getSharedPreferences("boss", Context.MODE_PRIVATE)
        prefs.edit().putString("task_repos", "alex/studio,another/studio").commit()
        assertEquals(listOf(TaskRepo("studio", "alex/studio"), TaskRepo("studio", "another/studio")), Store.taskRepos(ctx))
        prefs.edit().putString("task_repos", """{"studio":"alex/studio"}""").commit()
        assertEquals(listOf(TaskRepo("studio", "alex/studio")), Store.taskRepos(ctx))
        assertEquals("alex/studio", Store.github(ctx, "alex/studio").repo)
    }

    @Test fun successfulRetryClearsTheSavedFailureAndRedraws() = runBlocking {
        var redraws = 0
        val failed = runCatching { Store.sync(ctx, { throw UnknownHostException() }, { redraws++ }) }
        assertTrue(failed.isFailure)
        assertEquals("offline", Store.error(ctx))
        assertEquals(SyncProblem.TEMPORARY, Store.syncProblem(ctx))
        assertEquals(0L, Store.syncedAt(ctx))
        Store.sync(ctx, { listOf(issue) }, { redraws++ })
        assertEquals("", Store.error(ctx))
        assertNull(Store.syncProblem(ctx))
        assertEquals(listOf(issue), Store.issues(ctx))
        assertTrue(Store.syncedAt(ctx) > 0)
        assertEquals(2, redraws)
    }

    @Test fun unavailableTaskRepoKeepsSavedTasksAndLastFullSyncTime() = runBlocking {
        val shared = issue.copy(number = 8, repo = "alex/studio", repoAlias = "studio")
        val fresh = issue.copy(title = "Updated home task")
        val prefs = ctx.getSharedPreferences("boss", Context.MODE_PRIVATE)
        prefs.edit().putString("task_repos", """{"studio":"alex/studio"}""").commit()
        Store.sync(ctx, { listOf(issue, shared) }, {})
        val lastFullSync = 1_800_000L
        prefs.edit().putLong("synced", lastFullSync).commit()

        val outage = GitHubHttpException(503, "Service unavailable")
        val partial = Store.syncQueue(ctx, prepare = {}, fetch = { gh ->
            if (gh.repo == shared.repo) throw outage
            listOf(fresh)
        }, redraw = {})
        assertSame(outage, partial.temporaryFailure)
        assertEquals(listOf(fresh, shared), Store.issues(ctx))
        assertEquals(lastFullSync, Store.syncedAt(ctx))
        assertEquals(SyncProblem.TEMPORARY, Store.syncProblem(ctx))
        assertEquals("studio: Service unavailable", Store.error(ctx))

        val denied = Store.syncQueue(ctx, prepare = {}, fetch = { gh ->
            if (gh.repo == shared.repo) throw GitHubHttpException(404, "Not found")
            listOf(fresh)
        }, redraw = {})
        assertNull(denied.temporaryFailure)
        assertEquals(listOf(fresh, shared), Store.issues(ctx))
        assertEquals(lastFullSync, Store.syncedAt(ctx))
        assertEquals(SyncProblem.ACCESS, Store.syncProblem(ctx))

        Store.syncQueue(ctx, prepare = {}, fetch = { gh ->
            if (gh.repo == shared.repo) emptyList() else listOf(fresh)
        }, redraw = {})
        assertEquals(listOf(fresh), Store.issues(ctx))
        assertTrue(Store.syncedAt(ctx) > lastFullSync)
        assertNull(Store.syncProblem(ctx))
        assertEquals("", Store.error(ctx))
    }

    @Test fun appAndWorkerCannotFetchOrPublishOutOfOrder() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = async {
            runCatching { Store.sync(ctx, {
                started.complete(Unit)
                finish.await()
                throw UnknownHostException()
            }, {}) }
        }
        started.await()
        val second = async { Store.sync(ctx, { secondStarted = true; listOf(issue) }, {}) }
        yield()
        assertFalse(secondStarted)
        finish.complete(Unit)
        assertTrue(first.await().isFailure)
        second.await()
        assertEquals(listOf(issue), Store.issues(ctx))
        assertEquals("", Store.error(ctx))
    }

    @Test fun stoppedWorkerDoesNotTurnTheLastSuccessIntoAnError() = runBlocking {
        Store.sync(ctx, { listOf(issue) }, {})
        val synced = Store.syncedAt(ctx)
        val started = CompletableDeferred<Unit>()
        val stopped = launch { Store.sync(ctx, { started.complete(Unit); awaitCancellation() }, {}) }
        started.await()
        stopped.cancelAndJoin()
        assertEquals("", Store.error(ctx))
        assertEquals(synced, Store.syncedAt(ctx))
        assertEquals(listOf(issue), Store.issues(ctx))
        assertFalse(Store.refreshing(ctx))
    }

    @Test fun launcherRedrawFailureDoesNotDiscardSuccessfulData() = runBlocking {
        assertEquals(listOf(issue), Store.sync(ctx, { listOf(issue) }, { error("Launcher unavailable") }))
        assertEquals("", Store.error(ctx))
        assertEquals(listOf(issue), Store.issues(ctx))
    }

    @Test fun retriesAreBoundedAndNeverRetryCredentialsOrCancellation() {
        assertTrue(shouldRetrySync(UnknownHostException(), 0, manual = true))
        assertTrue(shouldRetrySync(IOException("connection reset"), 1, manual = true))
        assertFalse(shouldRetrySync(UnknownHostException(), 2, manual = true))
        assertTrue(shouldRetrySync(UnknownHostException(), 3, manual = false))
        assertTrue(shouldRetrySync(GitHubHttpException(503, "Unavailable"), 1, manual = true))
        assertTrue(shouldRetrySync(GitHubHttpException(403, "Rate limit", rateLimited = true), 0, manual = false))
        assertFalse(shouldRetrySync(GitHubHttpException(401, "Bad credentials"), 0, manual = true))
        assertFalse(shouldRetrySync(GitHubHttpException(403, "Forbidden"), 0, manual = false))
        assertFalse(shouldRetrySync(CancellationException(), 0, manual = true))
        assertFalse(shouldRetrySync(IllegalArgumentException(), 0, manual = true))
    }

    @Test fun searchCachesClosedIssuesWithoutChangingTheQueueOrWidgetSync() = runBlocking {
        Store.sync(ctx, { listOf(issue) }, {})
        val synced = Store.syncedAt(ctx)
        val done = issue.copy(number = 8, state = "closed", stateReason = "completed")
        val dropped = issue.copy(number = 9, state = "closed", stateReason = "not_planned")
        assertEquals(listOf(issue), Store.search(ctx))
        Store.syncSearch(ctx) { listOf(issue, done, dropped) }
        assertEquals(listOf(issue, done, dropped), Store.search(ctx))
        assertEquals(listOf(issue), Store.issues(ctx))
        assertEquals(synced, Store.syncedAt(ctx))
        assertEquals("", Store.error(ctx))
        assertTrue(runCatching { Store.syncSearch(ctx) { throw UnknownHostException() } }.isFailure)
        assertEquals(listOf(issue, done, dropped), Store.search(ctx))
        assertEquals("", Store.error(ctx))
        Store.save(ctx, "another/repo", "another-token")
        assertTrue(Store.search(ctx).isEmpty())
    }

    @Test fun oldRepositorySearchCannotRepopulateCacheAfterSettingsChange() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = async {
            runCatching { Store.syncSearch(ctx) { started.complete(Unit); finish.await(); listOf(issue) } }
        }
        started.await()
        Store.save(ctx, "another/repo", "another-token")
        finish.complete(Unit)
        assertTrue(request.await().exceptionOrNull() is CancellationException)
        assertTrue(Store.search(ctx).isEmpty())
    }
}
