package dev.braincore.boss

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GitHubAppTokenProviderTest {
    @Test fun concurrentAppAndWidgetRequestsRotateAndSaveOnlyOnce() = runBlocking {
        var current = GitHubAppTokens("before", "refresh-before", 10_000L)
        val refreshStarted = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var rotations = 0
        val provider = GitHubAppTokenProvider({ current }, { _, tokens -> current = tokens; true }, {
            rotations++
            assertEquals("refresh-before", it)
            refreshStarted.complete(Unit)
            proceed.await()
            GitHubAppTokens("after", "refresh-after", 300_000L)
        }, now = { 10_000L })
        val app = async { provider.access("account") }
        refreshStarted.await()
        val widget = async { provider.access("account") }
        proceed.complete(Unit)
        assertEquals(listOf("after", "after"), listOf(app.await(), widget.await()))
        assertEquals("refresh-after", current.refresh)
        assertEquals(1, rotations)
    }

    @Test fun leavingScreenDoesNotLoseARotatedRefreshToken() = runBlocking {
        var current = GitHubAppTokens("before", "refresh-before", 0)
        val refreshStarted = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val provider = GitHubAppTokenProvider({ current }, { _, tokens -> current = tokens; true }, {
            refreshStarted.complete(Unit)
            proceed.await()
            GitHubAppTokens("after", "refresh-after", 300_000L)
        }, now = { 10_000L })
        val screen = launch { provider.access("account") }
        refreshStarted.await()
        screen.cancel()
        proceed.complete(Unit)
        screen.cancelAndJoin()
        assertEquals("refresh-after", current.refresh)
    }

    @Test fun aStaleConnectionCannotOverwriteReplacementCredentials() = runBlocking {
        val provider = GitHubAppTokenProvider(
            { GitHubAppTokens("before", "refresh-before", 0) },
            { _, _ -> false },
            { GitHubAppTokens("after", "refresh-after", 300_000L) },
        )
        val error = runCatching { provider.access("old-account") }.exceptionOrNull()
        assertTrue(error is GitHubAuthException && error.reconnect)
    }
}
