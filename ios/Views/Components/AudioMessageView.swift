import SwiftUI
import AVFoundation

// MARK: - Audio Message Bubble
struct AudioMessageBubble: View {
    let audioURL: URL
    let duration: TimeInterval
    let messageId: String
    let isFromMe: Bool
    let timestamp: Date

    @StateObject private var audioService = AudioMessageService.shared

    private var isPlaying: Bool {
        audioService.isPlaying && audioService.currentPlayingId == messageId
    }

    var body: some View {
        VStack(alignment: isFromMe ? .trailing : .leading, spacing: 4) {
            HStack(spacing: 12) {
                // Play/Pause button
                Button(action: togglePlayback) {
                    Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 36))
                        .foregroundColor(isFromMe ? .white : .blue)
                }

                VStack(alignment: .leading, spacing: 4) {
                    // Waveform visualization (simplified)
                    WaveformView(progress: isPlaying ? audioService.playbackProgress : 0, isFromMe: isFromMe)
                        .frame(width: 120, height: 24)

                    // Duration
                    Text(formatDuration(isPlaying ? duration * audioService.playbackProgress : duration))
                        .font(.caption2)
                        .foregroundColor(isFromMe ? .white.opacity(0.7) : .gray)
                }
            }

            // Timestamp
            Text(formatTime(timestamp))
                .font(.caption2)
                .foregroundColor(isFromMe ? .white.opacity(0.7) : .gray)
        }
        .padding(12)
        .background(isFromMe ? Color.blue : Color(.systemGray5))
        .cornerRadius(16)
    }

    private func togglePlayback() {
        if isPlaying {
            audioService.pause()
        } else {
            audioService.play(url: audioURL, messageId: messageId)
        }
    }

    private func formatDuration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", minutes, secs)
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

// MARK: - Waveform Visualization
struct WaveformView: View {
    let progress: Double
    let isFromMe: Bool

    // Simulated waveform bars
    private let barCount = 20
    private let barHeights: [CGFloat] = (0..<20).map { _ in CGFloat.random(in: 0.3...1.0) }

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 2) {
                ForEach(0..<barCount, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(barColor(for: index))
                        .frame(width: 3, height: geometry.size.height * barHeights[index])
                }
            }
            .frame(height: geometry.size.height, alignment: .center)
        }
    }

    private func barColor(for index: Int) -> Color {
        let progressIndex = Int(progress * Double(barCount))
        if index <= progressIndex {
            return isFromMe ? .white : .blue
        } else {
            return isFromMe ? .white.opacity(0.4) : .gray.opacity(0.4)
        }
    }
}

// MARK: - Recording Button (Hold to record with slide-to-cancel)
struct VoiceRecordButton: View {
    let onRecordingComplete: (URL, TimeInterval) -> Void

    @StateObject private var audioService = AudioMessageService.shared
    @State private var isPressed = false
    @State private var dragOffset: CGFloat = 0
    @State private var showCancelHint = false
    @State private var pulseAnimation = false

    // Cancel threshold - how far left to drag to cancel
    private let cancelThreshold: CGFloat = -100

    // Progress towards cancel (0 to 1)
    private var cancelProgress: CGFloat {
        min(1, max(0, abs(dragOffset) / abs(cancelThreshold)))
    }

    // Whether we've passed the cancel threshold
    private var shouldCancel: Bool {
        dragOffset < cancelThreshold
    }

    var body: some View {
        ZStack {
            // Microphone button
            Image(systemName: audioService.isRecording ? "mic.fill" : "mic")
                .font(.system(size: 20))
                .foregroundColor(audioService.isRecording ? .red : .blue)
                .frame(width: 36, height: 36)
                .background(audioService.isRecording ? Color.red.opacity(0.1) : Color.clear)
                .cornerRadius(18)
                .scaleEffect(audioService.isRecording ? (pulseAnimation ? 1.1 : 1.0) : 1.0)
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            if !isPressed {
                                isPressed = true
                                audioService.startRecording()
                                // Haptic feedback on start
                                let impact = UIImpactFeedbackGenerator(style: .medium)
                                impact.impactOccurred()
                            }
                            // Only allow dragging left
                            dragOffset = min(0, value.translation.width)

                            // Show cancel hint when dragging
                            if dragOffset < -20 && !showCancelHint {
                                showCancelHint = true
                            }

                            // Haptic feedback when crossing cancel threshold
                            if shouldCancel && dragOffset > cancelThreshold - 5 && dragOffset < cancelThreshold + 5 {
                                let impact = UIImpactFeedbackGenerator(style: .heavy)
                                impact.impactOccurred()
                            }
                        }
                        .onEnded { value in
                            isPressed = false
                            showCancelHint = false

                            if shouldCancel {
                                // Cancel recording with haptic
                                let notification = UINotificationFeedbackGenerator()
                                notification.notificationOccurred(.warning)
                                audioService.cancelRecording()
                            } else if let url = audioService.stopRecording() {
                                let duration = audioService.recordingDuration
                                if duration >= 1.0 { // Minimum 1 second
                                    // Success haptic
                                    let notification = UINotificationFeedbackGenerator()
                                    notification.notificationOccurred(.success)
                                    onRecordingComplete(url, duration)
                                } else {
                                    // Too short, delete
                                    try? FileManager.default.removeItem(at: url)
                                }
                            }

                            // Reset drag offset
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                dragOffset = 0
                            }
                        }
                )
                .offset(x: dragOffset * 0.3) // Slight visual feedback on drag
        }
        .animation(.easeInOut(duration: 0.2), value: audioService.isRecording)
        .onAppear {
            // Start pulse animation when recording
            withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) {
                pulseAnimation = true
            }
        }
    }
}

// MARK: - Enhanced Recording Indicator View
struct RecordingIndicatorView: View {
    let duration: TimeInterval
    let dragOffset: CGFloat
    let cancelThreshold: CGFloat
    let onCancel: () -> Void

    @State private var pulseScale: CGFloat = 1.0
    @State private var waveformLevels: [CGFloat] = Array(repeating: 0.3, count: 12)

    private var cancelProgress: CGFloat {
        min(1, max(0, abs(dragOffset) / abs(cancelThreshold)))
    }

    private var shouldCancel: Bool {
        dragOffset < cancelThreshold
    }

    var body: some View {
        HStack(spacing: 12) {
            // Cancel icon (slides in from left)
            HStack(spacing: 6) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 20))
                    .foregroundColor(shouldCancel ? .white : .red.opacity(0.7 + cancelProgress * 0.3))

                if cancelProgress > 0.3 {
                    Text("Cancel")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(shouldCancel ? .white : .red)
                        .transition(.move(edge: .leading).combined(with: .opacity))
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(
                Capsule()
                    .fill(shouldCancel ? Color.red : Color.red.opacity(0.15))
            )
            .opacity(cancelProgress > 0.1 ? 1 : 0)
            .scaleEffect(cancelProgress > 0.1 ? 1 : 0.8)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: cancelProgress)

            Spacer()

            // Recording info
            HStack(spacing: 8) {
                // Pulsing red recording dot
                Circle()
                    .fill(Color.red)
                    .frame(width: 10, height: 10)
                    .scaleEffect(pulseScale)

                // Duration display
                Text(formatDuration(duration))
                    .font(.system(.subheadline, design: .monospaced))
                    .fontWeight(.semibold)
                    .foregroundColor(.red)

                // Animated waveform
                HStack(spacing: 2) {
                    ForEach(0..<12, id: \.self) { index in
                        RoundedRectangle(cornerRadius: 1)
                            .fill(Color.red.opacity(0.7))
                            .frame(width: 2, height: 4 + waveformLevels[index] * 16)
                    }
                }
                .frame(height: 20)
            }

            Spacer()

            // Slide to cancel hint
            if cancelProgress < 0.3 {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left")
                        .font(.caption2)
                    Text("Slide to cancel")
                        .font(.caption)
                }
                .foregroundColor(.gray)
                .transition(.opacity)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.red.opacity(0.1))
        .onAppear {
            startAnimations()
        }
    }

    private func startAnimations() {
        // Pulse animation
        withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
            pulseScale = 1.3
        }

        // Waveform animation
        Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { timer in
            withAnimation(.easeInOut(duration: 0.1)) {
                waveformLevels = waveformLevels.map { _ in CGFloat.random(in: 0.2...1.0) }
            }
        }
    }

    private func formatDuration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", minutes, secs)
    }
}

// MARK: - Standalone Recording Overlay (for full-width display)
struct VoiceRecordingOverlay: View {
    @ObservedObject var audioService: AudioMessageService
    let dragOffset: CGFloat
    let cancelThreshold: CGFloat

    @State private var pulseScale: CGFloat = 1.0
    @State private var waveformLevels: [CGFloat] = Array(repeating: 0.3, count: 20)
    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    private var cancelProgress: CGFloat {
        min(1, max(0, abs(dragOffset) / abs(cancelThreshold)))
    }

    private var shouldCancel: Bool {
        dragOffset < cancelThreshold
    }

    var body: some View {
        HStack(spacing: 16) {
            // Left side - Cancel indicator
            HStack(spacing: 8) {
                Image(systemName: shouldCancel ? "xmark.circle.fill" : "chevron.left")
                    .font(.system(size: shouldCancel ? 24 : 16))
                    .foregroundColor(shouldCancel ? .white : .gray)

                if shouldCancel {
                    Text("Release to cancel")
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                } else {
                    Text("Slide to cancel")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                Capsule()
                    .fill(shouldCancel ? Color.red : Color.gray.opacity(0.2))
            )
            .opacity(max(0.5, cancelProgress))

            Spacer()

            // Center - Waveform
            HStack(spacing: 2) {
                ForEach(0..<20, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(
                            LinearGradient(
                                colors: [.red, .orange],
                                startPoint: .bottom,
                                endPoint: .top
                            )
                        )
                        .frame(width: 3, height: 6 + waveformLevels[index] * 20)
                }
            }
            .frame(height: 26)

            Spacer()

            // Right side - Duration and recording indicator
            HStack(spacing: 10) {
                Circle()
                    .fill(Color.red)
                    .frame(width: 12, height: 12)
                    .scaleEffect(pulseScale)
                    .shadow(color: .red.opacity(0.5), radius: 4)

                Text(audioService.formatDuration(audioService.recordingDuration))
                    .font(.system(.headline, design: .monospaced))
                    .fontWeight(.bold)
                    .foregroundColor(.red)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color.red.opacity(0.08))
        .onAppear {
            withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) {
                pulseScale = 1.4
            }
        }
        .onReceive(timer) { _ in
            withAnimation(.easeInOut(duration: 0.1)) {
                waveformLevels = waveformLevels.map { _ in CGFloat.random(in: 0.15...1.0) }
            }
        }
    }
}

// MARK: - Voice Message Preview (before sending)
struct VoiceMessagePreview: View {
    let url: URL
    let duration: TimeInterval
    let onSend: () -> Void
    let onCancel: () -> Void

    @StateObject private var audioService = AudioMessageService.shared
    @State private var previewId = UUID().uuidString

    private var isPlaying: Bool {
        audioService.isPlaying && audioService.currentPlayingId == previewId
    }

    var body: some View {
        HStack(spacing: 16) {
            // Play preview
            Button(action: togglePreview) {
                Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 36))
                    .foregroundColor(.blue)
            }
            .frame(width: 44, height: 44)

            // Duration
            VStack(alignment: .leading, spacing: 2) {
                Text("Voice message")
                    .font(.subheadline.bold())
                    .foregroundColor(.white)
                Text(audioService.formatDuration(duration))
                    .font(.caption)
                    .foregroundColor(.gray)
            }

            Spacer()

            // Cancel
            Button(action: {
                audioService.stop()
                onCancel()
            }) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 28))
                    .foregroundColor(.gray)
            }
            .frame(width: 44, height: 44)

            // Send - larger and more prominent
            Button(action: {
                audioService.stop()
                onSend()
            }) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.blue, .purple],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
            }
            .frame(width: 50, height: 50)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(.systemGray6))
        .cornerRadius(16)
    }

    private func togglePreview() {
        if isPlaying {
            audioService.pause()
        } else {
            audioService.play(url: url, messageId: previewId)
        }
    }
}

#Preview {
    VStack(spacing: 20) {
        AudioMessageBubble(
            audioURL: URL(fileURLWithPath: "/tmp/test.m4a"),
            duration: 15,
            messageId: "test",
            isFromMe: true,
            timestamp: Date()
        )

        AudioMessageBubble(
            audioURL: URL(fileURLWithPath: "/tmp/test.m4a"),
            duration: 32,
            messageId: "test2",
            isFromMe: false,
            timestamp: Date()
        )

        VoiceRecordButton { url, duration in
            print("Recorded: \(url), duration: \(duration)")
        }
    }
    .padding()
}
