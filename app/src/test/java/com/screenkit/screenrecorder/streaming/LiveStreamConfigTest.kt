package com.screenkit.screenrecorder.streaming

import org.junit.Assert.*
import org.junit.Test

class LiveStreamConfigTest {
    @Test fun joinsServerAndKeyWithoutLosingQueryTokens() {
        assertEquals("rtmps://host.example:443/rtmp/key?token=abc&x=1", LiveStreamConfig.validateEndpoint(" rtmps://host.example:443/rtmp/ ", "key?token=abc&x=1"))
    }
    @Test fun supportsRtmpAndIpv6() {
        assertEquals("rtmp://localhost/live/key", LiveStreamConfig.validateEndpoint("rtmp://localhost/live", "key"))
        assertEquals("rtmps://[::1]:443/live/key", LiveStreamConfig.validateEndpoint("rtmps://[::1]:443/live", "key"))
    }
    @Test fun rejectsInvalidDestinationsWithoutEchoingSecrets() {
        val secret = "private-key"
        listOf("https://host/live", "rtmp:///live", "rtmp://host", "rtmp://host/", "rtmp://user:pass@host/live",
            "rtmp://host:0/live", "rtmp://host:65536/live", "rtmps://host/live?key=secret", "rtmps://host/live#fragment").forEach { server ->
            val failure = runCatching { LiveStreamConfig.validateEndpoint(server, secret) }.exceptionOrNull()
            assertNotNull(server, failure)
            assertFalse(failure!!.message.orEmpty().contains(secret))
        }
        listOf("", "a b", "a\nb", "rtmp://host/key", "key#fragment", "/key", "key%bad%", "a".repeat(4097)).forEach { key ->
            assertTrue(runCatching { LiveStreamConfig.validateEndpoint("rtmps://host/live", key) }.isFailure)
        }
    }
    @Test fun validatesQualityBounds() {
        LiveStreamConfig("rtmps://host/live", "key").validate()
        assertTrue(runCatching { LiveStreamConfig("rtmps://host/live", "key", 1440).validate() }.isFailure)
        assertTrue(runCatching { LiveStreamConfig("rtmps://host/live", "key", fps = 120).validate() }.isFailure)
        assertTrue(runCatching { LiveStreamConfig("rtmps://host/live", "key", bitrateKbps = 499).validate() }.isFailure)
        assertTrue(runCatching { LiveStreamConfig("rtmps://host/live", "key", bitrateKbps = 12001).validate() }.isFailure)
    }
    @Test fun preservesAspectRatioAndEvenDimensionsWithoutUpscaling() {
        val config = LiveStreamConfig("rtmps://host/live", "key")
        assertEquals(720 to 1280, config.dimensions(1080, 1920))
        assertEquals(1280 to 720, config.dimensions(1920, 1080))
        assertEquals(480 to 800, config.dimensions(480, 800))
        val (w, h) = config.dimensions(1081, 2401)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertTrue(h <= 1920)
    }
    @Test fun capsExtremeAspectRatioAndDoesNotLeakKeyInToString() {
        val config = LiveStreamConfig("rtmps://host/live", "private-key", 1080)
        assertEquals(864 to 1920, config.dimensions(1080, 2400))
        assertFalse(config.toString().contains("private-key"))
    }
}
