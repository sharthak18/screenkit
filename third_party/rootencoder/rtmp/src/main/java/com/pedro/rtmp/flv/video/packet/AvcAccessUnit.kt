package com.pedro.rtmp.flv.video.packet

/** ScreenKit: each NAL in an Annex-B access unit needs its own AVCC length prefix. */
internal object AvcAccessUnit {
  fun split(bytes: ByteArray): List<ByteArray> {
    fun startCodeAt(i: Int): Int = when {
      i + 3 < bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
      i + 2 < bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
      else -> 0
    }
    if (bytes.isEmpty()) return emptyList()
    val result = mutableListOf<ByteArray>()
    if (startCodeAt(0) == 0) {
      // Some encoders already produce four-byte AVCC lengths.
      var offset = 0
      while (offset + 4 <= bytes.size) {
        val length = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
        require(length > 0 && length <= bytes.size - offset - 4) { "Invalid AVC length" }
        result += bytes.copyOfRange(offset + 4, offset + 4 + length)
        offset += 4 + length
      }
      require(offset == bytes.size) { "Truncated AVC access unit" }
      return result
    }
    var start = startCodeAt(0)
    var cursor = start
    while (cursor < bytes.size) {
      val size = startCodeAt(cursor)
      if (size > 0) {
        if (cursor > start) result += bytes.copyOfRange(start, cursor)
        start = cursor + size
        cursor = start
      } else cursor++
    }
    if (start < bytes.size) result += bytes.copyOfRange(start, bytes.size)
    return result
  }
}
