package dev.braincore.boss

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Long-press a task in the list, drag it onto a status bubble, let go: the task moves there.

/** The statuses a task can be dropped on: "Move to…" without the one it has. */
fun bubbleTargets(status: String): List<String> = MOVE_TO.filter { it != status }

/**
 * Centers of [count] bubbles of diameter [bubble] on an arc of radius [arc] around [press]: above the finger, or below
 * when there is no room above. The arc slides sideways and every center stays [margin] clear of the edges of [bounds].
 */
fun bubbleCenters(press: Offset, bounds: Size, count: Int, arc: Float, bubble: Float, margin: Float): List<Offset> {
    if (count <= 0) return emptyList()
    val reach = arc + bubble / 2 + margin
    val up = press.y - reach >= 0f
    val cx = press.x.coerceIn(min(reach, bounds.width / 2), max(bounds.width - reach, bounds.width / 2))
    val span = PI * 5 / 6
    val r = bubble / 2 + margin
    return List(count) { i ->
        val t = if (count == 1) 0.5 else i.toDouble() / (count - 1)
        // Left to right either way.
        val a = if (up) -PI / 2 - span / 2 + span * t else PI / 2 + span / 2 - span * t
        Offset(
            (cx + arc * cos(a)).toFloat().coerceIn(r, max(r, bounds.width - r)),
            (press.y + arc * sin(a)).toFloat().coerceIn(r, max(r, bounds.height - r)),
        )
    }
}

/** The bubble under the finger: the nearest center within [reach], or null. */
fun bubbleAt(finger: Offset, centers: List<Offset>, reach: Float): Int? =
    centers.indices.filter { (centers[it] - finger).getDistance() <= reach }.minByOrNull { (centers[it] - finger).getDistance() }

/** A lifted task: where it was pressed and where the finger is now, in the overlay's pixels. */
data class BubbleDrag(val issue: Issue, val press: Offset, val finger: Offset)

private val BUBBLE = 56.dp
private val ARC = 112.dp
private val MARGIN = 28.dp // room for the label under a bubble

/** The lifted task, if any, and the overlay it is drawn in. The rows write to it, the overlay reads it. */
@Stable
internal class BubbleState(initial: BubbleDrag? = null) {
    var drag by mutableStateOf(initial)
        private set
    internal var onDrop: (Issue, String) -> Unit = { _, _ -> }
    private var origin = Offset.Zero
    private var bounds = Size.Zero
    private var px = 1f

    internal fun place(at: LayoutCoordinates, density: Float) {
        origin = at.positionInRoot()
        bounds = Size(at.size.width.toFloat(), at.size.height.toFloat())
        px = density
    }

    fun centers(d: BubbleDrag): List<Offset> =
        bubbleCenters(d.press, bounds, bubbleTargets(d.issue.status).size, ARC.value * px, BUBBLE.value * px, MARGIN.value * px)

    fun hot(d: BubbleDrag): String? =
        bubbleAt(d.finger, centers(d), (BUBBLE.value / 2 + 12) * px)?.let { bubbleTargets(d.issue.status)[it] }

    internal fun lift(issue: Issue, root: Offset) { drag = BubbleDrag(issue, root - origin, root - origin) }
    internal fun move(root: Offset) { drag = drag?.copy(finger = root - origin) }
    internal fun hover(local: Offset) { drag = drag?.copy(finger = local) }
    internal fun cancel() { drag = null }
    internal fun drop() {
        val d = drag ?: return
        drag = null
        hot(d)?.let { onDrop(d.issue, it) }
    }
}

/** A long press lifts the row's task into [state]; a tap and scrolling stay with the row and the list. */
@Composable
internal fun Modifier.liftToMove(issue: Issue, state: BubbleState?): Modifier {
    if (state == null) return this
    val row = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val current by rememberUpdatedState(issue)
    return onGloballyPositioned { row[0] = it }.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val at = row[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
            // From here on the gesture is ours: consuming every change cancels the row's click and the list's scroll.
            long.consume()
            state.lift(current, at.localToRoot(long.position))
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == long.id }
                if (change == null) { state.cancel(); break }
                change.consume()
                if (change.changedToUpIgnoreConsumed()) { state.drop(); break }
                if (!at.isAttached) { state.cancel(); break }
                state.move(at.localToRoot(change.position))
            }
        }
    }
}

/** The overlay of the list: dims it and, while a task is lifted, shows the bubbles around the press point. */
@Composable
internal fun BubbleOverlay(state: BubbleState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Box(modifier.fillMaxSize().onGloballyPositioned { state.place(it, density) }) {
        val d = state.drag ?: return@Box
        val c = MaterialTheme.colorScheme
        val haptic = LocalHapticFeedback.current
        val hot = state.hot(d)
        LaunchedEffect(d.issue.key) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        LaunchedEffect(hot) { if (hot != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.32f)))
        val half = with(LocalDensity.current) { (BUBBLE / 2).toPx() }
        val labelWidth = with(LocalDensity.current) { 88.dp.toPx() }
        for ((s, at) in bubbleTargets(d.issue.status).zip(state.centers(d))) {
            val look = statusLook(s)
            val on = s == hot
            Column(
                Modifier.offset { IntOffset((at.x - labelWidth / 2).roundToInt(), (at.y - half).roundToInt()) }.width(88.dp)
                    .semantics { contentDescription = "Move to ${look.name}" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(if (on) BUBBLE + 8.dp else BUBBLE).shadow(if (on) 8.dp else 3.dp, CircleShape)
                        .clip(CircleShape).background(if (on) c.primary else c.surfaceContainer)
                        .border(1.dp, if (on) c.primary else c.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(look.icon, null, tint = if (on) c.onPrimary else c.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
                Text(
                    look.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, color = c.inverseOnSurface,
                    modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(c.inverseSurface.copy(alpha = if (on) 1f else 0.8f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        // The task under the finger, so it reads as being carried.
        Surface(
            shape = CircleShape, color = c.inverseSurface, contentColor = c.inverseOnSurface, shadowElevation = 6.dp,
            modifier = Modifier.offset { IntOffset(d.finger.x.roundToInt() - 40, d.finger.y.roundToInt() - (half * 3).roundToInt()) },
        ) { Text(d.issue.ref, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
    }
}
