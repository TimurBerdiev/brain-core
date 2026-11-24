package dev.braincore.boss

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun overviewAgo(iso: String, now: Long): String = millis(iso).takeIf { it > 0 }?.let {
    android.text.format.DateUtils.getRelativeTimeSpanString(it, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
} ?: "Unknown time"

@Composable
internal fun OverviewNavigation(selected: String, onSelect: (String) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Tasks", "Activity", "Runs", "Usage").forEach { tab ->
                TextButton(onClick = { onSelect(tab) }) {
                    Text(tab, fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverviewBar(title: String, loading: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit, reference: AiReference) {
    TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, actions = {
        AskAiAction(reference)
        if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
        else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh $title") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
    })
}

@Composable
internal fun ActivityScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(Store.activity(ctx)) }
    var repos by remember { mutableStateOf(Store.activityCandidates(ctx)) }
    var watched by remember { mutableStateOf(Store.watchedRepos(ctx)) }
    var hours by rememberSaveable { mutableStateOf(24) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var synced by remember { mutableStateOf(Store.activitySynced(ctx)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    fun refresh() = scope.launch {
        if (loading) return@launch
        loading = true; error = ""
        try {
            try { repos = Store.refreshActivityCandidates(ctx) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Repository list: ${Store.describe(e)}" }
            val (fresh, warning) = Store.syncActivity(ctx)
            entries = fresh; error = listOf(error, warning).filter(String::isNotBlank).joinToString("\n")
            synced = Store.activitySynced(ctx); now = System.currentTimeMillis()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = Store.describe(e)
        } finally { loading = false }
    }
    LaunchedEffect(watched) { refresh() }
    ActivityContent(entries, repos, watched, hours, now, loading, error, synced,
        onHours = { hours = it }, onWatch = { watched = it; Store.watchRepos(ctx, it) },
        onRefresh = { refresh() }, onSettings = onSettings, onOpen = { uri.openUri(it) }, homeRepo = Store.repo(ctx))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityContent(
    entries: List<ActivityItem>, repos: List<String>, watched: Set<String>, hours: Int, now: Long,
    loading: Boolean, error: String, syncedAt: Long, onHours: (Int) -> Unit, onWatch: (Set<String>) -> Unit,
    onRefresh: () -> Unit, onSettings: () -> Unit, onOpen: (String) -> Unit,
    homeRepo: String = "",
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    var draft by remember(watched) { mutableStateOf(watched) }
    val visible = activitySince(entries.filter { it.repo in watched }, hours, now)
    Scaffold(topBar = { OverviewBar("Activity", loading, onRefresh, onSettings, AiReference.screen("Activity", homeRepo)) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("What changed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Code, tasks and agent activity across your repositories", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Last hour", 24 to "24 hours", 72 to "3 days").forEach { (n, label) ->
                        FilterChip(selected = hours == n, onClick = { onHours(n) }, label = { Text(label) })
                    }
                }
                TextButton(onClick = {
                    if (choosing) onWatch(draft) else draft = watched
                    choosing = !choosing
                }, enabled = !loading) { Text("Watching ${watched.size} repositories · ${if (choosing) "Done" else "Edit"}") }
                if (choosing) repos.forEach { repo ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = repo in draft, onCheckedChange = { draft = if (it) draft + repo else draft - repo }, enabled = !loading)
                        Text(repo, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (syncedAt > 0) Text("Last checked ${overviewAgo(Instant.ofEpochMilli(syncedAt).toString(), now)}", style = MaterialTheme.typography.labelMedium)
                Text("Task events can arrive later than commits. Author names are shown when GitHub provides them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error.isNotBlank()) item { ErrorBanner(error) }
            if (visible.isEmpty() && !loading) item { Panel { Text(if (watched.isEmpty()) "Choose repositories to watch." else "No activity in this period.", Modifier.padding(20.dp)) } }
            items(visible, key = { it.repo + ":" + it.id }) { event ->
                Panel(onClick = { if (event.url.startsWith("https://github.com/")) onOpen(event.url) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${event.repo.substringAfter('/')} · ${event.kind} · ${overviewAgo(event.at, now)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        AskAiAction(AiReference.activity(event), description = "Ask AI about this change")
                        if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun UsageScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(Store.usage(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    fun refresh() = scope.launch {
        if (loading) return@launch
        loading = true; error = ""
        try { text = Store.syncUsage(ctx); now = System.currentTimeMillis() }
        catch (e: CancellationException) { throw e }
        catch (e: GitHubHttpException) { error = if (e.code == 404) "No usage measurements: the workspace has no usage.json on its agent-state branch, or this token cannot read it. Format: Brain Core, boss/README.md." else Store.describe(e) }
        catch (e: Exception) { error = Store.describe(e) }
        finally { loading = false }
    }
    LaunchedEffect(Unit) { refresh() }
    val providers = runCatching { readUsage(text) }.getOrElse { readUsage("") }
    UsageContent(providers, now, loading, error, { refresh() }, onSettings, homeRepo = Store.repo(ctx))
}

private fun resetLabel(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("EEE d MMM, HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault("Unknown")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageContent(providers: List<ProviderUsage>, now: Long, loading: Boolean, error: String, onRefresh: () -> Unit, onSettings: () -> Unit, homeRepo: String = "") {
    Scaffold(topBar = { OverviewBar("Usage", loading, onRefresh, onSettings, AiReference.screen("Usage", homeRepo)) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Agent subscriptions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Measurements refresh about every 15 minutes. Refresh checks for newer measurements.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error.isNotBlank()) item { ErrorBanner(error) }
            items(providers.sortedBy { if (it.fresh(now)) 0 else if (it.observedAt.isNotBlank()) 1 else 2 }, key = { it.key }) { provider ->
                Panel {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val fresh = provider.fresh(now)
                        val reached = provider.quotaReached || provider.windows.any { (it.used ?: 0.0) >= 100 }
                        Text(provider.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val status = when {
                            provider.auth == "not_authenticated" -> "Sign-in required on the agent machine"
                            provider.error.isNotBlank() -> "Could not refresh"
                            provider.observedAt.isBlank() -> "Not measured yet"
                            !fresh -> "Out of date or incomplete"
                            reached -> "Quota reached"
                            else -> "Available"
                        }
                        Text(status, color = if (fresh && !reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                        if (provider.error.isNotBlank()) Text(provider.error, style = MaterialTheme.typography.bodySmall)
                        provider.windows.forEach { w ->
                            Text(w.name + (w.minutes?.let { " · ${when { it % 1440 == 0 -> "${it / 1440}d"; it % 60 == 0 -> "${it / 60}h"; else -> "${it}m" }} window" } ?: ""), style = MaterialTheme.typography.labelLarge)
                            Text(w.used?.let { "${(100 - it).toInt()}% remaining${if (!fresh) " at last measurement" else ""}" } ?: "Remaining quota unknown", style = MaterialTheme.typography.titleMedium)
                            w.used?.let { LinearProgressIndicator(progress = { ((100 - it) / 100).toFloat() }, modifier = Modifier.fillMaxWidth()) }
                            Text("Resets ${resetLabel(w.resetsAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (provider.windows.isEmpty()) Text("Quota unknown", style = MaterialTheme.typography.bodyMedium)
                        if (provider.credits.isNotBlank()) Text("Extra credits: ${provider.credits}", style = MaterialTheme.typography.bodySmall)
                        Text(if (provider.observedAt.isBlank()) "No successful measurement" else "Measured ${overviewAgo(provider.observedAt, now)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
