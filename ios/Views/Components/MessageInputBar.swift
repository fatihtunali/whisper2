import SwiftUI
import PhotosUI

/// Message input bar with attachments, voice recording, and location
struct MessageInputBar: View {
    @Binding var text: String
    var isFocused: FocusState<Bool>.Binding
    var isEnabled: Bool = true
    let onSend: () -> Void
    var onTextChange: (() -> Void)? = nil
    var onSendVoice: ((URL, TimeInterval) -> Void)? = nil
    var onSendLocation: ((LocationData) -> Void)? = nil
    var onSendAttachment: ((URL) -> Void)? = nil

    @State private var showAttachmentMenu = false
    @State private var showLocationPicker = false
    @State private var showPhotoPicker = false
    @State private var showDocumentPicker = false
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var pendingVoiceMessage: (url: URL, duration: TimeInterval)?

    @StateObject private var audioService = AudioMessageService.shared

    private var canSend: Bool {
        isEnabled && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var showVoiceButton: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && pendingVoiceMessage == nil
    }

    var body: some View {
        VStack(spacing: 0) {
            // Voice message preview
            if let voice = pendingVoiceMessage {
                VoiceMessagePreview(
                    url: voice.url,
                    duration: voice.duration,
                    onSend: {
                        onSendVoice?(voice.url, voice.duration)
                        pendingVoiceMessage = nil
                    },
                    onCancel: {
                        try? FileManager.default.removeItem(at: voice.url)
                        pendingVoiceMessage = nil
                    }
                )
                .padding(.horizontal)
                .padding(.top, 8)
            }

            // Recording indicator
            if audioService.isRecording {
                HStack(spacing: 8) {
                    Circle()
                        .fill(Color.red)
                        .frame(width: 8, height: 8)

                    Text(audioService.formatDuration(audioService.recordingDuration))
                        .font(.caption)
                        .foregroundColor(.red)
                        .monospacedDigit()

                    Spacer()

                    Text("Slide left to cancel")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                .background(Color.red.opacity(0.1))
            }

            // Main input bar
            HStack(spacing: 8) {
                // Attachment button
                Button(action: { showAttachmentMenu = true }) {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 28))
                        .foregroundColor(isEnabled ? .blue : .gray)
                }
                .disabled(!isEnabled)

                // Text field
                HStack(spacing: 8) {
                    TextField("Message", text: $text, axis: .vertical)
                        .textFieldStyle(.plain)
                        .foregroundColor(isEnabled ? .white : .gray)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .lineLimit(1...5)
                        .focused(isFocused)
                        .disabled(!isEnabled)
                        .onChange(of: text) { _, _ in
                            onTextChange?()
                        }
                }
                .background(Color.gray.opacity(isEnabled ? 0.2 : 0.1))
                .cornerRadius(20)

                // Voice or Send button
                if showVoiceButton {
                    VoiceRecordButton { url, duration in
                        pendingVoiceMessage = (url, duration)
                    }
                    .opacity(isEnabled ? 1 : 0.5)
                    .disabled(!isEnabled)
                } else {
                    // Send button
                    Button(action: {
                        if canSend {
                            onSend()
                        }
                    }) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 34))
                            .foregroundStyle(
                                canSend ?
                                LinearGradient(
                                    colors: [.blue, .purple],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ) :
                                LinearGradient(
                                    colors: [.gray, .gray],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                    }
                    .disabled(!canSend)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .background(Color.black)
        .confirmationDialog("Attach", isPresented: $showAttachmentMenu) {
            Button("Photo & Video") {
                showPhotoPicker = true
            }
            Button("Document") {
                showDocumentPicker = true
            }
            Button("Location") {
                showLocationPicker = true
            }
            Button("Cancel", role: .cancel) {}
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .any(of: [.images, .videos]))
        .onChange(of: selectedPhotoItem) { _, newItem in
            Task {
                if let item = newItem,
                   let data = try? await item.loadTransferable(type: Data.self) {
                    // Save to temp file and send
                    let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
                    try? data.write(to: tempURL)
                    onSendAttachment?(tempURL)
                }
                selectedPhotoItem = nil
            }
        }
        .sheet(isPresented: $showLocationPicker) {
            LocationPickerSheet { location in
                onSendLocation?(location)
            }
        }
        .sheet(isPresented: $showDocumentPicker) {
            DocumentPicker { url in
                onSendAttachment?(url)
            }
        }
    }
}

// MARK: - Document Picker
struct DocumentPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item])
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void

        init(onPick: @escaping (URL) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            // Copy to temp directory for security-scoped access
            let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)
            _ = url.startAccessingSecurityScopedResource()
            try? FileManager.default.copyItem(at: url, to: tempURL)
            url.stopAccessingSecurityScopedResource()
            onPick(tempURL)
        }
    }
}

#Preview {
    VStack {
        Spacer()
        MessageInputBar(
            text: .constant("Hello"),
            isFocused: FocusState<Bool>().projectedValue,
            isEnabled: true,
            onSend: {},
            onTextChange: {}
        )
        MessageInputBar(
            text: .constant(""),
            isFocused: FocusState<Bool>().projectedValue,
            isEnabled: true,
            onSend: {},
            onTextChange: {}
        )
    }
    .background(Color.black)
}
