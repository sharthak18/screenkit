import AVFoundation
import ImageIO
import UniformTypeIdentifiers

/// Work is performed off the UI thread. All outputs are app-owned files, removed on failure.
enum VideoTools {
    static func duration(_ url: URL) async throws -> Double {
        let asset = AVURLAsset(url: url)
        let duration = try await asset.load(.duration).seconds
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard duration.isFinite, duration > 0, !tracks.isEmpty else { throw ScreenKitError.media }
        return duration
    }
    static func trim(_ source: URL, output: URL, start: Double, end: Double) async throws {
        let asset = AVURLAsset(url: source)
        try ClipRange.validate(start: start, end: end, duration: try await duration(source))
        guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else { throw ScreenKitError.media }
        exporter.outputURL = output
        exporter.outputFileType = .mp4
        exporter.shouldOptimizeForNetworkUse = true
        exporter.timeRange = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 600), end: CMTime(seconds: end, preferredTimescale: 600))
        try await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                exporter.exportAsynchronously { continuation.resume() }
            }
        } onCancel: { exporter.cancelExport() }
        guard exporter.status == .completed, !Task.isCancelled else {
            try? FileManager.default.removeItem(at: output); throw ScreenKitError.media
        }
    }
    static func frame(_ source: URL, output: URL, seconds: Double) async throws {
        let length = try await duration(source)
        guard seconds.isFinite, seconds >= 0, seconds < length else { throw ScreenKitError.range }
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: source))
        generator.appliesPreferredTrackTransform = true
        let image = try generator.copyCGImage(at: CMTime(seconds: seconds, preferredTimescale: 600), actualTime: nil)
        guard let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil) else { throw ScreenKitError.media }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { try? FileManager.default.removeItem(at: output); throw ScreenKitError.media }
    }
    static func gif(_ source: URL, output: URL, start: Double, end: Double) async throws {
        try ClipRange.validate(start: start, end: end, duration: try await duration(source))
        guard end - start <= 15 else { throw ScreenKitError.range }
        let frames = max(1, Int(ceil((end - start) * 10)))
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: source))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 480, height: 480)
        guard let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.gif.identifier as CFString, frames, nil) else { throw ScreenKitError.media }
        CGImageDestinationSetProperties(destination, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]] as CFDictionary)
        do {
            for index in 0..<frames {
                try Task.checkCancellation()
                try autoreleasepool {
                    let image = try generator.copyCGImage(at: CMTime(seconds: start + Double(index) / 10, preferredTimescale: 600), actualTime: nil)
                    CGImageDestinationAddImage(destination, image, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 0.1]] as CFDictionary)
                }
            }
            guard CGImageDestinationFinalize(destination) else { throw ScreenKitError.media }
        } catch { try? FileManager.default.removeItem(at: output); throw error }
    }
}
