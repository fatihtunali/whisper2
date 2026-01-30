import SwiftUI

/// Chat/Message thread view
struct ChatView: View {
    let conversation: Conversation
    @StateObject private var viewModel: ChatViewModel
    @ObservedObject private var messagingService = MessagingService.shared
    @ObservedObject private var webSocket = WebSocketService.shared
    @ObservedObject private var settingsManager = AppSettingsManager.shared
    @FocusState private var isInputFocused: Bool
    @State private var showAddKeyAlert = false
    @State private var showContactProfile = false
    @State private var showDisappearingSettings = false
    @State private var showChatTheme = false

    // Search state
    @State private var isSearching = false
    @State private var searchText = ""
    @State private var searchResultIndex = 0
    @State private var scrollToMessageId: String?

    private var theme: ChatTheme {
        messagingService.getChatTheme(for: conversation.peerId)
    }

    private var disappearingTimer: DisappearingMessageTimer {
        messagingService.getDisappearingMessageTimer(for: conversation.peerId)
    }

    private var isMuted: Bool {
        messagingService.conversations.first { $0.peerId == conversation.peerId }?.isMuted ?? false
    }

    // Search results - filter messages containing search text
    private var searchResults: [Message] {
        guard !searchText.isEmpty else { return [] }
        return viewModel.messages.filter { message in
            message.content.containsIgnoringCase(searchText)
        }
    }

    private var connectionStatusColor: Color {
        switch webSocket.connectionState {
        case .connected: return .green
        case .connecting, .reconnecting: return .orange
        case .disconnected: return .red
        }
    }

    private var connectionStatusText: String {
        switch webSocket.connectionState {
        case .connected: return "Connected"
        case .connecting: return "Connecting..."
        case .reconnecting: return "Reconnecting..."
        case .disconnected: return "Disconnected"
        }
    }

    init(conversation: Conversation) {
        self.conversation = conversation
        self._viewModel = StateObject(wrappedValue: ChatViewModel(conversationId: conversation.peerId))
    }

    var body: some View {
        ZStack {
            theme.backgroundColor.color.ignoresSafeArea()

            VStack(spacing: 0) {
                // Search bar (when searching)
                if isSearching {
                    SearchMessagesBar(
                        searchText: $searchText,
                        isSearching: $isSearching,
                        resultsCount: searchResults.count,
                        currentIndex: searchResultIndex,
                        onPrevious: navigateToPreviousResult,
                        onNext: navigateToNextResult
                    )
                }

                // Connection lost warning
                if webSocket.connectionState == .disconnected {
                    HStack {
                        Image(systemName: "wifi.slash")
                            .foregroundColor(.red)
                        Text("No connection - messages will be sent when reconnected")
                            .font(.caption)
                            .foregroundColor(.red)
                        Spacer()
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(Color.red.opacity(0.1))
                }

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
                                MessageBubbleWithSearch(
                                    message: message,
                                    searchText: isSearching ? searchText : "",
                                    isHighlighted: isSearching && searchResults.indices.contains(searchResultIndex) && searchResults[searchResultIndex].id == message.id,
                                    onDelete: { deleteForEveryone in
                                        viewModel.deleteMessage(messageId: message.id, deleteForEveryone: deleteForEveryone)
                                    },
                                    theme: theme
                                )
                                .id(message.id)
                            }
                        }
                        .padding(.horizontal)
                        .padding(.top, 10)
                        .padding(.bottom, 10)
                    }
                    .onChange(of: viewModel.messages.count) { _, _ in
                        if !isSearching, let lastMessage = viewModel.messages.last {
                            withAnimation {
                                proxy.scrollTo(lastMessage.id, anchor: .bottom)
                            }
                        }
                    }
                    .onChange(of: scrollToMessageId) { _, newId in
                        if let id = newId {
                            withAnimation {
                                proxy.scrollTo(id, anchor: .center)
                            }
                            scrollToMessageId = nil
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
                    enterToSend: settingsManager.settings.enterToSend,
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
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Button(action: { showContactProfile = true }) {
                    HStack(spacing: 6) {
                        VStack(spacing: 2) {
                            Text(viewModel.contactName)
                                .font(.headline)
                                .foregroundColor(.white)
                            // Show connection status if not connected, otherwise show online status
                            if webSocket.connectionState != .connected {
                                HStack(spacing: 4) {
                                    Circle()
                                        .fill(connectionStatusColor)
                                        .frame(width: 6, height: 6)
                                    Text(connectionStatusText)
                                        .font(.caption2)
                                        .foregroundColor(connectionStatusColor)
                                }
                            } else if let contact = ContactsService.shared.getContact(whisperId: conversation.peerId) {
                                Text(contact.isOnline ? "Online" : "Tap for info")
                                    .font(.caption2)
                                    .foregroundColor(contact.isOnline ? .green : .gray)
                            }
                        }

                        // Muted indicator
                        if isMuted {
                            Image(systemName: "bell.slash.fill")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 16) {
                    // Search button
                    Button(action: {
                        withAnimation {
                            isSearching.toggle()
                            if !isSearching {
                                searchText = ""
                                searchResultIndex = 0
                            }
                        }
                    }) {
                        Image(systemName: isSearching ? "xmark" : "magnifyingglass")
                            .foregroundColor(isSearching ? .orange : .white)
                    }

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

                    // More options menu
                    Menu {
                        // View Profile
                        Button(action: { showContactProfile = true }) {
                            Label("View Profile", systemImage: "person.circle")
                        }

                        // Search Messages
                        Button(action: {
                            withAnimation {
                                isSearching = true
                            }
                        }) {
                            Label("Search Messages", systemImage: "magnifyingglass")
                        }

                        // Mute/Unmute Notifications
                        Button(action: toggleMute) {
                            Label(
                                isMuted ? "Unmute Notifications" : "Mute Notifications",
                                systemImage: isMuted ? "bell.fill" : "bell.slash"
                            )
                        }

                        Divider()

                        // Disappearing Messages
                        Button(action: { showDisappearingSettings = true }) {
                            Label(
                                disappearingTimer == .off ? "Disappearing Messages" : "Disappearing: \(disappearingTimer.displayName)",
                                systemImage: disappearingTimer == .off ? "timer" : "timer.circle.fill"
                            )
                        }

                        // Chat Theme
                        Button(action: { showChatTheme = true }) {
                            Label("Chat Theme", systemImage: "paintpalette")
                        }

                        Divider()

                        // Clear Chat
                        Button(role: .destructive, action: { viewModel.clearChat() }) {
                            Label("Clear Chat", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .toolbar(.hidden, for: .tabBar)
        .onAppear {
            viewModel.markAsRead()
        }
        .onChange(of: searchText) { _, _ in
            // Reset search index when search text changes
            searchResultIndex = 0
            navigateToCurrentResult()
        }
        .sheet(isPresented: $showAddKeyAlert) {
            QRScannerSheet(peerId: conversation.peerId)
        }
        .sheet(isPresented: $showContactProfile) {
            NavigationStack {
                ContactProfileView(peerId: conversation.peerId)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") {
                                showContactProfile = false
                            }
                        }
                    }
            }
        }
        .sheet(isPresented: $showDisappearingSettings) {
            NavigationStack {
                DisappearingMessageSettingsView(peerId: conversation.peerId)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") {
                                showDisappearingSettings = false
                            }
                        }
                    }
            }
        }
        .sheet(isPresented: $showChatTheme) {
            NavigationStack {
                ChatThemePickerView(peerId: conversation.peerId)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") {
                                showChatTheme = false
                            }
                        }
                    }
            }
        }
    }

    // MARK: - Search Navigation

    private func navigateToCurrentResult() {
        guard !searchResults.isEmpty, searchResults.indices.contains(searchResultIndex) else { return }
        scrollToMessageId = searchResults[searchResultIndex].id
    }

    private func navigateToPreviousResult() {
        guard !searchResults.isEmpty else { return }
        if searchResultIndex > 0 {
            searchResultIndex -= 1
        } else {
            searchResultIndex = searchResults.count - 1
        }
        scrollToMessageId = searchResults[searchResultIndex].id
    }

    private func navigateToNextResult() {
        guard !searchResults.isEmpty else { return }
        if searchResultIndex < searchResults.count - 1 {
            searchResultIndex += 1
        } else {
            searchResultIndex = 0
        }
        scrollToMessageId = searchResults[searchResultIndex].id
    }

    // MARK: - Mute Toggle

    private func toggleMute() {
        messagingService.toggleMute(for: conversation.peerId)
    }
}

/// Message bubble wrapper that supports search highlighting
struct MessageBubbleWithSearch: View {
    let message: Message
    let searchText: String
    let isHighlighted: Bool
    let onDelete: (Bool) -> Void
    let theme: ChatTheme

    var body: some View {
        MessageBubble(
            message: message,
            searchText: searchText,
            onDelete: onDelete,
            theme: theme
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.yellow, lineWidth: isHighlighted ? 2 : 0)
                .animation(.easeInOut(duration: 0.3), value: isHighlighted)
        )
        .scaleEffect(isHighlighted ? 1.02 : 1.0)
        .animation(.easeInOut(duration: 0.2), value: isHighlighted)
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
