package dev.braincore.boss

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        GitHub.tokenFor = { runBlocking { runCatching { Store.accessToken(this@App) }.getOrDefault("") } }
        Store.schedule(this)
    }

    override fun newImageLoader() = ImageLoader.Builder(this).okHttpClient(GitHub.http).crossfade(true).build()
}

class MainActivity : ComponentActivity() {
    private val open = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Status and navigation bar icons follow the app's own light / dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(LightColors.background.toArgb(), DarkColors.background.toArgb()),
            navigationBarStyle = SystemBarStyle.auto(LightColors.background.toArgb(), DarkColors.background.toArgb()),
        )
        open.value = issueFrom(intent)
        setContent { BossTheme { Root(open) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        issueFrom(intent)?.let { open.value = it }
    }

    /** A task key ([Issue.key]); widgets placed by older versions still send the bare issue number. */
    private fun issueFrom(intent: Intent?): String? = intent?.let {
        if (it.getStringExtra(EXTRA_WORKSPACE)?.equals(Store.repo(this), ignoreCase = true) == false) return null
        it.getStringExtra(EXTRA_ISSUE) ?: it.getIntExtra(EXTRA_ISSUE, 0).takeIf { n -> n > 0 }?.toString()
    }

    companion object {
        const val EXTRA_ISSUE = "issue"
        const val EXTRA_WORKSPACE = "workspace"
    }
}

@Composable
private fun Root(open: MutableState<String?>) {
    val ctx = LocalContext.current
    var configured by remember { mutableStateOf(Store.configured(ctx)) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("Tasks") }
    val screenState = key(Store.repo(ctx)) { rememberSaveableStateHolder() }
    when {
        !configured || settings -> SettingsScreen(
            canClose = configured,
            onClose = { settings = false },
            onSaved = { configured = true; settings = false },
        )
        open.value != null -> {
            BackHandler { open.value = null }
            DetailScreen(open.value!!, onBack = { open.value = null })
        }
        searching -> screenState.SaveableStateProvider("Search") {
            BackHandler { searching = false }
            SearchScreen(searchQuery, onQuery = { searchQuery = it }, onOpen = { open.value = it }, onBack = { searching = false })
        }
        else -> Scaffold(bottomBar = { OverviewNavigation(tab) { tab = it } }) { padding ->
            Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
                screenState.SaveableStateProvider(tab) {
                    when (tab) {
                        "Activity" -> ActivityScreen(onSettings = { settings = true })
                        "Runs" -> RunsScreen(onSettings = { settings = true })
                        "Usage" -> UsageScreen(onSettings = { settings = true })
                        else -> ListScreen(
                            onOpen = { open.value = it },
                            onSettings = { settings = true },
                            onSearch = {
                                screenState.removeState("Search")
                                searchQuery = ""
                                searching = true
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun toast(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

// ---------------------------------------------------------------- list

@Composable
private fun ListScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onSearch: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var issues by remember { mutableStateOf(Store.issues(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var synced by remember { mutableStateOf(Store.syncedAt(ctx)) }
    var filter by remember { mutableStateOf(Store.filter(ctx)) }
    var showEveryone by remember { mutableStateOf(Store.showEveryone(ctx)) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var completed by remember {
        mutableStateOf(CompletedTasks(Store.completed(ctx), loaded = Store.completedLoaded(ctx)))
    }

    fun refreshCompleted() = scope.launch {
        if (completed.loading) return@launch
        completed = completed.copy(loading = true, error = "")
        try {
            completed = CompletedTasks(Store.syncCompleted(ctx), loaded = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            completed = completed.copy(loading = false, error = Store.describe(e))
        }
    }

    fun refresh() = scope.launch {
        loading = true
        error = runCatching { issues = Store.sync(ctx) }.exceptionOrNull()?.let(Store::describe) ?: Store.error(ctx)
        synced = Store.syncedAt(ctx)
        loading = false
        if (showCompleted) refreshCompleted()
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(showCompleted) { if (showCompleted) refreshCompleted() }

    // A task dropped on a status bubble moves like "Move to…" in its details; on hold first asks what it waits on.
    var holding by remember { mutableStateOf<Issue?>(null) }
    fun move(issue: Issue, status: String, waitingOn: String? = null, checkAfter: String? = null) = scope.launch {
        val failure = runCatching {
            val (repo, number) = splitKey(issue.key)
            val gh = Store.github(ctx, repo)
            gh.setStatus(gh.issue(number), status, waitingOn, checkAfter) // fresh copy, as in the details
        }.exceptionOrNull()
        if (failure == null) toast(ctx, "Moved ${issue.ref} to ${statusLook(status).name}") else error = Store.describe(failure)
        refresh()
    }

    ListContent(
        issues, filter, loading, error, synced,
        onRefresh = { refresh() }, onOpen = onOpen, onSettings = onSettings,
        onFilter = { f -> filter = f; scope.launch { Store.setFilter(ctx, f) } },
        completed = completed, showCompleted = showCompleted,
        onToggleCompleted = { showCompleted = !showCompleted }, onRetryCompleted = { refreshCompleted() },
        onSearch = onSearch,
        me = Store.owner(ctx),
        showEveryone = showEveryone,
        onShowEveryone = { show -> showEveryone = show; scope.launch { Store.setShowEveryone(ctx, show) } },
        homeRepo = Store.repo(ctx),
        onMove = { issue, status -> if (status == "on-hold") holding = issue else move(issue, status) },
    )

    holding?.let { issue ->
        MoveDialog(issue, initial = "on-hold", onDismiss = { holding = null }) { status, waitingOn, checkAfter ->
            holding = null
            move(issue, status, waitingOn, checkAfter)
        }
    }
}

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var issues by remember { mutableStateOf(Store.search(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun refresh() = scope.launch {
        if (loading) return@launch
        loading = true
        error = ""
        try {
            issues = Store.syncSearch(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Store.describe(e)
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    SearchContent(issues, query, loading, error, onQuery, onOpen, onBack, onRefresh = { refresh() })
}

// ---------------------------------------------------------------- detail

@Composable
private fun DetailScreen(key: String, onBack: () -> Unit) {
    val (repo, number) = splitKey(key)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var issue by remember(key) { mutableStateOf((Store.issues(ctx) + Store.search(ctx) + Store.completed(ctx)).firstOrNull { it.key == key }) }
    val comments = remember(key) { mutableStateListOf<Comment>() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var moving by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        error = runCatching {
            val gh = Store.github(ctx, repo)
            issue = gh.issue(number)
            comments.clear(); comments.addAll(gh.comments(number))
        }.exceptionOrNull()?.let(Store::describe).orEmpty()
        loading = false
    }
    LaunchedEffect(key) { load() }

    /** Run a write, then re-sync the list and widget; back to the list on success. */
    fun act(done: String, block: suspend (GitHub, Issue) -> Unit) = scope.launch {
        busy = true
        val failure = runCatching {
            val gh = Store.github(ctx, repo)
            block(gh, gh.issue(number)) // fresh copy: labels and body may have changed since the screen loaded
        }.exceptionOrNull()
        runCatching { Store.sync(ctx) }
        busy = false
        if (failure == null) { toast(ctx, done); onBack() } else error = Store.describe(failure)
    }

    DetailContent(
        issue?.ref ?: refOf(key), issue, comments, loading, busy, error,
        onBack = onBack,
        onMove = { moving = true },
        homeRepo = Store.repo(ctx),
        onOpenGitHub = { issue?.let { uri.openUri(it.url) } },
        onReload = { scope.launch { load() } },
        onAnswer = { card, choice, note ->
            act("Answer sent") { gh, fresh ->
                gh.comment(number, answerComment(card.question, choice, note))
                gh.setStatus(fresh, "ready")
            }
        },
        onAsk = { card, text ->
            act("Question sent") { gh, fresh ->
                gh.comment(number, questionComment(card.question, text))
                gh.setStatus(fresh, "ready")
            }
        },
    )

    if (moving) issue?.let { current ->
        MoveDialog(current, onDismiss = { moving = false }) { status, waitingOn, checkAfter ->
            moving = false
            act("Moved to ${statusLook(status).name}") { gh, fresh -> gh.setStatus(fresh, status, waitingOn, checkAfter) }
        }
    }
}

@Composable
internal fun MoveDialog(issue: Issue, initial: String? = null, onDismiss: () -> Unit, onMove: (String, String?, String?) -> Unit) {
    var status by remember { mutableStateOf(initial ?: MOVE_TO.firstOrNull { it != issue.status } ?: "ready") }
    var waitingOn by remember { mutableStateOf(if (issue.status == "on-hold") issue.waitingOn else "") }
    var checkAfter by remember { mutableStateOf(issue.checkAfter) }
    val dateOk = checkAfter.isBlank() || Regex("\\d{4}-\\d{2}-\\d{2}").matches(checkAfter.trim())
    val ok = status != "on-hold" || (waitingOn.isNotBlank() && dateOk)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Move ${issue.ref}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoveOptions(issue.status, status) { status = it }
                if (status == "on-hold") {
                    OutlinedTextField(
                        waitingOn, { waitingOn = it }, label = { Text("Waiting on") }, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        checkAfter, { checkAfter = it }, label = { Text("Check after") }, placeholder = { Text("YYYY-MM-DD") },
                        supportingText = { Text("Optional") }, isError = !dateOk, singleLine = true, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = ok, shape = MaterialTheme.shapes.small, onClick = {
                if (status == "on-hold") onMove(status, waitingOn, checkAfter.takeIf { it.isNotBlank() }) else onMove(status, null, null)
            }) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- settings

@Composable
internal fun GitHubDeviceDialog(userCode: String, onCancel: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Authorize Boss on GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter this one-time code on GitHub, then return to Boss.")
                Text(userCode, style = MaterialTheme.typography.headlineMedium)
            }
        },
        confirmButton = { Button(onClick = onOpen) { Text("Copy code and open GitHub") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    canClose: Boolean,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    registration: GitHubAppRegistration = GitHubAppRegistration.current,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val signIn = remember(registration) { GitHubDeviceAuth(registration) }
    var repo by remember { mutableStateOf(Store.repo(ctx)) }
    var token by remember { mutableStateOf(Store.personalToken(ctx)) }
    var manual by remember { mutableStateOf(!registration.configured) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    var pendingTokens by remember { mutableStateOf<GitHubAppTokens?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val draftRepo = repo.trim()
    val draftToken = if (manual) token.trim() else Store.personalToken(ctx)
    val savedApp = Store.usesGitHubApp(ctx)
    val useSavedApp = savedApp && !manual
    val validRepo = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(draftRepo)
    val canCheck = validRepo && (useSavedApp || draftToken.isNotEmpty())
    val autoCheck = canClose && draftRepo == Store.repo(ctx) && (useSavedApp || draftToken == Store.personalToken(ctx))
    var checkRequest by remember(draftRepo, draftToken, useSavedApp) { mutableStateOf(0) }
    var accessReport by remember(draftRepo, draftToken, useSavedApp) { mutableStateOf<RepositoryAccessReport?>(null) }
    var checking by remember(draftRepo, draftToken, useSavedApp) { mutableStateOf(canCheck && autoCheck) }
    val cachedRepos = if (draftRepo.equals(Store.repo(ctx), ignoreCase = true)) Store.taskRepos(ctx) else emptyList()
    val pendingAccess = listOf(TaskRepo("Workspace", draftRepo)) + cachedRepos
    fun client(target: String) = if (useSavedApp) Store.client(ctx, target) else GitHub(draftToken, target)
    LaunchedEffect(draftRepo, draftToken, useSavedApp, checkRequest, autoCheck) {
        if (canCheck && (checkRequest > 0 || autoCheck)) {
            checking = true
            accessReport = checkRepositoryAccess(client(draftRepo), cachedRepos, useSavedApp, ::client)
            checking = false
        } else {
            checking = false
        }
    }
    fun perform(action: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = scope.launch {
            busy = true
            error = ""
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = Store.describe(e)
                if (e is GitHubHttpException && e.code == 401) pendingTokens = null
            } finally {
                deviceCode = null
                busy = false
            }
        }
    }
    suspend fun finishSettings() {
        try {
            Store.refresh(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The connection was already saved; scheduling a widget refresh must not undo it.
        }
        onSaved()
    }
    suspend fun finishAppConnection(tokens: GitHubAppTokens?) {
        val github = if (tokens == null) Store.client(ctx, draftRepo) else GitHub(tokens.access, draftRepo)
        if (!github.hasAppAccess()) {
            error = "Install the GitHub App on $draftRepo, or ask the repository owner to grant access."
            return
        }
        github.check()
        github.checkContents()
        withContext(Dispatchers.IO) {
            if (tokens != null) Store.saveGitHubApp(ctx, draftRepo, tokens) else Store.saveAppRepository(ctx, draftRepo)
        }
        pendingTokens = null
        finishSettings()
    }
    fun openGitHub(url: String) {
        if (runCatching { uri.openUri(url) }.isFailure) error = "Couldn't open GitHub. Check that a browser is installed."
    }
    deviceCode?.let { code ->
        GitHubDeviceDialog(code.userCode, onCancel = { operation?.cancel() }, onOpen = {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("GitHub sign-in code", code.userCode))
            openGitHub(GitHubDeviceAuth.VERIFICATION_URL)
        })
    }
    if (canClose) BackHandler { onClose() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (canClose) "Settings" else "") },
                navigationIcon = { if (canClose) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!canClose) Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Connect GitHub", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Boss reads tasks from your Brain repository and the task repositories it lists in repos.yaml.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionHeader("GitHub")
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        repo, { repo = it }, label = { Text("Brain repository") }, placeholder = { Text("owner/name") },
                        enabled = !busy,
                        singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                    )
                    if (registration.configured && !manual) {
                        if (savedApp) Text("Connected through a GitHub App", style = MaterialTheme.typography.bodyMedium)
                        else if (draftToken.isNotEmpty()) Text("Connected with a personal access token", style = MaterialTheme.typography.bodyMedium)
                        if (pendingTokens != null) Text("GitHub sign-in approved. Finish granting repository access to connect.")
                        Button(
                            enabled = !busy && validRepo, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onClick = {
                                perform {
                                    val tokens = pendingTokens ?: if (useSavedApp && draftRepo != Store.repo(ctx)) null else {
                                        val code = signIn.start()
                                        deviceCode = code
                                        signIn.authorize(code).also {
                                            deviceCode = null
                                            pendingTokens = it
                                        }
                                    }
                                    finishAppConnection(tokens)
                                }
                            },
                        ) {
                            Text(when {
                                pendingTokens != null -> "Check installation and connect"
                                savedApp && draftRepo != Store.repo(ctx) -> "Save repository"
                                savedApp -> "Reconnect with GitHub"
                                else -> "Connect with GitHub"
                            })
                        }
                        TextButton(enabled = !busy, onClick = { openGitHub(registration.installUrl) }) {
                            Text(if (savedApp) "Manage GitHub App access" else "Install GitHub App on your repositories")
                        }
                    }
                    if (manual) {
                        OutlinedTextField(
                            token, { token = it }, label = { Text("Access token") }, singleLine = true, shape = MaterialTheme.shapes.small,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Include your workspace and task repositories. Use Issues: read and write, and Contents: read. Saving the agent filter also needs Contents: write.") },
                        )
                        Button(
                            enabled = !busy && canCheck, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onClick = { perform {
                                client(draftRepo).check()
                                Store.save(ctx, draftRepo, draftToken)
                                finishSettings()
                            } },
                        ) { Text(if (canClose) "Save token" else "Connect with token") }
                    }
                    if (busy && deviceCode == null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    if (error.isNotEmpty()) ErrorBanner(errorText(error, 0))
                    if (registration.configured) {
                        TextButton(enabled = !busy, onClick = {
                            manual = !manual
                            pendingTokens = null
                            error = ""
                        }) { Text(if (manual) "Use GitHub App instead" else "Use a personal access token instead") }
                    }
                }
            }
            if (manual) LinkRow("Create a token on GitHub") { openGitHub("https://github.com/settings/personal-access-tokens/new") }

            RepositoryAccessSection(
                report = accessReport,
                pending = if (validRepo) pendingAccess else emptyList(),
                checking = checking,
                canCheck = canCheck && !busy,
                onCheck = { checkRequest++ },
                onManageToken = { openGitHub(if (useSavedApp) registration.installUrl else "https://github.com/settings/personal-access-tokens") },
                usingApp = useSavedApp,
            )

            var filter by remember { mutableStateOf(Store.filter(ctx)) }
            val workstreams = remember { workstreamsOf(Store.issues(ctx), filter) }
            if (workstreams.isNotEmpty()) {
                SectionHeader("Workstreams")
                WorkstreamFilter(workstreams, filter) { f -> filter = f; scope.launch { Store.setFilter(ctx, f) } }
                Text(
                    "What the list and the widget show. Tap once: only this one. Again: all except it. Again: off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (canClose && Store.configured(ctx)) {
                var dispatcher by remember { mutableStateOf(DispatcherState(loading = true)) }
                fun load() = scope.launch {
                    dispatcher = dispatcher.copy(loading = true, error = "")
                    dispatcher = try {
                        val (terms, file) = Store.dispatcherFilter(ctx)
                        DispatcherState(file, terms, terms)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        dispatcher.copy(loading = false, error = dispatcherError(e))
                    }
                }
                LaunchedEffect(Unit) { load() }
                DispatcherSection(
                    dispatcher, workstreamsOf(Store.issues(ctx), dispatcher.draft + dispatcher.saved),
                    onChange = { dispatcher = dispatcher.copy(draft = it, error = "") },
                    onSave = {
                        val file = dispatcher.file ?: return@DispatcherSection
                        scope.launch {
                            dispatcher = dispatcher.copy(saving = true, error = "")
                            dispatcher = try {
                                val saved = Store.setDispatcherFilter(ctx, file, dispatcher.draft)
                                DispatcherState(saved, dispatcher.draft, dispatcher.draft)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val error = dispatcherError(e)
                                // Someone else changed the file: show what is there now, keep the edit to save again.
                                val fresh = if ((e as? GitHubHttpException)?.code == 409) runCatching { Store.dispatcherFilter(ctx) }.getOrNull() else null
                                if (fresh != null) DispatcherState(fresh.second, fresh.first, dispatcher.draft, error = error)
                                else dispatcher.copy(saving = false, error = error)
                            }
                        }
                    },
                    onReset = { dispatcher = dispatcher.copy(draft = dispatcher.saved, error = "") },
                    onRetry = { load() },
                )
            }

            SectionHeader("About")
            val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            LinkRow("Get the latest build", version?.let { "You have $it" }) { uri.openUri(Store.RELEASES_URL) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}
