package com.pedro.common.socket

import org.junit.Assert.*
import org.junit.Test

class HostnameTrustManagerTest {
    @Test fun exactAndSingleLabelWildcardOnly() {
        assertTrue(HostnameTrustManager.matchesDns("LIVE.Example.com", "live.example.com"))
        assertTrue(HostnameTrustManager.matchesDns("live.example.com", "*.example.com"))
        assertFalse(HostnameTrustManager.matchesDns("example.com", "*.example.com"))
        assertFalse(HostnameTrustManager.matchesDns("a.live.example.com", "*.example.com"))
        assertFalse(HostnameTrustManager.matchesDns("badexample.com", "*.example.com"))
        assertFalse(HostnameTrustManager.matchesDns("example.com", "*.com"))
        assertFalse(HostnameTrustManager.matchesDns("a.example.com", "a.*.com"))
        assertFalse(HostnameTrustManager.matchesDns("a.example.com", "b.example.com"))
        assertFalse(HostnameTrustManager.matchesDns("a.example.com", ""))
    }
}
