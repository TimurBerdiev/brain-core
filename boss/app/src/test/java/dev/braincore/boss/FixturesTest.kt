package dev.braincore.boss

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The task format cases shared with `scripts/task.py` (the JSON files in `tests/fixtures/`, read by `scripts/test_fixtures.py` too),
 * so the app and the script can't drift apart quietly. A failing case here means one side changed the format alone.
 */
class FixturesTest {
    private val dir = File(System.getProperty("fixtures") ?: "../../tests/fixtures")

    private fun cases(name: String, key: String = "cases"): List<JSONObject> =
        JSONObject(File(dir, "$name.json").readText()).getJSONArray(key).let { a -> (0 until a.length()).map(a::getJSONObject) }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)

    private fun issue(labels: List<String>, body: String = "", state: String = "open", stateReason: String = "") =
        Issue(7, "t", body, labels, state, "2026-11-05T10:00:00Z", "", stateReason = stateReason)

    @Test fun cards() = cases("cards").forEach { c ->
        val name = c.getString("name")
        val card = parseCard(Comment(1, "a", CARD_MARK + "\n" + c.getJSONArray("card").strings().joinToString("\n"), "", "").text)
        assertEquals(name, c.getString("question"), card.question)
        assertEquals(name, c.getJSONArray("options").strings(), card.options)
        assertEquals(name, c.getString("recommend"), card.recommend)
    }

    @Test fun status() = cases("status").forEach { c ->
        val labels = c.getJSONArray("labels").strings()
        assertEquals(labels.toString(), c.getString("status"), issue(labels).status)
    }

    @Test fun fields() = cases("status", "fields").forEach { c ->
        val i = issue(emptyList(), c.getString("body"))
        assertEquals(c.getString("body"), c.getString("waiting_on"), i.waitingOn)
        assertEquals(c.getString("body"), c.getString("check_after"), i.checkAfter)
    }

    @Test fun transitions() = cases("transitions").forEach { c ->
        val name = c.getString("name")
        val b = c.getJSONObject("before")
        val before = issue(b.getJSONArray("labels").strings(), b.getString("body"), b.getString("state"), b.optString("state_reason"))
        val change = statusChange(before, c.getString("to"), c.text("waiting_on"), c.text("check_after"))
        val want = c.getJSONObject("after")
        val state = change.state ?: before.state
        assertEquals(name, want.getString("body"), change.body)
        assertEquals(name, want.getJSONArray("labels").strings(), change.labels)
        assertEquals(name, want.getString("state"), state)
        if (state == "closed") assertEquals(name, want.getString("state_reason"), change.stateReason)
        val after = issue(change.labels, change.body, state, change.stateReason ?: "")
        assertEquals(name, c.getString("to"), after.status)
    }

    @Test fun claims() = cases("claims").forEach { c ->
        assertEquals(c.getString("subject"), c.getString("holder"), claimHolder(c.getString("subject"), c.getDouble("age_hours")))
    }
}
