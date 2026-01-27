import Foundation
import Combine

/// Messaging service for sending and receiving messages
final class MessagingService: ObservableObject {
    static let shared = MessagingService()
    
    @Published var conversations: [Conversation] = []
    @Published var messages: [String: [Message]] = [:] // conversationId -> messages
    
    private let ws = WebSocketService.shared
    private let auth = AuthService.shared
    private let keychain = KeychainService.shared
    private let contactsService = ContactsService.shared
    private let crypto = CryptoService.shared
    private var cancellables = Set<AnyCancellable>()
    
    private let messageReceivedSubject = PassthroughSubject<Message, Never>()
    var messageReceivedPublisher: AnyPublisher<Message, Never> {
        messageReceivedSubject.eraseToAnyPublisher()
    }
    
    private let messageRequestSubject = PassthroughSubject<MessageRequest, Never>()
    var messageRequestPublisher: AnyPublisher<MessageRequest, Never> {
        messageRequestSubject.eraseToAnyPublisher()
    }

    private let messagesStorageKey = "whisper2.messages.data"
    private let conversationsStorageKey = "whisper2.conversations.data"

    private init() {
        loadFromStorage()
        setupMessageHandler()
        setupRequestAcceptedHandler()
    }

    // MARK: - Persistence

    private func loadFromStorage() {
        // Load messages
        if let data = keychain.getData(forKey: messagesStorageKey),
           let decoded = try? JSONDecoder().decode([String: [Message]].self, from: data) {
            messages = decoded
        }

        // Load conversations
        if let data = keychain.getData(forKey: conversationsStorageKey),
           let decoded = try? JSONDecoder().decode([Conversation].self, from: data) {
            conversations = decoded
        }
    }

    private func saveMessagesToStorage() {
        if let data = try? JSONEncoder().encode(messages) {
            keychain.setData(data, forKey: messagesStorageKey)
        }
    }

    private func saveConversationsToStorage() {
        if let data = try? JSONEncoder().encode(conversations) {
            keychain.setData(data, forKey: conversationsStorageKey)
        }
    }
    
    private func setupMessageHandler() {
        ws.messagePublisher
            .sink { [weak self] data in
                self?.handleMessage(data)
            }
            .store(in: &cancellables)
    }
    
    private func setupRequestAcceptedHandler() {
        // Listen for when a message request is accepted
        NotificationCenter.default.publisher(for: NSNotification.Name("ProcessPendingMessages"))
            .sink { [weak self] notification in
                if let senderId = notification.userInfo?["senderId"] as? String {
                    self?.processPendingMessages(from: senderId)
                }
            }
            .store(in: &cancellables)
    }
    
    // MARK: - Send Message
    
    func sendMessage(to recipientId: String, content: String, recipientPublicKey: Data) async throws -> Message {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            throw NetworkError.connectionFailed
        }
        
        // Check if blocked
        guard !contactsService.isBlocked(whisperId: recipientId) else {
            throw NetworkError.serverError(code: "BLOCKED", message: "Cannot send to blocked user")
        }
        
        // Validate public key
        guard recipientPublicKey.count == 32, recipientPublicKey != Data(repeating: 0, count: 32) else {
            throw CryptoError.invalidPublicKey
        }
        
        let messageId = UUID().uuidString.lowercased()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        
        // Encrypt message
        let (ciphertext, nonce) = try crypto.encryptMessage(
            content,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: user.encPrivateKey
        )
        
        // Sign message
        let signature = try crypto.signMessage(
            messageType: Constants.MessageType.sendMessage,
            messageId: messageId,
            from: user.whisperId,
            to: recipientId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: user.signPrivateKey
        )
        
        // Build payload
        let payload = SendMessagePayload(
            sessionToken: sessionToken,
            messageId: messageId,
            from: user.whisperId,
            to: recipientId,
            msgType: "text",
            timestamp: timestamp,
            nonce: nonce.base64EncodedString(),
            ciphertext: ciphertext.base64EncodedString(),
            sig: signature.base64EncodedString()
        )
        
        let frame = WsFrame(type: Constants.MessageType.sendMessage, payload: payload, requestId: messageId)
        try await ws.send(frame)
        
        // Create local message
        let message = Message(
            id: messageId,
            conversationId: recipientId,
            from: user.whisperId,
            to: recipientId,
            content: content,
            contentType: "text",
            timestamp: Date(timeIntervalSince1970: Double(timestamp) / 1000),
            status: .pending,
            direction: .outgoing
        )
        
        // Add to local messages
        await MainActor.run {
            if self.messages[recipientId] == nil {
                self.messages[recipientId] = []
            }
            self.messages[recipientId]?.append(message)
            self.updateConversation(for: recipientId, lastMessage: content)
            self.saveMessagesToStorage()
            self.saveConversationsToStorage()
        }

        return message
    }

    func sendMessage(to recipientId: String, content: String) async throws -> Message {
        guard let publicKey = contactsService.getPublicKey(for: recipientId) else {
            throw CryptoError.invalidPublicKey
        }
        return try await sendMessage(to: recipientId, content: content, recipientPublicKey: publicKey)
    }

    /// Send message with specified content type (audio, location, image, video, file)
    func sendMessage(to recipientId: String, content: String, contentType: String) async throws -> Message {
        guard let publicKey = contactsService.getPublicKey(for: recipientId) else {
            throw CryptoError.invalidPublicKey
        }
        return try await sendMessage(to: recipientId, content: content, contentType: contentType, recipientPublicKey: publicKey)
    }

    /// Send message with content type and public key
    func sendMessage(to recipientId: String, content: String, contentType: String, recipientPublicKey: Data) async throws -> Message {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            throw NetworkError.connectionFailed
        }

        guard !contactsService.isBlocked(whisperId: recipientId) else {
            throw NetworkError.serverError(code: "BLOCKED", message: "Cannot send to blocked user")
        }

        guard recipientPublicKey.count == 32, recipientPublicKey != Data(repeating: 0, count: 32) else {
            throw CryptoError.invalidPublicKey
        }

        let messageId = UUID().uuidString.lowercased()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        let (ciphertext, nonce) = try crypto.encryptMessage(
            content,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: user.encPrivateKey
        )

        let signature = try crypto.signMessage(
            messageType: Constants.MessageType.sendMessage,
            messageId: messageId,
            from: user.whisperId,
            to: recipientId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: user.signPrivateKey
        )

        let payload = SendMessagePayload(
            sessionToken: sessionToken,
            messageId: messageId,
            from: user.whisperId,
            to: recipientId,
            msgType: contentType,
            timestamp: timestamp,
            nonce: nonce.base64EncodedString(),
            ciphertext: ciphertext.base64EncodedString(),
            sig: signature.base64EncodedString()
        )

        let frame = WsFrame(type: Constants.MessageType.sendMessage, payload: payload, requestId: messageId)
        try await ws.send(frame)

        let message = Message(
            id: messageId,
            conversationId: recipientId,
            from: user.whisperId,
            to: recipientId,
            content: content,
            contentType: contentType,
            timestamp: Date(timeIntervalSince1970: Double(timestamp) / 1000),
            status: .pending,
            direction: .outgoing
        )

        await MainActor.run {
            if self.messages[recipientId] == nil {
                self.messages[recipientId] = []
            }
            self.messages[recipientId]?.append(message)
            self.updateConversation(for: recipientId, lastMessage: content)
            self.saveMessagesToStorage()
            self.saveConversationsToStorage()
        }

        return message
    }

    // MARK: - Delivery Receipt
    
    func sendDeliveryReceipt(messageId: String, from senderId: String, status: String) async throws {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else { return }
        
        let payload = DeliveryReceiptPayload(
            sessionToken: sessionToken,
            messageId: messageId,
            from: user.whisperId,
            to: senderId,
            status: status,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000)
        )
        
        let frame = WsFrame(type: Constants.MessageType.deliveryReceipt, payload: payload)
        try await ws.send(frame)
    }
    
    // MARK: - Typing Indicator
    
    func sendTypingIndicator(to recipientId: String, isTyping: Bool) async throws {
        guard let sessionToken = auth.currentUser?.sessionToken else { return }
        
        let payload = TypingPayload(
            sessionToken: sessionToken,
            to: recipientId,
            isTyping: isTyping
        )
        
        let frame = WsFrame(type: Constants.MessageType.typing, payload: payload)
        try await ws.send(frame)
    }
    
    // MARK: - Delete Message

    /// Delete a message locally and optionally for everyone
    func deleteMessage(messageId: String, conversationId: String, deleteForEveryone: Bool) async throws {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            throw NetworkError.connectionFailed
        }

        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        // Sign the delete request
        let signData = "delete|\(messageId)|\(conversationId)|\(deleteForEveryone)|\(timestamp)"
        let signature = try crypto.signChallenge(Data(signData.utf8), privateKey: user.signPrivateKey)

        let payload = DeleteMessagePayload(
            sessionToken: sessionToken,
            messageId: messageId,
            conversationId: conversationId,
            deleteForEveryone: deleteForEveryone,
            timestamp: timestamp,
            sig: signature.base64EncodedString()
        )

        let frame = WsFrame(type: Constants.MessageType.deleteMessage, payload: payload)
        try await ws.send(frame)

        // Delete locally immediately
        await MainActor.run {
            deleteLocalMessage(messageId: messageId, conversationId: conversationId)
        }
    }

    /// Delete a message locally only
    func deleteLocalMessage(messageId: String, conversationId: String) {
        if var convoMessages = messages[conversationId] {
            convoMessages.removeAll { $0.id == messageId }
            messages[conversationId] = convoMessages
            saveMessagesToStorage()
        }
    }

    // MARK: - Fetch Pending

    func fetchPendingMessages() async throws {
        guard let sessionToken = auth.currentUser?.sessionToken else { return }

        let payload = FetchPendingPayload(sessionToken: sessionToken)
        let frame = WsFrame(type: Constants.MessageType.fetchPending, payload: payload)
        try await ws.send(frame)
    }
    
    // MARK: - Message Handling
    
    private func handleMessage(_ data: Data) {
        guard let raw = try? JSONDecoder().decode(RawWsFrame.self, from: data) else { return }
        
        switch raw.type {
        case Constants.MessageType.messageReceived:
            handleMessageReceived(data)
            
        case Constants.MessageType.messageAccepted:
            handleMessageAccepted(data)
            
        case Constants.MessageType.messageDelivered:
            handleMessageDelivered(data)
            
        case Constants.MessageType.pendingMessages:
            handlePendingMessages(data)
            
        case Constants.MessageType.typingNotification:
            handleTypingNotification(data)

        case Constants.MessageType.messageDeleted:
            handleMessageDeleted(data)

        default:
            break
        }
    }
    
    private func handleMessageReceived(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<MessageReceivedPayload>.self, from: data),
              let user = auth.currentUser else { return }
        
        let payload = frame.payload
        
        // Check if sender is blocked
        guard !contactsService.isBlocked(whisperId: payload.from) else {
            // Silently ignore messages from blocked users
            return
        }
        
        // Check if sender is a known contact
        let isKnownContact = contactsService.hasContact(whisperId: payload.from)
        
        if !isKnownContact {
            // Unknown sender - create message request
            handleUnknownSenderMessage(payload)
            return
        }
        
        // Known contact - process message normally
        processReceivedMessage(payload, user: user)
    }
    
    private func handleUnknownSenderMessage(_ payload: MessageReceivedPayload) {
        // Store as message request
        contactsService.addMessageRequest(
            senderId: payload.from,
            messageId: payload.messageId,
            payload: payload
        )
        
        // Notify UI about new message request
        if let request = contactsService.messageRequests[payload.from] {
            DispatchQueue.main.async {
                self.messageRequestSubject.send(request)
            }
        }
        
        // Still send delivery receipt to let sender know it arrived
        Task {
            try? await sendDeliveryReceipt(messageId: payload.messageId, from: payload.from, status: "delivered")
        }
    }
    
    private func processReceivedMessage(_ payload: MessageReceivedPayload, user: LocalUser) {
        // Decode ciphertext and nonce
        guard let ciphertextData = Data(base64Encoded: payload.ciphertext),
              let nonceData = Data(base64Encoded: payload.nonce) else { return }
        
        // Try to decrypt message
        var decryptedContent = "[Unable to decrypt]"
        
        if let senderPublicKey = contactsService.getPublicKey(for: payload.from),
           senderPublicKey != Data(repeating: 0, count: 32) {
            do {
                decryptedContent = try crypto.decryptMessage(
                    ciphertext: ciphertextData,
                    nonce: nonceData,
                    senderPublicKey: senderPublicKey,
                    recipientPrivateKey: user.encPrivateKey
                )
            } catch {
                print("Decryption failed: \(error)")
                decryptedContent = "[Decryption failed]"
            }
        } else {
            decryptedContent = "[Scan QR to decrypt]"
        }
        
        let message = Message(
            id: payload.messageId,
            conversationId: payload.from,
            from: payload.from,
            to: payload.to,
            content: decryptedContent,
            contentType: payload.msgType,
            timestamp: Date(timeIntervalSince1970: Double(payload.timestamp) / 1000),
            status: .delivered,
            direction: .incoming,
            replyToId: payload.replyTo
        )
        
        DispatchQueue.main.async {
            if self.messages[payload.from] == nil {
                self.messages[payload.from] = []
            }
            self.messages[payload.from]?.append(message)

            let previewContent = decryptedContent.hasPrefix("[") ? "New message" : decryptedContent
            self.updateConversation(for: payload.from, lastMessage: previewContent, unreadIncrement: 1)
            self.messageReceivedSubject.send(message)

            // Save to storage
            self.saveMessagesToStorage()
            self.saveConversationsToStorage()
        }

        // Send delivery receipt
        Task {
            try? await sendDeliveryReceipt(messageId: payload.messageId, from: payload.from, status: "delivered")
        }
    }
    
    /// Process pending messages after accepting a request
    private func processPendingMessages(from senderId: String) {
        guard let user = auth.currentUser else { return }
        
        let pendingMsgs = contactsService.getPendingMessages(for: senderId)
        
        for payload in pendingMsgs {
            processReceivedMessage(payload, user: user)
        }
        
        // Clear the pending messages
        contactsService.clearPendingMessages(for: senderId)
    }
    
    private func handleMessageAccepted(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<MessageAcceptedPayload>.self, from: data) else { return }
        
        let messageId = frame.payload.messageId
        
        DispatchQueue.main.async {
            for (convId, msgs) in self.messages {
                if let index = msgs.firstIndex(where: { $0.id == messageId }) {
                    self.messages[convId]?[index].status = .sent
                    self.saveMessagesToStorage()
                    break
                }
            }
        }
    }
    
    private func handleMessageDelivered(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<MessageDeliveredPayload>.self, from: data) else { return }

        let payload = frame.payload
        let status: MessageStatus = payload.status == "read" ? .read : .delivered

        DispatchQueue.main.async {
            for (convId, msgs) in self.messages {
                if let index = msgs.firstIndex(where: { $0.id == payload.messageId }) {
                    self.messages[convId]?[index].status = status
                    self.saveMessagesToStorage()
                    break
                }
            }
        }
    }
    
    private func handlePendingMessages(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<PendingMessagesPayload>.self, from: data) else { return }
        
        for msg in frame.payload.messages {
            if let encodedData = try? JSONEncoder().encode(WsFrame(type: Constants.MessageType.messageReceived, payload: msg)) {
                handleMessageReceived(encodedData)
            }
        }
    }
    
    private func handleTypingNotification(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<TypingNotificationPayload>.self, from: data) else { return }

        let senderId = frame.payload.from
        let isTyping = frame.payload.isTyping

        // Ignore typing from blocked or unknown users
        guard !contactsService.isBlocked(whisperId: senderId),
              contactsService.hasContact(whisperId: senderId) else { return }

        DispatchQueue.main.async {
            if let index = self.conversations.firstIndex(where: { $0.peerId == senderId }) {
                self.conversations[index].isTyping = isTyping
            }
        }
    }

    private func handleMessageDeleted(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<MessageDeletedPayload>.self, from: data) else { return }

        let payload = frame.payload

        // If deleteForEveryone, remove the message locally
        if payload.deleteForEveryone {
            DispatchQueue.main.async {
                self.deleteLocalMessage(messageId: payload.messageId, conversationId: payload.conversationId)
            }
        }
    }

    // MARK: - Helpers
    
    private func updateConversation(for peerId: String, lastMessage: String, unreadIncrement: Int = 0) {
        if let index = conversations.firstIndex(where: { $0.peerId == peerId }) {
            conversations[index].lastMessage = lastMessage
            conversations[index].lastMessageTime = Date()
            conversations[index].unreadCount += unreadIncrement
            conversations[index].updatedAt = Date()
        } else {
            // Get contact info for display name
            let contact = contactsService.getContact(whisperId: peerId)
            let conv = Conversation(
                peerId: peerId,
                peerNickname: contact?.nickname,
                lastMessage: lastMessage,
                lastMessageTime: Date(),
                unreadCount: unreadIncrement
            )
            conversations.insert(conv, at: 0)
        }
        
        conversations.sort { ($0.lastMessageTime ?? .distantPast) > ($1.lastMessageTime ?? .distantPast) }
    }
    
    func markAsRead(conversationId: String) {
        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            conversations[index].unreadCount = 0
        }
        
        if let msgs = messages[conversationId] {
            let unreadMessages = msgs.filter { $0.direction == .incoming && $0.status == .delivered }
            for msg in unreadMessages {
                Task {
                    try? await sendDeliveryReceipt(messageId: msg.id, from: msg.from, status: "read")
                }
            }
        }
    }
    
    func getMessages(for conversationId: String) -> [Message] {
        messages[conversationId] ?? []
    }

    // MARK: - Clear All Data

    /// Clear all messaging data (for wipe data feature)
    func clearAllData() {
        messages.removeAll()
        conversations.removeAll()
        keychain.delete(key: messagesStorageKey)
        keychain.delete(key: conversationsStorageKey)
    }
}
