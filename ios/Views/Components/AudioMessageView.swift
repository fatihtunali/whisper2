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

// MARK: - Recording Button (Hold to record)
struct VoiceRecordButton: View {
    let onRecordingComplete: (URL, TimeInterval) -> Void

    @StateObject private var audioService = AudioMessageService.shared
    @State private var isPressed = false

    var body: some View {
        ZStack {
            // Recording indicator
            if audioService.isRecording {
                HStack(spacing: 8) {
                    // Pulsing red dot
                    Circle()
                        .fill(Color.red)
                        .frame(width: 8, height: 8)
                        .opacity(isPressed ? 1 : 0.5)
                        .animation(.easeInOut(duration: 0.5).repeatForever(), value: isPressed)

                    // Duration
                    Text(audioService.formatDuration(audioService.recordingDuration))
                        .font(.caption)
                        .foregroundColor(.red)
                        .monospacedDigit()

                    Text("Release to send")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Color(.systemGray6))
                .cornerRadius(16)
                .transition(.scale.combined(with: .opacity))
            }

            // Microphone button
            Image(systemName: audioService.isRecording ? "mic.fill" : "mic")
                .font(.system(size: 20))
                .foregroundColor(audioService.isRecording ? .red : .blue)
                .frame(width: 36, height: 36)
                .background(audioService.isRecording ? Color.red.opacity(0.1) : Color.clear)
                .cornerRadius(18)
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { _ in
                            if !isPressed {
                                isPressed = true
                                audioService.startRecording()
                            }
                        }
                        .onEnded { value in
                            isPressed = false
                            // Check if dragged left (cancel)
                            if value.translation.width < -50 {
                                audioService.cancelRecording()
                            } else if let url = audioService.stopRecording() {
                                let duration = audioService.recordingDuration
                                if duration >= 1.0 { // Minimum 1 second
                                    onRecordingComplete(url, duration)
                                } else {
                                    // Too short, delete
                                    try? FileManager.default.removeItem(at: url)
                                }
                            }
                        }
                )
        }
        .animation(.easeInOut(duration: 0.2), value: audioService.isRecording)
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
