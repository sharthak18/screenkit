import Foundation
import Security

/// The app and extension are separate processes. Share only protected files and a one-use Keychain item.
struct BroadcastStore {
    private let root: URL
    private let keychainGroup: String
    init(bundle: Bundle = .main) throws {
        guard let group = bundle.object(forInfoDictionaryKey: "ScreenKitAppGroup") as? String,
              let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
              let keychainGroup = bundle.object(forInfoDictionaryKey: "ScreenKitKeychainGroup") as? String,
              !keychainGroup.isEmpty, !keychainGroup.contains("$(") else { throw ScreenKitError.signing }
        self.root = root; self.keychainGroup = keychainGroup
    }
    private func url(_ name: String) -> URL { root.appendingPathComponent(name + ".json") }
    private func read<T: Decodable>(_ name: String) -> T? {
        guard let data = try? Data(contentsOf: url(name)), data.count < 32768 else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
    private func write<T: Encodable>(_ value: T, name: String) throws {
        let data = try JSONEncoder().encode(value)
        try data.write(to: url(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var file = url(name); var values = URLResourceValues(); values.isExcludedFromBackup = true
        try file.setResourceValues(values)
    }
    func status() -> BroadcastStatus? { read("status") }
    func control() -> BroadcastControl? { read("control") }
    func update(_ status: BroadcastStatus) throws { try write(status, name: "status") }
    func command(_ control: BroadcastControl) throws { try write(control, name: "control") }
    func prepared() -> BroadcastConfiguration? { read("prepared") }
    func prepare(_ config: BroadcastConfiguration, key: String) throws {
        try config.validate()
        _ = try StreamDestination(server: config.server, key: key)
        guard status()?.isActive() != true else { throw ScreenKitError.capture }
        clearPrepared()
        var query = keyQuery(config.id)
        query[kSecValueData as String] = Data(key.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else { throw ScreenKitError.credential }
        do { try write(config, name: "prepared"); try command(.init(id: config.id, muted: false)) }
        catch { SecItemDelete(keyQuery(config.id) as CFDictionary); throw error }
    }
    func consume() throws -> (BroadcastConfiguration, StreamDestination) {
        guard let config: BroadcastConfiguration = read("prepared") else { throw ScreenKitError.expired }
        defer { clearPrepared() }
        try config.validate()
        var query = keyQuery(config.id)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data, let key = String(data: data, encoding: .utf8) else { throw ScreenKitError.credential }
        return (config, try StreamDestination(server: config.server, key: key))
    }
    func clearPrepared() {
        if let config: BroadcastConfiguration = read("prepared") { SecItemDelete(keyQuery(config.id) as CFDictionary) }
        try? FileManager.default.removeItem(at: url("prepared"))
    }
    func removeExpiredPreparation() {
        if let config = prepared(), (try? config.validate()) == nil { clearPrepared() }
    }
    private func keyQuery(_ id: UUID) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "ScreenKit.Broadcast.OneUse",
         kSecAttrAccount as String: id.uuidString, kSecAttrAccessGroup as String: keychainGroup]
    }
}
