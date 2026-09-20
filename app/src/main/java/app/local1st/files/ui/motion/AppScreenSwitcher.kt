package app.local1st.files.ui.motion

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigationevent.NavigationEvent
import app.local1st.files.ui.main.AppScreenEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * At most two screens, rear under front. Interrupt is one playhead reversing.
 *
 * NavDisplay's AnimatedContent owns z-index and "one entry in one scene", so interrupting
 * an open with a pop blanks content and leaves the next push under the current page.
 */
@Stable
class AppScreenSwitcher(
    stack: List<AppScreenEntry>,
    private val scope: CoroutineScope,
) {
    var front by mutableStateOf(stack.last())
        private set
    var rear by mutableStateOf<AppScreenEntry?>(null)
        private set
    var mode by mutableStateOf(Mode.Idle)
        private set

    var playhead by mutableFloatStateOf(0f)
        private set
    var commit by mutableFloatStateOf(0f)
        private set
    var swipeEdge by mutableIntStateOf(NavigationEvent.EDGE_LEFT)
        private set
    var touchYDelta by mutableFloatStateOf(0f)
        private set

    private var lastStack: List<AppScreenEntry> = stack.toList()
    private var motionJob: Job? = null
    private var initialTouchY = Float.NaN

    enum class Mode { Idle, Forward, BackPreview, BackCommit }

    fun onStackChanged(stack: List<AppScreenEntry>) {
        if (stack.isEmpty()) return
        val previous = lastStack
        val prevIds = previous.map { it.id }
        val ids = stack.map { it.id }
        if (ids == prevIds) return
        lastStack = stack.toList()
        val top = stack.last()
        val prevTop = previous.last()

        if (mode == Mode.BackPreview || mode == Mode.BackCommit) {
            if (top.id == rear?.id && prevTop.id !in ids) {
                if (mode == Mode.BackPreview) startCommit()
                return
            }
        }
        // Late snapshot after a committed pop: already idle on this top.
        if (top.id == front.id && prevTop.id !in ids) return

        when {
            top.id == prevTop.id -> Unit
            // Push: the old top is still under the new page.
            prevTop.id in ids -> startForward(from = prevTop, to = top)
            // Pop back to a page that was already on the stack.
            top.id in prevIds -> {
                if (mode == Mode.Forward && front.id !in ids) reverseForward(newFront = top)
                else startBack(closing = prevTop, entering = top)
            }
            // Replace, or a pop+push whose intermediate stack was skipped.
            // Always cover the page that is actually under the new top — never the
            // page that just left, or the incoming page slides in over itself.
            else -> {
                val under = stack.getOrNull(stack.lastIndex - 1)
                if (under != null) startForward(from = under, to = top)
                else startBack(closing = prevTop, entering = top)
            }
        }
    }

    fun onPreview(event: NavigationEvent, current: AppScreenEntry, previous: AppScreenEntry) {
        if (mode == Mode.BackCommit) return
        if (mode == Mode.Forward) {
            // Pop animation: front is already off the stack.
            if (lastStack.none { it.id == front.id }) return
            val progress = event.progress.coerceIn(0f, 1f)
            if (progress < 0.02f) return
            motionJob?.cancel()
            playhead = (1f - EaseOut.transform(progress)).coerceIn(0f, 1f)
            return
        }
        if (mode == Mode.Idle) {
            front = current
            rear = previous
            mode = Mode.BackPreview
            commit = 0f
        }
        if (mode != Mode.BackPreview) return
        if (initialTouchY.isNaN()) initialTouchY = event.touchY
        swipeEdge = event.swipeEdge
        touchYDelta = event.touchY - initialTouchY
        playhead = EaseOut.transform(event.progress.coerceIn(0f, 1f))
    }

    fun cancelPreview() {
        when (mode) {
            Mode.Forward -> runMotion {
                animate(
                    initialValue = playhead,
                    targetValue = 1f,
                    animationSpec = tween(PostCommitMs, easing = ActivityEmphasizedEasing),
                ) { value, _ -> playhead = value }
                settleIdle(front = front, keepRear = false)
            }
            Mode.BackPreview -> runMotion {
                animate(
                    initialValue = playhead,
                    targetValue = 0f,
                    animationSpec = tween(PostCommitMs / 2, easing = ActivityEmphasizedEasing),
                ) { value, _ -> playhead = value }
                settleIdle(front = front, keepRear = false)
            }
            Mode.Idle, Mode.BackCommit -> Unit
        }
    }

    fun completeBack(pop: () -> Unit) {
        when (mode) {
            Mode.BackPreview -> startCommit(after = pop)
            Mode.Forward -> pop()
            Mode.Idle, Mode.BackCommit -> pop()
        }
    }

    private fun startForward(from: AppScreenEntry, to: AppScreenEntry) {
        if (from.id == to.id) return
        if (mode == Mode.Forward && front.id == to.id && rear?.id == from.id) return
        rear = from
        front = to
        mode = Mode.Forward
        playhead = 0f
        commit = 0f
        resetGesture()
        runMotion {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(PostCommitMs, easing = ActivityEmphasizedEasing),
            ) { value, _ -> playhead = value }
            settleIdle(front = to, keepRear = false)
        }
    }

    private fun reverseForward(newFront: AppScreenEntry) {
        rear = newFront
        mode = Mode.Forward
        runMotion {
            animate(
                initialValue = playhead,
                targetValue = 0f,
                animationSpec = tween(PostCommitMs, easing = ActivityEmphasizedEasing),
            ) { value, _ -> playhead = value }
            settleIdle(front = newFront, keepRear = false)
        }
    }

    private fun startBack(closing: AppScreenEntry, entering: AppScreenEntry) {
        front = closing
        rear = entering
        playhead = 1f
        commit = 0f
        resetGesture()
        mode = Mode.Forward
        reverseForward(newFront = entering)
    }

    private fun startCommit(after: (() -> Unit)? = null) {
        val closingId = front.id
        mode = Mode.BackCommit
        runMotion {
            animate(
                initialValue = commit,
                targetValue = 1f,
                animationSpec = tween(PostCommitMs, easing = ActivityEmphasizedEasing),
            ) { value, _ -> commit = value }
            val next = rear ?: front
            after?.invoke()
            lastStack = lastStack.filter { it.id != closingId }
            settleIdle(front = next, keepRear = false)
        }
    }

    private fun settleIdle(front: AppScreenEntry, keepRear: Boolean) {
        this.front = front
        if (!keepRear) rear = null
        mode = Mode.Idle
        playhead = 0f
        commit = 0f
        resetGesture()
    }

    private fun resetGesture() {
        touchYDelta = 0f
        initialTouchY = Float.NaN
        swipeEdge = NavigationEvent.EDGE_LEFT
    }

    private fun runMotion(block: suspend () -> Unit) {
        motionJob?.cancel()
        motionJob = scope.launch {
            try {
                block()
            } catch (_: CancellationException) {
                throw CancellationException()
            }
        }
    }
}
