import SwiftUI
import MapKit
import UIKit
import AVKit

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
        case "call":
            callContent
        default:
            textContent
        }
    }

    // MARK: - Call Content
    private var callContent: some View {
        CallMessageBubble(message: message, isOutgoing: isOutgoing)
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

// MARK: - Call Message Bubble
struct CallMessageBubble: View {
    let message: Message
    let isOutgoing: Bool

    // Parse call info from content: "type|outcome|duration"
    // e.g., "video|completed|125" or "audio|missed|0"
    private var callInfo: (isVideo: Bool, outcome: String, duration: Int) {
        let parts = message.content.components(separatedBy: "|")
        let isVideo = parts.first == "video"
        let outcome = parts.count > 1 ? parts[1] : "completed"
        let duration = parts.count > 2 ? Int(parts[2]) ?? 0 : 0
        return (isVideo, outcome, duration)
    }

    private var icon: String {
        let info = callInfo
        if info.outcome == "missed" {
            return "phone.arrow.down.left"
        } else if info.outcome == "declined" {
            return "phone.down"
        } else if info.outcome == "noAnswer" || info.outcome == "cancelled" {
            return "phone.arrow.up.right"
        } else {
            return info.isVideo ? "video.fill" : "phone.fill"
        }
    }

    private var iconColor: Color {
        let info = callInfo
        if info.outcome == "missed" || info.outcome == "declined" {
            return .red
        } else if info.outcome == "noAnswer" || info.outcome == "cancelled" || info.outcome == "failed" {
            return .orange
        }
        return .green
    }

    private var callText: String {
        let info = callInfo
        let callType = info.isVideo ? "Video call" : "Voice call"

        switch info.outcome {
        case "completed":
            return "\(callType) • \(formatDuration(info.duration))"
        case "missed":
            return "Missed \(callType.lowercased())"
        case "declined":
            return "Declined \(callType.lowercased())"
        case "noAnswer":
            return "\(callType) • No answer"
        case "cancelled":
            return "\(callType) • Cancelled"
        case "failed":
            return "\(callType) • Failed"
        default:
            return callType
        }
    }

    private func formatDuration(_ seconds: Int) -> String {
        let minutes = seconds / 60
        let secs = seconds % 60
        return String(format: "%d:%02d", minutes, secs)
    }

    var body: some View {
        HStack(spacing: 12) {
            // Call icon
            ZStack {
                Circle()
                    .fill(iconColor.opacity(0.2))
                    .frame(width: 40, height: 40)
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundColor(iconColor)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(callText)
                    .font(.subheadline)
                    .foregroundColor(isOutgoing ? .white : .primary)

                if isOutgoing {
                    Text("Outgoing")
                        .font(.caption2)
                        .foregroundColor(.white.opacity(0.7))
                } else {
                    Text("Incoming")
                        .font(.caption2)
                        .foregroundColor(.gray)
                }
            }

            Spacer()
        }
        .padding(12)
        .background(isOutgoing ? Color.blue.opacity(0.8) : Color(.systemGray5))
        .cornerRadius(16)
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

        // For outgoing messages, check the outgoing cache first
        if isOutgoing {
            if let cachedURL = AttachmentService.shared.getOutgoingFileURL(forObjectKey: attachment.objectKey) {
                audioURL = cachedURL
                if duration == 0 {
                    duration = audioService.getAudioDuration(url: cachedURL) ?? 0
                }
                audioService.play(url: cachedURL, messageId: message.id)
                return
            }
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
    @State private var isDownloading = false
    @State private var downloadError: String?

    var body: some View {
        VStack {
            if let img = image {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(maxWidth: 200, maxHeight: 200)
                    .clipped()
                    .cornerRadius(12)
                    .onTapGesture {
                        showFullImage = true
                    }
            } else if isDownloading {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.gray.opacity(0.3))
                    .frame(width: 200, height: 150)
                    .overlay {
                        VStack(spacing: 8) {
                            ProgressView()
                            Text("Downloading...")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
            } else if let error = downloadError {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.gray.opacity(0.3))
                    .frame(width: 200, height: 150)
                    .overlay {
                        VStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.title)
                                .foregroundColor(.orange)
                            Text(error)
                                .font(.caption)
                                .foregroundColor(.gray)
                            Button("Retry") {
                                loadImage()
                            }
                            .font(.caption)
                        }
                    }
            } else {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.gray.opacity(0.3))
                    .frame(width: 200, height: 150)
                    .overlay {
                        VStack(spacing: 8) {
                            Image(systemName: "photo")
                                .font(.title)
                                .foregroundColor(.gray)
                            Text("Tap to load")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                    .onTapGesture {
                        loadImage()
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
        // Already loaded or downloading
        guard image == nil && !isDownloading else { return }

        // Check if we have an attachment to download
        if let attachment = message.attachment {
            // For outgoing, check the outgoing cache first
            if isOutgoing {
                if let cachedURL = AttachmentService.shared.getOutgoingFileURL(forObjectKey: attachment.objectKey),
                   let data = try? Data(contentsOf: cachedURL),
                   let loadedImage = UIImage(data: data) {
                    image = loadedImage
                    return
                }
                downloadError = "Image unavailable"
                return
            }

            // For incoming: use sender's public key to decrypt
            guard let senderPublicKey = ContactsService.shared.getPublicKey(for: message.from) else {
                downloadError = "Missing sender key"
                return
            }

            isDownloading = true
            downloadError = nil

            Task {
                do {
                    let url = try await AttachmentService.shared.downloadFile(attachment, senderPublicKey: senderPublicKey)
                    if let data = try? Data(contentsOf: url),
                       let loadedImage = UIImage(data: data) {
                        await MainActor.run {
                            image = loadedImage
                            isDownloading = false
                        }
                    } else {
                        await MainActor.run {
                            downloadError = "Invalid image"
                            isDownloading = false
                        }
                    }
                } catch {
                    await MainActor.run {
                        downloadError = "Download failed"
                        isDownloading = false
                        print("[ImageMessage] Download error: \(error)")
                    }
                }
            }
        } else if let url = URL(string: message.content) {
            // Legacy: direct URL in content
            isDownloading = true
            Task {
                if let data = try? Data(contentsOf: url),
                   let loadedImage = UIImage(data: data) {
                    await MainActor.run {
                        image = loadedImage
                        isDownloading = false
                    }
                } else {
                    await MainActor.run {
                        downloadError = "Failed to load"
                        isDownloading = false
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
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var showSaveSuccess = false

    var body: some View {
        NavigationView {
            ZStack {
                Color.black.ignoresSafeArea()

                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { value in
                                let delta = value / lastScale
                                lastScale = value
                                scale = min(max(scale * delta, 1), 5)
                            }
                            .onEnded { _ in
                                lastScale = 1.0
                                if scale < 1 {
                                    withAnimation {
                                        scale = 1
                                        offset = .zero
                                    }
                                }
                            }
                    )
                    .simultaneousGesture(
                        DragGesture()
                            .onChanged { value in
                                if scale > 1 {
                                    offset = CGSize(
                                        width: lastOffset.width + value.translation.width,
                                        height: lastOffset.height + value.translation.height
                                    )
                                }
                            }
                            .onEnded { _ in
                                lastOffset = offset
                            }
                    )
                    .onTapGesture(count: 2) {
                        withAnimation {
                            if scale > 1 {
                                scale = 1
                                offset = .zero
                                lastOffset = .zero
                            } else {
                                scale = 2
                            }
                        }
                    }

                if showSaveSuccess {
                    VStack {
                        Spacer()
                        Text("Saved to Photos")
                            .padding()
                            .background(Color.green.opacity(0.9))
                            .cornerRadius(10)
                            .foregroundColor(.white)
                            .padding(.bottom, 50)
                    }
                    .transition(.opacity)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        dismiss()
                    }
                    .foregroundColor(.white)
                }
                ToolbarItem(placement: .primaryAction) {
                    HStack(spacing: 16) {
                        // Save to Photos
                        Button(action: saveToPhotos) {
                            Image(systemName: "square.and.arrow.down")
                                .foregroundColor(.white)
                        }
                        // Share
                        ShareLink(item: Image(uiImage: image), preview: SharePreview("Image")) {
                            Image(systemName: "square.and.arrow.up")
                                .foregroundColor(.white)
                        }
                    }
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
    }

    private func saveToPhotos() {
        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
        withAnimation {
            showSaveSuccess = true
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            withAnimation {
                showSaveSuccess = false
            }
        }
    }
}

// MARK: - Video Message Bubble
struct VideoMessageBubble: View {
    let message: Message
    let isOutgoing: Bool

    @State private var videoURL: URL?
    @State private var thumbnail: UIImage?
    @State private var isDownloading = false
    @State private var downloadError: String?
    @State private var showPlayer = false

    var body: some View {
        VStack {
            ZStack {
                if let thumb = thumbnail {
                    Image(uiImage: thumb)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 200, height: 150)
                        .clipped()
                        .cornerRadius(12)
                } else {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.gray.opacity(0.3))
                        .frame(width: 200, height: 150)
                }

                // Overlay content
                if isDownloading {
                    VStack(spacing: 8) {
                        ProgressView()
                        Text("Downloading...")
                            .font(.caption)
                            .foregroundColor(.white)
                    }
                    .frame(width: 200, height: 150)
                    .background(Color.black.opacity(0.5))
                    .cornerRadius(12)
                } else if let error = downloadError {
                    VStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.title)
                            .foregroundColor(.orange)
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.white)
                        Button("Retry") {
                            downloadVideo()
                        }
                        .font(.caption)
                        .foregroundColor(.blue)
                    }
                    .frame(width: 200, height: 150)
                    .background(Color.black.opacity(0.5))
                    .cornerRadius(12)
                } else if videoURL != nil {
                    // Ready to play
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 50))
                        .foregroundColor(.white)
                        .shadow(radius: 4)
                } else {
                    // Not downloaded yet
                    VStack(spacing: 8) {
                        Image(systemName: "video")
                            .font(.title)
                            .foregroundColor(.white)
                        Text("Tap to load")
                            .font(.caption)
                            .foregroundColor(.white)
                    }
                }
            }
            .onTapGesture {
                if let _ = videoURL {
                    showPlayer = true
                } else if !isDownloading {
                    downloadVideo()
                }
            }
        }
        .onAppear {
            downloadVideo()
        }
        .fullScreenCover(isPresented: $showPlayer) {
            if let url = videoURL {
                VideoPlayerView(url: url)
            }
        }
    }

    private func downloadVideo() {
        guard videoURL == nil && !isDownloading else { return }

        if let attachment = message.attachment {
            // For outgoing, check the outgoing cache first
            if isOutgoing {
                if let cachedURL = AttachmentService.shared.getOutgoingFileURL(forObjectKey: attachment.objectKey) {
                    videoURL = cachedURL
                    Task {
                        let thumbImage = await generateThumbnail(from: cachedURL)
                        await MainActor.run {
                            thumbnail = thumbImage
                        }
                    }
                    return
                }
                downloadError = "Video unavailable"
                return
            }

            // For incoming: use sender's public key to decrypt
            guard let senderPublicKey = ContactsService.shared.getPublicKey(for: message.from) else {
                downloadError = "Missing sender key"
                return
            }

            isDownloading = true
            downloadError = nil

            Task {
                do {
                    let url = try await AttachmentService.shared.downloadFile(attachment, senderPublicKey: senderPublicKey)

                    // Generate thumbnail
                    let thumbImage = await generateThumbnail(from: url)

                    await MainActor.run {
                        videoURL = url
                        thumbnail = thumbImage
                        isDownloading = false
                    }
                } catch {
                    await MainActor.run {
                        downloadError = "Download failed"
                        isDownloading = false
                        print("[VideoMessage] Download error: \(error)")
                    }
                }
            }
        } else if let url = URL(string: message.content) {
            videoURL = url
            Task {
                let thumbImage = await generateThumbnail(from: url)
                await MainActor.run {
                    thumbnail = thumbImage
                }
            }
        }
    }

    private func generateThumbnail(from url: URL) async -> UIImage? {
        let asset = AVAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 400, height: 400)

        do {
            let cgImage = try generator.copyCGImage(at: .zero, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            print("[VideoMessage] Thumbnail error: \(error)")
            return nil
        }
    }
}

// MARK: - Video Player View
struct VideoPlayerView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?

    var body: some View {
        NavigationView {
            ZStack {
                Color.black.ignoresSafeArea()

                if let player = player {
                    VideoPlayer(player: player)
                        .ignoresSafeArea()
                } else {
                    ProgressView()
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        player?.pause()
                        dismiss()
                    }
                    .foregroundColor(.white)
                }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: url) {
                        Image(systemName: "square.and.arrow.up")
                            .foregroundColor(.white)
                    }
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .onAppear {
            player = AVPlayer(url: url)
            player?.play()
        }
        .onDisappear {
            player?.pause()
            player = nil
        }
    }
}

// MARK: - File Message Bubble
struct FileMessageBubble: View {
    let message: Message
    let isOutgoing: Bool

    @State private var fileURL: URL?
    @State private var isDownloading = false
    @State private var downloadError: String?
    @State private var showShareSheet = false

    var body: some View {
        HStack(spacing: 12) {
            // File icon or progress
            ZStack {
                if isDownloading {
                    ProgressView()
                        .frame(width: 24, height: 24)
                } else {
                    Image(systemName: fileIcon)
                        .font(.title2)
                        .foregroundColor(isOutgoing ? .white : .blue)
                }
            }
            .frame(width: 30)

            VStack(alignment: .leading, spacing: 2) {
                Text(fileName)
                    .font(.caption.bold())
                    .foregroundColor(isOutgoing ? .white : .primary)
                    .lineLimit(1)

                if let error = downloadError {
                    Text(error)
                        .font(.caption2)
                        .foregroundColor(.red)
                } else if fileURL != nil {
                    Text("Tap to open")
                        .font(.caption2)
                        .foregroundColor(isOutgoing ? .white.opacity(0.7) : .gray)
                } else if let attachment = message.attachment {
                    Text(formatSize(attachment.ciphertextSize))
                        .font(.caption2)
                        .foregroundColor(isOutgoing ? .white.opacity(0.7) : .gray)
                } else {
                    Text("Tap to download")
                        .font(.caption2)
                        .foregroundColor(isOutgoing ? .white.opacity(0.7) : .gray)
                }
            }

            Spacer()

            // Download/Open indicator
            if fileURL != nil {
                Image(systemName: "arrow.up.forward.circle")
                    .foregroundColor(isOutgoing ? .white.opacity(0.7) : .blue)
            } else if !isDownloading {
                Image(systemName: "arrow.down.circle")
                    .foregroundColor(isOutgoing ? .white.opacity(0.7) : .blue)
            }
        }
        .padding(12)
        .background(isOutgoing ? Color.blue : Color(.systemGray5))
        .cornerRadius(12)
        .onTapGesture {
            if let url = fileURL {
                showShareSheet = true
            } else if !isDownloading {
                downloadFile()
            }
        }
        .sheet(isPresented: $showShareSheet) {
            if let url = fileURL {
                ShareSheet(items: [url])
            }
        }
    }

    private var fileName: String {
        // Try to get from content (which should be filename)
        if !message.content.isEmpty && !message.content.contains("/") {
            return message.content
        }
        // Fallback to attachment content type
        if let attachment = message.attachment {
            let ext = getExtension(for: attachment.contentType)
            return "File.\(ext)"
        }
        return "File"
    }

    private var fileIcon: String {
        guard let attachment = message.attachment else { return "doc.fill" }
        let contentType = attachment.contentType.lowercased()

        if contentType.contains("pdf") { return "doc.text.fill" }
        if contentType.contains("word") || contentType.contains("document") { return "doc.richtext.fill" }
        if contentType.contains("excel") || contentType.contains("spreadsheet") { return "tablecells.fill" }
        if contentType.contains("zip") || contentType.contains("archive") { return "doc.zipper" }
        if contentType.contains("text") { return "doc.plaintext.fill" }

        return "doc.fill"
    }

    private func formatSize(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }

    private func getExtension(for contentType: String) -> String {
        switch contentType.lowercased() {
        case "application/pdf": return "pdf"
        case "application/msword": return "doc"
        case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx"
        case "text/plain": return "txt"
        case "application/zip": return "zip"
        default: return "file"
        }
    }

    private func downloadFile() {
        guard fileURL == nil && !isDownloading else { return }

        if let attachment = message.attachment {
            // For outgoing, check the outgoing cache first
            if isOutgoing {
                if let cachedURL = AttachmentService.shared.getOutgoingFileURL(forObjectKey: attachment.objectKey) {
                    fileURL = cachedURL
                    return
                }
                downloadError = "File unavailable"
                return
            }

            // For incoming: use sender's public key to decrypt
            guard let senderPublicKey = ContactsService.shared.getPublicKey(for: message.from) else {
                downloadError = "Missing key"
                return
            }

            isDownloading = true
            downloadError = nil

            Task {
                do {
                    let url = try await AttachmentService.shared.downloadFile(attachment, senderPublicKey: senderPublicKey)
                    await MainActor.run {
                        fileURL = url
                        isDownloading = false
                    }
                } catch {
                    await MainActor.run {
                        downloadError = "Download failed"
                        isDownloading = false
                        print("[FileMessage] Download error: \(error)")
                    }
                }
            }
        }
    }
}

// MARK: - Share Sheet
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
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
