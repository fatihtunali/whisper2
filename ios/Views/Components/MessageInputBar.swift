import SwiftUI
import PhotosUI

/// Message input bar with attachments, voice recording, and location
struct MessageInputBar: View {
    @Binding var text: String
    var isFocused: FocusState<Bool>.Binding
    var isEnabled: Bool = true
    var enterToSend: Bool = true
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
    @State private var pendingAttachment: PendingAttachment?
    @State private var showFullPreview = false

    @StateObject private var audioService = AudioMessageService.shared

    private var canSend: Bool {
        isEnabled && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var showVoiceButton: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && pendingVoiceMessage == nil && pendingAttachment == nil
    }

    var body: some View {
        VStack(spacing: 0) {
            // Attachment preview (before send)
            if let attachment = pendingAttachment {
                AttachmentPreviewBar(
                    attachment: attachment,
                    onCancel: {
                        // Clean up temp file
                        try? FileManager.default.removeItem(at: attachment.url)
                        pendingAttachment = nil
                    },
                    onSend: {
                        onSendAttachment?(attachment.url)
                        pendingAttachment = nil
                    }
                )
                .onTapGesture {
                    showFullPreview = true
                }
            }

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

            // Enhanced recording indicator with waveform animation
            if audioService.isRecording {
                VoiceRecordingOverlay(
                    audioService: audioService,
                    dragOffset: 0,
                    cancelThreshold: -100
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
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
                        .onSubmit {
                            if enterToSend && canSend {
                                onSend()
                            }
                        }
                        .submitLabel(enterToSend ? .send : .return)
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
                if let item = newItem {
                    // Create pending attachment with preview instead of sending directly
                    if let attachment = await AttachmentHelper.createFromPhotosPickerItem(item) {
                        await MainActor.run {
                            pendingAttachment = attachment
                        }
                    }
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
                // Create pending attachment with preview instead of sending directly
                if let attachment = AttachmentHelper.createFromDocumentURL(url) {
                    pendingAttachment = attachment
                }
            }
        }
        .fullScreenCover(isPresented: $showFullPreview) {
            if let attachment = pendingAttachment {
                FullAttachmentPreview(
                    attachment: attachment,
                    isPresented: $showFullPreview,
                    onSend: {
                        onSendAttachment?(attachment.url)
                        pendingAttachment = nil
                    }
                )
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
            enterToSend: true,
            onSend: {},
            onTextChange: {}
        )
        MessageInputBar(
            text: .constant(""),
            isFocused: FocusState<Bool>().projectedValue,
            isEnabled: true,
            enterToSend: false,
            onSend: {},
            onTextChange: {}
        )
    }
    .background(Color.black)
}
