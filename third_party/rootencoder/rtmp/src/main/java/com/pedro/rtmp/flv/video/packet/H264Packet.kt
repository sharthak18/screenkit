/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.rtmp.flv.video.packet

// ScreenKit: upstream command/error logs may contain the stream key.
import com.pedro.common.StreamLog as Log
import com.pedro.common.frame.MediaFrame
import com.pedro.common.removeInfo
import com.pedro.rtmp.flv.BasePacket
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.flv.video.VideoDataType
import com.pedro.rtmp.flv.video.VideoFormat
import com.pedro.rtmp.flv.video.VideoNalType
import com.pedro.rtmp.flv.video.config.VideoSpecificConfigAVC
import java.nio.ByteBuffer

/**
 * Created by pedro on 8/04/21.
 *
 * ISO 14496-15
 */
class H264Packet: BasePacket() {

  private val TAG = "H264Packet"

  private val header = ByteArray(5)
  //first time we need send video config
  private var configSend = false

  private var sps: ByteArray? = null
  private var pps: ByteArray? = null

  enum class Type(val value: Byte) {
    SEQUENCE(0x00), NALU(0x01), EO_SEQ(0x02)
  }

  fun sendVideoInfo(sps: ByteBuffer, pps: ByteBuffer) {
    val mSps = removeHeader(sps)
    val mPps = removeHeader(pps)

    val spsBytes = ByteArray(mSps.remaining())
    val ppsBytes = ByteArray(mPps.remaining())
    mSps.get(spsBytes, 0, spsBytes.size)
    mPps.get(ppsBytes, 0, ppsBytes.size)

    this.sps = spsBytes
    this.pps = ppsBytes
  }

  override suspend fun createFlvPacket(
    mediaFrame: MediaFrame,
    callback: suspend (FlvPacket) -> Unit
  ) {
    val fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)
    val ts = mediaFrame.info.timestamp / 1000
    //header is 5 bytes length:
    //4 bits FrameType, 4 bits CodecID
    //1 byte AVCPacketType
    //3 bytes CompositionTime, the cts.
    val cts = 0
    header[2] = (cts shr 16).toByte()
    header[3] = (cts shr 8).toByte()
    header[4] = cts.toByte()

    var buffer: ByteArray
    if (!configSend) {
      header[0] = ((VideoDataType.KEYFRAME.value shl 4) or VideoFormat.AVC.value).toByte()
      header[1] = Type.SEQUENCE.value

      val sps = this.sps
      val pps = this.pps
      if (sps != null && pps != null) {
        val config = VideoSpecificConfigAVC(sps, pps)
        buffer = ByteArray(config.size + header.size)
        config.write(buffer, header.size)
      } else {
        Log.e(TAG, "waiting for a valid sps and pps")
        return
      }

      System.arraycopy(header, 0, buffer, 0, header.size)
      callback(FlvPacket(buffer, ts, buffer.size, FlvType.VIDEO))
      configSend = true
    }
    // ScreenKit: codecs can emit SEI + multiple slices (or repeated SPS/PPS) in one buffer.
    // Treating the entire access unit as one NAL produces an invalid FLV AVC packet.
    val bytes = ByteArray(fixedBuffer.remaining()).also { fixedBuffer.get(it) }
    val nals = AvcAccessUnit.split(bytes).filter { nal ->
      val type = nal[0].toInt() and 0x1f
      type != VideoNalType.SPS.value && type != VideoNalType.PPS.value
    }
    if (nals.isEmpty()) return
    val keyframe = mediaFrame.info.isKeyFrame || nals.any { (it[0].toInt() and 0x1f) == VideoNalType.IDR.value }
    val nalType = if (keyframe) VideoDataType.KEYFRAME.value else VideoDataType.INTER_FRAME.value
    header[0] = ((nalType shl 4) or VideoFormat.AVC.value).toByte()
    header[1] = Type.NALU.value
    val packet = ByteBuffer.allocate(header.size + nals.sumOf { 4 + it.size })
    packet.put(header)
    nals.forEach { packet.putInt(it.size); packet.put(it) }
    buffer = packet.array()
    callback(FlvPacket(buffer, ts, buffer.size, FlvType.VIDEO))
  }

  private fun removeHeader(byteBuffer: ByteBuffer): ByteBuffer {
    val data = byteBuffer.duplicate()
    val prefix = when {
      data.remaining() >= 4 && data.getInt(data.position()) == 1 -> 4
      data.remaining() >= 3 && data.get(data.position()) == 0.toByte() &&
        data.get(data.position() + 1) == 0.toByte() && data.get(data.position() + 2) == 1.toByte() -> 3
      else -> 0
    }
    data.position(data.position() + prefix)
    return data.slice()
  }

  override fun reset(resetInfo: Boolean) {
    if (resetInfo) {
      sps = null
      pps = null
    }
    configSend = false
  }
}
