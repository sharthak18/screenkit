import Foundation
import Photos
import UniformTypeIdentifiers

struct LibraryItem: Identifiable, Hashable {
    let url: URL
    let createdAt: Date
    var id: String { url.lastPathComponent }
    var isVideo: Bool { ["mp4", "mov", "m4v"].contains(url.pathExtension.lowercased()) }
    var title: String { url.deletingPathExtension().lastPathComponent.components(separatedBy: "_").first ?? "Capture" }
}

@MainActor
final class MediaLibrary: ObservableObject {
    @Published private(set) var items: [LibraryItem] = []
    @Published var message: String?
    let directory: URL
    init() {
        directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Captures", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        refresh()
    }
    func output(_ prefix: String, ext: String = "mp4") -> URL {
        directory.appendingPathComponent("\(prefix)_\(UUID().uuidString).\(ext)")
    }
    func refresh() {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey], options: [.skipsHiddenFiles])) ?? []
        items = urls.filter { ["mp4", "mov", "m4v", "png", "gif"].contains($0.pathExtension.lowercased()) }.map {
            LibraryItem(url: $0, createdAt: (try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast)
        }.sorted { $0.createdAt > $1.createdAt }
    }
    func importVideo(_ source: URL) async {
        let destination = output("Imported", ext: source.pathExtension.isEmpty ? "mov" : source.pathExtension.lowercased())
        do {
            try await Task.detached(priority: .userInitiated) {
                let access = source.startAccessingSecurityScopedResource()
                defer { if access { source.stopAccessingSecurityScopedResource() } }
                let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size > 0, size <= 1_000_000_000 else { throw ScreenKitError.size }
                try FileManager.default.copyItem(at: source, to: destination)
                do { _ = try await VideoTools.duration(destination) }
                catch { try? FileManager.default.removeItem(at: destination); throw error }
            }.value
            refresh(); message = "Video imported."
        } catch { message = (error as? ScreenKitError)?.localizedDescription ?? "Could not import this video. Check storage and file access." }
    }
    func delete(_ item: LibraryItem) {
        do { try FileManager.default.removeItem(at: item.url); refresh() }
        catch { message = "Could not delete this capture." }
    }
    func saveToPhotos(_ item: LibraryItem) async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            message = "Photos access was not granted. You can still use Share → Save to Files."; return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                if item.isVideo { PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: item.url) }
                else { PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: item.url) }
            }
            message = "Saved to Photos."
        } catch { message = "Could not save this capture to Photos." }
    }
}
