package dev.braincore.boss

import android.content.Context
import android.text.format.DateUtils
import java.text.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

val IssueKey = ActionParameters.Key<String>(MainActivity.EXTRA_ISSUE)
private val WorkspaceKey = ActionParameters.Key<String>(MainActivity.EXTRA_WORKSPACE)

class BossWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BossWidget()
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (Store.configured(context)) Store.refresh(context)
    }
}

/** The app's palette, with white (light) / raised navy (dark) rows on the widget's background. */
val WidgetColors = ColorProviders(
    light = LightColors.copy(surface = androidx.compose.ui.graphics.Color.White),
    dark = DarkColors.copy(background = DarkColors.surfaceContainerLow, surface = DarkColors.surfaceContainerHigh),
)

/** What the widget draws, read from the Store in one go. */
internal data class WidgetState(
    val configured: Boolean,
    val groups: Map<String, List<Issue>>,
    val synced: Long,
    val problem: SyncProblem?,
    val refreshing: Boolean,
    val filter: Set<String>,
    val workspace: String = "",
) {
    companion object {
        fun load(ctx: Context): WidgetState {
            val filter = Store.filter(ctx)
            return WidgetState(
                configured = Store.configured(ctx),
                groups = grouped(visibleIssues(Store.issues(ctx), filter, Store.owner(ctx), Store.showEveryone(ctx)), Store.owner(ctx)).toMap(),
                synced = Store.syncedAt(ctx),
                problem = Store.syncProblem(ctx),
                refreshing = Store.refreshing(ctx),
                filter = filter,
                workspace = Store.repo(ctx),
            )
        }
    }
}

/**
 * Keep the last full sync visible while a refresh is requested or the queue contains saved data.
 * An absolute time stays accurate when Android leaves the widget untouched between updates.
 */
internal fun footerTail(state: WidgetState, now: Long = System.currentTimeMillis()): String {
    val lastSync = if (state.synced > 0) {
        "Last synced ${DateUtils.formatSameDayTime(state.synced, now, DateFormat.SHORT, DateFormat.SHORT)}"
    } else "Not synced yet"
    val status = if (state.refreshing) "Refresh requested" else when (state.problem) {
        SyncProblem.TEMPORARY -> "Will try again"
        SyncProblem.ACCESS -> "Check GitHub access"
        SyncProblem.OTHER -> "Open Boss for details"
        null -> null
    }
    return listOfNotNull(lastSync, status).joinToString(" · ")
}

/** Home-screen widget: what waits on the owner (tap one to answer it), plus how many issues sit in each other status. */
class BossWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Re-read on every Store change: a live session doesn't run provideGlance again on update.
            val version by Store.changes.collectAsState()
            val state = remember(version) { WidgetState.load(context) }
            GlanceTheme(colors = WidgetColors) { Content(state) }
        }
    }

    @Composable
    private fun Content(state: WidgetState) {
        val c = GlanceTheme.colors
        val mine = state.groups["input-needed"].orEmpty()
        val open = actionStartActivity<MainActivity>()
        Column(GlanceModifier.fillMaxSize().background(c.background).cornerRadius(24.dp).padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)) {
            Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                Text("Boss", style = TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                if (mine.isNotEmpty()) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        " ${mine.size} ", maxLines = 1,
                        style = TextStyle(color = c.onTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.background(c.tertiary).cornerRadius(10.dp).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                if (state.filter.isNotEmpty()) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(describeFilter(state.filter), maxLines = 1, style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp))
                }
                Spacer(GlanceModifier.defaultWeight())
                Box(
                    GlanceModifier.size(36.dp).cornerRadius(18.dp).clickable(actionRunCallback<RefreshAction>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.ic_refresh), "Refresh", colorFilter = ColorFilter.tint(c.onSurfaceVariant), modifier = GlanceModifier.size(20.dp))
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            when {
                !state.configured -> Message(R.drawable.ic_refresh, "Connect GitHub", "Tap to open Boss and connect your Brain")
                mine.isEmpty() && state.synced == 0L -> Message(R.drawable.ic_refresh, "Not loaded yet", "Tap ↻ to load the queue")
                mine.isEmpty() && state.problem != null -> Message(R.drawable.ic_refresh, "Saved queue", "Some tasks may be out of date")
                mine.isEmpty() -> Message(R.drawable.ic_check, "All caught up", "Nothing waits on you")
                else -> LazyColumn(GlanceModifier.defaultWeight().padding(end = 6.dp)) {
                    items(mine, itemId = { it.key.hashCode().toLong() }) { issue -> DecisionItem(issue, state.workspace) }
                }
            }
            if (state.configured) {
                if (mine.isEmpty()) Spacer(GlanceModifier.defaultWeight()) // the list takes the room otherwise
                val ready = state.groups["ready"].orEmpty()
                val working = ready.count { it.inProgress }
                val counts = listOfNotNull(
                    "${ready.size} ready",
                    "$working working".takeIf { working > 0 },
                    state.groups["on-hold"].orEmpty().size.takeIf { it > 0 }?.let { "$it on hold" },
                    "${state.groups["backlog"].orEmpty().size} backlog",
                ).joinToString(" · ")
                Column(GlanceModifier.fillMaxWidth().padding(end = 6.dp).clickable(open)) {
                    val style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp)
                    Text(counts, maxLines = 1, style = style)
                    Text(footerTail(state), maxLines = 2, style = style)
                }
            }
        }
    }

    /** An icon in a tinted circle with a title and a line under it: the widget's empty and setup states. */
    @Composable
    private fun Message(icon: Int, title: String, text: String) {
        val c = GlanceTheme.colors
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(36.dp).background(c.primaryContainer).cornerRadius(18.dp), contentAlignment = Alignment.Center) {
                Image(ImageProvider(icon), null, colorFilter = ColorFilter.tint(c.primary), modifier = GlanceModifier.size(20.dp))
            }
            Spacer(GlanceModifier.width(12.dp))
            Column {
                Text(title, style = TextStyle(color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Text(text, style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp))
            }
        }
    }
}


/** One decision on the widget: an amber dot, the question, the task under it. Tap opens its card. */
@Composable
internal fun DecisionItem(issue: Issue, workspace: String = "") {
    val c = GlanceTheme.colors
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().background(c.surface).cornerRadius(14.dp).padding(horizontal = 12.dp, vertical = 9.dp)
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(IssueKey to issue.key, WorkspaceKey to workspace))),
        ) {
            Column(GlanceModifier.padding(top = 6.dp)) {
                Image(ImageProvider(R.drawable.ic_dot), null, colorFilter = ColorFilter.tint(c.tertiary), modifier = GlanceModifier.size(8.dp))
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    issue.waitingOn.ifBlank { issue.title }, maxLines = 2,
                    style = TextStyle(color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Text("${issue.ref} · ${issue.title}", maxLines = 1, style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp))
            }
        }
    }
}
