import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

/// The system picker grants access only to the selected movie; full Photos-library access isn't requested.
struct PhotoVideoPicker: UIViewControllerRepresentable {
    let onPicked: (URL?) -> Void
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(); config.filter = .videos; config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config); picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPicked) }
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPicked: (URL?) -> Void
        init(_ onPicked: @escaping (URL?) -> Void) { self.onPicked = onPicked }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider else { onPicked(nil); return }
            provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                var copied: URL?
                if let url {
                    let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(url.pathExtension)
                    if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 1_000_000_000 {
                        do { try FileManager.default.copyItem(at: url, to: target); copied = target } catch { }
                    }
                }
                DispatchQueue.main.async { self.onPicked(copied) }
            }
        }
    }
}
