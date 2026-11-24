package dev.braincore.boss

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblesTest {
    private val screen = Size(1080f, 2000f)
    private fun centers(press: Offset, count: Int = 4) = bubbleCenters(press, screen, count, arc = 300f, bubble = 150f, margin = 60f)

    @Test fun targetsLeaveOutTheCurrentStatus() {
        assertEquals(listOf("on-hold", "backlog", "done", "dropped"), bubbleTargets("ready"))
        assertEquals(MOVE_TO, bubbleTargets("input-needed"))
    }

    @Test fun bubblesSitAboveTheFingerLeftToRight() {
        val press = Offset(540f, 1200f)
        val c = centers(press, 5)
        assertTrue(c.all { it.y < press.y })
        assertEquals(c.sortedBy { it.x }, c)
        c.forEach { assertEquals(300f, (it - press).getDistance(), 1f) }
    }

    @Test fun nearTheTopTheyGoBelow() {
        val press = Offset(540f, 100f)
        val c = centers(press)
        assertTrue(c.all { it.y > press.y })
        assertEquals(c.sortedBy { it.x }, c)
    }

    @Test fun atTheEdgesTheyStayOnScreen() {
        for (press in listOf(Offset(0f, 1000f), Offset(1080f, 1000f), Offset(0f, 0f), Offset(1080f, 2000f))) {
            for (b in centers(press, 5)) {
                assertTrue("$press -> $b", b.x - 135f >= -0.5f && b.x + 135f <= 1080.5f)
                assertTrue("$press -> $b", b.y - 135f >= -0.5f && b.y + 135f <= 2000.5f)
            }
        }
    }

    @Test fun bubblesDoNotOverlap() {
        val c = centers(Offset(540f, 1200f), 5)
        for (i in 1 until c.size) assertTrue((c[i] - c[i - 1]).getDistance() > 150f)
    }

    @Test fun hitTestPicksTheNearestBubbleInReach() {
        val c = listOf(Offset(100f, 100f), Offset(300f, 100f))
        assertEquals(0, bubbleAt(Offset(120f, 110f), c, 90f))
        assertEquals(1, bubbleAt(Offset(260f, 100f), c, 90f))
        assertNull(bubbleAt(Offset(200f, 100f), c, 90f))
        assertNull(bubbleAt(Offset(100f, 500f), c, 90f))
    }
}
