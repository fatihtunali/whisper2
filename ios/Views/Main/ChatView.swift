import SwiftUI

/// Chat/Message thread view
struct ChatView: View {
    let conversation: Conversation
    @StateObject private var viewModel: ChatViewModel
    @FocusState private var isInputFocused: Bool
    @State private var showAddKeyAlert = false
    @State private var showContactProfile = false

    init(conversation: Conversation) {
        self.conversation = conversation
        self._viewModel = StateObject(wrappedValue: ChatViewModel(conversationId: conversation.peerId))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                // Key missing warning
                if !viewModel.canSendMessages {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)
                        Text("Scan contact's QR code to enable messaging")
                            .font(.caption)
                            .foregroundColor(.orange)
                        Spacer()
                        Button("Scan") {
                            showAddKeyAlert = true
                        }
                        .font(.caption)
                        .foregroundColor(.blue)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(Color.orange.opacity(0.1))
                }

                // Messages list
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(viewModel.messages) { message in
                                MessageBubble(message: message) { deleteForEveryone in
                                    viewModel.deleteMessage(messageId: message.id, deleteForEveryone: deleteForEveryone)
                                }
                                .id(message.id)
                            }
                        }
                        .padding(.horizontal)
                        .padding(.top, 10)
                        .padding(.bottom, 10)
                    }
                    .onChange(of: viewModel.messages.count) { _, _ in
                        if let lastMessage = viewModel.messages.last {
                            withAnimation {
                                proxy.scrollTo(lastMessage.id, anchor: .bottom)
                            }
                        }
                    }
                    .onAppear {
                        if let lastMessage = viewModel.messages.last {
                            proxy.scrollTo(lastMessage.id, anchor: .bottom)
                        }
                    }
                }

                // Typing indicator
                if conversation.isTyping {
                    HStack {
                        TypingIndicatorView()
                        Text("\(viewModel.contactName) is typing...")
                            .font(.caption)
                            .foregroundColor(.gray)
                            .italic()
                        Spacer()
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 4)
                }

                // Error display
                if let error = viewModel.error {
                    HStack {
                        Image(systemName: "exclamationmark.circle.fill")
                            .foregroundColor(.red)
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.red)
                        Spacer()
                        Button("Dismiss") {
                            viewModel.error = nil
                        }
                        .font(.caption)
                        .foregroundColor(.gray)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(Color.red.opacity(0.1))
                }

                // Input bar with all features
                MessageInputBar(
                    text: $viewModel.messageText,
                    isFocused: $isInputFocused,
                    isEnabled: viewModel.canSendMessages,
                    onSend: viewModel.sendMessage,
                    onTextChange: viewModel.textChanged,
                    onSendVoice: { url, duration in
                        viewModel.sendVoiceMessage(url: url, duration: duration)
                    },
                    onSendLocation: { location in
                        viewModel.sendLocationMessage(location: location)
                    },
                    onSendAttachment: { url in
                        viewModel.sendAttachment(url: url)
                    }
                )
            }
        }
        .navigationTitle(viewModel.contactName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 16) {
                    // Voice call button
                    Button(action: { viewModel.startVoiceCall() }) {
                        Image(systemName: "phone.fill")
                    }
                    .disabled(!viewModel.canSendMessages)
                    .opacity(viewModel.canSendMessages ? 1.0 : 0.4)

                    // Video call button
                    Button(action: { viewModel.startVideoCall() }) {
                        Image(systemName: "video.fill")
                    }
                    .disabled(!viewModel.canSendMessages)
                    .opacity(viewModel.canSendMessages ? 1.0 : 0.4)

                    // More options
                    Menu {
                        Button(action: { showContactProfile = true }) {
                            Label("View Profile", systemImage: "person.circle")
                        }
                        Button(action: {}) {
                            Label("Search Messages", systemImage: "magnifyingglass")
                        }
                        Button(action: {}) {
                            Label("Mute Notifications", systemImage: "bell.slash")
                        }
                        Divider()
                        Button(role: .destructive, action: {}) {
                            Label("Clear Chat", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .onAppear {
            viewModel.markAsRead()
        }
        .sheet(isPresented: $showAddKeyAlert) {
            QRScannerSheet(peerId: conversation.peerId)
        }
        .sheet(isPresented: $showContactProfile) {
            ContactProfileView(peerId: conversation.peerId)
        }
    }
}

/// Typing indicator animation
struct TypingIndicatorView: View {
    @State private var animating = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3) { index in
                Circle()
                    .fill(Color.gray)
                    .frame(width: 6, height: 6)
                    .scaleEffect(animating ? 1.0 : 0.5)
                    .animation(
                        .easeInOut(duration: 0.6)
                        .repeatForever()
                        .delay(Double(index) * 0.2),
                        value: animating
                    )
            }
        }
        .onAppear {
            animating = true
        }
    }
}

/// QR Scanner sheet for adding key to existing contact
struct QRScannerSheet: View {
    let peerId: String
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ZStack {
                QRScannerView { result in
                    handleScan(result)
                }

                if let error = error {
                    VStack {
                        Spacer()
                        Text(error)
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.red.opacity(0.8))
                            .cornerRadius(8)
                            .padding()
                    }
                }
            }
            .navigationTitle("Scan QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.white)
                }
            }
        }
    }

    private func handleScan(_ result: String) {
        // Parse QR code
        guard let url = URL(string: result),
              url.scheme == "whisper2",
              url.host == "add",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            error = "Invalid QR code"
            return
        }

        guard let idItem = queryItems.first(where: { $0.name == "id" }),
              let scannedId = idItem.value else {
            error = "QR code missing Whisper ID"
            return
        }

        // Verify it's the right contact
        guard scannedId == peerId else {
            error = "QR code is for a different contact (\(scannedId))"
            return
        }

        guard let keyItem = queryItems.first(where: { $0.name == "key" }),
              let keyBase64 = keyItem.value,
              let publicKey = Data(base64Encoded: keyBase64),
              publicKey.count == 32 else {
            error = "Invalid public key in QR code"
            return
        }

        // Update contact with public key
        ContactsService.shared.updateContactPublicKey(whisperId: peerId, encPublicKey: publicKey)
        dismiss()
    }
}

#Preview {
    NavigationStack {
        ChatView(conversation: Conversation(
            peerId: "WSP-TEST-1234-5678",
            peerNickname: "Alice"
        ))
    }
}
