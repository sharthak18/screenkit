import CoreImage
import CoreMedia
import ReplayKit

/// Rotate using ReplayKit's attachment and scale BEFORE queuing encoder frames.
/// A two-buffer pixel pool drops frames under pressure instead of allocating without bound.
final class ReplayVideoScaler {
    private let context = CIContext(options: [.cacheIntermediates: false])
    private var pool: CVPixelBufferPool?
    private var size = (0, 0)
    func convert(_ sample: CMSampleBuffer, quality: StreamQuality) throws -> CMSampleBuffer? {
        guard let input = CMSampleBufferGetImageBuffer(sample) else { throw ScreenKitError.capture }
        let orientation = (CMGetAttachment(sample, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)?.int32Value ?? 1
        let image = CIImage(cvPixelBuffer: input).oriented(forExifOrientation: orientation)
        let wanted = quality.dimensions(width: Int(image.extent.width), height: Int(image.extent.height))
        guard wanted.0 > 0, wanted.1 > 0 else { throw ScreenKitError.capture }
        if wanted.0 != size.0 || wanted.1 != size.1 || pool == nil {
            pool = nil; size = wanted
            let attributes: [String: Any] = [kCVPixelBufferWidthKey as String: size.0,
                                            kCVPixelBufferHeightKey as String: size.1,
                                            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                                            kCVPixelBufferIOSurfacePropertiesKey as String: [:]]
            guard CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &pool) == kCVReturnSuccess else { throw ScreenKitError.capture }
        }
        guard let pool else { throw ScreenKitError.capture }
        var output: CVPixelBuffer?
        let result = CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(nil, pool,
            [kCVPixelBufferPoolAllocationThresholdKey: 2] as CFDictionary, &output)
        if result == kCVReturnWouldExceedAllocationThreshold { return nil }
        guard result == kCVReturnSuccess, let output else { throw ScreenKitError.capture }
        let translated = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
        let scaled = translated.transformed(by: CGAffineTransform(scaleX: Double(size.0) / image.extent.width, y: Double(size.1) / image.extent.height))
        context.render(scaled, to: output)
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: output, formatDescriptionOut: &format) == noErr,
              let format else { throw ScreenKitError.capture }
        var timing = CMSampleTimingInfo(duration: CMSampleBufferGetDuration(sample),
                                       presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(sample), decodeTimeStamp: .invalid)
        var resized: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: output, formatDescription: format,
                                                       sampleTiming: &timing, sampleBufferOut: &resized) == noErr else { throw ScreenKitError.capture }
        return resized
    }
}
