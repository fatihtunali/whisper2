import SwiftUI

/// Share menu option types
enum ShareMenuOption {
    case photoLibrary
    case camera
    case document
    case location
    case contact
}

/// Text input bar with send and attachment buttons
struct InputBar: View {
    @Binding var text: String
    var placeholder: String = "Message"
    var showAttachmentButton: Bool = true
    var isEnabled: Bool = true
    var onSend: () -> Void
    var onAttachment: ((ShareMenuOption) -> Void)? = nil

    @FocusState private var isFocused: Bool

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && isEnabled
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: WhisperSpacing.xs) {
            // Attachment menu button
            if showAttachmentButton {
                Menu {
                    Button(action: {
                        // Delay to allow menu to dismiss before presenting sheet
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                            onAttachment?(.photoLibrary)
                        }
                    }) {
                        Label("Photo & Video", systemImage: "photo.on.rectangle")
                    }

                    Button(action: {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                            onAttachment?(.camera)
                        }
                    }) {
                        Label("Camera", systemImage: "camera")
                    }

                    Button(action: {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                            onAttachment?(.document)
                        }
                    }) {
                        Label("Document", systemImage: "doc")
                    }

                    Button(action: {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                            onAttachment?(.location)
                        }
                    }) {
                        Label("Location", systemImage: "location")
                    }

                    Button(action: {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                            onAttachment?(.contact)
                        }
                    }) {
                        Label("Contact", systemImage: "person.crop.circle")
                    }
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 28))
                        .foregroundColor(.whisperPrimary)
                }
                .disabled(!isEnabled)
            }

            // Text input
            HStack(alignment: .bottom, spacing: WhisperSpacing.xs) {
                TextField(placeholder, text: $text, axis: .vertical)
                    .font(.whisper(size: WhisperFontSize.md))
                    .lineLimit(1...5)
                    .focused($isFocused)
                    .disabled(!isEnabled)

                // Send button (inside text field)
                if canSend {
                    Button {
                        onSend()
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.whisperPrimary)
                    }
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.horizontal, WhisperSpacing.sm)
            .padding(.vertical, WhisperSpacing.xs)
            .background(Color.whisperSurface)
            .clipShape(RoundedRectangle(cornerRadius: 20))
        }
        .padding(.horizontal, WhisperSpacing.md)
        .padding(.vertical, WhisperSpacing.xs)
        .background(Color.whisperBackground)
        .animation(.easeInOut(duration: 0.2), value: canSend)
    }
}

// MARK: - Compact Input Bar

struct CompactInputBar: View {
    @Binding var text: String
    var placeholder: String = "Add a comment..."
    var onSend: () -> Void

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        HStack(spacing: Theme.Spacing.xs) {
            TextField(placeholder, text: $text)
                .font(Theme.Typography.subheadline)
                .textFieldStyle(.plain)

            Button {
                onSend()
            } label: {
                Text("Send")
                    .font(Theme.Typography.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(canSend ? Theme.Colors.primary : Theme.Colors.textTertiary)
            }
            .disabled(!canSend)
        }
        .padding(.horizontal, Theme.Spacing.sm)
        .padding(.vertical, Theme.Spacing.xs)
        .background(Theme.Colors.surface)
        .clipShape(Capsule())
    }
}

// MARK: - Voice Message Input

struct VoiceInputBar: View {
    @Binding var isRecording: Bool
    var recordingDuration: TimeInterval = 0
    var onStartRecording: () -> Void
    var onStopRecording: () -> Void
    var onCancelRecording: () -> Void

    private var formattedDuration: String {
        let minutes = Int(recordingDuration) / 60
        let seconds = Int(recordingDuration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    var body: some View {
        HStack(spacing: Theme.Spacing.md) {
            if isRecording {
                // Cancel button
                Button {
                    onCancelRecording()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: Theme.IconSize.lg))
                        .foregroundColor(Theme.Colors.error)
                }

                // Recording indicator
                HStack(spacing: Theme.Spacing.xs) {
                    Circle()
                        .fill(Theme.Colors.error)
                        .frame(width: 8, height: 8)
                        .opacity(isRecording ? 1 : 0)
                        .animation(.easeInOut(duration: 0.5).repeatForever(), value: isRecording)

                    Text(formattedDuration)
                        .font(Theme.Typography.monospaced)
                        .foregroundColor(Theme.Colors.textPrimary)
                }

                Spacer()

                // Stop/Send button
                Button {
                    onStopRecording()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(Theme.Colors.primary)
                }
            } else {
                // Microphone button
                Button {
                    onStartRecording()
                } label: {
                    Image(systemName: "mic.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(Theme.Colors.primary)
                }
            }
        }
        .padding(.horizontal, Theme.Spacing.md)
        .padding(.vertical, Theme.Spacing.xs)
        .background(Theme.Colors.background)
    }
}

// MARK: - Preview

#Preview {
    VStack {
        Spacer()

        VStack(spacing: Theme.Spacing.lg) {
            InputBar(
                text: .constant(""),
                onSend: {}
            )

            InputBar(
                text: .constant("Hello, this is a test message"),
                onSend: {}
            )

            CompactInputBar(
                text: .constant(""),
                onSend: {}
            )

            VoiceInputBar(
                isRecording: .constant(false),
                onStartRecording: {},
                onStopRecording: {},
                onCancelRecording: {}
            )

            VoiceInputBar(
                isRecording: .constant(true),
                recordingDuration: 45,
                onStartRecording: {},
                onStopRecording: {},
                onCancelRecording: {}
            )
        }
    }
    .background(Theme.Colors.background)
}
