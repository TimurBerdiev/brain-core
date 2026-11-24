package dev.braincore.boss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun issue(labels: List<String>, body: String = "", state: String = "open") =
        Issue(7, "Title", body, labels, state, "2026-11-05T10:00:00Z", "https://github.com/alex/brain/issues/7")

    private fun comment(body: String) = Comment(1, "a", body, "2026-11-05T10:00:00Z", "")

    private val cardText = """
        <!-- card -->
        ### Which subtitle preset should be the default?
        Options: Pop | Glide |  Reveal | Bar
        Recommend: Pop, it reads best on a phone

        - **Pop**: calm, readable ![pop](https://github.com/alex/brain/raw/cards/36/pop.png)
        - Glide is the calmest

        [Log](https://github.com/alex/brain/issues/36)
    """.trimIndent()

    @Test fun statusDefaultsToForAgent() {
        assertEquals("ready", issue(listOf("p1", "ws:studio")).status)
        assertEquals("on-hold", issue(listOf("on-hold", "p2")).status)
    }

    @Test fun fields() {
        val i = issue(listOf("on-hold"), "Waiting on: #20 (Reddit)\nCheck after: 2026-12-01\n\n## Goal\nx")
        assertEquals("#20 (Reddit)", i.waitingOn)
        assertEquals("2026-12-01", i.checkAfter)
        assertEquals(listOf<String>(), issue(listOf("p1", "input-needed")).tags)
    }

    @Test fun parsesCard() {
        val c = parseCard(comment(cardText).text)
        assertEquals("Which subtitle preset should be the default?", c.question)
        assertEquals(listOf("Pop", "Glide", "Reveal", "Bar"), c.options)
        assertEquals("Pop, it reads best on a phone", c.recommend)
        assertTrue(c.rest.startsWith("- **Pop**"))
        assertEquals(listOf("Yes", "No"), parseCard("### OK?").choices)
    }

    @Test fun currentCardIsTheLatestUnanswered() {
        val alex = issue(listOf("input-needed"), "Waiting on: Old question\n\nbody")
        assertEquals("Old question", currentCard(alex, emptyList())!!.question)
        val card = comment(cardText)
        assertEquals("Which subtitle preset should be the default?", currentCard(alex, listOf(card))!!.question)
        // Answered after the card: fall back to the Waiting on line (a new card will come with the next question).
        assertEquals("Old question", currentCard(alex, listOf(card, comment("<!-- answer -->\nPop")))!!.question)
        assertNull(currentCard(issue(listOf("ready")), listOf(card)))
    }

    @Test fun statusChangeMatchesTaskPy() {
        val body = "Waiting on: X\nCheck after: 2026-01-01\n\n## Goal\ng"
        assertEquals("## Goal\ng", bodyFor(body, null, null))
        assertEquals("Waiting on: Y\nCheck after: 2026-02-02\n\n## Goal\ng", bodyFor(body, "Y", "2026-02-02"))
        assertEquals(listOf("p1", "ready", "ws:x"), labelsFor(listOf("ws:x", "input-needed", "p1"), "ready"))
        assertEquals(listOf("p1"), labelsFor(listOf("input-needed", "p1"), "done"))
    }

    @Test fun oldInReviewLabelReadsAsInputNeeded() {
        val old = issue(listOf("in-review", "p1"), "Waiting on: Which plan?")
        assertEquals("input-needed", old.status)
        assertEquals(listOf<String>(), old.tags)
        assertEquals("Which plan?", currentCard(old, emptyList())!!.question)
        assertEquals(listOf("p1", "ready"), labelsFor(old.labels, "ready"))
        assertEquals(1, grouped(listOf(old)).toMap()["input-needed"]!!.size)
    }

    @Test fun answerQuotesTheQuestion() {
        assertEquals("<!-- answer -->\n> Default?\n\n**Pop**\n\nmake it bigger", answerComment("Default?", "Pop", "make it bigger"))
        assertEquals("<!-- answer -->\n> Default?\n\nonly a note", answerComment("Default?", null, "only a note"))
        assertEquals("<!-- question -->\n> Default?\n\nwhy not Bar?", questionComment("Default?", " why not Bar? "))
    }

    @Test fun claims() {
        assertEquals("me@box#a1", claimHolder("claim #7 by me@box#a1", 1.0))
        assertEquals("me@box#a1", claimHolder("claim #7 by me@box#a1", 2.0))
        assertEquals("", claimHolder("claim #7 by me@box#a1", 2.001))
        assertEquals("", claimHolder("claim #7 by me@box#a1", 7.0))
        assertEquals("", claimHolder("release #7 by me@box#a1", 0.1))
        val list = grouped(listOf(issue(listOf("ready", "p1")), issue(listOf("ready", "p3")).copy(number = 8, claim = "x")))
        assertEquals(listOf(8, 7), list.first { it.first == "ready" }.second.map { it.number })
    }

    @Test fun eachQueueSectionListsMostRecentlyUpdatedIssuesFirst() {
        for (status in OPEN) {
            val oldest = issue(listOf(status, "p1")).copy(number = 1, updatedAt = "2026-11-01T10:00:00Z")
            val newest = issue(listOf(status, "p3")).copy(number = 2, updatedAt = "2026-11-07T10:00:00Z")
            val otherRepo = issue(listOf(status)).copy(number = 3, repo = "alex/studio")
            val unknown = issue(listOf(status)).copy(number = 4, updatedAt = "")

            val tasks = grouped(listOf(oldest, unknown, newest, otherRepo)).toMap().getValue(status)

            assertEquals(status, listOf(newest, otherRepo, oldest, unknown), tasks)
        }
    }

    @Test fun workstreamFilter() {
        val ex = issue(listOf("ready", "ws:studio"))
        val none = issue(listOf("ready"))
        assertTrue(ex.matches(emptySet()) && none.matches(emptySet()))
        assertTrue(ex.matches(setOf("studio", "home")))
        assertTrue(!none.matches(setOf("studio")))
        assertEquals(listOf("home", "studio"), workstreamsOf(listOf(ex, none), setOf("home")))
    }

    @Test fun exceptFilter() {
        val ex = issue(listOf("ready", "ws:studio"))
        val tr = issue(listOf("ready", "ws:proxy"))
        val none = issue(listOf("ready"))
        // "All except proxy" keeps the tasks without a workstream.
        assertEquals(listOf(true, false, true), listOf(ex, tr, none).map { it.matches(setOf("-proxy")) })
        assertEquals(listOf(true, false, false), listOf(ex, tr, none).map { it.matches(setOf("studio", "-proxy")) })
        assertEquals(listOf("proxy", "studio"), workstreamsOf(listOf(ex), setOf("-proxy")))
        assertEquals("All except proxy", describeFilter(setOf("-proxy")))
        assertEquals("studio, not home", describeFilter(setOf("-home", "studio")))
        assertEquals("All", describeFilter(emptySet()))
    }

    @Test fun chipCyclesOnlyExceptOff() {
        val only = cycle(emptySet(), "proxy")
        assertEquals(setOf("proxy"), only)
        assertEquals(Pick.ONLY, pickOf(only, "proxy"))
        val except = cycle(only, "proxy")
        assertEquals(setOf("-proxy"), except)
        assertEquals(Pick.EXCEPT, pickOf(except, "proxy"))
        assertEquals(emptySet<String>(), cycle(except, "proxy"))
    }

    @Test fun dispatcherFilterInWorkersYaml() {
        val yaml = "max_parallel: 2\n# `name`: only; `-name`: all except\nfilter: [studio]\ndefault: agro\n"
        assertEquals(setOf("studio"), dispatcherFilter(yaml))
        val out = withDispatcherFilter(yaml, setOf("-proxy", "home"))!!
        assertEquals("max_parallel: 2\n# `name`: only; `-name`: all except\nfilter: [\"home\", \"-proxy\"]\ndefault: agro\n", out)
        assertEquals(setOf("home", "-proxy"), dispatcherFilter(out))
        assertEquals("filter: []", withDispatcherFilter("filter: [\"-x\"]  # c", emptySet()))
        assertEquals(emptySet<String>(), dispatcherFilter("filter: []"))
        assertEquals(null, dispatcherFilter("default: agro"))
        assertEquals(null, withDispatcherFilter("default: agro", setOf("x")))
        assertEquals(null, withDispatcherFilter(yaml, setOf("ws:x")))
    }

    @Test fun imageUrls() {
        assertEquals(
            "https://api.github.com/repos/alex/brain/contents/36/pop.png?ref=cards",
            apiImageUrl("https://github.com/alex/brain/raw/cards/36/pop.png"),
        )
        assertNull(apiImageUrl("https://example.com/a.png"))
    }

    @Test fun markdownBlocks() {
        val b = parseBlocks(parseCard(comment(cardText).text).rest + "\n\n## Next\n1. one\n  - nested\n\n```\ncode\n```\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(Item(0, "-", "**Pop**: calm, readable"), b[0])
        assertEquals(Img("https://github.com/alex/brain/raw/cards/36/pop.png", "pop"), b[1])
        assertTrue(b.contains(Heading(2, "Next")))
        assertTrue(b.contains(Item(1, "-", "nested")))
        assertTrue(b.contains(Code("code")))
        assertTrue(b.contains(Code("| a | b |\n| 1 | 2 |")))
        assertTrue(parseBlocks("<!-- card -->\nhi").single() == Para("hi"))
    }

    @Test fun taskRepos() {
        val yaml = """
            # Registry
            defaults:
              account: alex
            repos:
              # --- Studio ---
              "studio":
                remote: 'https://github.com/alex/studio.git'
                path: repos/studio
                tasks: true
                notes: Task repo.
              tools: {remote: 'git@github.com:alex/tools.git', tasks: true}
              shared: {remote: 'https://github.com/alex/shared.git', tasks: assigned}
              site:
                remote: https://github.com/alex/site.git
                tasks: false
              app:
                remote: https://github.com/alex/app.git
        """.trimIndent()
        assertEquals(mapOf("shared" to "alex/shared", "studio" to "alex/studio", "tools" to "alex/tools"), taskReposOf(yaml))
        assertTrue(taskReposOf("repos:\n  x:\n    remote: https://github.com/a/x.git\n    tasks: 'true'\n").isEmpty())
    }

    @Test fun assigneeDecidesWhoseCardItIs() {
        val mine = issue(listOf("input-needed")).copy(number = 1, assignees = listOf("AlEx"))
        val unassigned = issue(listOf("input-needed")).copy(number = 2)
        val theirs = issue(listOf("input-needed")).copy(number = 3, assignees = listOf("sam"))
        val groups = grouped(listOf(mine, unassigned, theirs), "alex").toMap()
        assertEquals(listOf(1, 2), groups["input-needed"]!!.map { it.number })
        assertEquals(listOf(3), groups["on-hold"]!!.map { it.number })
        assertEquals(listOf("sam"), theirs.othersOnly("alex"))
        assertEquals(3, grouped(listOf(mine, unassigned, theirs)).toMap()["input-needed"]!!.size)  // owner unknown: all
        assertEquals("alex", ownerOf("owner: Alex\ngithub: alex\nname: brain\n"))
        assertEquals("", ownerOf("owner: Alex\n"))
    }

    @Test fun queueVisibilityCoversEveryStatusRepoAndOwnership() {
        for (status in OPEN + "done") {
            val base = issue(listOf(status), state = if (status == "done") "closed" else "open")
            val tasks = listOf(
                base.copy(number = 1, assignees = listOf("AlEx")),
                base.copy(number = 2),
                base.copy(number = 3, repo = "alex/studio"),
                base.copy(number = 4, repo = "alex/studio", assignees = listOf("sam")),
                base.copy(number = 5, assignees = listOf("someone", "ALEX")),
                base.copy(number = 6, assignees = listOf("someone")),
            )
            assertEquals(status, listOf(1, 2, 3, 5), visibleIssues(tasks, emptySet(), "alex").map { it.number })
            assertEquals(tasks, visibleIssues(tasks, emptySet(), "alex", showEveryone = true))
            assertEquals(tasks, visibleIssues(tasks, emptySet(), ""))
            assertEquals(listOf(3), visibleIssues(tasks, setOf("studio"), "alex").map { it.number })
            assertEquals(listOf(3, 4), visibleIssues(tasks, setOf("studio"), "alex", true).map { it.number })
            assertEquals(listOf("sam"), tasks[3].othersOnly("alex"))
            assertTrue(tasks[4].othersOnly("alex").isEmpty())
            // Search intentionally ignores both queue filters, even for collaborators' closed tasks.
            assertEquals(tasks[3], searchIssues(tasks, "studio#4").single())
        }
    }

    @Test fun assigneesSurviveTheCache() {
        val o = org.json.JSONObject("""{"number": 5, "title": "t", "state": "open", "labels": [], "assignees": [{"login": "alex"}]}""")
        val i = GitHub.issue(o)
        assertEquals(listOf("alex"), i.assignees)
        assertEquals(listOf("alex"), GitHub.issue(GitHub.toJson(i)).assignees)
    }

    @Test fun taskKeysAcrossRepos() {
        val home = issue(listOf("ready", "ws:home"))
        val other = home.copy(repo = "alex/studio", repoAlias = "Studio", labels = listOf("ready"))
        assertEquals("7" to "#7", home.key to home.ref)
        assertEquals("alex/studio#7" to "Studio#7", other.key to other.ref)
        assertEquals("alex/studio" to 7, splitKey(other.key))
        assertEquals("" to 7, splitKey(home.key))
        assertEquals(listOf("studio"), other.workstreams)
        assertTrue(other.matches(setOf("studio")))
        assertTrue(!other.matches(setOf("home")))
        assertEquals(2, searchIssues(listOf(home, other), "#7").size)
        assertEquals(other, GitHub.issue(GitHub.toJson(other)))
        val oldSnapshot = GitHub.toJson(other).apply { remove("x_repo_alias") }
        assertEquals("studio#7", GitHub.issue(oldSnapshot).ref)
    }
}
