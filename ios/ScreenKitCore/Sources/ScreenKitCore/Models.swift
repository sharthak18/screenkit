import Foundation

public enum ScreenKitError: String, Error, LocalizedError {
    case destination = "Enter a valid RTMP/RTMPS server URL with an application path and a separate stream key."
    case quality = "Choose a supported resolution, frame rate, and bitrate."
    case expired = "The broadcast setup expired. Prepare it again in ScreenKit."
    case signing = "Shared storage is unavailable. Sign the app and broadcast extension with matching App Group and Keychain Sharing capabilities."
    case credential = "The broadcast key could not be accessed securely. Prepare the broadcast again."
    case connection = "The streaming connection failed. Check your network and the destination in your platform's dashboard."
    case capture = "Capture was interrupted or is unavailable. Try again on an unlocked iPhone or iPad."
    case media = "This file could not be processed. Choose a playable video and a valid time range."
    case range = "Choose a start time before the end, within the video's duration."
    case size = "This video is too large to import. Choose a video smaller than 1 GB."
    public var errorDescription: String? { rawValue }
}

/// Separate connect URL and publish name: queries on a key must remain byte-for-byte intact.
public struct StreamDestination: CustomStringConvertible {
    public let server: String
    public let key: String
    public var description: String { "Private streaming destination" }
    public init(server: String, key: String) throws {
        let base = server.trimmingCharacters(in: .whitespacesAndNewlines).trimmingTrailingSlash()
        let secret = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard base.count <= 4096, secret.count <= 4096, !secret.isEmpty,
              !(base + secret).unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.union(.controlCharacters).contains($0) }),
              let parts = URLComponents(string: base), ["rtmp", "rtmps"].contains(parts.scheme ?? ""),
              let host = parts.host, !host.isEmpty, !parts.path.isEmpty, parts.path != "/",
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.port == nil || (1...65535).contains(parts.port!),
              !secret.hasPrefix("/"), !secret.contains("://"), !secret.contains("#"),
              !secret.contains("\\"), URL(string: base + "/" + secret) != nil else { throw ScreenKitError.destination }
        self.server = base
        self.key = secret
    }
}

private extension String {
    func trimmingTrailingSlash() -> String {
        var value = self
        while value.hasSuffix("/") { value.removeLast() }
        return value
    }
}

public enum StreamAudio: String, Codable, CaseIterable, Sendable {
    case app = "Device audio", microphone = "Microphone", mixed = "Device + microphone", silent = "No audio"
    public var includesMicrophone: Bool { self == .microphone || self == .mixed }
}

public struct StreamQuality: Codable, Equatable, Sendable {
    public var shortEdge: Int
    public var fps: Int
    public var bitrateKbps: Int
    public init(shortEdge: Int = 720, fps: Int = 30, bitrateKbps: Int = 2500) {
        self.shortEdge = shortEdge; self.fps = fps; self.bitrateKbps = bitrateKbps
    }
    public func validate() throws {
        guard [480, 720].contains(shortEdge), [24, 30].contains(fps), (500...6000).contains(bitrateKbps) else { throw ScreenKitError.quality }
    }
    public func dimensions(width: Int, height: Int) -> (Int, Int) {
        guard width > 0, height > 0 else { return (0, 0) }
        let scale = min(1, Double(shortEdge) / Double(min(width, height)), 1280 / Double(max(width, height)))
        return (max(2, Int(Double(width) * scale) & ~1), max(2, Int(Double(height) * scale) & ~1))
    }
}

/// Contains no key. The one-use key is held in a shared, device-only Keychain item.
public struct BroadcastConfiguration: Codable, Sendable {
    public let id: UUID
    public let server: String
    public let quality: StreamQuality
    public let audio: StreamAudio
    public let createdAt: Date
    public init(id: UUID = UUID(), server: String, quality: StreamQuality, audio: StreamAudio, createdAt: Date = Date()) {
        self.id = id; self.server = server; self.quality = quality; self.audio = audio; self.createdAt = createdAt
    }
    public func validate(now: Date = Date()) throws {
        guard now.timeIntervalSince(createdAt) >= -5, now.timeIntervalSince(createdAt) < 300 else { throw ScreenKitError.expired }
        try quality.validate()
    }
}

public struct BroadcastStatus: Codable, Sendable {
    public enum Phase: String, Codable, Sendable { case connecting, live, paused, ended, failed }
    public var id: UUID
    public var phase: Phase
    public var message: String
    public var heartbeat: Date
    public var startedAt: Date?
    public var muted: Bool
    public init(id: UUID, phase: Phase, message: String, heartbeat: Date = Date(), startedAt: Date? = nil, muted: Bool = false) {
        self.id = id; self.phase = phase; self.message = message; self.heartbeat = heartbeat; self.startedAt = startedAt; self.muted = muted
    }
    public func isActive(now: Date = Date()) -> Bool {
        [.connecting, .live, .paused].contains(phase) && (0..<15).contains(now.timeIntervalSince(heartbeat))
    }
}
public struct BroadcastControl: Codable, Sendable {
    public let id: UUID
    public let muted: Bool
    public let stop: Bool
    public init(id: UUID, muted: Bool, stop: Bool = false) { self.id = id; self.muted = muted; self.stop = stop }
}
public enum ClipRange {
    public static func validate(start: Double, end: Double, duration: Double) throws {
        guard start.isFinite, end.isFinite, duration.isFinite, start >= 0, end > start, end <= duration + 0.01 else { throw ScreenKitError.range }
    }
}
