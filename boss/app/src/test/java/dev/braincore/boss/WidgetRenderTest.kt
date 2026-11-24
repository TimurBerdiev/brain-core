package dev.braincore.boss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the widget's RemoteViews to app/build/screens/widget-*.png, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi", application = android.app.Application::class)
class WidgetRenderTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun seed(issues: List<Issue>, problem: SyncProblem? = null) {
        ctx.getSharedPreferences("boss", Context.MODE_PRIVATE).edit().clear()
            .putString("token", "t").putString("repo", "alex/brain")
            .putString("snapshot", JSONArray(issues.map(GitHub::toJson)).toString())
            .putLong("synced", System.currentTimeMillis())
            .putString("sync_problem", problem?.name)
            .commit()
    }

    /** The widget's list can't be drawn here (RemoteViews collections need a host), so its rows render on their own. */
    private class Rows(val issues: List<Issue>) : androidx.glance.appwidget.GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) = provideContent {
            androidx.glance.GlanceTheme(colors = WidgetColors) {
                androidx.glance.layout.Column(androidx.glance.GlanceModifier.fillMaxSize().background(androidx.glance.GlanceTheme.colors.background).padding(12.dp)) {
                    for (i in issues) DecisionItem(i)
                }
            }
        }
    }

    private fun render(name: String, widget: () -> androidx.glance.appwidget.GlanceAppWidget = { BossWidget() }) {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val size = DpSize(360.dp, 200.dp)
            val views = runBlocking { widget().compose(ctx, size = size) }
            val parent = FrameLayout(ctx)
            val view = views.apply(ctx, parent)
            val d = ctx.resources.displayMetrics.density
            val w = (size.width.value * d).toInt()
            val h = (size.height.value * d).toInt()
            val frame = FrameLayout(ctx).apply { setBackgroundColor(if (night) 0xFF30343C.toInt() else 0xFF8A93A0.toInt()) }
            frame.addView(view, FrameLayout.LayoutParams(w, h))
            frame.measure(View.MeasureSpec.makeMeasureSpec(w + 80, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h + 80, View.MeasureSpec.EXACTLY))
            frame.setPadding(40, 40, 40, 40)
            frame.measure(View.MeasureSpec.makeMeasureSpec(w + 80, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h + 80, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, w + 80, h + 80)
            val bitmap = Bitmap.createBitmap(w + 80, h + 80, Bitmap.Config.ARGB_8888)
            frame.draw(Canvas(bitmap))
            val out = File("build/screens/widget-$name${if (night) "-dark" else ""}.png").apply { parentFile?.mkdirs() }
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun i(n: Int, title: String, labels: List<String>, body: String = "") = Issue(n, title, body, labels, "open", "", "")

    @Test fun decisions() {
        seed(listOf(
            i(36, "Studio: high-contrast highlight and nicer subtitles", listOf("input-needed"), "Waiting on: Which subtitle preset should be the default?"),
            i(36, "Studio: check subscriptions", listOf("input-needed", "p1"), "Waiting on: Decision on webhooks / uid in checkout / §312k")
                .copy(repo = "alex/studio", repoAlias = "studio"),
            i(41, "Proxy: broker health checks", listOf("ready")).copy(claim = "claude"),
            i(43, "x", listOf("ready")), i(12, "y", listOf("backlog")),
        ))
        render("decisions")
        render("rows") { Rows(Store.issues(ctx).filter { it.status == "input-needed" }) }
    }

    @Test fun clearAndRetrying() {
        val issues = listOf(i(41, "x", listOf("ready")), i(43, "x", listOf("ready")), i(44, "x", listOf("ready"))) +
            (1..12).map { i(it, "b", listOf("backlog")) }
        seed(issues)
        render("clear")
        seed(issues, SyncProblem.TEMPORARY)
        render("retrying")
    }
}
