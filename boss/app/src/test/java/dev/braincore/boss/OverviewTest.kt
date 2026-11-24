package dev.braincore.boss

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OverviewTest {
    private val now = Instant.parse("2026-11-14T18:00:00Z").toEpochMilli()
    private fun event(repo: String, id: String, hours: Int) = ActivityItem(id, repo, "Change", "", Instant.ofEpochMilli(now - hours * 3_600_000L).toString(), "https://github.com/$repo", "Commit")

    @Test fun timeWindowsAreRollingAndDoNotMergeRepositories() {
        val events = listOf(event("r/one", "same", 0), event("r/two", "same", 0), event("r/one", "day", 23), event("r/one", "old", 71), event("r/one", "past", 73), event("r/one", "future", -1))
        assertEquals(2, activitySince(events + events.first(), 1, now).size)
        assertEquals(3, activitySince(events, 24, now).size)
        assertEquals(4, activitySince(events, 72, now).size)
    }

    @Test fun registrationIncludesNonTaskReposWithoutTreatingThemAsTasks() {
        val yaml = """repos:
  home: {remote: 'https://github.com/r/home.git', tasks: true}
  site: {remote: 'git@github.com:r/site.git'}
  other: {remote: 'https://gitlab.com/r/other'}
"""
        assertEquals(listOf("r/home", "r/site"), activityReposOf(yaml))
        assertEquals(mapOf("home" to "r/home"), taskReposOf(yaml))
    }

    @Test fun commitUsesCommitterDateAndExplicitCoauthorRatherThanGuessingAnAgent() {
        val o = JSONObject("""{"sha":"abc","html_url":"https://github.com/r/one/commit/abc","author":{"login":"alex"},"commit":{"message":"Ship activity\n\nCo-Authored-By: Codex <codex@example.invalid>","author":{"date":"2026-11-01T00:00:00Z"},"committer":{"date":"2026-11-14T18:00:00Z"}}}""")
        val c = commitActivity("r/one", o)
        assertEquals("Ship activity", c.title)
        assertEquals("alex · Codex", c.detail)
        assertEquals(now, millis(c.at))
        assertEquals(c, ActivityItem.read(c.json()))
    }

    @Test fun pushEventsOnlyAddClaimsSinceCommitsAreFetchedSeparately() {
        val o = JSONObject("""{"id":"1","type":"PushEvent","payload":{"ref":"refs/heads/main"},"created_at":"2026-11-14T18:00:00Z"}""")
        assertNull(eventActivity("r/one", o))
        o.getJSONObject("payload").put("ref", "refs/heads/claim/73")
        assertTrue(eventActivity("r/one", o)!!.title.contains("#73"))
    }

    @Test fun missingAndInvalidQuotasStayUnknownAndErrorsDoNotLookFresh() {
        val providers = readUsage("""{"schema_version":1,"providers":[{"key":"codex","auth_state":"authenticated","observed_at":"2026-11-14T17:50:00Z","windows":[{"name":"5h","used_percent":18,"resets_at":"2026-11-14T20:00:00Z"},{"name":"invalid","used_percent":101}]}]}""")
        assertEquals(3, providers.size)
        assertFalse(providers.first().fresh(now))
        val codex = providers.last()
        assertNull(codex.windows.last().used)
        assertFalse(codex.fresh(now))
        val complete = codex.copy(windows = codex.windows.take(1))
        assertTrue(complete.fresh(now))
        assertFalse(complete.copy(error = "Refresh failed").fresh(now))
        assertFalse(complete.fresh(now + 1_800_001))
        assertFalse(complete.fresh(now + 3 * 3_600_000))
        assertFalse(complete.fresh(now - 3_600_000))
    }

    @Test fun providerReportedLimitIsKeptSeparatelyFromWindowPercentages() {
        val codex = readUsage("""{"schema_version":1,"providers":[{"key":"codex","quota_reached":true}]}""").last()
        assertTrue(codex.quotaReached)
        assertTrue(codex.windows.isEmpty())
    }
}
