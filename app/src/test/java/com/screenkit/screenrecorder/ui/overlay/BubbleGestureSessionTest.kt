package com.screenkit.screenrecorder.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class BubbleGestureSessionTest {
    @Test fun upThenReentrantCancelCanFinishOnlyOnce() {
        val state = BubbleGestureSession()
        val token = state.begin()
        state.drag(token)
        val end = state.finish(token, false)!!
        assertFalse(state.active)
        assertFalse(state.dragging)
        assertTrue(end.dragged)
        assertFalse(end.cancelled)
        assertNull(state.finish(token, true))
        assertNull(state.finish(token, false))
        assertTrue(state.isCurrent(end))
    }
    @Test fun newGestureInvalidatesOldQueuedDismissalAndOldCancel() {
        val state = BubbleGestureSession()
        val first = state.begin()
        state.drag(first)
        val end = state.finish(first, false)!!
        val second = state.begin()
        assertFalse(state.isCurrent(end))
        assertNull(state.finish(first, true))
        assertTrue(state.accepts(second))
        assertFalse(state.dragging)
    }
    @Test fun teardownAndRotationInvalidatePendingWork() {
        val state = BubbleGestureSession()
        val end = state.finish(state.begin(), false)!!
        state.invalidate()
        assertFalse(state.isCurrent(end))
        assertFalse(state.active)
    }
    @Test fun cancelIsNotATapOrDismissal() {
        val state = BubbleGestureSession()
        val token = state.begin()
        state.drag(token)
        assertTrue(state.finish(token, true)!!.cancelled)
        assertNull(state.finish(token, true))
    }
    @Test fun closeTargetUsesCircleNotWholeBottomStrip() {
        assertTrue(BubbleDismissTarget.contains(180f, 740f, 180f, 740f, 41f))
        assertFalse(BubbleDismissTarget.contains(100f, 740f, 180f, 740f, 41f))
        assertFalse(BubbleDismissTarget.contains(180f, 680f, 180f, 740f, 41f))
        assertFalse(BubbleDismissTarget.contains(Float.NaN, 740f, 180f, 740f, 41f))
        assertFalse(BubbleDismissTarget.contains(1000000f, 1000000f, 180f, 740f, 41f))
    }
}
