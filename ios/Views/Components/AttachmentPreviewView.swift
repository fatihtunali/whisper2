import SwiftUI
import PhotosUI
import AVFoundation
import UIKit

/// Attachment type for preview
enum PendingAttachmentType: Equatable {
    case image(UIImage)
    case video(URL, thumbnail: UIImage?)
    case document(URL)

    var isMedia: Bool {
        switch self {
        case .image, .video:
            return true
        case .document:
            return false
        }
    }

    static func == (lhs: PendingAttachmentType, rhs: PendingAttachmentType) -> Bool {
        switch (lhs, rhs) {
        case (.image(let lImg), .image(let rImg)):
            return lImg === rImg
        case (.video(let lURL, _), .video(let rURL, _)):
            return lURL == rURL
        case (.document(let lURL), .document(let rURL)):
            return lURL == rURL
        default:
            return false
        }
    }
}

/// Pending attachment model
struct PendingAttachment: Identifiable, Equatable {
    let id = UUID()
    let type: PendingAttachmentType
    let url: URL
    let fileName: String
    let fileSize: Int64

    var fileSizeString: String {
        ByteCountFormatter.string(fromByteCount: fileSize, countStyle: .file)
    }

    var icon: String {
        switch type {
        case .image:
            return "photo.fill"
        case .video:
            return "video.fill"
        case .document:
            return documentIcon
        }
    }

    private var documentIcon: String {
        let ext = (fileName as NSString).pathExtension.lowercased()
        switch ext {
        case "pdf":
            return "doc.fill"
        case "doc", "docx":
            return "doc.text.fill"
        case "xls", "xlsx":
            return "tablecells.fill"
        case "ppt", "pptx":
            return "rectangle.on.rectangle.fill"
        case "zip", "rar", "7z":
            return "doc.zipper"
        case "txt":
            return "doc.plaintext.fill"
        case "mp3", "m4a", "wav":
            return "waveform"
        default:
            return "doc.fill"
        }
    }

    static func == (lhs: PendingAttachment, rhs: PendingAttachment) -> Bool {
        lhs.id == rhs.id
    }
}

/// Preview view shown above the input bar when attachment is selected
struct AttachmentPreviewBar: View {
    let attachment: PendingAttachment
    let onCancel: () -> Void
    let onSend: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Separator line
            Rectangle()
                .fill(Color.gray.opacity(0.3))
                .frame(height: 1)

            HStack(spacing: 12) {
                // Thumbnail/Icon
                thumbnailView
                    .frame(width: 60, height: 60)
                    .cornerRadius(8)
                    .clipped()

                // File info
                VStack(alignment: .leading, spacing: 4) {
                    Text(attachment.fileName)
                        .font(.subheadline.bold())
                        .foregroundColor(.white)
                        .lineLimit(1)

                    HStack(spacing: 6) {
                        Text(attachment.fileSizeString)
                            .font(.caption)
                            .foregroundColor(.gray)

                        Text("Ready to send")
                            .font(.caption)
                            .foregroundColor(.green)
                    }
                }

                Spacer()

                // Cancel button
                Button(action: onCancel) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 28))
                        .foregroundColor(.gray)
                }

                // Send button
                Button(action: onSend) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 36))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.blue, .purple],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.black.opacity(0.95))
        }
    }

    @ViewBuilder
    private var thumbnailView: some View {
        switch attachment.type {
        case .image(let image):
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)

        case .video(_, let thumbnail):
            ZStack {
                if let thumb = thumbnail {
                    Image(uiImage: thumb)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } else {
                    Color.gray.opacity(0.3)
                }

                // Play icon overlay
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 24))
                    .foregroundColor(.white)
                    .shadow(radius: 2)
            }

        case .document:
            ZStack {
                Color.gray.opacity(0.2)

                Image(systemName: attachment.icon)
                    .font(.system(size: 28))
                    .foregroundColor(.blue)
            }
        }
    }
}

/// Full-screen attachment preview (for images/videos)
struct FullAttachmentPreview: View {
    let attachment: PendingAttachment
    @Binding var isPresented: Bool
    let onSend: () -> Void

    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // Content
            switch attachment.type {
            case .image(let image):
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .scaleEffect(scale)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { value in
                                scale = lastScale * value
                            }
                            .onEnded { _ in
                                lastScale = scale
                                if scale < 1.0 {
                                    withAnimation {
                                        scale = 1.0
                                        lastScale = 1.0
                                    }
                                }
                            }
                    )

            case .video(let url, _):
                VideoPreviewPlayer(url: url)

            case .document:
                VStack(spacing: 16) {
                    Image(systemName: attachment.icon)
                        .font(.system(size: 80))
                        .foregroundColor(.blue)

                    Text(attachment.fileName)
                        .font(.title3.bold())
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)

                    Text(attachment.fileSizeString)
                        .font(.subheadline)
                        .foregroundColor(.gray)
                }
                .padding()
            }
        }
        .overlay(alignment: .top) {
            // Top bar
            HStack {
                Button(action: { isPresented = false }) {
                    Image(systemName: "xmark")
                        .font(.title2)
                        .foregroundColor(.white)
                        .padding(12)
                        .background(Color.black.opacity(0.5))
                        .clipShape(Circle())
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text(attachment.fileName)
                        .font(.subheadline.bold())
                        .foregroundColor(.white)
                        .lineLimit(1)

                    Text(attachment.fileSizeString)
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }
            .padding()
            .background(
                LinearGradient(
                    colors: [Color.black.opacity(0.7), Color.clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
        .overlay(alignment: .bottom) {
            // Bottom send button
            HStack {
                Spacer()

                Button(action: {
                    onSend()
                    isPresented = false
                }) {
                    HStack(spacing: 8) {
                        Image(systemName: "paperplane.fill")
                        Text("Send")
                            .fontWeight(.semibold)
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 14)
                    .background(
                        LinearGradient(
                            colors: [.blue, .purple],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(25)
                }

                Spacer()
            }
            .padding(.bottom, 40)
            .background(
                LinearGradient(
                    colors: [Color.clear, Color.black.opacity(0.7)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
    }
}

/// Simple video preview player
struct VideoPreviewPlayer: View {
    let url: URL
    @State private var player: AVPlayer?
    @State private var isPlaying = false

    var body: some View {
        ZStack {
            if let player = player {
                VideoPlayerView(player: player)
                    .aspectRatio(contentMode: .fit)
            }

            if !isPlaying {
                Button(action: togglePlayback) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 72))
                        .foregroundColor(.white)
                        .shadow(radius: 4)
                }
            }
        }
        .onTapGesture {
            togglePlayback()
        }
        .onAppear {
            player = AVPlayer(url: url)
        }
        .onDisappear {
            player?.pause()
            player = nil
        }
    }

    private func togglePlayback() {
        guard let player = player else { return }

        if isPlaying {
            player.pause()
        } else {
            player.seek(to: .zero)
            player.play()
        }
        isPlaying.toggle()
    }
}

/// UIKit AVPlayer wrapper for SwiftUI
struct VideoPlayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> UIView {
        let view = PlayerUIView()
        view.player = player
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    class PlayerUIView: UIView {
        var player: AVPlayer? {
            get { playerLayer.player }
            set { playerLayer.player = newValue }
        }

        var playerLayer: AVPlayerLayer {
            layer as! AVPlayerLayer
        }

        override static var layerClass: AnyClass {
            AVPlayerLayer.self
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            playerLayer.videoGravity = .resizeAspect
        }
    }
}

/// Helper to create PendingAttachment from various sources
struct AttachmentHelper {
    /// Create attachment from photo picker item
    static func createFromPhotosPickerItem(_ item: PhotosPickerItem) async -> PendingAttachment? {
        // Try to load as image first
        if let data = try? await item.loadTransferable(type: Data.self) {
            let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
            try? data.write(to: tempURL)

            let fileSize = Int64(data.count)

            if let image = UIImage(data: data) {
                return PendingAttachment(
                    type: .image(image),
                    url: tempURL,
                    fileName: "Photo.jpg",
                    fileSize: fileSize
                )
            }
        }

        // Try to load as video
        if let movie = try? await item.loadTransferable(type: VideoTransferable.self) {
            let fileSize = (try? FileManager.default.attributesOfItem(atPath: movie.url.path)[.size] as? Int64) ?? 0
            let thumbnail = generateVideoThumbnail(from: movie.url)

            return PendingAttachment(
                type: .video(movie.url, thumbnail: thumbnail),
                url: movie.url,
                fileName: movie.url.lastPathComponent,
                fileSize: fileSize
            )
        }

        return nil
    }

    /// Create attachment from document URL
    static func createFromDocumentURL(_ url: URL) -> PendingAttachment? {
        // Copy to temp directory for security-scoped access
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)

        do {
            _ = url.startAccessingSecurityScopedResource()
            defer { url.stopAccessingSecurityScopedResource() }

            if FileManager.default.fileExists(atPath: tempURL.path) {
                try FileManager.default.removeItem(at: tempURL)
            }
            try FileManager.default.copyItem(at: url, to: tempURL)
        } catch {
            print("Failed to copy document: \(error)")
            return nil
        }

        let fileSize = (try? FileManager.default.attributesOfItem(atPath: tempURL.path)[.size] as? Int64) ?? 0
        let fileName = url.lastPathComponent

        // Check if it's an image
        let ext = (fileName as NSString).pathExtension.lowercased()
        if ["jpg", "jpeg", "png", "gif", "heic", "heif", "webp"].contains(ext) {
            if let image = UIImage(contentsOfFile: tempURL.path) {
                return PendingAttachment(
                    type: .image(image),
                    url: tempURL,
                    fileName: fileName,
                    fileSize: fileSize
                )
            }
        }

        // Check if it's a video
        if ["mp4", "mov", "m4v", "3gp", "avi", "mkv"].contains(ext) {
            let thumbnail = generateVideoThumbnail(from: tempURL)
            return PendingAttachment(
                type: .video(tempURL, thumbnail: thumbnail),
                url: tempURL,
                fileName: fileName,
                fileSize: fileSize
            )
        }

        // Document type
        return PendingAttachment(
            type: .document(tempURL),
            url: tempURL,
            fileName: fileName,
            fileSize: fileSize
        )
    }

    /// Generate video thumbnail
    static func generateVideoThumbnail(from url: URL) -> UIImage? {
        let asset = AVAsset(url: url)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true

        do {
            let cgImage = try imageGenerator.copyCGImage(at: .zero, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            print("Failed to generate video thumbnail: \(error)")
            return nil
        }
    }
}

/// Transferable video type for PhotosPicker
struct VideoTransferable: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { video in
            SentTransferredFile(video.url)
        } importing: { received in
            let tempDir = FileManager.default.temporaryDirectory
            let fileName = received.file.lastPathComponent
            let copy = tempDir.appendingPathComponent(fileName)

            if FileManager.default.fileExists(atPath: copy.path) {
                try FileManager.default.removeItem(at: copy)
            }
            try FileManager.default.copyItem(at: received.file, to: copy)

            return Self.init(url: copy)
        }
    }
}

#Preview {
    VStack {
        Spacer()

        // Simulate image attachment
        AttachmentPreviewBar(
            attachment: PendingAttachment(
                type: .document(URL(fileURLWithPath: "/tmp/test.pdf")),
                url: URL(fileURLWithPath: "/tmp/test.pdf"),
                fileName: "Document.pdf",
                fileSize: 1_500_000
            ),
            onCancel: {},
            onSend: {}
        )
    }
    .background(Color.black)
}
