package com.pedro.common

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUrlTest {
  @Test fun escapedKeysStayByteForByteIntact() {
    val parser = UrlParser.parse("rtmps://host/live/key%2Fvalue?token=a%2Bb%3D&x=1", arrayOf("rtmps"))
    assertEquals("live", parser.getAppName())
    assertEquals("key%2Fvalue?token=a%2Bb%3D&x=1", parser.getStreamName())
    assertEquals("rtmps://host/live", parser.getTcUrl())
  }
  @Test fun facebookStyleKeyKeepsQuery() {
    val parser = UrlParser.parse("rtmps://live-api-s.facebook.com:443/rtmp/123?s_bl=1&s_sc=abc", arrayOf("rtmps"))
    assertEquals("rtmp", parser.getAppName())
    assertEquals("123?s_bl=1&s_sc=abc", parser.getStreamName())
  }
}
