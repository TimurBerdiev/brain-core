package dev.braincore.boss

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders whole screens, light and dark, to app/build/screens/ so a change can be looked at without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, laidOut: () -> Unit = {}, content: @Composable () -> Unit) {
        val dark = mutableStateOf(false)
        rule.setContent { BossTheme(dark = dark.value) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.waitForIdle()
        rule.runOnIdle(laidOut)
        rule.waitForIdle()
        save(name)
        rule.runOnIdle { dark.value = true }
        rule.waitForIdle()
        save("$name-dark")
    }

    private fun save(name: String) {
        val out = File("build/screens/$name.png").apply { parentFile?.mkdirs() }
        // Draw the window in software (captureToImage's PixelCopy times out under Robolectric).
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            ?: rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun i(n: Int, title: String, labels: List<String>, body: String = "") =
        Issue(n, title, body, labels, "open", "", "https://github.com/alex/brain/issues/$n")

    private val issues = listOf(
        i(36, "Studio: high-contrast highlight and nicer subtitles", listOf("input-needed"), "Waiting on: Which subtitle preset should be the default?").copy(repo = "alex/studio"),
        i(38, "Studio: check subscriptions, especially the cancel path", listOf("input-needed", "p1"), "Waiting on: Decision on webhooks / uid in checkout / §312k").copy(repo = "alex/studio"),
        i(41, "Proxy: broker health checks", listOf("ready", "ws:proxy")).copy(claim = "claude@runner#3f2a"),
        i(43, "Studio: voiceover pauses between scenes", listOf("ready", "p1")).copy(repo = "alex/studio", assignees = listOf("alex")),
        i(47, "Studio: pricing page copy", listOf("input-needed"), "Waiting on: Which plan names?").copy(repo = "alex/studio", assignees = listOf("sam")),
        i(44, "Boss: widget shows the card's options", listOf("ready", "p3")),
        i(24, "Proxy online: warm up Reddit and question posts", listOf("on-hold", "ws:proxy"), "Waiting on: #20 (Reddit account)\nCheck after: 2026-12-01"),
        i(12, "Phone line: pick a telephony provider", listOf("backlog")),
        i(13, "Home: shortlist neighbourhoods", listOf("backlog", "ws:home")),
    )

    private val cardText = """
        <!-- card -->
        ### Which subtitle preset should be the default?
        Options: Pop | Glide | Reveal | Bar
        Recommend: Pop, it reads best on a phone

        - **Pop**: the word jumps out, still calm
        - **Bar**: strongest contrast, a bit heavy
        - Glide and Reveal read weaker on pastel accents

        [All four on staging](https://staging.example.com) · [diff](https://github.com/alex/studio/commit/abc1234)
    """.trimIndent()

    private val comments = listOf(
        Comment(1, "claude-agent", "Rendered the four presets on staging; contact sheet attached.", "2026-11-05T10:00:00Z", ""),
        Comment(2, "claude-agent", cardText, "2026-11-05T10:05:00Z", ""),
    )

    @Test fun list() = shoot("list") {
        ListContent(issues, emptySet(), loading = false, error = "", syncedAt = System.currentTimeMillis(), {}, {}, {}, {})
    }

    @Test fun listForOwner() = shoot("list-owner") {
        ListContent(issues, emptySet(), loading = false, error = "", syncedAt = System.currentTimeMillis(), {}, {}, {}, {}, me = "alex")
    }

    private val assigneeTasks = listOf(
        i(101, "Your next task", listOf("ready")).copy(assignees = listOf("alex")),
        i(102, "Unassigned shared task", listOf("ready")).copy(repo = "alex/studio"),
        i(103, "Collaborator task", listOf("ready")).copy(repo = "alex/studio", assignees = listOf("sam")),
        i(104, "Collaborator decision", listOf("input-needed")).copy(assignees = listOf("sam")),
    )
    private val assigneeCompleted = listOf(
        i(105, "Your completed task", emptyList()).copy(state = "closed"),
        i(106, "Collaborator completed task", emptyList()).copy(state = "closed", assignees = listOf("sam")),
    )

    @Test @Config(qualifiers = "w400dp-h1500dp-xxhdpi")
    fun listHidesOthersAcrossQueueAndCompletedByDefault() {
        shoot("list-assignees-default") {
            ListContent(assigneeTasks, emptySet(), false, "", 0, {}, {}, {}, {}, me = "alex",
                completed = CompletedTasks(assigneeCompleted, loaded = true), showCompleted = true)
        }
        rule.onNodeWithText("Your next task").assertIsDisplayed()
        rule.onNodeWithText("Unassigned shared task").assertIsDisplayed()
        rule.onNodeWithText("Your completed task").assertIsDisplayed()
        rule.onNodeWithText("Collaborator task").assertDoesNotExist()
        rule.onNodeWithText("Collaborator decision").assertDoesNotExist()
        rule.onNodeWithText("Collaborator completed task").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w400dp-h1500dp-xxhdpi")
    fun listToggleShowsEveryoneAndKeepsAssigneeBadges() {
        var show by mutableStateOf(false)
        shoot("list-assignees-everyone", laidOut = {
            rule.onNodeWithText("Show everyone's tasks").performClick()
        }) {
            ListContent(assigneeTasks, emptySet(), false, "", 0, {}, {}, {}, {}, me = "alex",
                showEveryone = show, onShowEveryone = { show = it },
                completed = CompletedTasks(assigneeCompleted, loaded = true), showCompleted = true)
        }
        rule.onNodeWithText("Collaborator task").assertIsDisplayed()
        rule.onNodeWithText("Collaborator decision").assertIsDisplayed()
        rule.onNodeWithText("Collaborator completed task").assertIsDisplayed()
        rule.onNodeWithText("studio#103 · @sam · studio").assertIsDisplayed()
        rule.onNodeWithText("Show everyone's tasks").performClick()
        rule.onNodeWithText("Collaborator task").assertDoesNotExist()
        rule.onNodeWithText("Collaborator completed task").assertDoesNotExist()
    }

    @Test fun globalSearchIncludesCollaboratorsEvenWhenQueueHidesThem() {
        shoot("search-assignees") {
            SearchContent(assigneeTasks + assigneeCompleted, "Collaborator", false, "", {}, {}, {}, {})
        }
        rule.onNodeWithText("Collaborator task").assertIsDisplayed()
        rule.onNodeWithText("Collaborator decision").assertIsDisplayed()
        rule.onNodeWithText("Collaborator completed task").assertIsDisplayed()
    }

    @Test fun listOfflineFiltered() = shoot("list-offline") {
        ListContent(issues, setOf("proxy"), loading = false, error = "offline", syncedAt = System.currentTimeMillis() - 3_600_000, {}, {}, {}, {})
    }

    @Test fun listExceptFilter() = shoot("list-except") {
        ListContent(issues, setOf("-proxy"), loading = false, error = "", syncedAt = System.currentTimeMillis(), {}, {}, {}, {})
    }

    private val dispatcherFile = RepoFile("filter: [studio]\n", "abc")

    @Test fun taskRepositoryAccessShowsWhichPermissionsWork() {
        val report = RepositoryAccessReport(listOf(
            RepositoryAccess(TaskRepo("Workspace", "alex/brain"), ReadAccess(), ReadAccess()),
            RepositoryAccess(TaskRepo("studio", "alex/studio"), ReadAccess(),
                ReadAccess("GitHub denied access (403). Check the token's repositories and permissions.", denied = true)),
        ))
        var rechecked = false
        shoot("settings-repo-access") {
            Column(Modifier.padding(16.dp)) {
                RepositoryAccessSection(report, emptyList(), false, true, { rechecked = true }, {})
            }
        }
        rule.onNodeWithText("alex/brain").assertIsDisplayed()
        rule.onNodeWithText("alex/studio").assertIsDisplayed()
        rule.onNodeWithText("Contents · Can't read").assertIsDisplayed()
        rule.onNodeWithText("Manage tokens on GitHub").assertIsDisplayed()
        rule.onNodeWithText("Check access").performClick()
        rule.runOnIdle { assertEquals(true, rechecked) }
    }

    private val workstreamNames = listOf("home", "studio", "proxy")

    @Test fun dispatcherUnsaved() = shoot("dispatcher-unsaved") {
        Column(Modifier.padding(16.dp)) {
            DispatcherSection(DispatcherState(dispatcherFile, setOf("studio"), setOf("-proxy")), workstreamNames, {}, {}, {}, {})
        }
    }

    @Test fun dispatcherNoWriteAccess() = shoot("dispatcher-no-write") {
        Column(Modifier.padding(16.dp)) {
            DispatcherSection(
                DispatcherState(dispatcherFile, setOf("studio"), setOf("-proxy"), error = dispatcherError(GitHubHttpException(403, "GitHub 403"))),
                workstreamNames, {}, {}, {}, {},
            )
        }
    }

    @Test fun dispatcherSaveCyclesChipAndSaves() {
        var state by mutableStateOf(DispatcherState(dispatcherFile, setOf("studio"), setOf("studio")))
        var saved = false
        rule.setContent {
            BossTheme {
                DispatcherSection(state, workstreamNames, { state = state.copy(draft = it) }, { saved = true }, {}, {})
            }
        }
        rule.onNodeWithText("proxy").performClick()
        rule.onNodeWithText("proxy").performClick()
        rule.onNodeWithText("Ready tasks from: studio, not proxy").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        assertEquals(true, saved)
    }

    private val searchTasks = listOf(
        i(70, "Studio: export analytics", listOf("ready", "ws:studio")),
        i(81, "Boss: export search results", listOf("ws:boss")).copy(state = "closed", stateReason = "completed"),
        i(7, "Proxy: old export experiment", listOf("ws:proxy")).copy(state = "closed", stateReason = "not_planned"),
    )

    @Test fun searchIncludesAndOpensCompletedTasks() {
        var opened: String? = null
        shoot("search-results") {
            SearchContent(searchTasks, "export", loading = false, error = "", {}, { opened = it }, {}, {})
        }
        rule.onNodeWithText(searchTasks[1].title).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("81", opened) }
    }

    @Test fun searchFiltersWhileTypingAndCanClear() {
        val query = mutableStateOf("")
        shoot("search-all") {
            SearchContent(searchTasks, query.value, loading = false, error = "", { query.value = it }, {}, {}, {})
        }
        rule.onNode(hasSetTextAction()).performTextInput("#81")
        rule.onNodeWithText(searchTasks[1].title).assertIsDisplayed()
        rule.onNodeWithText(searchTasks[0].title).assertDoesNotExist()
        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.onNodeWithText(searchTasks[0].title).assertIsDisplayed()
    }

    @Test fun searchOpensQualifiedTaskWhenHomeHasTheSameNumber() {
        val home = searchTasks.first()
        val remote = home.copy(title = "Fix export", repo = "alex/studio", repoAlias = "studio")
        var opened: String? = null
        shoot("search-qualified") {
            SearchContent(listOf(home, remote), "studio#70", loading = false, error = "", {}, { opened = it }, {}, {})
        }
        rule.onNodeWithText(home.title).assertDoesNotExist()
        rule.onNodeWithText(remote.title).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("alex/studio#70", opened) }
    }

    @Test fun searchEmpty() = shoot("search-empty") {
        SearchContent(searchTasks, "nothing matches", loading = false, error = "", {}, {}, {}, {})
    }

    @Test fun searchLoading() = shoot("search-loading") {
        SearchContent(emptyList(), "", loading = true, error = "", {}, {}, {}, {})
    }

    @Test fun searchOfflineKeepsSavedResults() = shoot("search-offline") {
        SearchContent(searchTasks, "export", loading = false, error = "offline", {}, {}, {}, {})
    }

    @Test fun completedTasks() {
        val done = i(8, "Boss: refresh recovers after a network failure", listOf("ws:studio"))
            .copy(state = "closed", stateReason = "completed")
        var opened: String? = null
        shoot("list-completed") {
            ListContent(
                issues.takeLast(2), emptySet(), loading = false, error = "", syncedAt = System.currentTimeMillis(),
                onRefresh = {}, onOpen = { opened = it }, onSettings = {}, onFilter = {},
                completed = CompletedTasks(listOf(done), loaded = true), showCompleted = true,
            )
        }
        rule.onNodeWithText(done.title).performScrollTo().performClick()
        rule.runOnIdle { assertEquals("8", opened) }
    }

    @Test fun completedTaskDetails() = shoot("completed-detail") {
        val done = issues[0].copy(state = "closed", stateReason = "completed", body = "## Goal\nFix widget sync.\n\n## Result\nReleased and verified.")
        DetailContent(done.ref, done, comments.take(1), loading = false, busy = false, error = "", {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> })
    }

    @Test @Config(qualifiers = "w400dp-h1500dp-xxhdpi")
    fun card() = shoot("card") {
        DetailContent(issues[0].ref, issues[0], comments, loading = false, busy = false, error = "", {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> })
    }

    @Test fun yesNoCard() = shoot("card-yes-no") {
        DetailContent(issues[1].ref, issues[1], emptyList(), loading = false, busy = false, error = "", {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> })
    }

    @Test fun onHold() = shoot("on-hold") {
        val issue = issues[5].copy(body = "Waiting on: #20 (Reddit account)\nCheck after: 2026-12-01\n\n## Goal\nWarm up the account.\n\n## Next\n- Two question posts a week\n- Reply to comments")
        DetailContent(issue.ref, issue, comments.take(1), loading = false, busy = false, error = "", {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> })
    }

    @Test fun settings() = shoot("settings") { SettingsScreen(canClose = true, onClose = {}, onSaved = {}) }

    @Test fun askAiPicker() = shoot("ask-ai-picker") {
        AskAiDialog(AiReference.task(issues[0], "alex/brain"), {}, {})
    }

    @Test fun askAiOnlyHandsOffAfterChoosingAndSupportsBothApps() {
        val sent = mutableListOf<Pair<AiApp, AiReference>>()
        val ref = AiReference.task(issues[0], "alex/brain")
        shoot("ask-ai-action") { AskAiAction(ref) { app, reference -> sent += app to reference } }
        for (app in AiApp.entries) {
            val before = sent.size
            rule.onNodeWithText("Ask AI").performClick()
            rule.onNodeWithText(ref.text).assertIsDisplayed()
            rule.runOnIdle { assertEquals(before, sent.size) }
            rule.onNodeWithText(app.label).performClick()
            rule.runOnIdle { assertEquals(app to ref, sent.last()) }
            rule.onNodeWithText("Claude").assertDoesNotExist()
        }
    }

    private fun assertChooser(reference: AiReference) {
        rule.onNodeWithText(reference.text).assertIsDisplayed()
        rule.onNodeWithText("Claude").assertIsDisplayed()
        rule.onNodeWithText("ChatGPT").assertIsDisplayed()
    }

    @Test fun tasksAskAi() {
        shoot("list-ask-ai") {
            ListContent(issues, emptySet(), false, "", 0, {}, {}, {}, {}, homeRepo = "alex/brain")
        }
        rule.onNodeWithText("Ask AI").performClick()
        assertChooser(AiReference.screen("Tasks", "alex/brain"))
    }

    @Test fun detailAskAi() {
        val task = issues[0]
        shoot("detail-ask-ai") {
            DetailContent(task.ref, task, emptyList(), false, false, "", {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, homeRepo = "alex/brain")
        }
        rule.onNodeWithText("Ask AI").performClick()
        assertChooser(AiReference.task(task, "alex/brain"))
    }

    @Test fun activityAskAi() {
        val event = ActivityItem("1", "alex/app", "Fix #86", "Private log", "2026-11-14T17:45:00Z", "https://github.com/alex/app/commit/abc", "Commit")
        shoot("activity-ask-ai") {
            ActivityContent(listOf(event), listOf(event.repo), setOf(event.repo), 24, overviewNow, false, "", 0,
                {}, {}, {}, {}, {}, homeRepo = "alex/brain")
        }
        rule.onNodeWithContentDescription("Ask AI about this change").performClick()
        assertChooser(AiReference.activity(event))
    }

    @Test fun activityHeaderAskAi() {
        shoot("activity-header-ask-ai") {
            ActivityContent(emptyList(), emptyList(), emptySet(), 24, overviewNow, false, "", 0,
                {}, {}, {}, {}, {}, homeRepo = "alex/brain")
        }
        rule.onNodeWithText("Ask AI").performClick()
        assertChooser(AiReference.screen("Activity", "alex/brain"))
    }

    @Test fun usageAskAi() {
        shoot("usage-ask-ai") {
            UsageContent(emptyList(), overviewNow, false, "", {}, {}, homeRepo = "alex/brain")
        }
        rule.onNodeWithText("Ask AI").performClick()
        assertChooser(AiReference.screen("Usage", "alex/brain"))
    }

    private val overviewNow = java.time.Instant.parse("2026-11-14T18:00:00Z").toEpochMilli()
    @Test fun activity() = shoot("activity") {
        Column {
            Box(Modifier.weight(1f)) {
                ActivityContent(listOf(
                    ActivityItem("1", "alex/app", "Add Usage and Activity tabs", "alex · Codex", "2026-11-14T17:45:00Z", "https://github.com/alex/app", "Commit"),
                    ActivityItem("2", "alex/brain", "#73 · Subscription usage", "claude-agent · Verified five-hour and weekly windows", "2026-11-14T16:30:00Z", "https://github.com/alex/brain/issues/73", "Comment"),
                    ActivityItem("3", "alex/studio", "Fix export progress after reconnecting", "alex", "2026-11-14T15:10:00Z", "https://github.com/alex/studio", "Commit"),
                ), listOf("alex/app", "alex/brain", "alex/studio"), setOf("alex/app", "alex/brain", "alex/studio"),
                    24, overviewNow, false, "", overviewNow, {}, {}, {}, {}, {})
            }
            OverviewNavigation("Activity") {}
        }
    }

    @Test fun usage() = shoot("usage") {
        Column {
            Box(Modifier.weight(1f)) {
                UsageContent(listOf(
                    ProviderUsage("codex", "Codex", "2026-11-14T17:55:00Z", auth = "authenticated", windows = listOf(
                        UsageWindow("Codex", 18.0, 300, "2026-11-14T20:00:00Z"), UsageWindow("Codex", 42.0, 10080, "2026-11-18T10:00:00Z"))),
                    ProviderUsage("claude-code", "Claude Code", auth = "authenticated", error = "runner subscription token cannot read usage. Interactive sign-in is needed."),
                    ProviderUsage("antigravity", "Antigravity", auth = "not_authenticated", error = "Sign in on the runner to enable quota measurements."),
                ), overviewNow, false, "", {}, {})
            }
            OverviewNavigation("Usage") {}
        }
    }

    private val sampleRuns = listOf(
        RunEntry("alex/studio", "deep-test-20261122-095202-cd3b", "deep-test", "failed", "2026-11-14T16:52:00Z",
            "2026-11-14T17:58:00Z", summary = "4 scenarios: 3 pass, 1 fail (voiceover drift on phone)",
            sha = "12ab713aaaabbbbccccddddeeeeffff000011112", env = "staging", engine = "antigravity"),
        RunEntry("alex/studio", "release-20261114-024000-aa01", "release", "passed", "2026-11-14T02:40:00Z",
            "2026-11-14T03:05:00Z", summary = "Shipped e822aab: 40 commits, smoke 18/18 on staging and prod",
            ref = "prod-2026-11-14", env = "production", engine = "antigravity"),
        RunEntry("alex/studio", "release-20261113-024000-bb02", "release", "running", "2026-11-13T02:40:00Z",
            deadline = "2026-11-13T06:10:00Z", engine = "antigravity"),
    )

    @Test fun runs() = shoot("runs") {
        Column {
            Box(Modifier.weight(1f)) {
                RunsContent(sampleRuns, emptyMap(), emptySet(), overviewNow, false, "", {}, {}, {}, {})
            }
            OverviewNavigation("Runs") {}
        }
    }

    @Test fun runOpened() {
        val run = sampleRuns[0]
        val key = run.repo + ":" + run.id
        val detail = run.copy(checks = listOf(
            RunCheck("mcp-agent-video", "pass", "3 scenes, 24 s, no render errors"),
            RunCheck("studio-playback", "pass", "loads in 2.1 s, 0 page errors"),
            RunCheck("voiceover", "fail", "drift 310 ms on phone (limit 100 ms)"),
            RunCheck("export-download", "pass", "MP4 ready in 48 s")),
            links = listOf(RunLink("Log", "https://github.com/alex/studio"), RunLink("Evidence", "https://console.cloud.google.com/")),
            issues = listOf("studio#90"))
        var toggled = 0
        shoot("runs-opened") {
            RunsContent(sampleRuns, mapOf(key to detail), setOf(key), overviewNow, false, "", {}, {}, { toggled++ }, {})
        }
        rule.onNodeWithText("✗ voiceover — drift 310 ms on phone (limit 100 ms)").assertIsDisplayed()
        rule.onNodeWithText("Lost", substring = true).assertIsDisplayed()
        rule.onNodeWithText("4 scenarios: 3 pass, 1 fail (voiceover drift on phone)").performClick()
        assertEquals(1, toggled)
    }

    @Test fun usageStale() = shoot("usage-stale") {
        UsageContent(listOf(ProviderUsage("codex", "Codex", "2026-11-13T17:00:00Z", auth = "authenticated",
            windows = listOf(UsageWindow("Codex", 90.0, 300, "2026-11-13T20:00:00Z")))), overviewNow, false, "offline", {}, {})
    }

    @Test fun activityFiltersAndRepositorySelectionWork() {
        var hours by mutableStateOf(24)
        var watched by mutableStateOf(setOf("r/one"))
        val entries = listOf(
            ActivityItem("recent", "r/one", "Recent change", "", "2026-11-14T17:45:00Z", "https://github.com/r/one", "Commit"),
            ActivityItem("earlier", "r/one", "Earlier change", "", "2026-11-14T16:00:00Z", "https://github.com/r/one", "Commit"))
        shoot("activity-controls") {
            ActivityContent(entries, listOf("r/one", "r/two"), watched, hours, overviewNow, false, "", overviewNow,
                { hours = it }, { watched = it }, {}, {}, {})
        }
        rule.onNodeWithText("Earlier change").assertIsDisplayed()
        rule.onNodeWithText("Last hour").performClick()
        rule.onNodeWithText("Earlier change").assertDoesNotExist()
        rule.onNodeWithText("Recent change").assertIsDisplayed()
        rule.onNodeWithText("Watching 1 repositories · Edit").performClick()
        rule.onNodeWithText("r/two").assertIsDisplayed()
        rule.onNodeWithText("Watching 1 repositories · Done").performClick()
        rule.runOnIdle { assertEquals(setOf("r/one"), watched) }
    }

    // A long press on #44 (ready), the finger on the Backlog bubble.
    @Test fun bubbles() {
        val d = rule.density.density
        val press = Offset(200f * d, 810f * d) // on its row
        val state = BubbleState(BubbleDrag(issues.first { it.number == 44 }, press, press))
        shoot("bubbles", laidOut = { state.hover(state.centers(state.drag!!)[1]) }) {
            ListContent(issues, emptySet(), loading = false, error = "", syncedAt = System.currentTimeMillis(), {}, {}, {}, {}, bubbles = state)
        }
        rule.onNodeWithContentDescription("Move to Backlog").assertIsDisplayed()
        rule.onNodeWithContentDescription("Move to Ready").assertDoesNotExist()
        rule.runOnIdle { assertEquals("backlog", state.hot(state.drag!!)) }
    }

    private fun center(description: String) = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center

    /** Long-presses the row showing [text], drags to [to] (or straight down, off every bubble) and lets go. */
    private fun dragTask(text: String, to: (() -> Offset)?): List<Pair<String, String>> {
        val moved = mutableListOf<Pair<String, String>>()
        val opened = mutableListOf<String>()
        rule.setContent {
            BossTheme { ListContent(issues, emptySet(), loading = false, error = "", syncedAt = 0, {}, { opened += it }, {}, {}, onMove = { i, s -> moved += i.ref to s }) }
        }
        val start = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(start); advanceEventTime(1000); moveBy(Offset(0f, 1f)) }
        rule.waitForIdle()
        val target = to?.invoke() ?: (start + Offset(0f, 300f))
        rule.onRoot().performTouchInput { moveTo(target); moveBy(Offset(1f, 0f)); up() }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), opened)
        rule.onNodeWithContentDescription("Move to Dropped").assertDoesNotExist()
        return moved
    }

    @Test fun dropOnBubbleMovesTheTask() {
        assertEquals(listOf("#44" to "backlog"), dragTask("Boss: widget shows the card's options") { center("Move to Backlog") })
    }

    @Test fun dropOnBubbleMovesADecision() {
        assertEquals(listOf("studio#36" to "on-hold"), dragTask("Which subtitle preset should be the default?") { center("Move to On hold") })
    }

    @Test fun dropElsewhereDoesNothing() {
        assertEquals(emptyList<Pair<String, String>>(), dragTask("Boss: widget shows the card's options", null))
    }

    @Test fun tapStillOpens() {
        val opened = mutableListOf<String>()
        rule.setContent { BossTheme { ListContent(issues, emptySet(), loading = false, error = "", syncedAt = 0, {}, { opened += it }, {}, {}) } }
        rule.onNodeWithText("Boss: widget shows the card's options").performClick()
        rule.runOnIdle { assertEquals(listOf("44"), opened) }
    }

    // The dialog itself opens in its own window, which the capture doesn't see: render its content.
    @Test fun move() = shoot("move") {
        Box(Modifier.padding(24.dp)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.padding(16.dp)) { MoveOptions(current = "ready", selected = "on-hold") {} }
            }
        }
    }
}
