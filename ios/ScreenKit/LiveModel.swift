import SwiftUI
import ReplayKit

@MainActor
final class LiveModel: ObservableObject {
    @Published var status: BroadcastStatus?
    @Published var prepared: BroadcastConfiguration?
    @Published var message: String?
    @Published var storeAvailable = false
    var active: Bool { status?.isActive() == true }
    func refresh() {
        do {
            let store = try BroadcastStore(); storeAvailable = true
            store.removeExpiredPreparation()
            status = store.status(); prepared = store.prepared()
        } catch { storeAvailable = false }
    }
    func prepare(server: String, key: String, quality: StreamQuality, audio: StreamAudio) -> Bool {
        do {
            let destination = try StreamDestination(server: server, key: key)
            try quality.validate()
            let config = BroadcastConfiguration(server: destination.server, quality: quality, audio: audio)
            try BroadcastStore().prepare(config, key: destination.key)
            refresh(); return true
        } catch { message = (error as? ScreenKitError)?.localizedDescription ?? ScreenKitError.credential.localizedDescription; return false }
    }
    func cancelPreparation() { (try? BroadcastStore())?.clearPrepared(); refresh() }
    func control(stop: Bool = false) {
        guard let status, status.isActive() else { return }
        do {
            try BroadcastStore().command(.init(id: status.id, muted: stop ? status.muted : !status.muted, stop: stop))
            if stop { message = "Stop requested. You can also use iOS's red recording indicator to stop immediately." }
        } catch { message = "Could not reach the broadcast. Stop using iOS's recording indicator." }
    }
}

/// This is Apple's real picker, not a hidden-button hack. The user explicitly chooses Start Broadcast.
struct BroadcastPicker: UIViewRepresentable {
    let microphone: Bool
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let view = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 60, height: 60))
        view.preferredExtension = Bundle.main.object(forInfoDictionaryKey: "ScreenKitBroadcastIdentifier") as? String
        view.showsMicrophoneButton = microphone
        view.tintColor = .systemRed
        view.accessibilityLabel = "Open Apple's Start Broadcast controls"
        return view
    }
    func updateUIView(_ view: RPSystemBroadcastPickerView, context: Context) { view.showsMicrophoneButton = microphone }
}

struct LiveView: View {
    @ObservedObject var recording: RecordingModel
    @StateObject private var model = LiveModel()
    @State private var server = "rtmps://a.rtmps.youtube.com/live2"
    @State private var key = ""
    @State private var platform = "YouTube"
    @State private var quality = StreamQuality()
    @State private var audio = StreamAudio.app
    @State private var consent = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("Your screen, on air.", systemImage: "dot.radiowaves.left.and.right").font(.title2.bold())
                    Text("Broadcast your screen with ReplayKit. Nothing starts until you confirm in Apple's broadcast controls.")
                }
                if model.active, let status = model.status {
                    Section("Broadcast") {
                        Label(status.message, systemImage: "record.circle").foregroundStyle(.red)
                        if let started = status.startedAt { Text(started, style: .timer).monospacedDigit() }
                        Text(status.muted ? "Outgoing audio muted" : "Using your selected audio sources")
                        Button(status.muted ? "Unmute audio" : "Mute audio") { model.control() }
                        Button("End broadcast", role: .destructive) { model.control(stop: true) }
                        Text("Server acceptance does not mean your broadcast is publicly live. Check your platform dashboard.").font(.footnote)
                    }
                } else if let config = model.prepared {
                    Section("Ready for Apple's approval") {
                        Text("Tap the broadcast icon below, choose ScreenKit Live, then Start Broadcast. Setup expires after 5 minutes.")
                        HStack { Spacer(); BroadcastPicker(microphone: config.audio.includesMicrophone).frame(width: 60, height: 60); Spacer() }
                        if config.audio.includesMicrophone { Text("Turn Microphone On in Apple's controls to include your voice. ScreenKit cannot turn it on for you.").font(.footnote) }
                        Button("Cancel and erase prepared key", role: .destructive) { model.cancelPreparation() }
                    }
                } else {
                    if let status = model.status {
                        Section {
                            Text(status.isActive() ? status.message : ([.live, .connecting, .paused].contains(status.phase) ? "Broadcast status is stale. Check iOS's recording indicator before starting again." : status.message))
                        }
                    }
                    Section("Destination") {
                        Picker("Platform", selection: $platform) { ForEach(["YouTube", "Facebook", "Custom"], id: \.self) { Text($0) } }
                            .onChange(of: platform) { _, value in
                                key = ""
                                server = value == "YouTube" ? "rtmps://a.rtmps.youtube.com/live2" : value == "Facebook" ? "rtmps://live-api-s.facebook.com:443/rtmp" : ""
                            }
                        TextField("Server URL", text: $server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("Stream key", text: $key).textInputAutocapitalization(.never).autocorrectionDisabled().privacySensitive()
                        Text("Paste the exact values from your live dashboard. Your key is transferred once through device-only Keychain storage and erased when the extension consumes it. Unused setup expires after 5 minutes.").font(.footnote)
                        if server.hasPrefix("rtmp://") { Text("RTMP is unencrypted. Prefer RTMPS to protect your stream and key in transit.").foregroundStyle(.orange) }
                    }
                    Section("Stream quality") {
                        Picker("Resolution", selection: $quality.shortEdge) { Text("480p").tag(480); Text("720p").tag(720) }
                        Picker("Frame rate", selection: $quality.fps) { Text("24 fps").tag(24); Text("30 fps").tag(30) }
                        Stepper("Video bitrate: \(quality.bitrateKbps) kbps", value: $quality.bitrateKbps, in: 500...6000, step: 250)
                        Picker("Audio", selection: $audio) { ForEach(StreamAudio.allCases, id: \.self) { Text($0.rawValue).tag($0) } }
                        Text("Resolution preserves aspect ratio, capped at a 1280-pixel long edge to respect the extension's memory limit. App audio is only available when iOS and the source app permit it.").font(.footnote)
                    }
                    Section {
                        Button("Prepare broadcast") { consent = true }
                            .disabled(key.isEmpty || recording.recording || recording.busy || !model.storeAvailable)
                        if recording.recording { Text("Stop the in-app recording before broadcasting.") }
                        if !model.storeAvailable { Text(ScreenKitError.signing.localizedDescription).foregroundStyle(.orange) }
                    }
                }
                Section("Know before you go live") {
                    Text("Visible notifications and personal information may be broadcast. Enable Focus first. DRM and other protected screens cannot be captured. No floating controls or auto-clicking over other apps.")
                    Text("One destination, no simultaneous local recording. Failed connections do not silently restart. Use the iOS recording indicator to stop even when ScreenKit is closed.")
                }
            }
            .navigationTitle("Go Live")
            .task {
                while !Task.isCancelled { model.refresh(); try? await Task.sleep(for: .seconds(1)) }
            }
            .confirmationDialog("Share your screen live?", isPresented: $consent, titleVisibility: .visible) {
                Button("Prepare secure broadcast setup") {
                    if model.prepare(server: server, key: key, quality: quality, audio: audio) { key = "" }
                }
            } message: { Text("Your screen and selected audio can be sent to your streaming destination. Review your platform's privacy setting and hide personal information first.") }
            .alert("ScreenKit Live", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
                Button("OK") { model.message = nil }
            } message: { Text(model.message ?? "") }
        }
    }
}
