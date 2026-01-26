import SwiftUI
import Observation

/// UI Model representing a conversation for display
struct ConversationUI: Identifiable, Equatable, Hashable {
    let id: String
    let participantId: String
    let participantName: String
    let participantAvatarURL: URL?
    var lastMessage: String?
    var lastMessageTimestamp: Date?
    var unreadCount: Int
    var isOnline: Bool
    var isTyping: Bool

    /// Participant's X25519 public key for encryption (base64)
    var participantEncPublicKey: String?

    static func == (lhs: ConversationUI, rhs: ConversationUI) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }

    /// Get participant's public key as Data
    var participantPublicKeyData: Data? {
        guard let keyString = participantEncPublicKey else { return nil }
        return Data(base64Encoded: keyString)
    }
}

/// Stored conversation for persistence
private struct StoredConversation: Codable {
    let id: String
    let participantId: String
    let participantName: String
    let participantAvatarURL: String?
    var lastMessage: String?
    var lastMessageTimestamp: Date?
    var unreadCount: Int
    var isOnline: Bool
    var participantEncPublicKey: String?
}

/// Manages the conversations list - connected to real server
@Observable
final class ChatsViewModel {
    // MARK: - State

    var conversations: [ConversationUI] = []
    var filteredConversations: [ConversationUI] = []
    var searchText: String = "" {
        didSet { filterConversations() }
    }
    var isLoading = false
    var error: String?
    var isConnected = false

    private var readNotificationObserver: NSObjectProtocol?
    private var messageSentObserver: NSObjectProtocol?
    private let conversationsStorageKey = "whisper2.conversations.list"

    // Sort options
    enum SortOption {
        case recent
        case unread
        case alphabetical
    }
    var sortOption: SortOption = .recent {
        didSet { sortConversations() }
    }

    // MARK: - Dependencies

    private let connectionManager = AppConnectionManager.shared
    private let keychain = KeychainService.shared

    // MARK: - Init

    init() {
        setupNotificationObservers()
    }

    deinit {
        if let observer = readNotificationObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = messageSentObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func setupNotificationObservers() {
        // Listen for conversation read notifications from ChatViewModel
        readNotificationObserver = NotificationCenter.default.addObserver(
            forName: .conversationRead,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let conversationId = notification.object as? String else { return }
            self?.handleConversationRead(conversationId)
        }

        // Listen for message sent notifications to add/update conversations
        messageSentObserver = NotificationCenter.default.addObserver(
            forName: .messageSent,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let info = notification.object as? MessageSentInfo else { return }
            self?.handleMessageSent(info)
        }
    }

    private func handleConversationRead(_ conversationId: String) {
        // Match by either id or participantId for flexibility
        if let index = conversations.firstIndex(where: { $0.id == conversationId || $0.participantId == conversationId }) {
            conversations[index].unreadCount = 0
            filterConversations()
            saveConversationsToLocalStorage()
        }
    }

    private func handleMessageSent(_ info: MessageSentInfo) {
        // Always use participantId as the conversation id for consistency
        // This ensures messages are stored/retrieved with the same key regardless of entry point
        let conversationId = info.participantId

        if let index = conversations.firstIndex(where: { $0.participantId == info.participantId }) {
            // Update existing conversation
            conversations[index].lastMessage = info.lastMessage
            conversations[index].lastMessageTimestamp = info.timestamp
        } else {
            // Create new conversation - use participantId as id for consistency
            let newConversation = ConversationUI(
                id: conversationId,
                participantId: info.participantId,
                participantName: info.participantName,
                participantAvatarURL: info.participantAvatarURL,
                lastMessage: info.lastMessage,
                lastMessageTimestamp: info.timestamp,
                unreadCount: 0,
                isOnline: false,
                isTyping: false,
                participantEncPublicKey: info.participantEncPublicKey
            )
            conversations.append(newConversation)
        }
        filterConversations()
        saveConversationsToLocalStorage()
    }

    // MARK: - Computed Properties

    var hasUnread: Bool {
        conversations.contains { $0.unreadCount > 0 }
    }

    var totalUnreadCount: Int {
        conversations.reduce(0) { $0 + $1.unreadCount }
    }

    var myWhisperId: String? {
        keychain.whisperId
    }

    // MARK: - Actions

    func loadConversations() {
        isLoading = true
        error = nil

        Task { @MainActor in
            // 1. First load from local storage (persisted conversations)
            loadConversationsFromLocalStorage()

            // Set up message callback
            connectionManager.onMessageReceived = { [weak self] message in
                self?.handleNewMessage(message)
            }

            // Connect to server
            await connectionManager.connect()

            // Update connection state
            isConnected = connectionManager.connectionState.isConnected

            // 2. Merge any pending messages from server into conversations
            updateConversationsFromMessages()

            // Fetch public keys for any conversations missing them
            fetchMissingPublicKeys()

            // 3. Save updated conversations
            saveConversationsToLocalStorage()

            isLoading = false

            if let lastError = connectionManager.lastError {
                self.error = lastError
            }
        }
    }

    func refreshConversations() async {
        isLoading = true

        // Reconnect to fetch fresh messages
        await connectionManager.disconnect()
        await connectionManager.connect()

        isConnected = connectionManager.connectionState.isConnected
        updateConversationsFromMessages()

        isLoading = false
    }

    private func handleNewMessage(_ message: PendingMessage) {
        // Find or create conversation for this sender
        if let index = conversations.firstIndex(where: { $0.participantId == message.from }) {
            // Update existing conversation
            conversations[index].lastMessage = message.decodedText ?? "[Encrypted]"
            conversations[index].lastMessageTimestamp = message.timestampDate
            conversations[index].unreadCount += 1
        } else {
            // Create new conversation
            let conversation = ConversationUI(
                id: message.from,
                participantId: message.from,
                participantName: message.from, // Use WhisperID as name for now
                participantAvatarURL: nil,
                lastMessage: message.decodedText ?? "[Encrypted]",
                lastMessageTimestamp: message.timestampDate,
                unreadCount: 1,
                isOnline: true,
                isTyping: false
            )
            conversations.append(conversation)

            // Fetch public key for the new conversation
            Task { @MainActor in
                if let publicKey = await fetchUserPublicKey(message.from) {
                    if let idx = conversations.firstIndex(where: { $0.participantId == message.from }) {
                        conversations[idx].participantEncPublicKey = publicKey
                        filterConversations()
                        saveConversationsToLocalStorage()
                    }
                }
            }
        }
        filterConversations()
        saveConversationsToLocalStorage()
    }

    private func updateConversationsFromMessages() {
        // Merge pending messages into existing conversations
        for message in connectionManager.pendingMessages {
            let senderId = message.from

            if let index = conversations.firstIndex(where: { $0.participantId == senderId }) {
                // Update existing conversation if this message is newer
                if message.timestampDate > (conversations[index].lastMessageTimestamp ?? .distantPast) {
                    conversations[index].lastMessage = message.decodedText ?? "[Encrypted]"
                    conversations[index].lastMessageTimestamp = message.timestampDate
                }
                // Note: unreadCount is managed separately via markAsRead
            } else {
                // Create new conversation for this sender
                let newConversation = ConversationUI(
                    id: senderId,
                    participantId: senderId,
                    participantName: senderId,
                    participantAvatarURL: nil,
                    lastMessage: message.decodedText ?? "[Encrypted]",
                    lastMessageTimestamp: message.timestampDate,
                    unreadCount: 1,
                    isOnline: false,
                    isTyping: false
                )
                conversations.append(newConversation)
            }
        }

        filterConversations()
    }

    func markAsRead(_ conversation: ConversationUI) {
        // Match by participantId for consistency
        guard let index = conversations.firstIndex(where: { $0.participantId == conversation.participantId }) else { return }
        conversations[index].unreadCount = 0
        filterConversations()
        saveConversationsToLocalStorage()
    }

    func deleteConversation(_ conversation: ConversationUI) {
        conversations.removeAll { $0.participantId == conversation.participantId }
        filterConversations()
        saveConversationsToLocalStorage()

        // Also delete messages for this conversation (use participantId as that's the storage key)
        let messagesKey = "whisper2.messages.\(conversation.participantId)"
        UserDefaults.standard.removeObject(forKey: messagesKey)
    }

    func archiveConversation(_ conversation: ConversationUI) {
        deleteConversation(conversation)
    }

    // MARK: - Local Storage

    private func loadConversationsFromLocalStorage() {
        guard let data = UserDefaults.standard.data(forKey: conversationsStorageKey),
              let stored = try? JSONDecoder().decode([StoredConversation].self, from: data) else {
            return
        }

        conversations = stored.map { stored in
            ConversationUI(
                id: stored.id,
                participantId: stored.participantId,
                participantName: stored.participantName,
                participantAvatarURL: stored.participantAvatarURL.flatMap { URL(string: $0) },
                lastMessage: stored.lastMessage,
                lastMessageTimestamp: stored.lastMessageTimestamp,
                unreadCount: stored.unreadCount,
                isOnline: stored.isOnline,
                isTyping: false,
                participantEncPublicKey: stored.participantEncPublicKey
            )
        }
        filterConversations()
    }

    private func saveConversationsToLocalStorage() {
        let stored = conversations.map { conv in
            StoredConversation(
                id: conv.id,
                participantId: conv.participantId,
                participantName: conv.participantName,
                participantAvatarURL: conv.participantAvatarURL?.absoluteString,
                lastMessage: conv.lastMessage,
                lastMessageTimestamp: conv.lastMessageTimestamp,
                unreadCount: conv.unreadCount,
                isOnline: conv.isOnline,
                participantEncPublicKey: conv.participantEncPublicKey
            )
        }

        if let data = try? JSONEncoder().encode(stored) {
            UserDefaults.standard.set(data, forKey: conversationsStorageKey)
            UserDefaults.standard.synchronize()  // Force immediate save
        }
    }

    // MARK: - Private Methods

    private func filterConversations() {
        if searchText.isEmpty {
            filteredConversations = conversations
        } else {
            filteredConversations = conversations.filter { conversation in
                conversation.participantName.localizedCaseInsensitiveContains(searchText) ||
                (conversation.lastMessage?.localizedCaseInsensitiveContains(searchText) ?? false)
            }
        }
        sortConversations()
    }

    private func sortConversations() {
        switch sortOption {
        case .recent:
            filteredConversations.sort { ($0.lastMessageTimestamp ?? .distantPast) > ($1.lastMessageTimestamp ?? .distantPast) }
        case .unread:
            filteredConversations.sort { $0.unreadCount > $1.unreadCount }
        case .alphabetical:
            filteredConversations.sort { $0.participantName < $1.participantName }
        }
    }

    func clearError() {
        error = nil
    }

    // MARK: - Fetch Public Key

    /// Fetch a user's public key from the server
    private func fetchUserPublicKey(_ whisperId: String) async -> String? {
        let urlString = "\(Constants.Server.baseURL)/users/\(whisperId)/keys"
        guard let url = URL(string: urlString) else { return nil }

        // Get session token for authentication
        guard let sessionToken = SessionManager.shared.sessionToken else {
            logger.warning("Cannot fetch public key: not authenticated", category: .network)
            return nil
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                return nil
            }

            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let encPublicKey = json["encPublicKey"] as? String else {
                return nil
            }

            return encPublicKey
        } catch {
            logger.error(error, message: "Failed to fetch public key for \(whisperId)", category: .network)
            return nil
        }
    }

    /// Fetch public keys for all conversations that don't have them
    func fetchMissingPublicKeys() {
        Task { @MainActor in
            for i in conversations.indices {
                if conversations[i].participantEncPublicKey == nil {
                    if let publicKey = await fetchUserPublicKey(conversations[i].participantId) {
                        conversations[i].participantEncPublicKey = publicKey
                        logger.debug("Fetched public key for \(conversations[i].participantId)", category: .network)
                    }
                }
            }
            filterConversations()
        }
    }
}
