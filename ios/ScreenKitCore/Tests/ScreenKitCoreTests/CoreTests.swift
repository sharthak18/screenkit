import XCTest
@testable import ScreenKitCore

final class CoreTests: XCTestCase {
    func testYouTubeDestinationAndWhitespace() throws {
        let value = try StreamDestination(server: " rtmps://a.rtmps.youtube.com/live2/ ", key: " abc ")
        XCTAssertEqual(value.server, "rtmps://a.rtmps.youtube.com/live2")
        XCTAssertEqual(value.key, "abc")
        XCTAssertFalse(value.description.contains("abc"))
    }
    func testFacebookQueryKeyPreserved() throws {
        let key = "123?s_bl=1&token=a%2Bb%3D"
        let value = try StreamDestination(server: "rtmps://live-api-s.facebook.com:443/rtmp", key: key)
        XCTAssertEqual(value.key, key)
    }
    func testInvalidServers() {
        for server in ["https://host/live", "rtmp:///live", "rtmp://host", "rtmp://host/", "rtmp://host:0/live", "rtmp://host:65536/live", "rtmps://user:pass@host/live", "rtmps://host/live?q=key", "rtmps://host/live#x", "rtmp://bad host/live"] {
            XCTAssertThrowsError(try StreamDestination(server: server, key: "private-key")) { error in
                XCTAssertFalse(error.localizedDescription.contains("private-key"))
            }
        }
    }
    func testInvalidKeys() {
        for key in ["", "abc def", "abc\ndef", "rtmp://host/key", "/key", "key#fragment", String(repeating: "x", count: 4097)] {
            XCTAssertThrowsError(try StreamDestination(server: "rtmps://host/live", key: key))
        }
    }
    func testQualityBounds() throws {
        try StreamQuality().validate()
        XCTAssertThrowsError(try StreamQuality(shortEdge: 1080).validate())
        XCTAssertThrowsError(try StreamQuality(fps: 60).validate())
        XCTAssertThrowsError(try StreamQuality(bitrateKbps: 499).validate())
        XCTAssertThrowsError(try StreamQuality(bitrateKbps: 6001).validate())
    }
    func testDimensionsPreserveAspectAndDoNotUpscale() {
        let quality = StreamQuality()
        let portrait = quality.dimensions(width: 1080, height: 1920)
        XCTAssertEqual(portrait.0, 720); XCTAssertEqual(portrait.1, 1280)
        let landscape = quality.dimensions(width: 1920, height: 1080)
        XCTAssertEqual(landscape.0, 1280); XCTAssertEqual(landscape.1, 720)
        XCTAssertEqual(quality.dimensions(width: 320, height: 480).0, 320)
        XCTAssertEqual(quality.dimensions(width: 1179, height: 2556).0 % 2, 0)
        XCTAssertEqual(quality.dimensions(width: 0, height: 0).0, 0)
    }
    func testPreparedConfigurationExpiresAndRejectsClockRollback() throws {
        let date = Date(timeIntervalSince1970: 1000)
        let config = BroadcastConfiguration(server: "rtmps://host/live", quality: .init(), audio: .app, createdAt: date)
        try config.validate(now: date.addingTimeInterval(299))
        XCTAssertThrowsError(try config.validate(now: date.addingTimeInterval(300)))
        XCTAssertThrowsError(try config.validate(now: date.addingTimeInterval(-30)))
    }
    func testSharedConfigurationContainsNoCredentialField() throws {
        let config = BroadcastConfiguration(server: "rtmps://host/live", quality: .init(), audio: .mixed)
        let text = String(decoding: try JSONEncoder().encode(config), as: UTF8.self)
        XCTAssertFalse(text.lowercased().contains("key"))
        let decoded = try JSONDecoder().decode(BroadcastConfiguration.self, from: Data(text.utf8))
        XCTAssertEqual(decoded.id, config.id)
    }
    func testHeartbeatAndTerminalStatuses() {
        let date = Date(timeIntervalSince1970: 1000)
        var status = BroadcastStatus(id: UUID(), phase: .live, message: "LIVE", heartbeat: date)
        XCTAssertTrue(status.isActive(now: date.addingTimeInterval(10)))
        XCTAssertFalse(status.isActive(now: date.addingTimeInterval(15)))
        XCTAssertFalse(status.isActive(now: date.addingTimeInterval(-1)))
        status.phase = .ended
        XCTAssertFalse(status.isActive(now: date))
    }
    func testClipRangesRejectNonFiniteAndReversedTimes() throws {
        try ClipRange.validate(start: 1, end: 5, duration: 10)
        for range in [(-1.0, 2.0), (2, 1), (0, 11), (.nan, 3), (0, .infinity), (0, 0)] {
            XCTAssertThrowsError(try ClipRange.validate(start: range.0, end: range.1, duration: 10))
        }
    }
}
