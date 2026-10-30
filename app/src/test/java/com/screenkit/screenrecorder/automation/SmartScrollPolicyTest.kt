package com.screenkit.screenrecorder.automation

import org.junit.Assert.*
import org.junit.Test

class SmartScrollPolicyTest {
    private val content = PlaybackObservation("com.facebook.katana", supported = true)
    private fun tick(policy: SmartScrollPolicy, now: Long, observation: PlaybackObservation = content, delay: Long = 30_000,
                     smart: Boolean = true, hold: Boolean = false) = policy.decide(now, delay, smart, hold, observation)

    @Test fun customDelayAllowsMinutesAndRejectsInvalidOrOverflow() {
        assertEquals(90_000L, ScrollDelay.parseSeconds("90"))
        assertEquals(3_600_000L, ScrollDelay.parseSeconds("3600"))
        assertEquals(250L, ScrollDelay.parseSeconds("0.25"))
        listOf("", "NaN", "Infinity", "1e99", "-1", "0", "3601", "abc").forEach { assertNull(ScrollDelay.parseSeconds(it)) }
    }
    @Test fun timedModeWaitsBeforeFirstSwipeAndSupportsLongDelays() {
        val p = SmartScrollPolicy().apply { reset(0) }
        for (second in 1..89) assertEquals(ScrollDecision.WAIT, tick(p, second * 1000L, delay = 90_000, smart = false).decision)
        assertEquals(ScrollDecision.NEXT, tick(p, 90_000, delay = 90_000, smart = false).decision)
    }
    @Test fun holdDoesNotSpendViewingTime() {
        val p = SmartScrollPolicy().apply { reset(0) }
        for (second in 1..20) assertEquals(ScrollDecision.WAIT, tick(p, second * 1000L, delay = 1000, smart = false, hold = true).decision)
        assertEquals(ScrollDecision.NEXT, tick(p, 21_000, delay = 1000, smart = false).decision)
    }
    @Test fun midrollDoesNotSwipeOrTreatProgressResetAsContentLoop() {
        val p = SmartScrollPolicy().apply { reset(0) }
        tick(p, 350, content.copy(progress = 0.8))
        for (second in 1..40) assertEquals(ScrollDecision.WAIT, tick(p, second * 1000L, content.copy(adBreak = true, progress = 0.02), delay = 8000).decision)
        assertEquals(ScrollDecision.WAIT, tick(p, 41_000, content.copy(progress = 0.05), delay = 8000).decision)
        assertEquals(ScrollDecision.WAIT, tick(p, 43_000, content.copy(progress = 0.82), delay = 8000).decision)
    }
    @Test fun visibleVideoProgressOverridesShortFixedTimer() {
        val p = SmartScrollPolicy().apply { reset(0) }
        tick(p, 100, content.copy(progress = 0.01))
        for (second in 1..20) assertEquals(ScrollDecision.WAIT, tick(p, second * 1000L, content.copy(progress = second / 30.0), delay = 1000).decision)
    }
    @Test fun advancesAtEndOrAfterLoopNotAtInitialZero() {
        val p = SmartScrollPolicy().apply { reset(0) }
        tick(p, 100, content.copy(progress = 0.0))
        tick(p, 1100, content.copy(progress = 0.7))
        assertEquals(ScrollDecision.NEXT, tick(p, 2100, content.copy(progress = 0.99)).decision)
        p.reset(3000)
        tick(p, 3100, content.copy(progress = 0.0))
        tick(p, 4100, content.copy(progress = 0.8))
        assertEquals(ScrollDecision.NEXT, tick(p, 5100, content.copy(progress = 0.02)).decision)
    }
    @Test fun skipsOnlyLabelledFeedAdsAndVisibleSkipCommands() {
        val p = SmartScrollPolicy().apply { reset(0) }
        tick(p, 100, content.copy(sponsoredFeed = true))
        tick(p, 1100, content.copy(sponsoredFeed = true))
        tick(p, 2100, content.copy(sponsoredFeed = true))
        assertEquals(ScrollDecision.NEXT, tick(p, 2450, content.copy(sponsoredFeed = true)).decision)
        p.reset(3000)
        assertEquals(ScrollDecision.SKIP_AD, tick(p, 3100, content.copy(adBreak = true, skipAvailable = true)).decision)
        assertEquals(ScrollDecision.WAIT, tick(p, 3450, content.copy(adBreak = true, skipAvailable = true)).decision)
        assertEquals(ScrollDecision.WAIT, tick(p, 6000, content.copy(adBreak = true, sponsoredFeed = true)).decision)
    }
    @Test fun unsupportedAppNeverGetsAnAutomaticSmartSwipe() {
        val p = SmartScrollPolicy().apply { reset(0) }
        for (second in 1..30) assertEquals(ScrollDecision.WAIT, tick(p, second * 1000L, PlaybackObservation(), delay = 200).decision)
    }
    @Test fun missingProgressUsesHonestTimedFallback() {
        val p = SmartScrollPolicy().apply { reset(0) }
        tick(p, 100)
        tick(p, 1100, delay = 2000)
        assertEquals(ScrollDecision.NEXT, tick(p, 2100, delay = 2000).decision)
        p.reset(2200)
        assertTrue(tick(p, 2300).message.contains("No playback info"))
    }
    @Test fun parserAvoidsSubstringAdsAndHandlesExplicitTimes() {
        assertTrue(PlaybackLabels.adBreak("Ad 1 of 2"))
        assertTrue(PlaybackLabels.adBreak("Ad · 0:15"))
        assertTrue(PlaybackLabels.skip("Skip ads"))
        assertFalse(PlaybackLabels.skip("Skip intro"))
        assertFalse(PlaybackLabels.adBreak("I hate this ad"))
        assertFalse(PlaybackLabels.sponsored("A discussion about sponsored videos"))
        assertEquals(0.5, PlaybackLabels.progress("0:15 / 0:30")!!, 0.0001)
        assertEquals(0.5, PlaybackLabels.progress("1:00:00 of 2:00:00")!!, 0.0001)
        assertNull(PlaybackLabels.progress("0:99 / 1:00"))
        assertNull(PlaybackLabels.progress("10 / 100 likes"))
    }
}
