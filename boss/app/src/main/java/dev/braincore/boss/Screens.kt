package dev.braincore.boss

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat

// Stateless screens and pieces. MainActivity holds the state and the GitHub calls; ScreenshotTest renders these.

/** How a status reads and looks in the app. */
data class StatusLook(val name: String, val hint: String, val icon: ImageVector)

fun statusLook(status: String): StatusLook = when (status) {
    "input-needed" -> StatusLook("For you", "Waits on your answer", Icons.Default.Notifications)
    "ready" -> StatusLook("Ready", "Agents pick it up", Icons.Default.PlayArrow)
    "on-hold" -> StatusLook("On hold", "Waits for something else", Icons.Default.DateRange)
    "backlog" -> StatusLook("Backlog", "An idea, not ready yet", Icons.AutoMirrored.Filled.List)
    "done" -> StatusLook("Done", "Close as completed", Icons.Default.CheckCircle)
    "dropped" -> StatusLook("Dropped", "Close as not planned", Icons.Default.Clear)
    else -> StatusLook(status, "", Icons.Default.PlayArrow)
}

private val SECTION_NAMES = mapOf("input-needed" to "For you", "ready" to "Agents", "on-hold" to "On hold", "backlog" to "Backlog")

private fun syncedTime(at: Long) =
    DateUtils.formatSameDayTime(at, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT).toString()

/** A failed request says nothing certain about the connection right now. */
fun errorText(error: String, syncedAt: Long): String = when {
    error == "offline" && syncedAt > 0 -> "Couldn't update. Showing what was synced at ${syncedTime(syncedAt)}."
    error == "offline" -> "Couldn't update. Check your connection and try again."
    else -> error
}

// ---------------------------------------------------------------- list

internal data class CompletedTasks(
    val issues: List<Issue> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListContent(
    issues: List<Issue>,
    filter: Set<String>,
    loading: Boolean,
    error: String,
    syncedAt: Long,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onFilter: (Set<String>) -> Unit,
    completed: CompletedTasks = CompletedTasks(),
    showCompleted: Boolean = false,
    onToggleCompleted: () -> Unit = {},
    onRetryCompleted: () -> Unit = {},
    onSearch: () -> Unit = {},
    me: String = "",
    showEveryone: Boolean = false,
    onShowEveryone: (Boolean) -> Unit = {},
    onMove: (Issue, String) -> Unit = { _, _ -> },
    bubbles: BubbleState = remember { BubbleState() },
    homeRepo: String = "",
) {
    bubbles.onDrop = onMove
    var showBacklog by rememberSaveable { mutableStateOf(false) }
    val groups = grouped(visibleIssues(issues, filter, me, showEveryone), me)
    val mine = groups.first { it.first == "input-needed" }.second
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "header") {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Boss", style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    when (mine.size) { 0 -> "Nothing waits on you"; 1 -> "1 decision waits on you"; else -> "${mine.size} decisions wait on you" } +
                                        if (syncedAt > 0 && error.isEmpty()) " · ${syncedTime(syncedAt)}" else "",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search issues", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    if (error.isNotEmpty()) item(key = "error") { ErrorBanner(errorText(error, syncedAt), Modifier.padding(bottom = 12.dp)) }
                    item(key = "ask-ai") { AskAiAction(AiReference.screen("Tasks", homeRepo)) }
                    item(key = "filter") { WorkstreamFilter(workstreamsOf(issues + completed.issues, filter), filter, onChange = onFilter) }
                    item(key = "assignees") {
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = showEveryone, role = Role.Switch, onValueChange = onShowEveryone)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Show everyone's tasks", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (showEveryone) "All assignees · also in widget" else "Assigned to you or unassigned",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = showEveryone, onCheckedChange = null)
                        }
                    }
                    for ((status, list) in groups) {
                        if (status != "input-needed" && list.isEmpty()) continue
                        val backlog = status == "backlog"
                        item(key = "h-$status") {
                            SectionHeader(SECTION_NAMES[status] ?: status, list.size) {
                                if (backlog) TextButton(onClick = { showBacklog = !showBacklog }) { Text(if (showBacklog) "Hide" else "Show") }
                            }
                        }
                        when {
                            status == "input-needed" && list.isEmpty() -> item(key = "clear") { AllClear() }
                            status == "input-needed" -> for (issue in list) item(key = issue.key) {
                                DecisionRow(issue, onOpen, Modifier.padding(bottom = 10.dp), bubbles)
                            }
                            backlog && !showBacklog -> {}
                            else -> item(key = "g-$status") { TaskGroup(list, onOpen, bubbles) }
                        }
                    }
                    val done = visibleIssues(completed.issues, filter, me, showEveryone)
                        .sortedByDescending { it.updatedAt }
                    item(key = "h-completed") {
                        SectionHeader("Completed", done.size.takeIf { completed.loaded }) {
                            TextButton(onClick = onToggleCompleted) { Text(if (showCompleted) "Hide" else "Show") }
                        }
                    }
                    if (showCompleted) {
                        if (completed.loading) item(key = "completed-loading") {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Updating completed tasks…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (completed.error.isNotEmpty()) item(key = "completed-error") {
                            Column {
                                ErrorBanner(errorText(completed.error, 0))
                                TextButton(onClick = onRetryCompleted, enabled = !completed.loading) { Text("Try again") }
                            }
                        }
                        for (issue in done) item(key = "completed-${issue.key}") {
                            Box(Modifier.padding(bottom = 6.dp)) { TaskGroup(listOf(issue), onOpen, bubbles) }
                        }
                        if (completed.loaded && !completed.loading && done.isEmpty() && completed.error.isEmpty()) item(key = "completed-empty") {
                            Text(
                                "No completed tasks for these filters.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
            BubbleOverlay(bubbles)
        }
    }
}

/** Search is global: closed tasks and other workstreams remain discoverable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(
    issues: List<Issue>,
    query: String,
    loading: Boolean,
    error: String,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusOnEntry by rememberSaveable { mutableStateOf(true) }
    val results = remember(issues, query) { searchIssues(issues, query) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Search issues", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = { keyboard?.hide(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh search") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                label = { Text("Task reference or text") }, placeholder = { Text("#70, site#38, export…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) { {
                    IconButton(onClick = { onQuery(""); focus.requestFocus(); keyboard?.show() }) { Icon(Icons.Default.Clear, "Clear search") }
                } } else null,
                singleLine = true, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) {
                if (focusOnEntry) {
                    focus.requestFocus()
                    keyboard?.show()
                    focusOnEntry = false
                }
            }
            Text(
                "All assignees and workstreams · includes completed and dropped",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            if (error.isNotEmpty()) {
                ErrorBanner(errorText(error, 0))
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Try again") }
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    if (loading) "Loading all issues…" else "${results.size} ${if (results.size == 1) "issue" else "issues"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                for (issue in results) item(key = issue.key) {
                    Panel { TaskRow(issue, { keyboard?.hide(); onOpen(it) }, showStatus = true) }
                }
                if (results.isEmpty() && !loading && error.isEmpty()) item {
                    Text(
                        if (query.isBlank()) "No issues yet." else "No issues match “${query.trim()}”.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, count: Int? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = Overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Pill(count.toString(), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private val cardBorder @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** A white rounded panel with a hairline border: the one container shape used across the app. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val color = MaterialTheme.colorScheme.surfaceContainer
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = cardBorder, content = content)
    else Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = cardBorder, content = content)
}

@Composable
internal fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun AllClear() {
    Panel {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("You're all caught up", style = MaterialTheme.typography.titleMedium)
                Text("The agents have what they need.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun RepositoryAccessSection(
    report: RepositoryAccessReport?,
    pending: List<TaskRepo>,
    checking: Boolean,
    canCheck: Boolean,
    onCheck: () -> Unit,
    onManageToken: () -> Unit,
) {
    SectionHeader("Task repositories") {
        TextButton(onClick = onCheck, enabled = canCheck && !checking) {
            Text("Check access")
        }
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Checking access…", style = MaterialTheme.typography.bodyMedium)
                }
                report == null -> Text(
                    if (canCheck) "Check the token entered above." else "Enter a workspace repository and token to check access.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            report?.registryProblem?.takeUnless { checking }?.let {
                ErrorBanner(it)
                if (report?.repositories.orEmpty().size > 1) Text(
                    "Showing task repositories from the last saved list.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val rows = if (checking || report == null) pending.map { RepositoryAccess(it) } else report.repositories
            rows.forEachIndexed { index, result ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.repo.fullName, style = MaterialTheme.typography.titleSmall)
                    Text(result.repo.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RepositoryPermission("Issues", result.issues, checking)
                    RepositoryPermission("Contents", result.contents, checking)
                }
            }
            if (report?.registryProblem == null && report?.repositories?.size == 1 && !checking) {
                Text("No other task repositories are listed in repos.yaml.", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Checks read access. Answers and status changes need Issues: write.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!checking && report?.repositories.orEmpty().any { it.issues?.denied == true || it.contents?.denied == true }) {
                TextButton(onClick = onManageToken) { Text("Manage tokens on GitHub") }
            }
        }
    }
}

@Composable
private fun RepositoryPermission(label: String, access: ReadAccess?, checking: Boolean) {
    val colors = MaterialTheme.colorScheme
    val status = when {
        access != null -> when {
            access.problem == null -> "Can read"
            access.denied -> "Can't read"
            else -> "Couldn't verify"
        }
        checking -> "Checking…"
        else -> "Not checked"
    }
    Text(
        "$label · $status", style = MaterialTheme.typography.bodyMedium,
        color = when {
            access == null -> colors.onSurfaceVariant
            access.problem == null -> colors.primary
            access.denied -> colors.error
            else -> colors.onSurfaceVariant
        },
    )
    access?.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
}

/**
 * "All" plus one chip per workstream. A tap cycles a chip: only (✓) -> except (✗, struck through) -> off; several can
 * be on at once. Shared by the list, the settings and the dispatcher's filter.
 */
@Composable
internal fun WorkstreamFilter(workstreams: List<String>, filter: Set<String>, enabled: Boolean = true, onChange: (Set<String>) -> Unit) {
    if (workstreams.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun chip(label: String, pick: Pick, onClick: () -> Unit) {
            val except = pick == Pick.EXCEPT
            val on = if (except) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
            FilterChip(
                selected = pick != Pick.OFF, onClick = onClick, enabled = enabled,
                label = { Text(label, textDecoration = if (except) TextDecoration.LineThrough else null) },
                leadingIcon = when (pick) {
                    Pick.OFF -> null
                    Pick.ONLY -> { { Icon(Icons.Default.Check, "only", Modifier.size(FilterChipDefaults.IconSize)) } }
                    Pick.EXCEPT -> { { Icon(Icons.Default.Clear, "except", Modifier.size(FilterChipDefaults.IconSize)) } }
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    selectedContainerColor = if (except) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                    selectedLabelColor = on,
                    selectedLeadingIconColor = on,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = enabled, selected = pick != Pick.OFF,
                    borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = Color.Transparent,
                ),
            )
        }
        chip("All", if (filter.isEmpty()) Pick.ONLY else Pick.OFF) { onChange(emptySet()) }
        for (ws in workstreams) chip(ws, pickOf(filter, ws)) { onChange(cycle(filter, ws)) }
    }
}

/** The dispatcher's filter as Settings shows it: what is saved in workers.yaml, and the edit not saved yet. */
internal data class DispatcherState(
    val file: RepoFile? = null,
    val saved: Set<String> = emptySet(),
    val draft: Set<String> = emptySet(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String = "",
) {
    val loaded: Boolean get() = file != null
    val dirty: Boolean get() = loaded && draft != saved
}

/** Which ready tasks the agents pick; saved as a commit to workers.yaml, so it is an explicit Save, not every tap. */
@Composable
internal fun DispatcherSection(
    state: DispatcherState,
    workstreams: List<String>,
    onChange: (Set<String>) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
) {
    SectionHeader("Agents pick")
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loaded -> Text(
                    "Ready tasks from: " + describeFilter(state.draft),
                    style = MaterialTheme.typography.bodyLarge,
                )
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Reading the agents' filter…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.loaded) WorkstreamFilter(workstreams, state.draft, enabled = !state.saving, onChange = onChange)
            if (state.error.isNotEmpty()) {
                ErrorBanner(errorText(state.error, 0))
                if (!state.loaded) TextButton(onClick = onRetry, enabled = !state.loading) { Text("Try again") }
            }
            if (state.dirty) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave, enabled = !state.saving, shape = MaterialTheme.shapes.small, modifier = Modifier.height(44.dp)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text("Save")
                }
                TextButton(onClick = onReset, enabled = !state.saving) { Text("Undo") }
            }
            Text(
                "Autopilot and the orchestrator take ready tasks only from these. Saving commits workers.yaml, so the token needs Contents: read and write.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Errors from reading or saving workers.yaml, in words that say what to do. */
fun dispatcherError(e: Throwable): String = when ((e as? GitHubHttpException)?.code) {
    403 -> "This token can't change files. On GitHub, give it Contents: read and write, then save again."
    404 -> "This repository has no workers.yaml."
    409 -> "workers.yaml changed on GitHub in the meantime. Reloaded it; check and save again."
    else -> Store.describe(e)
}

/** A task that waits on the owner: the question first, the task second, an amber edge. */
@Composable
internal fun DecisionRow(issue: Issue, onOpen: (String) -> Unit, modifier: Modifier = Modifier, bubbles: BubbleState? = null) {
    Panel(modifier, onClick = { onOpen(issue.key) }) {
        Row(Modifier.height(IntrinsicSize.Min).liftToMove(issue, bubbles)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
            Column(Modifier.weight(1f).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DECISION", style = Overline, color = MaterialTheme.colorScheme.tertiary)
                    PriorityPill(issue)
                    issue.workstreams.firstOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(issue.waitingOn.ifBlank { issue.title }, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "${issue.ref} · ${issue.title}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun PriorityPill(issue: Issue) {
    when (issue.priority) {
        1 -> Pill("URGENT", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
        3 -> Pill("LOW", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The tasks of one status in one panel, hairlines between them. */
@Composable
internal fun TaskGroup(issues: List<Issue>, onOpen: (String) -> Unit, bubbles: BubbleState? = null) {
    Panel {
        Column {
            issues.forEachIndexed { i, issue ->
                TaskRow(issue, onOpen, bubbles = bubbles)
                if (i < issues.lastIndex) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
internal fun TaskRow(issue: Issue, onOpen: (String) -> Unit, showStatus: Boolean = false, bubbles: BubbleState? = null) {
    val c = MaterialTheme.colorScheme
    Row(
        // The long press sits inside the click, so it sees the gesture first and can cancel the click.
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onOpen(issue.key) }.liftToMove(issue, bubbles).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) { StatusMark(issue) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(issue.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildAnnotatedString {
                fun sep() { if (length > 0) append(" · ") }
                append(issue.ref)
                for (who in issue.assignees) { sep(); append("@$who") }
                if (showStatus) { sep(); append(statusLook(issue.status).name) }
                if (issue.inProgress) { sep(); withStyle(SpanStyle(color = c.primary, fontWeight = FontWeight.SemiBold)) { append("Working") }; append(" (${issue.claim.substringBefore('#')})") }
                if (issue.waitingOn.isNotBlank()) { sep(); append("Waits on ${issue.waitingOn}") }
                when (issue.priority) {
                    1 -> { sep(); withStyle(SpanStyle(color = c.error, fontWeight = FontWeight.SemiBold)) { append("Urgent") } }
                    3 -> { sep(); append("Low") }
                }
                for (ws in issue.workstreams) { sep(); append(ws) }
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The leading mark of a row: a filled dot while an agent works on it, a ring when it's free, a clock-ish icon on hold. */
@Composable
private fun StatusMark(issue: Issue) {
    val c = MaterialTheme.colorScheme
    when {
        issue.inProgress -> Box(Modifier.size(18.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.primary))
        }
        issue.status == "on-hold" -> Icon(Icons.Default.DateRange, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        issue.status == "done" -> Icon(Icons.Default.CheckCircle, null, tint = c.primary, modifier = Modifier.size(18.dp))
        issue.status == "dropped" -> Icon(Icons.Default.Clear, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        issue.status == "backlog" -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.outline))
        else -> Box(Modifier.size(14.dp).border(2.dp, c.primary, CircleShape))
    }
}

// ---------------------------------------------------------------- detail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailContent(
    ref: String,
    issue: Issue?,
    comments: List<Comment>,
    loading: Boolean,
    busy: Boolean,
    error: String,
    onBack: () -> Unit,
    onMove: () -> Unit,
    onOpenGitHub: () -> Unit,
    onReload: () -> Unit,
    onAnswer: (Card, String?, String) -> Unit,
    onAsk: (Card, String) -> Unit,
    homeRepo: String = "",
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(ref, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    issue?.let { AskAiAction(AiReference.task(it, homeRepo)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text("Open on GitHub") }, onClick = { menu = false; onOpenGitHub() })
                        DropdownMenuItem(text = { Text("Reload") }, onClick = { menu = false; onReload() })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (error.isNotEmpty()) ErrorBanner(errorText(error, 0))
            if (issue == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator() else Text("Task $ref not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(issue, onMove)
                    PriorityPill(issue)
                    for (ws in issue.workstreams) Text(ws, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (who in issue.assignees) Text("@$who", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // With a card the question is the headline; the task's title steps back.
                val hasCard = issue.status == "input-needed"
                Text(issue.title, style = if (hasCard) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    color = if (hasCard) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (issue.inProgress) InfoLine(Icons.Default.PlayArrow, "${issue.claim.substringBefore('#')} is working on it", MaterialTheme.colorScheme.primary)
                if (issue.status != "input-needed" && issue.waitingOn.isNotBlank()) InfoLine(
                    Icons.Default.DateRange,
                    "Waits on ${issue.waitingOn}" + if (issue.checkAfter.isNotBlank()) " · check after ${issue.checkAfter}" else "",
                )
            }
            val card = if (loading && comments.isEmpty()) null else currentCard(issue, comments)
            when {
                card != null -> DecisionCard(card, busy, onAnswer = { choice, note -> onAnswer(card, choice, note) }, onAsk = { onAsk(card, it) })
                issue.status == "input-needed" -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            val body = stripStatusLines(issue.body)
            if (body.isNotBlank()) Expandable("Details", initiallyOpen = card == null) { Markdown(body) }
            if (comments.isNotEmpty()) Expandable("Activity", count = comments.size, initiallyOpen = false) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { for (c in comments) LogEntry(c) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** The status as a tappable pill with a caret: tapping it opens "Move to…". */
@Composable
internal fun StatusChip(issue: Issue, onClick: () -> Unit) {
    val look = statusLook(issue.status)
    val mine = issue.status == "input-needed"
    val bg = if (mine) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (mine) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(onClick = onClick, shape = CircleShape, color = bg, contentColor = fg) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(look.icon, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(look.name, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun Expandable(title: String, count: Int? = null, initiallyOpen: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "caret")
    Panel {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (count != null) { Spacer(Modifier.width(8.dp)); Pill(count.toString(), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowDown, if (open) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
            }
            AnimatedVisibility(open) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.padding(16.dp)) { content() }
                }
            }
        }
    }
}

@Composable
internal fun LogEntry(c: Comment) {
    Row {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(c.author.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.author, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(ago(c.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                when (c.kind) {
                    "card" -> Pill("CARD", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    "answer" -> Pill("ANSWER", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    "question" -> Pill("QUESTION", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Markdown(c.text)
        }
    }
}

fun ago(iso: String): String = runCatching {
    val t = java.time.Instant.parse(iso).toEpochMilli()
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}.getOrDefault(iso)

/** Whether the card's `Recommend:` line names this option ("Pop, it reads best" recommends "Pop"). */
fun recommends(card: Card, option: String): Boolean {
    val r = card.recommend.trim()
    if (!r.startsWith(option, ignoreCase = true)) return false
    return r.length == option.length || !r[option.length].isLetterOrDigit()
}

/** The slide: the question, the recommendation, the evidence, then the answer. */
@Composable
internal fun DecisionCard(card: Card, busy: Boolean, onAnswer: (String?, String) -> Unit, onAsk: (String) -> Unit) {
    var choice by rememberSaveable(card.question) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(card.question) { mutableStateOf("") }
    val c = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = c.surfaceContainer,
        border = BorderStroke(1.dp, c.outlineVariant),
    ) {
        Column {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("YOUR DECISION", style = Overline, color = c.tertiary)
                Text(card.question, style = MaterialTheme.typography.headlineSmall)
                if (card.recommend.isNotBlank()) Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(c.primaryContainer).padding(12.dp),
                ) {
                    Icon(Icons.Default.Star, null, tint = c.primary, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Recommended", style = MaterialTheme.typography.labelMedium, color = c.primary)
                        Text(card.recommend, style = MaterialTheme.typography.bodyMedium, color = c.onPrimaryContainer)
                    }
                }
                if (card.rest.isNotBlank()) Markdown(card.rest)
                Text("Your answer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                val compact = card.choices.size <= 2 && card.choices.all { it.length <= 14 }
                @Composable
                fun tile(o: String, m: Modifier) = OptionTile(o, choice == o, recommends(card, o), enabled = !busy, modifier = m) {
                    choice = if (choice == o) null else o
                }
                if (compact) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (o in card.choices) tile(o, Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in card.choices) tile(o, Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, enabled = !busy, minLines = 2,
                    placeholder = { Text("Add a note (optional)") }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onAnswer(choice, note) }, enabled = !busy && (choice != null || note.isNotBlank()),
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.onPrimary, strokeWidth = 2.dp)
                    else {
                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (choice != null) "Send “$choice”" else "Send answer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { onAsk(note) }, enabled = !busy && note.isNotBlank()) { Text("Ask a question instead") }
                    Text(
                        if (note.isBlank()) "Write a note to ask a question instead." else "The agent replies to a question with a new card.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
internal fun OptionTile(text: String, selected: Boolean, recommended: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = MaterialTheme.shapes.small,
        color = if (selected) c.primaryContainer else c.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outline),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Radio(selected)
            Spacer(Modifier.width(12.dp))
            Text(
                text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) c.onPrimaryContainer else c.onSurface, modifier = Modifier.weight(1f),
            )
            if (recommended) Icon(Icons.Default.Star, "Recommended", tint = c.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Radio(selected: Boolean, size: Dp = 20.dp) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).border(2.dp, if (selected) c.primary else c.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (selected) Box(Modifier.size(size / 2).clip(CircleShape).background(c.primary)) }
}

// ---------------------------------------------------------------- move

/** The choices of "Move to…": an icon, the status, what it means; on hold asks what it waits on. */
@Composable
internal fun MoveOptions(current: String, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (s in MOVE_TO) {
            val look = statusLook(s)
            val on = s == selected
            val c = MaterialTheme.colorScheme
            Surface(
                onClick = { onSelect(s) }, shape = MaterialTheme.shapes.small,
                color = if (on) c.primaryContainer else Color.Transparent,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(look.icon, null, tint = if (on) c.primary else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(look.name + if (s == current) " (now)" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(look.hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Radio(on, 18.dp)
                }
            }
        }
    }
}
