package com.screenkit.screenrecorder.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded asynchronous RTMP/RTMPS transport; codec drain threads never perform socket I/O. */
internal class RtmpPublisher(
    private val onConnected: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onBitrate: (Long) -> Unit,
) : ConnectChecker {
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    @Volatile private var connected = false
    @Volatile private var waitingForKeyFrame = true
    private val client = RtmpClient(this).apply {
        setLogs(false)
        setCheckServerAlive(true)
        resizeCache(120)
        setAudioInfo(48_000, false)
    }
    private val timeout = Runnable { fail("Connection timed out. Check your network, server URL, and stream key.") }

    fun connect(config: LiveStreamConfig, width: Int, height: Int, hasAudio: Boolean) {
        client.setOnlyVideo(!hasAudio)
        client.setVideoResolution(width, height)
        client.setFps(config.fps)
        handler.postDelayed(timeout, 25_000L)
        client.connect(config.endpoint())
    }

    fun videoFormat(format: MediaFormat) {
        val sps = format.getByteBuffer("csd-0") ?: error("H.264 configuration unavailable")
        val pps = format.getByteBuffer("csd-1") ?: error("H.264 configuration unavailable")
        client.setVideoInfo(sps.duplicate(), pps.duplicate(), null)
    }

    fun sample(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (closed.get() || !connected) return
        if (waitingForKeyFrame) {
            if (!video || info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
            waitingForKeyFrame = false
        }
        // RootEncoder clones the bytes synchronously before queueing them; the codec may then release its buffer.
        if (video) client.sendVideo(buffer, info) else client.sendAudio(buffer, info)
    }

    fun encoderError() = fail("The audio/video encoder stopped. Lower the quality and start a new stream.")

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        handler.removeCallbacks(timeout)
        client.disconnect()
    }

    private fun fail(message: String) {
        handler.post {
            if (!closed.get()) {
                close()
                onFailure(message)
            }
        }
    }

    override fun onConnectionStarted(url: String) = Unit // Never expose/log the URL: it includes the key.
    override fun onConnectionSuccess() {
        if (closed.get()) return
        handler.removeCallbacks(timeout)
        connected = true
        handler.post { if (!closed.get()) onConnected() }
    }
    override fun onConnectionFailed(reason: String) = fail(
        "Stream connection failed. Check your network, live dashboard, server URL, and stream key."
    ) // Server error strings can contain credentials; never display them.
    override fun onDisconnect() { if (!closed.get()) fail("The streaming server disconnected. Start a new stream to reconnect.") }
    override fun onAuthError() = fail("The server rejected authentication. Check the stream key in your live dashboard.")
    override fun onAuthSuccess() = Unit
    override fun onNewBitrate(bitrate: Long) { if (!closed.get()) handler.post { if (!closed.get()) onBitrate(bitrate) } }
}
