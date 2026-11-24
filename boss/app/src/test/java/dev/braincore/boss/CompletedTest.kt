package dev.braincore.boss

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompletedTest {
    private fun issue(number: Int, reason: String, state: String = "closed") = JSONObject()
        .put("number", number).put("title", "Task $number").put("state", state).put("state_reason", reason)

    @Test fun completedHistoryExcludesDroppedOpenAndPullRequests() {
        val completed = GitHub.completedIssues(listOf(
            issue(1, "completed"), issue(2, "not_planned"), issue(3, "", "open"),
            issue(4, "completed").put("pull_request", JSONObject()), issue(5, "completed"),
        ))
        assertEquals(listOf(1, 5), completed.map { it.number })
        assertTrue(completed.all { it.status == "done" })
        // History is never included in the widget's open groups.
        assertTrue(grouped(completed).all { it.second.isEmpty() })
    }

    @Test fun closedStatusAndReasonSurviveTheCache() {
        val done = GitHub.issue(issue(1, "completed"))
        assertEquals("done", GitHub.issue(GitHub.toJson(done)).status)
        val dropped = GitHub.issue(issue(2, "not_planned"))
        assertEquals("dropped", GitHub.issue(GitHub.toJson(dropped)).status)
        assertNull(currentCard(done.copy(labels = listOf("input-needed")), emptyList()))
        assertFalse(done.copy(claim = "old-agent").inProgress)
    }
}
