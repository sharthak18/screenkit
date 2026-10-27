import ReplayKit
import CoreMedia

/// ReplayKit owns these callbacks. A bounded channel per media type avoids spawning a task per frame.
private struct ReplaySample: @unchecked Sendable { let buffer: CMSampleBuffer }

final class SampleHandler: RPBroadcastSampleHandler, @unchecked Sendable {
    private let video = AsyncStream<ReplaySample>.makeStream(bufferingPolicy: .bufferingNewest(1))
    private let appAudio = AsyncStream<ReplaySample>.makeStream(bufferingPolicy: .bufferingNewest(8))
    private let micAudio = AsyncStream<ReplaySample>.makeStream(bufferingPolicy: .bufferingNewest(8))
    private let pipeline = BroadcastPipeline()
    private var job: Task<Void, Never>?
    private let lock = NSLock()

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        let task = Task { [self] in
            do {
                try await pipeline.start()
                try Task.checkCancellation()
                try await withThrowingTaskGroup(of: Void.self) { group in
                    group.addTask { for await sample in self.video.stream { try Task.checkCancellation(); try await self.pipeline.video(sample.buffer) } }
                    group.addTask { for await sample in self.appAudio.stream { try Task.checkCancellation(); await self.pipeline.audio(sample.buffer, microphone: false) } }
                    group.addTask { for await sample in self.micAudio.stream { try Task.checkCancellation(); await self.pipeline.audio(sample.buffer, microphone: true) } }
                    group.addTask { try await self.pipeline.monitor() }
                    try await group.next()
                    group.cancelAll()
                }
            } catch {
                let stopped = error is BroadcastStopped
                await pipeline.finish(failed: !stopped && !Task.isCancelled)
                if !Task.isCancelled {
                    let message = stopped ? "Live stream ended." : (error as? ScreenKitError)?.localizedDescription ?? ScreenKitError.connection.localizedDescription
                    finishBroadcastWithError(NSError(domain: "ScreenKit.Broadcast", code: stopped ? 0 : 1,
                                                      userInfo: [NSLocalizedDescriptionKey: message]))
                }
            }
            await pipeline.finish(failed: false)
        }
        lock.lock(); job = task; lock.unlock()
    }
    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard CMSampleBufferDataIsReady(sampleBuffer) else { return }
        let sample = ReplaySample(buffer: sampleBuffer)
        switch sampleBufferType {
        case .video: video.continuation.yield(sample)
        case .audioApp: appAudio.continuation.yield(sample)
        case .audioMic: micAudio.continuation.yield(sample)
        @unknown default: break
        }
    }
    override func broadcastPaused() { Task { await pipeline.setPaused(true) } }
    override func broadcastResumed() { Task { await pipeline.setPaused(false) } }
    override func broadcastFinished() {
        video.continuation.finish(); appAudio.continuation.finish(); micAudio.continuation.finish()
        lock.lock(); let task = job; job = nil; lock.unlock()
        task?.cancel()
        Task { await pipeline.finish(failed: false) }
    }
}
