package com.screenkit.screenrecorder.ui.overlay

/** Ends each touch stream once, before any view replacement can synthesize another CANCEL. */
internal class BubbleGestureSession {
    data class End(val generation: Long, val dragged: Boolean, val cancelled: Boolean)
    private var generation = 0L
    var active = false
        private set
    var dragging = false
        private set

    fun begin(): Long {
        generation++
        active = true
        dragging = false
        return generation
    }
    fun accepts(token: Long) = active && token == generation
    fun drag(token: Long) { if (accepts(token)) dragging = true }
    fun finish(token: Long, cancelled: Boolean): End? {
        if (!accepts(token)) return null
        val end = End(generation, dragging, cancelled)
        active = false
        dragging = false
        return end
    }
    fun isCurrent(end: End) = !active && generation == end.generation
    fun invalidate() { generation++; active = false; dragging = false }
}

internal object BubbleDismissTarget {
    fun contains(x: Float, y: Float, centerX: Float, centerY: Float, radius: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !centerX.isFinite() || !centerY.isFinite() || !radius.isFinite() || radius <= 0) return false
        val dx = x.toDouble() - centerX
        val dy = y.toDouble() - centerY
        return dx * dx + dy * dy <= radius.toDouble() * radius
    }
}
