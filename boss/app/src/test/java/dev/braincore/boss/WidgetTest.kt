package dev.braincore.boss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WidgetTest {
    private fun state(synced: Long = 0, problem: SyncProblem? = null, refreshing: Boolean = false) =
        WidgetState(true, emptyMap(), synced, problem, refreshing, emptySet())

    private val now = Calendar.getInstance().apply { set(2026, 10, 5, 22, 30) }.timeInMillis
    private val earlier = now - 24 * 60_000L // 22:06 the same day

    @Test fun widgetLoadsTheSameAssigneeAndWorkstreamFiltersAsTheQueue() = kotlinx.coroutines.runBlocking {
        val ctx: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("boss", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putString("owner", "alex").commit()
        try {
            val tasks = OPEN.flatMapIndexed { index, status ->
                val task = Issue(index * 10, "Task", "", listOf(status), "open", "", "", repo = "alex/studio")
                listOf(task, task.copy(number = task.number + 1, assignees = listOf("ALEX")),
                    task.copy(number = task.number + 2, assignees = listOf("sam")))
            }
            Store.sync(ctx, { tasks }, {})
            assertEquals(8, WidgetState.load(ctx).groups.values.flatten().size)
            assertEquals(2, WidgetState.load(ctx).groups["input-needed"]!!.size)
            Store.setShowEveryone(ctx, true)
            val all = WidgetState.load(ctx)
            assertEquals(12, all.groups.values.flatten().size)
            assertEquals(2, all.groups["input-needed"]!!.size) // Collaborators' cards still wait on them.
            assertEquals(4, all.groups["on-hold"]!!.size)
            Store.setShowEveryone(ctx, false)
            Store.setFilter(ctx, setOf("-studio"))
            assertTrue(WidgetState.load(ctx).groups.values.all { it.isEmpty() })
            Store.setFilter(ctx, emptySet())
            prefs.edit().remove("owner").commit()
            assertEquals(12, WidgetState.load(ctx).groups.values.flatten().size)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun networkErrorsReadAsOffline() {
        assertEquals("offline", Store.describe(UnknownHostException("Unable to resolve host \"api.github.com\"")))
        assertEquals("offline", Store.describe(SocketTimeoutException("timeout")))
        assertEquals("GitHub 401: Bad credentials", Store.describe(IOException("GitHub 401: Bad credentials")))
    }

    @Test fun footerKeepsLastSyncVisibleWhileGivingTheNextStep() {
        val synced = footerTail(state(earlier), now)
        assertTrue(synced, synced.startsWith("Last synced ") && synced.contains("10:06"))
        assertEquals("$synced · Refresh requested", footerTail(state(earlier, refreshing = true), now))
        assertEquals("$synced · Will try again", footerTail(state(earlier, SyncProblem.TEMPORARY), now))
        assertEquals("$synced · Check GitHub access", footerTail(state(earlier, SyncProblem.ACCESS), now))
        assertEquals("$synced · Open Boss for details", footerTail(state(earlier, SyncProblem.OTHER), now))
        assertEquals("Not synced yet", footerTail(state(), now))
        assertEquals("Not synced yet · Will try again", footerTail(state(problem = SyncProblem.TEMPORARY), now))
        assertTrue(!errorText("offline", earlier).contains("You're offline"))
    }
}
