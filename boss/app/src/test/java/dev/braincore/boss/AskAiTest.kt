package dev.braincore.boss

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AskAiTest {
    private val task = Issue(86, "Open AI apps", "PRIVATE BODY token=secret", listOf("ready"), "open", "",
        "https://github.com/alex/brain/issues/86?token=secret#private", claim = "PRIVATE CLAIM", assignees = listOf("PRIVATE ACCOUNT"))
    private val reference get() = AiReference.task(task, "alex/brain")

    @Test fun taskReferenceIncludesOnlyShortPublicFields() {
        assertEquals("#86: Open AI apps\nRepository: alex/brain\nhttps://github.com/alex/brain/issues/86", reference.text)
        val remote = task.copy(repo = "alex/studio", repoAlias = "studio")
        assertTrue(AiReference.task(remote, "alex/brain").text.startsWith("studio#86: Open AI apps\nRepository: alex/studio"))
        assertFalse(AiReference.task(task.copy(url = "https://token@github.com/secret"), "home/repo").text.contains("token"))
        assertFalse(AiReference.task(task.copy(url = "https://evil.test/secret"), "home/repo").text.contains("evil"))
        val long = AiReference.task(task.copy(title = "Title\n" + "x".repeat(5000)), "home/repo").text
        assertTrue(long.length < 500)
        assertTrue(long.startsWith("#86: Title x"))
    }

    @Test fun screensAndActivityNeverCarryLogsOrUsageData() {
        assertEquals("Boss · Usage\nRepository: alex/brain", AiReference.screen("Usage", "alex/brain").text)
        val event = ActivityItem("private-id", "alex/app", "Fix #86", "PRIVATE LOG", "private-time", "https://github.com/alex/app/commit/abc", "Commit")
        assertEquals("Boss · Activity · Fix #86\nRepository: alex/app\nhttps://github.com/alex/app/commit/abc", AiReference.activity(event).text)
    }

    @Test fun sharingTargetsOnlyTheChosenOfficialApp() {
        for (app in AiApp.entries) {
            val intent = shareReference(app, reference)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertEquals(app.packageName, intent.`package`)
            assertEquals(reference.text, intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras!!.keySet())
            assertNull(intent.data)
            val launches = mutableListOf<Intent>()
            assertEquals(AiHandoff.Shared, handoffReference(app, reference, { launches += it; true },
                { error("Must not open another activity after sharing") }, { error("Must not copy after sharing") }))
            assertEquals(1, launches.size)
        }
    }

    @Test fun unavailableSharingCopiesBeforeOpeningSelectedApp() {
        for (app in AiApp.entries) {
            val launches = mutableListOf<Intent>()
            var copied = ""
            val result = handoffReference(app, reference, {
                launches += it
                if (it.action == Intent.ACTION_SEND) false else { assertEquals(reference.text, copied); true }
            }, { packageName -> assertEquals(app.packageName, packageName); Intent(Intent.ACTION_MAIN).setPackage(packageName) }, { copied = it })
            assertEquals(AiHandoff.CopiedToApp, result)
            assertEquals(2, launches.size)
            assertEquals(app.packageName, launches.last().`package`)
        }
    }

    @Test fun absentOrUnlaunchableAppUsesOfficialWebsiteWithoutPrompt() {
        for (app in AiApp.entries) for (installed in listOf(false, true)) {
            var copied = ""
            val launches = mutableListOf<Intent>()
            val result = handoffReference(app, reference, {
                launches += it
                it.action == Intent.ACTION_VIEW
            }, { if (installed) Intent(Intent.ACTION_MAIN).setPackage(it) else null }, { copied = it })
            assertEquals(AiHandoff.CopiedToWebsite, result)
            assertEquals(reference.text, copied)
            val browser = launches.last()
            assertEquals(app.website, browser.dataString)
            assertNull(browser.data!!.query)
            assertNull(browser.extras)
            assertNull(browser.`package`)
        }
    }

    @Test fun androidLaunchFailuresCopyAndOpenWebsite() {
        val base = org.robolectric.RuntimeEnvironment.getApplication()
        for (failure in listOf(android.content.ActivityNotFoundException(), SecurityException())) {
            val launches = mutableListOf<Intent>()
            val context = object : android.content.ContextWrapper(base) {
                override fun startActivity(intent: Intent) {
                    launches += intent
                    if (intent.action == Intent.ACTION_SEND) throw failure
                }
            }
            openAi(context, AiApp.Claude, reference)
            assertEquals(listOf(Intent.ACTION_SEND, Intent.ACTION_VIEW), launches.map { it.action })
            assertEquals(AiApp.Claude.website, launches.last().dataString)
            assertTrue(launches.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
            val clipboard = base.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(reference.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun phoneWithNoBrowserKeepsReferenceOnClipboard() {
        var copied = ""
        assertEquals(AiHandoff.CopiedOnly, handoffReference(AiApp.Claude, reference, { false }, { null }, { copied = it }))
        assertEquals(reference.text, copied)
    }
}
