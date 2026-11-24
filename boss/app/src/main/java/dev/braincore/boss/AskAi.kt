package dev.braincore.boss

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class AiApp(val label: String, val packageName: String, val website: String) {
    Claude("Claude", "com.anthropic.claude", "https://claude.ai/"),
    ChatGPT("ChatGPT", "com.openai.chatgpt", "https://chatgpt.com/"),
}

/** Only explicit reference fields cross the app boundary; bodies, logs and account state never enter here. */
internal class AiReference private constructor(val text: String) {
    companion object {
        private fun line(text: String) = text.replace(Regex("\\s+"), " ").trim().take(160)
        private fun source(url: String): String? {
            val uri = Uri.parse(url)
            return if (uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null)
                uri.buildUpon().clearQuery().fragment(null).build().toString().takeIf { it.length <= 300 }
            else null
        }
        private fun make(label: String, repo: String, url: String = "") = AiReference(
            listOf(line(label), "Repository: ${line(repo)}", source(url)).filterNotNull().joinToString("\n")
        )
        fun task(issue: Issue, homeRepo: String) = make("${issue.ref}: ${issue.title}", issue.repo.ifEmpty { homeRepo }, issue.url)
        fun screen(name: String, homeRepo: String) = make("Boss · $name", homeRepo)
        fun activity(event: ActivityItem) = make("Boss · Activity · ${event.title}", event.repo, event.url)
    }
}

internal fun shareReference(app: AiApp, reference: AiReference) = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    setPackage(app.packageName)
    putExtra(Intent.EXTRA_TEXT, reference.text)
}

internal enum class AiHandoff { Shared, CopiedToApp, CopiedToWebsite, CopiedOnly }

/** Launches only after a user chooses an app. No chat URLs, prompt submission or voice deep links. */
internal fun handoffReference(
    app: AiApp, reference: AiReference,
    launch: (Intent) -> Boolean, appLaunch: (String) -> Intent?, copy: (String) -> Unit,
): AiHandoff {
    if (launch(shareReference(app, reference))) return AiHandoff.Shared
    copy(reference.text)
    appLaunch(app.packageName)?.let { if (launch(it)) return AiHandoff.CopiedToApp }
    if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(app.website)).addCategory(Intent.CATEGORY_BROWSABLE)))
        return AiHandoff.CopiedToWebsite
    return AiHandoff.CopiedOnly
}

internal fun openAi(ctx: Context, app: AiApp, reference: AiReference) {
    val result = handoffReference(app, reference, launch = { intent ->
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) { false
        } catch (_: SecurityException) { false }
    }, appLaunch = { ctx.packageManager.getLaunchIntentForPackage(it) }, copy = {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Boss reference", it))
    })
    val message = when (result) {
        AiHandoff.Shared -> "Reference shared with ${app.label}"
        AiHandoff.CopiedToApp -> "Reference copied. Paste it in ${app.label}."
        AiHandoff.CopiedToWebsite -> "Reference copied. Paste it on ${app.label}'s website."
        AiHandoff.CopiedOnly -> "Reference copied. Open ${app.label} to paste it."
    }
    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
}

@Composable
internal fun AskAiAction(reference: AiReference, description: String = "Ask AI", onChoose: ((AiApp, AiReference) -> Unit)? = null) {
    val ctx = LocalContext.current
    var choosing by rememberSaveable(reference.text) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { choosing = true }, modifier = Modifier.semantics { contentDescription = description }) { Text("Ask AI") }
        if (choosing) AskAiDialog(reference, onDismiss = { choosing = false }) { app ->
            choosing = false
            if (onChoose != null) onChoose(app, reference) else openAi(ctx, app, reference)
        }
    }
}

@Composable
internal fun AskAiDialog(reference: AiReference, onDismiss: () -> Unit, onChoose: (AiApp) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask AI") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(reference.text, style = MaterialTheme.typography.bodyMedium)
                Text("Share this reference, or copy it to paste if sharing is unavailable. You choose what to ask in the app.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(AiApp.ChatGPT) }) { Text("ChatGPT") } },
        dismissButton = { TextButton(onClick = { onChoose(AiApp.Claude) }) { Text("Claude") } },
    )
}
