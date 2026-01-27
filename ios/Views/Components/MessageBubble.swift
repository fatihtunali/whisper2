import SwiftUI
import MapKit
import UIKit

/// Message bubble component - supports text, audio, location, and attachments
struct MessageBubble: View {
    let message: Message
    var onDelete: ((Bool) -> Void)? = nil  // Bool = deleteForEveryone
    var theme: ChatTheme? = nil  // Optional theme for custom colors

    private var isOutgoing: Bool {
        message.direction == .outgoing
    }

    private var outgoingColor: Color {
        theme?.outgoingBubbleColor.color ?? Color.blue
    }

    private var incomingColor: Color {
        theme?.incomingBubbleColor.color ?? Color.gray.opacity(0.4)
    }

    private var outgoingTextColor: Color {
        theme?.outgoingTextColor.color ?? .white
    }

    private var incomingTextColor: Color {
        theme?.incomingTextColor.color ?? .white
    }

    var body: some View {
        HStack {
            if isOutgoing { Spacer(minLength: 60) }

            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 4) {
                // Message content based on type
                contentView

                // Time and status
                HStack(spacing: 4) {
                    Text(message.timeString)
                        .font(.caption2)
                        .foregroundColor(.gray)

                    if isOutgoing {
                        statusIcon
                    }
                }
            }
            .contextMenu {
                // Copy (for text messages)
                if message.contentType == "text" {
                    Button(action: {
                        UIPasteboard.general.string = message.content
                    }) {
                        Label("Copy", systemImage: "doc.on.doc")
                    }
                }

                // Delete for me
                Button(role: .destructive, action: {
                    onDelete?(false)
                }) {
                    Label("Delete for Me", systemImage: "trash")
                }

                // Delete for everyone (only for outgoing messages)
                if isOutgoing {
                    Button(role: .destructive, action: {
                        onDelete?(true)
                    }) {
                        Label("Delete for Everyone", systemImage: "trash.fill")
                    }
                }
            }

            if !isOutgoing { Spacer(minLength: 60) }
        }
    }

    @ViewBuilder
    private var contentView: some View {
        switch message.contentType {
        case "audio", "voice":
            audioContent
        case "location":
            locationContent
        case "image":
            imageContent
        case "video":
            videoContent
        case "file":
            fileContent
        default:
            textContent
        }
    }

    // MARK: - Text Content
    private var textContent: some View {
        Text(message.content)
            .font(.body)
            .foregroundColor(isOutgoing ? outgoingTextColor : incomingTextColor)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(bubbleBackground)
            .clipShape(BubbleShape(isOutgoing: isOutgoing))
    }

    @ViewBuilder
    private var bubbleBackground: some View {
        if let _ = theme {
            // Use solid color when theme is applied
            isOutgoing ? outgoingColor : incomingColor
        } else {
            // Default gradient
            bubbleGradient
        }
    }

    // MARK: - Audio Content
    private var audioContent: some View {
        AudioMessageBubbleContent(
            message: message,
            isOutgoing: isOutgoing
        )
    }

    // MARK: - Location Content
    private var locationContent: some View {
        LocationMessageBubble(
            message: message,
            isOutgoing: isOutgoing
        )
    }

    // MARK: - Image Content
    private var imageContent: some View {
        ImageMessageBubble(
            message: message,
            isOutgoing: isOutgoing
        )
    }

    // MARK: - Video Content
    private var videoContent: some View {
        VideoMessageBubble(
            message: message,
            isOutgoing: isOutgoing
        )
    }

    // MARK: - File Content
    private var fileContent: some View {
        FileMessageBubble(
            message: message,
            isOutgoing: isOutgoing
        )
    }

    private var bubbleGradient: LinearGradient {
        isOutgoing ?
        LinearGradient(
            colors: [Color.blue, Color.purple.opacity(0.8)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        ) :
        LinearGradient(
            colors: [Color.gray.opacity(0.4), Color.gray.opacity(0.3)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch message.status {
        case .pending:
            Image(systemName: "clock")
                .font(.caption2)
                .foregroundColor(.gray)
        case .sent:
            Image(systemName: "checkmark")
                .font(.caption2)
                .foregroundColor(.gray)
        case .delivered:
            Image(systemName: "checkmark.circle")
                .font(.caption2)
                .foregroundColor(.gray)
        case .read:
            Image(systemName: "checkmark.circle.fill")
                .font(.caption2)
                .foregroundColor(.blue)
        case .failed:
            Image(systemName: "exclamationmark.circle")
                .font(.caption2)
                .foregroundColor(.red)
        }
    }
}

// MARK: - Audio Message Bubble Content
struct AudioMessageBubbleContent: View {
    let message: Message
    let isOutgoing: Bool

    @StateObject private var audioService = AudioMessageService.shared
    @State private var audioURL: URL?
    @State private var duration: TimeInterval = 0
    @State private var isDownloading = false
    @State private var downloadError: String?

    private var isPlaying: Bool {
        audioService.isPlaying && audioService.currentPlayingId == message.id
    }

    var body: some View {
        HStack(spacing: 12) {
            // Play/Pause button
            Button(action: togglePlayback) {
                if isDownloading {
                    ProgressView()
                        .frame(width: 36, height: 36)
                } else {
                    Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 36))
                        .foregroundColor(isOutgoing ? .white : .blue)
                }
            }
            .disabled(isDownloading)

            VStack(alignment: .leading, spacing: 4) {
                // Waveform
                WaveformView(progress: isPlaying ? audioService.playbackProgress : 0, isFromMe: isOutgoing)
                    .frame(width: 120, height: 24)

                // Duration or error
                if let error = downloadError {
                    Text(error)
                        .font(.caption2)
                        .foregroundColor(.red)
                } else {
                    Text(audioService.formatDuration(isPlaying ? duration * audioService.playbackProgress : duration))
                        .font(.caption2)
                        .foregroundColor(isOutgoing ? .white.opacity(0.7) : .gray)
                }
            }
        }
        .padding(12)
        .background(isOutgoing ? Color.blue : Color(.systemGray5))
        .cornerRadius(16)
        .onAppear {
            parseAudioContent()
        }
    }

    private func parseAudioContent() {
        // Parse duration from content (e.g., "6" for 6 seconds)
        if let durationValue = TimeInterval(message.content) {
            duration = durationValue
        }

        // If there's an attachment, we'll download it on play
        // For legacy format "duration|url", try to parse
        let parts = message.content.components(separatedBy: "|")
        if parts.count >= 2 {
            duration = TimeInterval(parts[0]) ?? 0
            audioURL = URL(string: parts[1])
        }
    }

    private func togglePlayback() {
        if isPlaying {
            audioService.pause()
            return
        }

        // If we already have the URL, play it
        if let url = audioURL {
            audioService.play(url: url, messageId: message.id)
            return
        }

        // Need to download from attachment
        guard let attachment = message.attachment else {
            downloadError = "No audio file"
            return
        }

        // For outgoing messages, we can't re-download (fileKeyBox was encrypted for recipient only)
        if isOutgoing {
            downloadError = "File unavailable"
            return
        }

        // For incoming messages: use sender's public key to decrypt
        guard let senderPublicKey = ContactsService.shared.getPublicKey(for: message.from) else {
            downloadError = "Missing sender key"
            return
        }

        isDownloading = true
        downloadError = nil

        Task {
            do {
                let url = try await AttachmentService.shared.downloadFile(attachment, senderPublicKey: senderPublicKey)
                await MainActor.run {
                    audioURL = url
                    isDownloading = false
                    // Get actual duration from file if not set
                    if duration == 0 {
                        duration = audioService.getAudioDuration(url: url) ?? 0
                    }
                    // Auto-play after download
                    audioService.play(url: url, messageId: message.id)
                }
            } catch {
                await MainActor.run {
                    isDownloading = false
                    downloadError = "Download failed"
                    print("[AudioMessage] Download error: \(error)")
                }
            }
        }
    }
}

// MARK: - Location Message Bubble
struct LocationMessageBubble: View {
    let message: Message
    let isOutgoing: Bool

    @State private var location: LocationData?
    @State private var region: MKCoordinateRegion?
    @State private var showFullMap = false

    var body: some View {
        VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 4) {
            if let location = location, let region = region {
                // Map preview
                Map(coordinateRegion: .constant(region), annotationItems: [LocationAnnotation(coordinate: location.coordinate)]) { item in
                    MapMarker(coordinate: item.coordinate, tint: .red)
                }
                .frame(width: 200, height: 150)
                .cornerRadius(12)
                .allowsHitTesting(false)
                .onTapGesture {
                    showFullMap = true
                }

                // Open in Maps button
                Button(action: openInMaps) {
                    HStack(spacing: 4) {
                        Image(systemName: "map.fill")
                            .font(.caption)
                        Text("Open in Maps")
                            .font(.caption)
                    }
                    .foregroundColor(isOutgoing ? .white.opacity(0.9) : .blue)
                }
            } else {
                // Loading or error state
                HStack {
                    Image(systemName: "mappin.circle")
                    Text("Location")
                }
                .foregroundColor(.gray)
            }
        }
        .padding(8)
        .background(isOutgoing ? Color.blue : Color(.systemGray5))
        .cornerRadius(16)
        .onAppear {
            parseLocationContent()
        }
        .sheet(isPresented: $showFullMap) {
            if let loc = location {
                FullScreenMapView(location: loc)
            }
        }
    }

    private func parseLocationContent() {
        // Parse content: "lat,lng" or JSON
        if let data = message.content.data(using: .utf8),
           let loc = try? JSONDecoder().decode(LocationData.self, from: data) {
            location = loc
            region = MKCoordinateRegion(
                center: loc.coordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
            )
        } else {
            let parts = message.content.components(separatedBy: ",")
            if parts.count >= 2,
               let lat = Double(parts[0].trimmingCharacters(in: .whitespaces)),
               let lng = Double(parts[1].trimmingCharacters(in: .whitespaces)) {
                let loc = LocationData(latitude: lat, longitude: lng)
                location = loc
                region = MKCoordinateRegion(
                    center: loc.coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                )
            }
        }
    }

    private func openInMaps() {
        guard let location = location else { return }
        // Show action sheet to choose map app
        let coordinate = location.coordinate
        let appleMapsURL = URL(string: "maps://?ll=\(coordinate.latitude),\(coordinate.longitude)&q=Shared%20Location")
        let googleMapsURL = URL(string: "comgooglemaps://?q=\(coordinate.latitude),\(coordinate.longitude)")
        let webURL = URL(string: "https://maps.apple.com/?ll=\(coordinate.latitude),\(coordinate.longitude)")

        // Try Google Maps first, then Apple Maps, then web
        if let url = googleMapsURL, UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url)
        } else if let url = appleMapsURL {
            UIApplication.shared.open(url)
        } else if let url = webURL {
            UIApplication.shared.open(url)
        }
    }
}

// MARK: - Image Message Bubble
struct ImageMessageBubble: View {
    let message: Message
    let isOutgoing: Bool

    @State private var image: UIImage?
    @State private var showFullImage = false

    var body: some View {
        VStack {
            if let img = image {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(maxWidth: 200, maxHeight: 200)
                    .cornerRadius(12)
                    .onTapGesture {
                        showFullImage = true
                    }
            } else {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.gray.opacity(0.3))
                    .frame(width: 200, height: 150)
                    .overlay {
                        ProgressView()
                    }
            }
        }
        .onAppear {
            loadImage()
        }
        .fullScreenCover(isPresented: $showFullImage) {
            if let img = image {
                ImageViewer(image: img)
            }
        }
    }

    private func loadImage() {
        // Load from URL or local file
        if let url = URL(string: message.content) {
            Task {
                if let data = try? Data(contentsOf: url),
                   let loadedImage = UIImage(data: data) {
                    await MainActor.run {
                        image = loadedImage
                    }
                }
            }
        }
    }
}

// MARK: - Image Viewer
struct ImageViewer: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .background(Color.black)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") {
                            dismiss()
                        }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: Image(uiImage: image), preview: SharePreview("Image"))
                    }
                }
        }
    }
}

// MARK: - Video Message Bubble
struct VideoMessageBubble: View {
    let message: Message
    let isOutgoing: Bool

    var body: some View {
        VStack {
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.gray.opacity(0.3))
                .frame(width: 200, height: 150)
                .overlay {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 44))
                        .foregroundColor(.white)
                }
        }
    }
}

// MARK: - File Message Bubble
struct FileMessageBubble: View {
    let message: Message
    let isOutgoing: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.fill")
                .font(.title2)
                .foregroundColor(isOutgoing ? .white : .blue)

            VStack(alignment: .leading) {
                Text(fileName)
                    .font(.caption.bold())
                    .foregroundColor(isOutgoing ? .white : .primary)
                Text(fileSize)
                    .font(.caption2)
                    .foregroundColor(isOutgoing ? .white.opacity(0.7) : .gray)
            }
        }
        .padding(12)
        .background(isOutgoing ? Color.blue : Color(.systemGray5))
        .cornerRadius(12)
    }

    private var fileName: String {
        URL(string: message.content)?.lastPathComponent ?? "File"
    }

    private var fileSize: String {
        "Tap to download"
    }
}

/// Custom bubble shape
struct BubbleShape: Shape {
    let isOutgoing: Bool

    func path(in rect: CGRect) -> Path {
        let radius: CGFloat = 16
        let tailSize: CGFloat = 6

        var path = Path()

        if isOutgoing {
            // Outgoing: tail on right
            path.move(to: CGPoint(x: rect.minX + radius, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX - radius - tailSize, y: rect.minY))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX - tailSize, y: rect.minY + radius),
                control: CGPoint(x: rect.maxX - tailSize, y: rect.minY)
            )
            path.addLine(to: CGPoint(x: rect.maxX - tailSize, y: rect.maxY - radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX - tailSize - radius, y: rect.maxY),
                control: CGPoint(x: rect.maxX - tailSize, y: rect.maxY)
            )
            // Tail
            path.addLine(to: CGPoint(x: rect.maxX - tailSize, y: rect.maxY))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX, y: rect.maxY + tailSize),
                control: CGPoint(x: rect.maxX, y: rect.maxY)
            )
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX - tailSize - radius, y: rect.maxY),
                control: CGPoint(x: rect.maxX - tailSize, y: rect.maxY + tailSize)
            )
            path.addLine(to: CGPoint(x: rect.minX + radius, y: rect.maxY))
            path.addQuadCurve(
                to: CGPoint(x: rect.minX, y: rect.maxY - radius),
                control: CGPoint(x: rect.minX, y: rect.maxY)
            )
            path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.minX + radius, y: rect.minY),
                control: CGPoint(x: rect.minX, y: rect.minY)
            )
        } else {
            // Incoming: tail on left
            path.move(to: CGPoint(x: rect.minX + radius + tailSize, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX, y: rect.minY + radius),
                control: CGPoint(x: rect.maxX, y: rect.minY)
            )
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX - radius, y: rect.maxY),
                control: CGPoint(x: rect.maxX, y: rect.maxY)
            )
            path.addLine(to: CGPoint(x: rect.minX + radius + tailSize, y: rect.maxY))
            // Tail
            path.addQuadCurve(
                to: CGPoint(x: rect.minX, y: rect.maxY + tailSize),
                control: CGPoint(x: rect.minX + tailSize, y: rect.maxY)
            )
            path.addQuadCurve(
                to: CGPoint(x: rect.minX + tailSize, y: rect.maxY - radius),
                control: CGPoint(x: rect.minX + tailSize, y: rect.maxY)
            )
            path.addLine(to: CGPoint(x: rect.minX + tailSize, y: rect.minY + radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.minX + radius + tailSize, y: rect.minY),
                control: CGPoint(x: rect.minX + tailSize, y: rect.minY)
            )
        }

        path.closeSubpath()
        return path
    }
}

#Preview {
    VStack(spacing: 16) {
        MessageBubble(message: Message(
            id: "1",
            conversationId: "test",
            from: "me",
            to: "other",
            content: "Hello! How are you?",
            status: .read,
            direction: .outgoing
        ))

        MessageBubble(message: Message(
            id: "2",
            conversationId: "test",
            from: "other",
            to: "me",
            content: "I am doing great, thanks for asking!",
            status: .delivered,
            direction: .incoming
        ))

        MessageBubble(message: Message(
            id: "3",
            conversationId: "test",
            from: "me",
            to: "other",
            content: "41.0082,28.9784",
            contentType: "location",
            status: .sent,
            direction: .outgoing
        ))
    }
    .padding()
    .background(Color.black)
}
