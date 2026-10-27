import SwiftUI
import AVKit
import UniformTypeIdentifiers

@main
struct ScreenKitApp: App {
    @StateObject private var library = MediaLibrary()
    @StateObject private var recording = RecordingModel()
    var body: some Scene {
        WindowGroup {
            TabView {
                RecordView(recording: recording, library: library).tabItem { Label("Record", systemImage: "record.circle") }
                LiveView(recording: recording).tabItem { Label("Go Live", systemImage: "dot.radiowaves.left.and.right") }
                LibraryView(library: library).tabItem { Label("Library", systemImage: "rectangle.stack") }
                AboutView().tabItem { Label("About", systemImage: "info.circle") }
            }
            .tint(Color(red: 0.72, green: 0.95, blue: 0.43))
            .preferredColorScheme(.dark)
            .alert("ScreenKit", isPresented: Binding(get: { library.message != nil }, set: { if !$0 { library.message = nil } })) {
                Button("OK") { library.message = nil }
            } message: { Text(library.message ?? "") }
        }
    }
}

private struct RecordView: View {
    @ObservedObject var recording: RecordingModel
    @ObservedObject var library: MediaLibrary
    @State private var script = "Welcome to ScreenKit. Record a walkthrough, read your notes, or share your screen live."
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("Create. Capture. Go live.", systemImage: "viewfinder").font(.title2.bold())
                    Text("ScreenKit for iPhone & iPad").foregroundStyle(.secondary)
                }
                Section("Record inside ScreenKit") {
                    Toggle("Include microphone", isOn: $recording.microphone).disabled(recording.recording || recording.busy)
                    if let date = recording.startedAt { Label { Text(date, style: .timer).monospacedDigit() } icon: { Image(systemName: "record.circle").foregroundStyle(.red) } }
                    Button(recording.recording ? "Stop and save recording" : "Start in-app recording", systemImage: recording.recording ? "stop.fill" : "record.circle") {
                        if recording.recording { recording.stop(library: library) } else { recording.start() }
                    }.disabled(recording.busy)
                    if recording.busy { ProgressView("Waiting for iOS…") }
                    Text("ReplayKit's in-app recorder records ScreenKit, not other apps. iOS controls the permission prompt. Stay in the app until you stop and save.").font(.footnote)
                }
                Section("Record other apps") {
                    Text("Use iOS Control Center → Screen Recording to record other apps to Photos, then import the video into ScreenKit's Library. To broadcast other apps instead, use Go Live.")
                }
                Section("Your recording notes") {
                    TextEditor(text: $script).frame(minHeight: 180)
                    Text("These notes are visible in the recording. They are not a private overlay.").font(.footnote)
                }
            }
            .navigationTitle("ScreenKit")
            .alert("Screen recording", isPresented: Binding(get: { recording.message != nil }, set: { if !$0 { recording.message = nil } })) {
                Button("OK") { recording.message = nil }
            } message: { Text(recording.message ?? "") }
        }
    }
}

private struct LibraryView: View {
    @ObservedObject var library: MediaLibrary
    @State private var importingFiles = false
    @State private var importingPhotos = false
    @State private var importing = false
    @State private var editor: LibraryItem?
    @State private var deleting: LibraryItem?
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button("Import from Photos", systemImage: "photo.on.rectangle") { importingPhotos = true }.disabled(importing)
                    Button("Import from Files", systemImage: "folder") { importingFiles = true }.disabled(importing)
                    if importing { ProgressView("Importing video…") }
                }
                Section("Captures & exports") {
                    if library.items.isEmpty { ContentUnavailableView("Your studio starts here", systemImage: "film", description: Text("Record inside ScreenKit or import a video to trim it, make a GIF, or save a frame.")) }
                    ForEach(library.items) { item in
                        VStack(alignment: .leading, spacing: 10) {
                            Label(item.title, systemImage: item.isVideo ? "film" : "photo").font(.headline)
                            Text(item.createdAt, style: .date).font(.caption).foregroundStyle(.secondary)
                            HStack {
                                if item.isVideo { Button("Studio") { editor = item }.buttonStyle(.bordered) }
                                ShareLink(item: item.url) { Label("Share", systemImage: "square.and.arrow.up") }.buttonStyle(.bordered)
                                Menu {
                                    Button("Save to Photos") { Task { await library.saveToPhotos(item) } }
                                    Button("Delete", role: .destructive) { deleting = item }
                                } label: { Image(systemName: "ellipsis.circle").font(.title3) }
                            }
                        }.padding(.vertical, 6)
                    }
                }
            }
            .navigationTitle("Your Library")
            .refreshable { library.refresh() }
            .fileImporter(isPresented: $importingFiles, allowedContentTypes: [.movie]) { result in
                switch result {
                case .success(let url): importing = true; Task { await library.importVideo(url); importing = false }
                case .failure: library.message = "Import cancelled or the file is unavailable."
                }
            }
            .sheet(isPresented: $importingPhotos) {
                PhotoVideoPicker { url in
                    importingPhotos = false
                    guard let url else { return }
                    importing = true
                    Task { await library.importVideo(url); try? FileManager.default.removeItem(at: url); importing = false }
                }
            }
            .sheet(item: $editor) { StudioView(item: $0, library: library) }
            .confirmationDialog("Delete this capture from ScreenKit?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button("Delete", role: .destructive) { if let item = deleting { library.delete(item) }; deleting = nil }
            }
        }
    }
}

private struct AboutView: View {
    var body: some View {
        NavigationStack {
            Form {
                Section("ScreenKit iOS 1.0.0") { Text("A native SwiftUI companion to ScreenKit for Android. Editing runs locally on your device. No advertising or analytics SDK is included.") }
                Section("Privacy") {
                    Text("Only the destination you choose receives your live screen/audio. Enable Focus and hide private information before broadcasting. Stream keys use one-use, device-only shared Keychain storage, never logs or analytics.")
                    Text("Recordings and exports remain in ScreenKit's Documents folder unless you share or save them to Photos. They may be included in your device backup. Uninstalling ScreenKit removes its local library.")
                }
                Section("iOS boundaries") {
                    Text("No auto-clicking or macros in other apps, system-wide floating controls, permission bypass, or protected-content capture. No silent background recording. Public broadcasts depend on your platform's account eligibility and dashboard controls.")
                }
                Section("Open source") {
                    Link("ScreenKit source & support", destination: URL(string: "https://github.com/sharthak18/screenkit")!)
                    Text("Broadcast transport: HaishinKit.swift 2.1.0 and Logboard 2.5.0 (BSD-3-Clause). Notices are included with this app.")
                    if let url = Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) { Text(text).font(.caption) }
                }
            }.navigationTitle("About")
        }
    }
}
