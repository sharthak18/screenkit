package com.screenkit.screenrecorder.streaming

import org.junit.Assert.*
import org.junit.Test

class LiveDestinationTest {
    @Test fun chooserIncludesThreePlatformsAndCustom() {
        assertEquals(listOf("YouTube", "Facebook", "TikTok", "Custom server"), LiveDestination.entries.map { it.label })
    }
    @Test fun knownPresetsValidateAndTikTokDoesNotInventAnIngestUrl() {
        for (platform in listOf(LiveDestination.YOUTUBE, LiveDestination.FACEBOOK)) {
            LiveStreamConfig(platform.defaultServer, "test-key").validate()
        }
        assertTrue(LiveDestination.TIKTOK.defaultServer.isEmpty())
        assertTrue(LiveDestination.CUSTOM.defaultServer.isEmpty())
        assertNull(LiveDestination.CUSTOM.dashboardUrl)
    }
    @Test fun handoffUsesHttpsWithoutPassingCredentials() {
        LiveDestination.entries.mapNotNull { it.dashboardUrl }.forEach {
            val uri = java.net.URI(it)
            assertEquals("https", uri.scheme)
            assertNull(uri.userInfo)
            assertNull(uri.query)
        }
    }
}
