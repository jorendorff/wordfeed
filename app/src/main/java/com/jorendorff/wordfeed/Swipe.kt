package com.jorendorff.wordfeed

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Page swiping that doesn't steal vertical scrolls.
 *
 * The pager's own drag handling claims a gesture as soon as it moves
 * sideways past the tiny system touch slop, so a slightly diagonal scroll
 * becomes a swipe. Instead (with the pager's userScrollEnabled = false), we
 * watch each drag before the page content sees it, and claim it only once
 * it has gone [minDistance] sideways and is at least [ratio] times as
 * horizontal as vertical. A drag that turns out to be mostly vertical is
 * left alone for the WebView to scroll.
 */
fun Modifier.pickySwipe(
    pagerState: PagerState,
    scope: CoroutineScope,
    minDistance: Dp = 40.dp,
    ratio: Float = 2f,
    flingVelocity: Dp = 400.dp, // per second
): Modifier = pointerInput(pagerState) {
    val minPx = minDistance.toPx()
    val flingPx = flingVelocity.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startPage = pagerState.settledPage
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var claimed = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (claimed) {
                    change.consume()
                    val vx = tracker.calculateVelocity().x
                    val target = when {
                        vx < -flingPx -> startPage + 1
                        vx > flingPx -> startPage - 1
                        else -> (pagerState.currentPage + pagerState.currentPageOffsetFraction).roundToInt()
                    }.coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                    scope.launch { pagerState.animateScrollToPage(target) }
                }
                break
            }

            val delta = change.positionChange()
            total += delta
            tracker.addPosition(change.uptimeMillis, change.position)

            if (!claimed) {
                val dx = abs(total.x)
                val dy = abs(total.y)
                if (dy > slop && dy * ratio > dx) {
                    break // a scroll; not ours
                }
                if (dx >= minPx && dx >= ratio * dy) {
                    claimed = true
                    // Catch the page up with the finger.
                    pagerState.dispatchRawDelta(-total.x)
                    change.consume()
                    continue
                }
            } else {
                pagerState.dispatchRawDelta(-delta.x)
                change.consume()
            }
        }
    }
}
