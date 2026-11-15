package com.screenkit.screenrecorder.ui.overlay

/** Window geometry is derived from a separate bubble anchor, never fed back into it on expansion. */
internal object BubblePlacement {
    data class Layout(val x: Int, val y: Int, val width: Int, val height: Int,
                      val menuWidth: Int, val menuBeforeBubble: Boolean, val bubbleOffsetX: Int, val bubbleOffsetY: Int)
    fun layout(anchorX: Int, anchorY: Int, screenWidth: Int, screenHeight: Int, bubble: Int,
               menuWidth: Int, menuHeight: Int, padding: Int, gap: Int, expanded: Boolean,
               menuBeforeBubble: Boolean? = null): Layout {
        val right = menuBeforeBubble ?: (anchorX + bubble / 2 >= screenWidth / 2)
        val actualMenuWidth = menuWidth.coerceAtMost((screenWidth - bubble - padding * 2 - gap).coerceAtLeast(0))
        val width = if (expanded) bubble + actualMenuWidth + padding * 2 + gap else bubble
        val height = if (expanded) maxOf(bubble, menuHeight) + padding * 2 else bubble
        val offsetX = if (!expanded) 0 else padding + if (right) actualMenuWidth + gap else 0
        val offsetY = (height - bubble) / 2
        return Layout((anchorX - offsetX).coerceIn(0, (screenWidth - width).coerceAtLeast(0)),
            (anchorY - offsetY).coerceIn(0, (screenHeight - height).coerceAtLeast(0)),
            width, height, actualMenuWidth, right, offsetX, offsetY)
    }
}
