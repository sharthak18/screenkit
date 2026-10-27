import AVFoundation
import HaishinKit
import RTMPHaishinKit
@preconcurrency import Logboard
import VideoToolbox

struct BroadcastStopped: Error {}

/// Never forward transport errors or log payloads: RTMP server responses may contain the key.
private struct PrivateLogAppender: LBLoggerAppender {
    func append(_ logboard: LBLogger, level: LBLogger.Level, message: [Any], file: StaticString, function: StaticString, line: Int) {}
    func append(_ logboard: LBLogger, level: LBLogger.Level, format: String, arguments: any CVarArg, file: StaticString, function: StaticString, line: Int) {}
}

actor BroadcastPipeline {
    private let scaler = ReplayVideoScaler()
    private var connection: RTMPConnection?
    private var stream: RTMPStream?
    private var mixer: MediaMixer?
    private var store: BroadcastStore?
    private var config: BroadcastConfiguration?
    private var status: BroadcastStatus?
    private var closed = false
    private var paused = false
    private var configuredSize: (Int, Int)?
    private var lastVideoTime = -Double.infinity
    private var lastReceivedVideo = Date()

    func start() async throws {
        try Task.checkCancellation()
        guard !closed else { throw CancellationError() }
        LBLogger.with(kHaishinKitIdentifier).appender = PrivateLogAppender()
        LBLogger.with(kRTMPHaishinKitIdentifier).appender = PrivateLogAppender()
        let store = try BroadcastStore()
        self.store = store
        let (config, destination) = try store.consume()
        self.config = config
        status = .init(id: config.id, phase: .connecting, message: "Connecting to streaming server…")
        try persist()
        let connection = RTMPConnection(timeout: 10, requestTimeout: 5000)
        let stream = RTMPStream(connection: connection)
        let mixer = MediaMixer(captureSessionMode: .manual, multiTrackAudioMixingEnabled: true)
        self.connection = connection; self.stream = stream; self.mixer = mixer
        await stream.setVideoInputBufferCounts(1)
        await mixer.setVideoMixerSettings(.init(mode: .passthrough))
        // ReplayKit app audio is the clock for mixed output; microphone-only selects the mic clock.
        let audio = AudioMixerSettings(sampleRate: 48000, channels: 2,
                                       isMuted: config.audio == .silent,
                                       mainTrack: config.audio == .microphone ? 1 : 0,
                                       tracks: [0: .default, 1: .default])
        await mixer.setAudioMixerSettings(audio)
        await mixer.addOutput(stream)
        await mixer.startRunning()
        do {
            try Task.checkCancellation()
            guard !closed else { throw CancellationError() }
            _ = try await connection.connect(destination.server)
            try Task.checkCancellation()
            guard !closed else { throw CancellationError() }
            _ = try await stream.publish(destination.key)
            try Task.checkCancellation()
            guard !closed else { throw CancellationError() }
        } catch {
            if Task.isCancelled || closed { throw CancellationError() }
            throw ScreenKitError.connection
        }
        status?.phase = .live; status?.message = "LIVE · Sending to server"
        status?.startedAt = Date(); lastReceivedVideo = Date()
        try persist()
    }

    func video(_ buffer: CMSampleBuffer) async throws {
        guard !closed, !paused, let stream, let mixer, let config else { return }
        lastReceivedVideo = Date()
        let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(buffer))
        guard pts.isFinite, pts - lastVideoTime >= 1 / Double(config.quality.fps) - 0.001 else { return }
        lastVideoTime = pts
        guard let prepared = try scaler.convert(buffer, quality: config.quality),
              let format = CMSampleBufferGetFormatDescription(prepared) else { return }
        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
        let size = config.quality.dimensions(width: Int(dimensions.width), height: Int(dimensions.height))
        if configuredSize?.0 != size.0 || configuredSize?.1 != size.1 {
            var settings = VideoCodecSettings(videoSize: CGSize(width: size.0, height: size.1),
                                               bitRate: config.quality.bitrateKbps * 1000,
                                               profileLevel: kVTProfileLevel_H264_Baseline_AutoLevel as String,
                                               scalingMode: .letterbox, maxKeyFrameIntervalDuration: 2,
                                               allowFrameReordering: false)
            settings.frameInterval = 1 / Double(config.quality.fps) - 0.001
            do { try await stream.setVideoSettings(settings) } catch { throw ScreenKitError.capture }
            configuredSize = size
        }
        await mixer.append(prepared)
    }

    func audio(_ buffer: CMSampleBuffer, microphone: Bool) async {
        guard !closed, !paused, let config, let mixer else { return }
        if microphone {
            guard config.audio.includesMicrophone else { return }
        } else if config.audio == .microphone { return }
        // Silent mode feeds app samples through a muted mixer, rather than capturing the mic.
        await mixer.append(buffer, track: microphone ? 1 : 0)
    }

    func setPaused(_ value: Bool) async {
        guard !closed else { return }
        paused = value
        status?.phase = value ? .paused : .live
        status?.message = value ? "Sharing paused by iOS" : "LIVE · Sending to server"
        lastReceivedVideo = Date()
        try? persist()
    }

    func monitor() async throws {
        while !Task.isCancelled && !closed {
            try await Task.sleep(for: .seconds(1))
            guard let config, let connection, let stream else { throw ScreenKitError.connection }
            guard await connection.connected, await stream.readyState == .publishing else { throw ScreenKitError.connection }
            // No automatic reconnect: a new user-approved session is required after failure.
            if let control = store?.control(), control.id == config.id {
                if control.stop { throw BroadcastStopped() }
                if status?.muted != control.muted, let mixer {
                    var audio = await mixer.audioMixerSettings
                    audio.isMuted = control.muted || config.audio == .silent
                    await mixer.setAudioMixerSettings(audio)
                    status?.muted = control.muted
                }
            }
            if !paused && Date().timeIntervalSince(lastReceivedVideo) > 30 { throw ScreenKitError.capture }
            try persist()
        }
    }

    func finish(failed: Bool) async {
        guard !closed else { return }
        closed = true
        status?.phase = failed ? .failed : .ended
        status?.message = failed ? "Stream ended. Check the destination and network, then prepare again." : "Live stream ended."
        try? persist()
        await mixer?.stopRunning()
        _ = try? await stream?.close()
        try? await connection?.close()
        stream = nil; connection = nil; mixer = nil; config = nil
    }
    private func persist() throws {
        guard var value = status, let store else { return }
        value.heartbeat = Date(); status = value
        try store.update(value)
    }
}
