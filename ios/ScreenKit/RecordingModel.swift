import ReplayKit

@MainActor
final class RecordingModel: NSObject, ObservableObject, RPScreenRecorderDelegate {
    @Published private(set) var recording = false
    @Published private(set) var busy = false
    @Published var microphone = false
    @Published var message: String?
    @Published private(set) var startedAt: Date?
    private let recorder = RPScreenRecorder.shared()
    override init() { super.init(); recorder.delegate = self }
    func start() {
        guard !busy, !recording else { return }
        guard recorder.isAvailable else { message = ScreenKitError.capture.localizedDescription; return }
        guard (try? BroadcastStore().status()?.isActive()) != true else { message = "End your broadcast before recording."; return }
        busy = true
        recorder.isMicrophoneEnabled = microphone
        recorder.startRecording { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                self.busy = false
                self.recording = error == nil
                self.startedAt = error == nil ? Date() : nil
                if error != nil { self.message = "Recording could not start or permission was declined." }
            }
        }
    }
    func stop(library: MediaLibrary) {
        guard recording, !busy else { return }
        busy = true
        let output = library.output("Recording")
        recorder.stopRecording(withOutput: output) { [weak self] error in
            Task { @MainActor in
                self?.busy = false; self?.recording = false; self?.startedAt = nil
                if error == nil { library.refresh(); self?.message = "Recording saved in your ScreenKit library." }
                else { try? FileManager.default.removeItem(at: output); self?.message = "The recording could not be saved." }
            }
        }
    }
    nonisolated func screenRecorder(_ screenRecorder: RPScreenRecorder, didStopRecordingWith previewViewController: RPPreviewViewController?, error: Error?) {
        Task { @MainActor in
            self.recording = false; self.busy = false; self.startedAt = nil
            if error != nil { self.message = "iOS interrupted recording. Start a new recording when ready." }
        }
    }
}
