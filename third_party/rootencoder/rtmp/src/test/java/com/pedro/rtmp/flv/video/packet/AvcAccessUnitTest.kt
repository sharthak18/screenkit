package com.pedro.rtmp.flv.video.packet

import com.pedro.common.frame.MediaFrame
import com.pedro.rtmp.flv.FlvPacket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class AvcAccessUnitTest {
  @Test fun splitsBothStartCodeSizesAndPreservesEmulationPrevention() {
    val nals = AvcAccessUnit.split(byteArrayOf(0,0,0,1,6,9,0,0,1,0x65,0,0,3,1))
    assertEquals(2, nals.size)
    assertArrayEquals(byteArrayOf(6,9), nals[0])
    assertArrayEquals(byteArrayOf(0x65,0,0,3,1), nals[1])
  }
  @Test fun acceptsLengthPrefixedNal() {
    assertArrayEquals(byteArrayOf(0x65,9), AvcAccessUnit.split(byteArrayOf(0,0,0,2,0x65,9)).single())
  }
  @Test fun rejectsTruncatedLengths() {
    assertTrue(runCatching { AvcAccessUnit.split(byteArrayOf(0,0,0,8,0x65)) }.isFailure)
  }
  @Test fun emitsConfigAndOneLengthPerNalWithTimestamp() = runBlocking {
    val packetizer = H264Packet()
    packetizer.sendVideoInfo(ByteBuffer.wrap(byteArrayOf(0,0,0,1,0x67,0x42,0,0x1f)), ByteBuffer.wrap(byteArrayOf(0,0,1,0x68,1)))
    val bytes = byteArrayOf(0,0,0,1,6,9,0,0,1,0x65,10,11)
    val packets = mutableListOf<FlvPacket>()
    packetizer.createFlvPacket(MediaFrame(ByteBuffer.wrap(bytes), MediaFrame.Info(0, bytes.size, 2_000_000, true), MediaFrame.Type.VIDEO)) { packets += it }
    assertEquals(2, packets.size)
    assertEquals(0, packets[0].buffer[1].toInt())
    val payload = packets[1].buffer
    assertEquals(0x17, payload[0].toInt())
    assertEquals(1, payload[1].toInt())
    val nals = AvcAccessUnit.split(payload.copyOfRange(5, payload.size))
    assertEquals(2, nals.size)
    assertArrayEquals(byteArrayOf(6,9), nals[0])
    assertArrayEquals(byteArrayOf(0x65,10,11), nals[1])
    assertEquals(2000L, packets[1].timeStamp)
  }
}
