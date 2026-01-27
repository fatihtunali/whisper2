import SwiftUI
import PhotosUI
import QuickLook

/// View for displaying an attachment in a message
struct AttachmentBubble: View {
    let attachment: AttachmentPointer
    let senderPublicKey: Data?
    let isOutgoing: Bool
    @State private var downloadState: DownloadState = .notDownloaded
    @State private var downloadProgress: Double = 0
    @State private var localURL: URL?
    @State private var showPreview = false

    enum DownloadState {
        case notDownloaded
        case downloading
        case downloaded
        case failed
    }

    private let attachmentService = AttachmentService.shared

    var body: some View {
        VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 4) {
            Button(action: handleTap) {
                ZStack {
                    // Background
                    RoundedRectangle(cornerRadius: 12)
                        .fill(isOutgoing ? Color.blue.opacity(0.3) : Color.gray.opacity(0.2))
                        .frame(width: 200, height: contentHeight)

                    // Content based on state
                    switch downloadState {
                    case .notDownloaded:
                        notDownloadedView

                    case .downloading:
                        downloadingView

                    case .downloaded:
                        downloadedView

                    case .failed:
                        failedView
                    }
                }
            }
            .buttonStyle(.plain)

            // File info
            HStack(spacing: 4) {
                Text(attachmentService.formatFileSize(attachment.ciphertextSize))
                    .font(.caption2)
                Text("·")
                Text(fileTypeLabel)
                    .font(.caption2)
            }
            .foregroundColor(.gray)
        }
        .sheet(isPresented: $showPreview) {
            if let url = localURL {
                AttachmentPreviewView(url: url)
            }
        }
    }

    private var contentHeight: CGFloat {
        if attachmentService.isImage(attachment.contentType) ||
           attachmentService.isVideo(attachment.contentType) {
            return 150
        }
        return 80
    }

    private var fileTypeLabel: String {
        if attachmentService.isImage(attachment.contentType) {
            return "Photo"
        } else if attachmentService.isVideo(attachment.contentType) {
            return "Video"
        } else if attachmentService.isAudio(attachment.contentType) {
            return "Audio"
        } else {
            return "File"
        }
    }

    private var notDownloadedView: some View {
        VStack(spacing: 8) {
            Image(systemName: iconName)
                .font(.title)
                .foregroundColor(.white)

            Text("Tap to download")
                .font(.caption)
                .foregroundColor(.gray)
        }
    }

    private var downloadingView: some View {
        VStack(spacing: 8) {
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle(tint: .white))

            Text("\(Int(downloadProgress * 100))%")
                .font(.caption)
                .foregroundColor(.white)
        }
    }

    private var downloadedView: some View {
        SwiftUI.Group {
            if attachmentService.isImage(attachment.contentType), let url = localURL {
                AsyncImage(url: url) { image in
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 200, height: 150)
                        .clipped()
                        .cornerRadius(12)
                } placeholder: {
                    ProgressView()
                }
            } else if attachmentService.isVideo(attachment.contentType) {
                ZStack {
                    Rectangle()
                        .fill(Color.black.opacity(0.5))
                        .cornerRadius(12)

                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 50))
                        .foregroundColor(.white)
                }
            } else {
                VStack(spacing: 8) {
                    Image(systemName: iconName)
                        .font(.title)
                        .foregroundColor(.white)

                    Text("Tap to open")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }
        }
    }

    private var failedView: some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.title)
                .foregroundColor(.orange)

            Text("Download failed")
                .font(.caption)
                .foregroundColor(.gray)

            Text("Tap to retry")
                .font(.caption2)
                .foregroundColor(.blue)
        }
    }

    private var iconName: String {
        if attachmentService.isImage(attachment.contentType) {
            return "photo.fill"
        } else if attachmentService.isVideo(attachment.contentType) {
            return "video.fill"
        } else if attachmentService.isAudio(attachment.contentType) {
            return "waveform"
        } else {
            return "doc.fill"
        }
    }

    private func handleTap() {
        switch downloadState {
        case .notDownloaded, .failed:
            downloadAttachment()
        case .downloaded:
            showPreview = true
        case .downloading:
            break
        }
    }

    private func downloadAttachment() {
        guard let publicKey = senderPublicKey else { return }

        downloadState = .downloading

        Task {
            do {
                let url = try await attachmentService.downloadFile(attachment, senderPublicKey: publicKey)
                await MainActor.run {
                    localURL = url
                    downloadState = .downloaded
                }
            } catch {
                await MainActor.run {
                    downloadState = .failed
                }
            }
        }
    }
}

/// Full-screen attachment preview using QuickLook
struct AttachmentPreviewView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QuickLookPreview(url: url)
                .ignoresSafeArea()
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { dismiss() }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        ShareLink(item: url)
                    }
                }
        }
    }
}

/// QuickLook wrapper
struct QuickLookPreview: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(url: url)
    }

    class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL

        init(url: URL) {
            self.url = url
        }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
            return 1
        }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            return url as QLPreviewItem
        }
    }
}

/// Attachment picker (photos, files)
struct AttachmentPicker: View {
    @Binding var isPresented: Bool
    let onSelect: (URL) -> Void

    @State private var showPhotoPicker = false
    @State private var showFilePicker = false
    @State private var selectedPhotos: [PhotosPickerItem] = []

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Add Attachment")
                    .font(.headline)
                Spacer()
                Button("Cancel") { isPresented = false }
            }
            .padding()
            .background(Color.gray.opacity(0.1))

            // Options
            VStack(spacing: 16) {
                AttachmentOptionButton(
                    icon: "photo.fill",
                    title: "Photo or Video",
                    color: .purple
                ) {
                    showPhotoPicker = true
                }

                AttachmentOptionButton(
                    icon: "doc.fill",
                    title: "Document",
                    color: .blue
                ) {
                    showFilePicker = true
                }
            }
            .padding()

            Spacer()
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotos, maxSelectionCount: 1)
        .onChange(of: selectedPhotos) { _, newValue in
            handlePhotoSelection(newValue)
        }
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            handleFileSelection(result)
        }
    }

    private func handlePhotoSelection(_ items: [PhotosPickerItem]) {
        guard let item = items.first else { return }

        Task {
            if let data = try? await item.loadTransferable(type: Data.self) {
                // Save to temp file
                let tempDir = FileManager.default.temporaryDirectory
                let fileName = "\(UUID().uuidString).jpg"
                let fileURL = tempDir.appendingPathComponent(fileName)

                try? data.write(to: fileURL)
                onSelect(fileURL)
                isPresented = false
            }
        }
    }

    private func handleFileSelection(_ result: Result<[URL], Error>) {
        if case .success(let urls) = result, let url = urls.first {
            // Copy to temp location to ensure access
            let tempDir = FileManager.default.temporaryDirectory
            let fileName = url.lastPathComponent
            let destURL = tempDir.appendingPathComponent(fileName)

            do {
                _ = url.startAccessingSecurityScopedResource()
                defer { url.stopAccessingSecurityScopedResource() }

                if FileManager.default.fileExists(atPath: destURL.path) {
                    try FileManager.default.removeItem(at: destURL)
                }
                try FileManager.default.copyItem(at: url, to: destURL)

                onSelect(destURL)
                isPresented = false
            } catch {
                print("Failed to copy file: \(error)")
            }
        }
    }
}

/// Button for attachment option
struct AttachmentOptionButton: View {
    let icon: String
    let title: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(color.opacity(0.2))
                        .frame(width: 50, height: 50)

                    Image(systemName: icon)
                        .font(.title2)
                        .foregroundColor(color)
                }

                Text(title)
                    .font(.headline)
                    .foregroundColor(.white)

                Spacer()

                Image(systemName: "chevron.right")
                    .foregroundColor(.gray)
            }
            .padding()
            .background(Color.gray.opacity(0.1))
            .cornerRadius(12)
        }
    }
}

#Preview {
    AttachmentPicker(isPresented: .constant(true)) { _ in }
        .preferredColorScheme(.dark)
}
