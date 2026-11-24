package dev.braincore.boss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RunsTest {
    private val now = java.time.Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()

    private val index = """
        {"v": 1, "updated": "2026-11-22T11:00:00Z", "runs": [
          {"id": "deep-test-20261122-095202-cd3b", "kind": "deep-test", "status": "failed", "started": "2026-11-22T09:52:02Z",
           "ended": "2026-11-22T11:01:00Z", "summary": "4 scenarios: 3 pass, 1 fail", "engine": "antigravity",
           "target": {"sha": "12ab713aaaabbbbccccddddeeeeffff000011112", "env": "staging"}},
          {"id": "release-20261122-024000-aa01", "kind": "release", "status": "running", "started": "2026-11-22T02:40:00Z",
           "deadline": "2026-11-22T06:10:00Z"},
          {"kind": "broken entry without id"}
        ]}
    """.trimIndent()

    @Test fun readsTheIndexAndSkipsEntriesWithoutId() {
        val runs = readRunIndex(index, "alex/studio")
        assertEquals(listOf("deep-test-20261122-095202-cd3b", "release-20261122-024000-aa01"), runs.map { it.id })
        assertEquals("12ab713 · staging", runs[0].target())
        assertEquals("Took 1h 8m", runDuration(runs[0], now))
    }

    @Test fun aRunningRecordPastItsDeadlineIsLost() {
        val running = readRunIndex(index, "r/x")[1]
        assertEquals("lost", running.shownStatus(now))
        assertEquals("running", running.shownStatus(java.time.Instant.parse("2026-11-22T06:00:00Z").toEpochMilli()))
        assertTrue(runIsBad("lost"))
    }

    @Test fun mergesReposNewestFirstAndSurvivesTheCache() {
        val a = readRunIndex(index, "alex/studio")
        val b = listOf(RunEntry("alex/brain", "probe-20261122-110000-0001", "probe", "passed", "2026-11-22T11:00:00Z"))
        val merged = mergeRuns(mapOf("alex/studio" to a, "alex/brain" to b))
        assertEquals(listOf("probe", "deep-test", "release"), merged.map { it.kind })
        assertEquals(merged, runsFromJson(runsJson(merged)))
    }

    @Test fun detailKeepsOnlyHttpsLinks() {
        val run = readRun(org.json.JSONObject("""{"id": "x", "status": "failed", "checks": [{"name": "voiceover", "result": "fail", "note": "drift 300 ms"}],
            "links": [{"label": "Log", "url": "https://github.com/x"}, {"label": "bad", "url": "javascript:alert(1)"}], "issues": ["studio#90"]}"""), "r/x")
        assertEquals(listOf("Log"), run.links.map { it.label })
        assertEquals("drift 300 ms", run.checks.single().note)
        assertEquals(listOf("studio#90"), run.issues)
    }
}
