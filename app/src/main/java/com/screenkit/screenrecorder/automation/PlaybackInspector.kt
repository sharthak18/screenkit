package com.screenkit.screenrecorder.automation

import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock

/** On-device, bounded inspection, invoked only while user-enabled smart scrolling is running. */
internal object PlaybackInspector {
    private val packages = setOf("com.google.android.youtube", "com.instagram.android", "com.facebook.katana", "com.facebook.lite")
    /** Some apps expose the Skip ad text as a child of the actual button. Never click a broad feed container. */
    @Suppress("DEPRECATION")
    private fun skipTarget(node: AccessibilityNodeInfo, app: String, deadline: Long): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(3) { depth ->
            val current = candidate ?: return null
            if (SystemClock.uptimeMillis() >= deadline || !current.isVisibleToUser ||
                !current.isEnabled || current.packageName?.toString() != app ||
                (depth > 0 && (current.childCount > 5 || current.isScrollable))) {
                current.recycle()
                return null
            }
            if (current.isClickable) return current // Caller owns this copy.
            candidate = if (depth < 2) current.parent else null
            current.recycle()
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun inspect(root: AccessibilityNodeInfo?, clickSkip: Boolean = false): PlaybackObservation {
        root ?: return PlaybackObservation()
        val app = root.packageName?.toString().orEmpty()
        if (app !in packages) { root.recycle(); return PlaybackObservation(packageName = app) }
        val stack = java.util.ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var nodes = 0
        var ad = false; var sponsored = false; var skip = false; var clicked = false
        var progress: Double? = null; var paused = false
        val deadline = SystemClock.uptimeMillis() + 35L
        try {
            while (stack.isNotEmpty() && nodes++ < 250 && SystemClock.uptimeMillis() < deadline) {
                val node = stack.removeFirst()
                try {
                    if (!node.isVisibleToUser || node.packageName?.toString() != app) continue
                    val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).map { it.take(160) }
                    val id = node.viewIdResourceName.orEmpty().lowercase()
                    // Comment/caption text is intentionally not used as player/ad evidence.
                    val playerControl = node.className?.toString()?.contains("SeekBar") == true ||
                        id.contains("progress") || id.contains("player") || id.contains("time_bar") || id.contains("timestamp")
                    if (playerControl) {
                        val range = node.rangeInfo
                        if (range != null && range.max > range.min) {
                            progress = ((range.current - range.min) / (range.max - range.min)).toDouble()
                        }
                        labels.firstNotNullOfOrNull { PlaybackLabels.progress(it) }?.let { progress = it }
                        if (node.isClickable && labels.any { it.equals("play", true) || it.equals("play video", true) }) paused = true
                    }
                    // Only compact UI labels; never inspect long post/comment bodies for keywords.
                    if (!id.contains("comment") && !id.contains("caption") && !id.contains("description")) {
                        labels.filter { it.length <= 70 }.forEach {
                            ad = ad || PlaybackLabels.adBreak(it)
                            sponsored = sponsored || PlaybackLabels.sponsored(it)
                            if (PlaybackLabels.skip(it)) {
                                val target = skipTarget(node, app, deadline)
                                if (target != null) {
                                    try {
                                        skip = true
                                        if (clickSkip && !clicked) clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    } finally { target.recycle() }
                                }
                            }
                        }
                    }
                    for (i in 0 until node.childCount.coerceAtMost(40)) {
                        if (SystemClock.uptimeMillis() >= deadline || nodes + stack.size >= 250) break
                        node.getChild(i)?.let(stack::addLast)
                    }
                } finally { node.recycle() }
            }
        } finally { while (stack.isNotEmpty()) stack.removeFirst().recycle() }
        return PlaybackObservation(app, true, ad, sponsored, skip, progress, paused)
    }
}
