import SwiftUI
import AVKit

@MainActor
final class StudioModel: ObservableObject {
    @Published var busy = false
    @Published var message: String?
    private var job: Task<Void, Never>?
    func run(library: MediaLibrary, ext: String, prefix: String, operation: @escaping @Sendable (URL) async throws -> Void) {
        guard !busy else { return }
        busy = true
        let output = library.output(prefix, ext: ext)
        job = Task {
            let work = Task.detached(priority: .userInitiated) { try await operation(output) }
            do {
                try await withTaskCancellationHandler { try await work.value } onCancel: { work.cancel() }
                try Task.checkCancellation()
                library.refresh(); message = "Saved to your ScreenKit library."
            } catch {
                try? FileManager.default.removeItem(at: output)
                if !Task.isCancelled { message = (error as? ScreenKitError)?.localizedDescription ?? ScreenKitError.media.localizedDescription }
            }
            busy = false
        }
    }
    func cancel() { job?.cancel() }
}

struct StudioView: View {
    let item: LibraryItem
    @ObservedObject var library: MediaLibrary
    @StateObject private var model = StudioModel()
    @State private var duration = 0.0
    @State private var start = "0"
    @State private var end = "10"
    @State private var frameTime = "0"
    @State private var player: AVPlayer?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let player { VideoPlayer(player: player).frame(height: 220) }
                    Text("Duration: \(duration, specifier: "%.1f") seconds")
                }
                Section("Trim or GIF range · seconds") {
                    TextField("Start", text: $start).keyboardType(.decimalPad)
                    TextField("End", text: $end).keyboardType(.decimalPad)
                    Button("Export trimmed MP4") {
                        let a = Double(start) ?? .nan, b = Double(end) ?? .nan, source = item.url
                        model.run(library: library, ext: "mp4", prefix: "Trimmed") { try await VideoTools.trim(source, output: $0, start: a, end: b) }
                    }
                    Button("Make animated GIF") {
                        let a = Double(start) ?? .nan, b = Double(end) ?? .nan, source = item.url
                        model.run(library: library, ext: "gif", prefix: "GIF") { try await VideoTools.gif(source, output: $0, start: a, end: b) }
                    }
                    Text("GIF: up to 15 seconds, 10 fps, maximum 480 px. MP4 trimming re-encodes for compatibility and may take time.").font(.footnote)
                }.disabled(model.busy || duration <= 0)
                Section("Extract a still frame") {
                    TextField("Time in seconds", text: $frameTime).keyboardType(.decimalPad)
                    Button("Save PNG frame") {
                        let time = Double(frameTime) ?? .nan, source = item.url
                        model.run(library: library, ext: "png", prefix: "Frame") { try await VideoTools.frame(source, output: $0, seconds: time) }
                    }
                }.disabled(model.busy || duration <= 0)
                if model.busy { Section { ProgressView("Processing on device…"); Button("Cancel", role: .cancel) { model.cancel() } } }
            }
            .navigationTitle("Video Studio")
            .task {
                do { duration = try await VideoTools.duration(item.url); end = String(format: "%.2f", min(duration, 15)); player = AVPlayer(url: item.url) }
                catch { model.message = ScreenKitError.media.localizedDescription }
            }
            .onDisappear { player?.pause(); model.cancel() }
            .alert("Video Studio", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
                Button("OK") { model.message = nil }
            } message: { Text(model.message ?? "") }
        }
    }
}
