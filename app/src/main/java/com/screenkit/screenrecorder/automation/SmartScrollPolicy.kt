package com.screenkit.screenrecorder.automation

/** No Android dependency: conservative decisions are regression-tested with synthetic observations. */
internal data class PlaybackObservation(
    val packageName: String = "",
    val supported: Boolean = false,
    val adBreak: Boolean = false,
    val sponsoredFeed: Boolean = false,
    val skipAvailable: Boolean = false,
    val progress: Double? = null,
    val paused: Boolean = false,
)
internal enum class ScrollDecision { WAIT, NEXT, SKIP_AD }
internal data class ScrollAdvice(val decision: ScrollDecision, val message: String)

internal class SmartScrollPolicy {
    private var lastTick = 0L
    private var watchMs = 0L
    private var previousProgress: Double? = null
    private var progressSeenAt = 0L
    private var app = ""
    private var skipAttemptAt = -10_000L
    private var adClearSince: Long? = null
    private var inAd = false

    fun reset(now: Long) {
        lastTick = now; watchMs = 0; previousProgress = null; progressSeenAt = 0
        skipAttemptAt = -10_000L; adClearSince = null; inAd = false; app = ""
    }
    fun decide(now: Long, intervalMs: Long, smart: Boolean, hold: Boolean, observation: PlaybackObservation): ScrollAdvice {
        val elapsed = (now - lastTick).coerceIn(0, 1000)
        lastTick = now
        if (hold) {
            adClearSince = null
            return ScrollAdvice(ScrollDecision.WAIT, "HOLD · tap Resume or Next")
        }
        if (!smart) {
            watchMs += elapsed
            return timed(intervalMs)
        }
        if (!observation.supported) {
            adClearSince = null
            return ScrollAdvice(ScrollDecision.WAIT, "Smart: open YouTube, Instagram or Facebook")
        }
        if (app != observation.packageName) {
            reset(now); app = observation.packageName
        }
        if (observation.adBreak || observation.skipAvailable) {
            inAd = true; adClearSince = null
            // A skip command is only a click on a currently visible, labelled Skip ad button.
            if (observation.skipAvailable && now - skipAttemptAt >= 3000) {
                skipAttemptAt = now
                return ScrollAdvice(ScrollDecision.SKIP_AD, "Using the visible Skip ad button")
            }
            return ScrollAdvice(ScrollDecision.WAIT, "Ad break · holding this video until it resumes")
        }
        if (inAd) {
            // Require a stable absence of ad controls: labels can disappear between ads in a pod.
            val clearSince = adClearSince
            if (clearSince == null) adClearSince = now
            else if (now - clearSince >= 2000) {
                inAd = false; adClearSince = null; watchMs = 0
                previousProgress = null; progressSeenAt = 0
            }
            // Never count the confirmation interval as content viewing time or infer a loop from it.
            return ScrollAdvice(ScrollDecision.WAIT, "Content resuming after ad…")
        }
        if (observation.sponsoredFeed && watchMs >= 1200) return ScrollAdvice(ScrollDecision.NEXT, "Skipping labelled sponsored feed item")
        if (observation.paused) return ScrollAdvice(ScrollDecision.WAIT, "Video paused · waiting for playback")
        watchMs += elapsed
        val progress = observation.progress?.takeIf { it.isFinite() && it in 0.0..1.0 }
        if (progress != null) {
            val old = previousProgress
            previousProgress = progress; progressSeenAt = now
            if (watchMs >= 1500 && ((progress >= 0.985 && old != null && progress > old) ||
                        (old != null && old >= 0.65 && progress <= 0.15))) {
                return ScrollAdvice(ScrollDecision.NEXT, "Video ended / loop detected")
            }
            return ScrollAdvice(ScrollDecision.WAIT, "Watching video · ${(progress * 100).toInt()}%")
        }
        if (progressSeenAt > 0 && now - progressSeenAt < 5000) {
            return ScrollAdvice(ScrollDecision.WAIT, "Waiting for playback progress…")
        }
        return timed(intervalMs, "No playback info · timed fallback")
    }
    private fun timed(interval: Long, prefix: String = "Timed scroll"): ScrollAdvice {
        if (watchMs >= interval) return ScrollAdvice(ScrollDecision.NEXT, "Next item")
        val seconds = (interval - watchMs + 999) / 1000
        return ScrollAdvice(ScrollDecision.WAIT, "$prefix · ${seconds}s")
    }
}

internal object ScrollDelay {
    const val MIN_MS = 200L
    const val MAX_MS = 3_600_000L
    fun parseSeconds(text: String): Long? {
        val seconds = text.trim().toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds < 0.2 || seconds > 3600) return null
        return (seconds * 1000).toLong().coerceIn(MIN_MS, MAX_MS)
    }
}

/** Parse only explicit playback/ad labels, not arbitrary timer-looking comment text. */
internal object PlaybackLabels {
    private val timePair = Regex("(?:^|\\s)(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*(?:/|of)\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)(?:$|\\s)", RegexOption.IGNORE_CASE)
    fun progress(label: String): Double? {
        val match = timePair.find(label.trim()) ?: return null
        fun seconds(s: String): Long? {
            val parts = s.split(':').map { it.toLongOrNull() ?: return null }
            if (parts.drop(1).any { it !in 0..59 }) return null
            return parts.fold(0L) { a, v -> a * 60 + v }
        }
        val elapsed = seconds(match.groupValues[1]) ?: return null
        val total = seconds(match.groupValues[2]) ?: return null
        return if (total > 0 && elapsed in 0..total) elapsed.toDouble() / total else null
    }
    fun skip(label: String): Boolean = label.trim().lowercase() in setOf("skip ad", "skip ads", "skip advertisement")
    fun adBreak(label: String): Boolean {
        val value = label.trim().lowercase()
        return Regex("^ad\\s+\\d+\\s+of\\s+\\d+.*$").matches(value) ||
            Regex("^ad\\s*[·•:–-]?\\s*\\d{1,2}:\\d{2}.*$").matches(value) ||
            value.startsWith("ad ends in") || value.startsWith("your video will resume") ||
            value.startsWith("video will resume after") || value.startsWith("video will play after") ||
            value == "advertisement" || value == "ad"
    }
    fun sponsored(label: String): Boolean = label.trim().lowercase() in setOf("sponsored", "sponsored post", "স্পন্সরড")
}
