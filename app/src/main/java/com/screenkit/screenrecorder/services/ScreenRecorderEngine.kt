package com.screenkit.screenrecorder.services

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import com.screenkit.screenrecorder.data.CaptureAudioMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * H.264 + AAC screen recorder built on MediaCodec and MediaMuxer.
 *
 * Handles Microphone (with 4.0x +12 dB pre-amp gain & soft-knee limiter), Device Audio
 * (via AudioPlaybackCapture), and Mixed (Mic + Device Audio) at 48 kHz.
 */
internal class ScreenRecorderEngine(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    private val audioMode: CaptureAudioMode,
    private val projection: MediaProjection,
    private val descriptor: ParcelFileDescriptor?,
    private val file: File?,
    private val onAudioUnavailable: (String) -> Unit,
    private val publisher: com.screenkit.screenrecorder.streaming.RtmpPublisher? = null,
) {
    private class CaptureSource(
        val record: AudioRecord,
        val queue: LinkedBlockingQueue<ShortArray>,
        val isMicrophone: Boolean,
        var thread: Thread? = null,
        var agc: AutomaticGainControl? = null,
        var ns: NoiseSuppressor? = null,
    )

    /** MediaMuxer only accepts direct buffers, so early encoder buffers are stashed here. */
    private class StashedSample(val buffer: ByteBuffer, val size: Int, val ptsUs: Long, val flags: Int)

    private val captureActive = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val muxerLock = Any()
    private val audioSources = mutableListOf<CaptureSource>()
    private var micSource: CaptureSource? = null
    private var deviceSource: CaptureSource? = null
    private val videoStash = ArrayDeque<StashedSample>()
    private val audioStash = ArrayDeque<StashedSample>()

    @Volatile private var videoCodec: MediaCodec? = null
    @Volatile private var audioCodec: MediaCodec? = null
    @Volatile private var audioAbandoned = false
    @Volatile private var videoTrackIndex = -1
    @Volatile private var audioTrackIndex = -1
    @Volatile private var muxerStarted = false
    @Volatile private var wroteVideoSample = false
    @Volatile private var totalAudioSamplesFed = 0L
    @Volatile private var lastAudioInputPtsUs = 0L
    @Volatile var isPaused: Boolean = false
        private set
    private var pauseStartedAtRawVideoUs = -1L
    private var totalPausedVideoUs = 0L
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoDrain: Thread? = null
    private var audioDrain: Thread? = null
    private var mixerThread: Thread? = null
    private var sessionStartElapsed = 0L
    private var drainDeadline = 0L
    private var firstVideoRawUs = -1L
    private var firstAudioRawUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var prepared = false
    @Volatile var muted: Boolean = false

    fun requestKeyFrame() {
        runCatching { videoCodec?.setParameters(android.os.Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }) }
    }

    /** Surface the caller feeds the display into. */
    val surface: Surface get() = inputSurface ?: error("The encoder surface is not ready.")

    /**
     * Prepares the video encoder and its input surface so the caller can attach VirtualDisplay
     * before audio capture and encoding threads begin.
     */
    fun prepare() {
        if (prepared) return
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (publisher != null) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        val video = MediaCodec.createEncoderByType(VIDEO_MIME)
        videoCodec = video // Owned immediately so a configure/prepare failure can release it.
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val createdSurface = video.createInputSurface()
        inputSurface = createdSurface
        video.start()
        videoCodec = video
        inputSurface = createdSurface

        if (publisher == null) {
            muxer = createMuxer() ?: throw IllegalStateException("Could not open the recording output.")
        }
        prepared = true
    }

    fun pause() {
        if (!captureActive.get() || isPaused) return
        isPaused = true
    }

    fun resume() {
        if (!captureActive.get() || !isPaused) return
        isPaused = false
        runCatching {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            videoCodec?.setParameters(params)
        }
    }

    fun start() {
        if (!prepared) prepare()
        val video = videoCodec ?: error("Video encoder was not prepared.")
        sessionStartElapsed = SystemClock.elapsedRealtime()

        // IMPORTANT: Set captureActive = true BEFORE starting audio capture threads so reader
        // threads never see captureActive == false on startup!
        captureActive.set(true)

        startAudioCapture()

        videoDrain = drainLoop(video, isVideo = true).also { it.start() }
        audioCodec?.let { codec ->
            audioDrain = drainLoop(codec, isVideo = false).also { it.start() }
        }
        if (audioCodec != null) {
            mixerThread = mixerLoop().also { it.start() }
        }
    }

    /**
     * Finishes the file. Returns true when a playable recording was written.
     */
    fun stop(): Boolean {
        captureActive.set(false)
        audioSources.forEach { source -> runCatching { source.record.stop() } }
        audioSources.forEach { source -> runCatching { source.thread?.join(500L) } }
        mixerThread?.let { thread -> runCatching { thread.join(800L) } }

        drainDeadline = SystemClock.elapsedRealtime() + 2_500L
        signalAudioEndOfStream()
        runCatching { videoCodec?.signalEndOfInputStream() }
        videoDrain?.let { thread -> runCatching { thread.join(2_600L) } }
        audioDrain?.let { thread -> runCatching { thread.join(2_600L) } }

        var finalized = false
        synchronized(muxerLock) {
            val current = muxer
            if (muxerStarted && current != null) {
                finalized = runCatching { current.stop(); true }.getOrDefault(false)
                muxerStarted = false
            }
        }
        releaseResources()
        return finalized && wroteVideoSample
    }

    /** Releases everything without finalizing; used when a session fails or is cancelled. */
    fun abort() {
        captureActive.set(false)
        audioSources.forEach { source -> runCatching { source.record.stop() } }
        drainDeadline = SystemClock.elapsedRealtime()
        videoDrain?.let { thread -> runCatching { thread.join(300L) } }
        audioDrain?.let { thread -> runCatching { thread.join(300L) } }
        mixerThread?.let { thread -> runCatching { thread.join(300L) } }
        releaseResources()
    }

    // ------------------------------------------------------------------ setup

    private fun createMuxer(): MediaMuxer? {
        val target = descriptor
        if (target != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            return runCatching {
                MediaMuxer(target.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }.getOrNull()
        }
        val path = file ?: return null
        path.parentFile?.mkdirs()
        return runCatching {
            MediaMuxer(path.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }.getOrNull()
    }

    private fun startAudioCapture() {
        if (publisher == null && !audioMode.usesMicrophone && !audioMode.usesDeviceAudio) return
        if (audioMode.usesDeviceAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onAudioUnavailable("Device audio needs Android 10 or newer.")
        }

        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1_024)
        }
        val codec = runCatching {
            MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.getOrNull()
        if (codec == null) {
            if (publisher != null) error("No AAC encoder is available for streaming.")
            onAudioUnavailable("No audio encoder is available; this recording is silent.")
            return
        }

        val wantsMicrophone = audioMode.usesMicrophone
        val wantsDevice = audioMode.usesDeviceAudio
        val canUseDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // Open device audio first on the active MediaProjection, then open the microphone so both
        // AudioRecord sessions coexist cleanly on Android 10–15.
        val device = if (wantsDevice && canUseDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openAndStartDeviceAudio()
        } else {
            null
        }
        val microphone = if (wantsMicrophone) {
            openAndStartMicrophone()
        } else {
            null
        }

        deviceSource = device
        micSource = microphone

        if (wantsMicrophone && microphone == null) {
            onAudioUnavailable("The microphone could not be opened, so it is missing from this recording.")
        }
        if (wantsDevice && device == null) {
            onAudioUnavailable("Device audio was unavailable for this session.")
        }
        if (microphone == null && device == null && publisher == null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (wantsMicrophone || wantsDevice) {
                onAudioUnavailable("No audio source started, so this recording is silent.")
            }
            return
        }
        audioCodec = codec
    }

    private fun openAndStartMicrophone(): CaptureSource? {
        val candidateSources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.DEFAULT,
        )
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING).coerceAtLeast(4096)
        val bufferBytes = maxOf(minimum * 2, 16 * 1_024)

        for (audioSource in candidateSources) {
            val record = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(pcmFormat())
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull() ?: continue

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                continue
            }

            val started = runCatching {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

            if (!started) {
                runCatching { record.release() }
                continue
            }

            val agc = runCatching {
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
                } else null
            }.getOrNull()

            val ns = runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                } else null
            }.getOrNull()

            val queue = LinkedBlockingQueue<ShortArray>(64)
            val source = CaptureSource(
                record = record,
                queue = queue,
                isMicrophone = true,
                agc = agc,
                ns = ns,
            )
            val readerThread = Thread({ readSource(source) }, "ScreenKitMicReader").apply {
                isDaemon = true
            }
            source.thread = readerThread
            audioSources += source
            readerThread.start()
            return source
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openAndStartDeviceAudio(): CaptureSource? {
        val configuration = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        }.getOrNull() ?: return null

        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING).coerceAtLeast(4096)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(pcmFormat())
                .setAudioPlaybackCaptureConfig(configuration)
                .setBufferSizeInBytes(maxOf(minimum * 2, 32 * 1_024))
                .build()
        }.getOrNull() ?: return null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }

        val started = runCatching {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)

        if (!started) {
            runCatching { record.release() }
            return null
        }

        val queue = LinkedBlockingQueue<ShortArray>(64)
        val source = CaptureSource(
            record = record,
            queue = queue,
            isMicrophone = false,
        )
        val readerThread = Thread({ readSource(source) }, "ScreenKitDeviceAudioReader").apply {
            isDaemon = true
        }
        source.thread = readerThread
        audioSources += source
        readerThread.start()
        return source
    }

    private fun pcmFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(ENCODING)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(CHANNEL_MASK)
        .build()

    // ------------------------------------------------------------------ audio threads & DSP amplification

    private fun readSource(source: CaptureSource) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val record = source.record
        val queue = source.queue
        val isMic = source.isMicrophone
        var chunk = ShortArray(SAMPLES_PER_CHUNK)
        var offset = 0

        while (captureActive.get()) {
            val read = runCatching {
                record.read(chunk, offset, SAMPLES_PER_CHUNK - offset)
            }.getOrDefault(-1)

            if (read < 0) break
            if (read == 0) {
                runCatching { Thread.sleep(2L) }
                continue
            }

            if (isMic) {
                for (i in offset until (offset + read)) {
                    chunk[i] = amplifyMicSample(chunk[i])
                }
            } else {
                for (i in offset until (offset + read)) {
                    chunk[i] = amplifyDeviceSample(chunk[i])
                }
            }

            offset += read
            if (offset >= SAMPLES_PER_CHUNK) {
                if (!queue.offer(chunk)) {
                    queue.poll()
                    queue.offer(chunk)
                }
                chunk = ShortArray(SAMPLES_PER_CHUNK)
                offset = 0
            }
        }
    }

    /**
     * Amplifies microphone input by 4.0x (+12 dB) with a smooth rational soft-knee limiter above
     * 22,000 so voice commentary is loud and clear without harsh digital clipping.
     */
    private fun amplifyMicSample(sample: Short): Short {
        val boosted = sample.toInt() * MIC_GAIN_MULTIPLIER
        val absVal = abs(boosted)
        val knee = 22_000f
        val maxOut = 32_600f
        val headroom = maxOut - knee
        val shaped = if (absVal <= knee) {
            absVal
        } else {
            val excess = (absVal - knee) / (headroom * 2.5f)
            knee + headroom * (excess / (1f + excess))
        }
        val signed = if (boosted < 0f) -shaped else shaped
        return signed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Gently boosts internal device audio (1.25x) with soft-knee limiting.
     */
    private fun amplifyDeviceSample(sample: Short): Short {
        val boosted = sample.toInt() * 1.25f
        val absVal = abs(boosted)
        val knee = 25_000f
        val maxOut = 32_600f
        val headroom = maxOut - knee
        val shaped = if (absVal <= knee) {
            absVal
        } else {
            val excess = (absVal - knee) / (headroom * 2.0f)
            knee + headroom * (excess / (1f + excess))
        }
        val signed = if (boosted < 0f) -shaped else shaped
        return signed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Mixes an already-amplified microphone sample with a device audio sample using soft-knee
     * anti-clipping saturation so both tracks remain clear.
     */
    private fun mixSamples(micSample: Short, deviceSample: Short): Short {
        val combined = micSample.toInt() + (deviceSample.toInt() * 9) / 10
        val absVal = abs(combined).toFloat()
        val knee = 24_000f
        val maxOut = 32_600f
        val headroom = maxOut - knee
        val shaped = if (absVal <= knee) {
            absVal
        } else {
            val excess = (absVal - knee) / (headroom * 2.2f)
            knee + headroom * (excess / (1f + excess))
        }
        val signed = if (combined < 0) -shaped else shaped
        return signed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun mixerLoop(): Thread = Thread({
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val mixed = ShortArray(SAMPLES_PER_CHUNK)
        val silent = ShortArray(SAMPLES_PER_CHUNK)
        val mic = micSource
        val dev = deviceSource

        while (captureActive.get() && !audioAbandoned) {
            if (isPaused) {
                mic?.queue?.clear()
                dev?.queue?.clear()
                runCatching { Thread.sleep(20L) }
                continue
            }
            val micChunk: ShortArray?
            val devChunk: ShortArray?

            if (mic != null && dev != null) {
                // MIXED (Mic + Device): pace on hardware microphone (20ms per chunk) and mix
                // device audio chunks when available.
                micChunk = runCatching { mic.queue.poll(25L, TimeUnit.MILLISECONDS) }.getOrNull()
                while (dev.queue.size > 4) {
                    dev.queue.poll()
                }
                devChunk = if (micChunk != null) {
                    dev.queue.poll() ?: runCatching { dev.queue.poll(2L, TimeUnit.MILLISECONDS) }.getOrNull()
                } else {
                    runCatching { dev.queue.poll(5L, TimeUnit.MILLISECONDS) }.getOrNull()
                }
            } else if (mic != null) {
                micChunk = runCatching { mic.queue.poll(25L, TimeUnit.MILLISECONDS) }.getOrNull()
                devChunk = null
            } else if (dev != null) {
                devChunk = runCatching { dev.queue.poll(22L, TimeUnit.MILLISECONDS) }.getOrNull()
                micChunk = null
            } else {
                // Live destinations still get a paced silent AAC track when sound is off.
                runCatching { Thread.sleep(20L) }
                micChunk = null
                devChunk = null
            }

            when {
                micChunk != null && devChunk != null -> {
                    for (i in 0 until SAMPLES_PER_CHUNK) {
                        mixed[i] = mixSamples(micChunk[i], devChunk[i])
                    }
                    feedAudio(mixed, SAMPLES_PER_CHUNK)
                }
                micChunk != null -> {
                    feedAudio(micChunk, SAMPLES_PER_CHUNK)
                }
                devChunk != null -> {
                    feedAudio(devChunk, SAMPLES_PER_CHUNK)
                }
                else -> {
                    // Keep the AAC encoder fed with 20ms silent frames when no app is playing audio
                    // so the muxer starts immediately and audio stays synced when sound begins.
                    feedAudio(silent, SAMPLES_PER_CHUNK)
                }
            }
        }
    }, "ScreenKitAudioMixer").apply { isDaemon = true }

    private fun feedAudio(samples: ShortArray, length: Int) {
        val codec = audioCodec ?: return
        if (audioAbandoned) return
        val bytes = ByteArray(length * 2)
        if (!muted) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, length)
        var offset = 0
        var attempts = 0
        while (captureActive.get() && offset < bytes.size) {
            val index = runCatching { codec.dequeueInputBuffer(20_000L) }.getOrDefault(-1)
            if (index < 0) {
                attempts++
                if (attempts > 120 || audioAbandoned) return
                continue
            }
            val buffer = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: return
            buffer.clear()
            val size = minOf(buffer.remaining(), bytes.size - offset)
            buffer.put(bytes, offset, size)
            val samplesInBuffer = (size / 2).toLong()
            val ptsUs = (totalAudioSamplesFed * 1_000_000L) / SAMPLE_RATE.toLong()
            totalAudioSamplesFed += samplesInBuffer
            lastAudioInputPtsUs = ptsUs
            runCatching { codec.queueInputBuffer(index, 0, size, ptsUs, 0) }
            offset += size
        }
    }

    private fun signalAudioEndOfStream() {
        val codec = audioCodec ?: return
        if (audioAbandoned) return
        runCatching {
            val index = codec.dequeueInputBuffer(80_000L)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, lastAudioInputPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    // ------------------------------------------------------------------ encoder threads

    private fun drainLoop(codec: MediaCodec, isVideo: Boolean): Thread = Thread({
        val info = MediaCodec.BufferInfo()
        var endOfStream = false
        while (!endOfStream) {
            if (drainDeadline > 0L && SystemClock.elapsedRealtime() > drainDeadline) break
            val index = runCatching { codec.dequeueOutputBuffer(info, 10_000L) }
                .getOrDefault(Int.MIN_VALUE)
            when {
                index == Int.MIN_VALUE -> {
                    if (captureActive.get()) publisher?.encoderError()
                    break
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (isVideo) nudgeAudioStart()
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> registerTrack(codec, isVideo)
                index >= 0 -> {
                    val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        try { handleSample(isVideo, buffer, info) }
                        catch (_: Exception) { publisher?.encoderError() }
                    }
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) endOfStream = true
                }
            }
        }
    }, if (isVideo) "ScreenKitVideoEncode" else "ScreenKitAudioEncode").apply { isDaemon = true }

    private fun registerTrack(codec: MediaCodec, isVideo: Boolean) {
        val format = runCatching { codec.outputFormat }.getOrNull() ?: return
        if (publisher != null) {
            if (isVideo) runCatching { publisher.videoFormat(format) }.onFailure { publisher.encoderError() }
            return
        }
        synchronized(muxerLock) {
            val current = muxer ?: return
            if (muxerStarted) return
            if (isVideo) {
                if (videoTrackIndex < 0) {
                    videoTrackIndex = runCatching { current.addTrack(format) }.getOrDefault(-1)
                }
            } else {
                if (audioTrackIndex < 0) {
                    audioTrackIndex = runCatching { current.addTrack(format) }.getOrDefault(-1)
                }
            }
            maybeStartMuxerLocked()
        }
    }

    private fun maybeStartMuxerLocked() {
        if (muxerStarted) return
        val current = muxer ?: return
        if (videoTrackIndex < 0) return
        val audioExpected = audioCodec != null && !audioAbandoned
        if (audioExpected && audioTrackIndex < 0) return
        val started = runCatching {
            current.start()
            true
        }.getOrDefault(false)
        if (!started) return
        muxerStarted = true
        flushStashLocked(current)
    }

    private fun nudgeAudioStart() {
        val codec = audioCodec ?: return
        if (audioAbandoned || videoTrackIndex < 0 || audioTrackIndex >= 0) return
        if (SystemClock.elapsedRealtime() - sessionStartElapsed < 3_500L) return
        audioAbandoned = true
        onAudioUnavailable("Audio encoder did not start in time; this recording has video only.")
        runCatching { codec.stop() }
        runCatching { codec.release() }
        audioCodec = null
        synchronized(muxerLock) { maybeStartMuxerLocked() }
    }

    private fun handleSample(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (isPaused) {
            if (isVideo && pauseStartedAtRawVideoUs < 0L) {
                pauseStartedAtRawVideoUs = info.presentationTimeUs
            }
            return
        }
        if (isVideo && pauseStartedAtRawVideoUs >= 0L) {
            val delta = (info.presentationTimeUs - pauseStartedAtRawVideoUs).coerceAtLeast(0L)
            totalPausedVideoUs += delta
            pauseStartedAtRawVideoUs = -1L
        }
        val track = if (isVideo) videoTrackIndex else audioTrackIndex
        val ptsUs = if (isVideo) videoPtsUs(info.presentationTimeUs) else audioPtsUs(info.presentationTimeUs)
        if (publisher != null) {
            val out = MediaCodec.BufferInfo().apply { set(0, info.size, ptsUs, info.flags) }
            val copy = buffer.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.slice()
            publisher.sample(isVideo, copy, out)
            return
        }
        synchronized(muxerLock) {
            val current = muxer ?: return
            if (!muxerStarted || track < 0) {
                val stash = if (isVideo) videoStash else audioStash
                val limit = if (isVideo) 36 else 400
                if (stash.size >= limit) stash.removeFirst()
                val copy = ByteBuffer.allocateDirect(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                copy.put(buffer)
                copy.flip()
                stash.addLast(StashedSample(copy, info.size, ptsUs, info.flags))
                return
            }
            val out = MediaCodec.BufferInfo().apply { set(0, info.size, ptsUs, info.flags) }
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            runCatching { current.writeSampleData(track, buffer, out) }
            if (isVideo) wroteVideoSample = true
        }
    }

    private fun flushStashLocked(current: MediaMuxer) {
        while (videoStash.isNotEmpty()) {
            writeStashedLocked(current, videoTrackIndex, videoStash.removeFirst())
        }
        while (audioStash.isNotEmpty()) {
            writeStashedLocked(current, audioTrackIndex, audioStash.removeFirst())
        }
    }

    private fun writeStashedLocked(current: MediaMuxer, track: Int, sample: StashedSample) {
        if (track < 0 || sample.size <= 0) return
        val buffer = sample.buffer
        buffer.position(0)
        buffer.limit(sample.size)
        val out = MediaCodec.BufferInfo().apply { set(0, sample.size, sample.ptsUs, sample.flags) }
        runCatching { current.writeSampleData(track, buffer, out) }
        if (track == videoTrackIndex) wroteVideoSample = true
    }

    // ------------------------------------------------------------------ timestamps

    /**
     * Both video and audio tracks are anchored to 0 us on their first sample so they advance in
     * lockstep elapsed recording microseconds regardless of hardware clock domain.
     */
    private fun videoPtsUs(rawUs: Long): Long {
        if (firstVideoRawUs < 0L) firstVideoRawUs = rawUs
        val elapsed = (rawUs - firstVideoRawUs - totalPausedVideoUs).coerceAtLeast(0L)
        val next = if (elapsed > lastVideoPtsUs) elapsed else lastVideoPtsUs + 100L
        lastVideoPtsUs = next
        return next
    }

    private fun audioPtsUs(rawUs: Long): Long {
        if (firstAudioRawUs < 0L) firstAudioRawUs = rawUs
        val elapsed = (rawUs - firstAudioRawUs).coerceAtLeast(0L)
        val next = if (elapsed > lastAudioPtsUs) elapsed else lastAudioPtsUs + 100L
        lastAudioPtsUs = next
        return next
    }

    // ------------------------------------------------------------------ teardown

    private fun releaseResources() {
        if (!released.compareAndSet(false, true)) return
        audioSources.forEach { source ->
            runCatching { source.agc?.release() }
            runCatching { source.ns?.release() }
            runCatching { source.record.release() }
        }
        audioSources.clear()
        micSource = null
        deviceSource = null
        val audio = audioCodec
        if (audio != null) {
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }
        audioCodec = null
        val video = videoCodec
        if (video != null) {
            runCatching { video.stop() }
            runCatching { video.release() }
        }
        videoCodec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        synchronized(muxerLock) {
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
        }
        videoStash.clear()
        audioStash.clear()
    }

    private companion object {
        const val VIDEO_MIME = "video/avc"
        const val AUDIO_MIME = "audio/mp4a-latm"
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 1
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val SAMPLES_PER_CHUNK = 960
        const val MIC_GAIN_MULTIPLIER = 4.0f
    }
}
