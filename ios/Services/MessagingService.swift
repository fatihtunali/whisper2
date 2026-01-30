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
    private let pushService = PushNotificationService.shared
    private var cancellables = Set<AnyCancellable>()
    
    private let messageReceivedSubject = PassthroughSubject<Message, Never>()
    var messageReceivedPublisher: AnyPublisher<Message, Never> {
        messageReceivedSubject.eraseToAnyPublisher()
    }
    
    private let messageRequestSubject = PassthroughSubject<MessageRequest, Never>()
    var messageRequestPublisher: AnyPublisher<MessageRequest, Never> {
        messageRequestSubject.eraseToAnyPublisher()
    }

    // Typing timeout timers - auto-clear typing status after 5 seconds
    private var typingTimers: [String: Timer] = [:]

    // Disappearing messages cleanup timer
    private var disappearingMessagesTimer: Timer?

    private let messagesStorageKey = "whisper2.messages.data"
    private let conversationsStorageKey = "whisper2.conversations.data"

    // Serial queue for keychain operations to prevent race conditions
    private let storageQueue = DispatchQueue(label: "com.whisper2.messaging.storage", qos: .userInitiated)

    // Debounce timers for saves - prevents rapid saves from crashing
    private var messagesSaveWorkItem: DispatchWorkItem?
    private var conversationsSaveWorkItem: DispatchWorkItem?
    private let saveDebounceInterval: TimeInterval = 0.3 // 300ms debounce

    // Thread-safety locks for concurrent access to messages and conversations
    private let messagesLock = NSLock()
    private let conversationsLock = NSLock()

    private init() {
        loadFromStorage()
        setupMessageHandler()
        setupRequestAcceptedHandler()
        setupConnectionMonitor()
        startDisappearingMessagesTimer()
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

    /// Save messages with debouncing to prevent rapid saves from crashing
    /// Multiple rapid calls will be coalesced into a single save after the debounce interval
    private func saveMessagesToStorage() {
        // Cancel any pending save
        messagesSaveWorkItem?.cancel()

        // Capture current messages (copy for thread safety)
        let messagesToSave = messages

        // Create new work item for debounced save
        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            if let data = try? JSONEncoder().encode(messagesToSave) {
                self.keychain.setData(data, forKey: self.messagesStorageKey)
            }
        }

        messagesSaveWorkItem = workItem

        // Execute after debounce interval on the serial queue
        storageQueue.asyncAfter(deadline: .now() + saveDebounceInterval, execute: workItem)
    }

    /// Save conversations with debouncing to prevent rapid saves from crashing
    private func saveConversationsToStorage() {
        // Cancel any pending save
        conversationsSaveWorkItem?.cancel()

        // Capture current conversations (copy for thread safety)
        let conversationsToSave = conversations

        // Create new work item for debounced save
        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            if let data = try? JSONEncoder().encode(conversationsToSave) {
                self.keychain.setData(data, forKey: self.conversationsStorageKey)
            }
        }

        conversationsSaveWorkItem = workItem

        // Execute after debounce interval on the serial queue
        storageQueue.asyncAfter(deadline: .now() + saveDebounceInterval, execute: workItem)
    }

    /// Force immediate save (for app termination or background)
    func flushPendingSaves() {
        messagesSaveWorkItem?.cancel()
        conversationsSaveWorkItem?.cancel()

        storageQueue.sync {
            if let data = try? JSONEncoder().encode(messages) {
                keychain.setData(data, forKey: messagesStorageKey)
            }
            if let data = try? JSONEncoder().encode(conversations) {
                keychain.setData(data, forKey: conversationsStorageKey)
            }
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

    private func setupConnectionMonitor() {
        // Monitor both WebSocket connection AND auth state
        // Only retry messages when BOTH connected AND authenticated
        // This ensures we have a valid session token before attempting retries
        auth.$isAuthenticated
            .combineLatest(ws.$connectionState)
            .dropFirst() // Skip initial combined value
            .removeDuplicates { (prev, curr) in
                prev.0 == curr.0 && prev.1 == curr.1
            }
            .sink { [weak self] (isAuthenticated, connectionState) in
                if isAuthenticated && connectionState == .connected {
                    print("MessagingService: Connected and authenticated, retrying failed messages...")
                    Task {
                        await self?.retryFailedMessages()
                    }
                }
            }
            .store(in: &cancellables)
    }

    /// Retry all messages that are in failed or pending status
    private func retryFailedMessages() async {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            print("No user or session token, cannot retry failed messages")
            return
        }

        // Collect all failed/pending messages
        var messagesToRetry: [(conversationId: String, message: Message)] = []

        await MainActor.run {
            for (convId, msgs) in self.messages {
                for msg in msgs where msg.direction == .outgoing && (msg.status == .failed || msg.status == .pending) {
                    messagesToRetry.append((conversationId: convId, message: msg))
                }
            }
        }

        if messagesToRetry.isEmpty {
            print("No failed messages to retry")
            return
        }

        print("Retrying \(messagesToRetry.count) failed/pending messages...")

        for (convId, msg) in messagesToRetry {
            do {
                // Get recipient's public key
                guard let recipientPublicKey = contactsService.getPublicKey(for: msg.to) else {
                    print("No public key for \(msg.to), marking message as failed")
                    await markMessageStatus(messageId: msg.id, conversationId: convId, status: .failed)
                    continue
                }

                // Re-encrypt and send
                let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

                let (ciphertext, nonce) = try crypto.encryptMessage(
                    msg.content,
                    recipientPublicKey: recipientPublicKey,
                    senderPrivateKey: user.encPrivateKey
                )

                let signature = try crypto.signMessage(
                    messageType: Constants.MessageType.sendMessage,
                    messageId: msg.id,
                    from: user.whisperId,
                    to: msg.to,
                    timestamp: timestamp,
                    nonce: nonce,
                    ciphertext: ciphertext,
                    privateKey: user.signPrivateKey
                )

                let payload = SendMessagePayload(
                    sessionToken: sessionToken,
                    messageId: msg.id,
                    from: user.whisperId,
                    to: msg.to,
                    msgType: msg.contentType,
                    timestamp: timestamp,
                    nonce: nonce.base64EncodedString(),
                    ciphertext: ciphertext.base64EncodedString(),
                    sig: signature.base64EncodedString(),
                    attachment: msg.attachment
                )

                let frame = WsFrame(type: Constants.MessageType.sendMessage, payload: payload, requestId: msg.id)
                try await ws.send(frame)

                print("Retried message \(msg.id) to \(msg.to)")
            } catch {
                print("Failed to retry message \(msg.id): \(error)")
                await markMessageStatus(messageId: msg.id, conversationId: convId, status: .failed)
            }
        }
    }

    /// Mark a message with a specific status
    private func markMessageStatus(messageId: String, conversationId: String, status: MessageStatus) async {
        await MainActor.run {
            if var msgs = self.messages[conversationId],
               let index = msgs.firstIndex(where: { $0.id == messageId }) {
                msgs[index].status = status
                self.messages[conversationId] = msgs
                self.saveMessagesToStorage()
            }
        }
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
        
        // Calculate disappearsAt based on conversation settings
        let disappearsAt = calculateDisappearsAt(for: recipientId)

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
            direction: .outgoing,
            disappearsAt: disappearsAt
        )

        // Add to local messages
        await MainActor.run {
            if self.messages[recipientId] == nil {
                self.messages[recipientId] = []
            }
            // Check for duplicate message ID to prevent ForEach crashes
            if self.messages[recipientId]?.contains(where: { $0.id == messageId }) == true {
                print("Duplicate outgoing message ID detected, skipping: \(messageId)")
            } else {
                self.messages[recipientId]?.append(message)
                self.updateConversation(for: recipientId, lastMessage: content)
                self.saveMessagesToStorage()
                self.saveConversationsToStorage()
            }
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
        return try await sendMessage(to: recipientId, content: content, contentType: contentType, attachment: nil, recipientPublicKey: publicKey)
    }

    /// Send message with attachment
    func sendMessage(to recipientId: String, content: String, contentType: String, attachment: AttachmentPointer) async throws -> Message {
        guard let publicKey = contactsService.getPublicKey(for: recipientId) else {
            throw CryptoError.invalidPublicKey
        }
        return try await sendMessage(to: recipientId, content: content, contentType: contentType, attachment: attachment, recipientPublicKey: publicKey)
    }

    /// Send message with content type, optional attachment and public key
    func sendMessage(to recipientId: String, content: String, contentType: String, attachment: AttachmentPointer?, recipientPublicKey: Data) async throws -> Message {
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
            sig: signature.base64EncodedString(),
            attachment: attachment
        )

        let frame = WsFrame(type: Constants.MessageType.sendMessage, payload: payload, requestId: messageId)
        try await ws.send(frame)

        // For display, use content or generate preview based on attachment
        let displayContent = content.isEmpty ? "[\(contentType.capitalized)]" : content

        // Calculate disappearsAt based on conversation settings
        let disappearsAt = calculateDisappearsAt(for: recipientId)

        let message = Message(
            id: messageId,
            conversationId: recipientId,
            from: user.whisperId,
            to: recipientId,
            content: content,
            contentType: contentType,
            timestamp: Date(timeIntervalSince1970: Double(timestamp) / 1000),
            status: .pending,
            direction: .outgoing,
            attachment: attachment,
            disappearsAt: disappearsAt
        )

        await MainActor.run {
            if self.messages[recipientId] == nil {
                self.messages[recipientId] = []
            }
            // Check for duplicate message ID to prevent ForEach crashes
            if self.messages[recipientId]?.contains(where: { $0.id == messageId }) == true {
                print("Duplicate outgoing message ID detected, skipping: \(messageId)")
            } else {
                self.messages[recipientId]?.append(message)
                self.updateConversation(for: recipientId, lastMessage: displayContent)
                self.saveMessagesToStorage()
                self.saveConversationsToStorage()
            }
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
        messagesLock.lock()
        defer { messagesLock.unlock() }

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

        // Check if this is a group message
        if let groupId = payload.groupId {
            // Route to GroupService for group message handling
            let groupPayload = GroupMessagePayload(
                groupId: groupId,
                messageId: payload.messageId,
                from: payload.from,
                msgType: payload.msgType,
                timestamp: payload.timestamp,
                nonce: payload.nonce,
                ciphertext: payload.ciphertext,
                sig: payload.sig,
                attachment: payload.attachment
            )
            GroupService.shared.handleIncomingGroupMessage(groupPayload)
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

        // Calculate disappearsAt based on conversation settings
        let disappearsAt = calculateDisappearsAt(for: payload.from)

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
            replyToId: payload.replyTo,
            attachment: payload.attachment,
            disappearsAt: disappearsAt
        )

        DispatchQueue.main.async {
            if self.messages[payload.from] == nil {
                self.messages[payload.from] = []
            }

            // Check for duplicate message ID to prevent ForEach crashes
            if self.messages[payload.from]?.contains(where: { $0.id == message.id }) == true {
                print("Duplicate message ID detected, skipping: \(message.id)")
                return
            }

            self.messages[payload.from]?.append(message)

            // Generate preview based on content type
            var previewContent: String
            if decryptedContent.hasPrefix("[") {
                previewContent = "New message"
            } else if payload.attachment != nil {
                switch payload.msgType {
                case "voice", "audio":
                    previewContent = "🎵 Voice message"
                case "image":
                    previewContent = "📷 Photo"
                case "video":
                    previewContent = "🎬 Video"
                case "file":
                    previewContent = "📎 File"
                default:
                    previewContent = decryptedContent
                }
            } else {
                previewContent = decryptedContent
            }

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
            self.messagesLock.lock()
            defer { self.messagesLock.unlock() }

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
            self.messagesLock.lock()
            defer { self.messagesLock.unlock() }

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
            // Cancel existing timer for this sender
            self.typingTimers[senderId]?.invalidate()
            self.typingTimers[senderId] = nil

            // Lock for thread-safe access
            self.conversationsLock.lock()
            if let index = self.conversations.firstIndex(where: { $0.peerId == senderId }) {
                self.objectWillChange.send()
                self.conversations[index].isTyping = isTyping

                // If typing started, set a timeout to auto-clear after 5 seconds
                if isTyping {
                    self.conversationsLock.unlock()
                    self.typingTimers[senderId] = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false) { [weak self] _ in
                        DispatchQueue.main.async {
                            self?.conversationsLock.lock()
                            if let idx = self?.conversations.firstIndex(where: { $0.peerId == senderId }) {
                                self?.objectWillChange.send()
                                self?.conversations[idx].isTyping = false
                            }
                            self?.conversationsLock.unlock()
                            self?.typingTimers[senderId] = nil
                        }
                    }
                } else {
                    self.conversationsLock.unlock()
                }
            } else {
                self.conversationsLock.unlock()
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
        // Lock to prevent race conditions during read-modify-write
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        // Notify observers that changes are coming (required for in-place array modifications)
        objectWillChange.send()

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
        updateAppBadge()
    }

    func markAsRead(conversationId: String) {
        // Lock conversations for thread-safe access
        conversationsLock.lock()
        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            if conversations[index].unreadCount > 0 {
                objectWillChange.send()
                conversations[index].unreadCount = 0
                saveConversationsToStorage()
                updateAppBadge()
            }
        }
        conversationsLock.unlock()

        // Lock messages for thread-safe access
        messagesLock.lock()
        guard var msgs = messages[conversationId] else {
            messagesLock.unlock()
            return
        }

        // Find messages that need to be marked as read (incoming, delivered but not yet read)
        var needsSave = false
        var receiptsToSend: [(messageId: String, senderId: String)] = []

        for i in msgs.indices {
            if msgs[i].direction == .incoming && msgs[i].status == .delivered {
                receiptsToSend.append((messageId: msgs[i].id, senderId: msgs[i].from))
                msgs[i].status = .read
                needsSave = true
            }
        }

        // Update messages array and save if any changes were made
        if needsSave {
            messages[conversationId] = msgs
            saveMessagesToStorage()
        }
        messagesLock.unlock()

        // Send receipts outside the lock to avoid blocking
        for receipt in receiptsToSend {
            Task {
                try? await sendDeliveryReceipt(messageId: receipt.messageId, from: receipt.senderId, status: "read")
            }
        }
    }
    
    func getMessages(for conversationId: String) -> [Message] {
        messages[conversationId] ?? []
    }

    /// Add a local-only message (e.g., call records, system messages)
    /// This message is not sent over the network, only stored locally
    func addLocalMessage(_ message: Message) {
        DispatchQueue.main.async {
            // Create new array with appended message to trigger @Published update
            var conversationMessages = self.messages[message.conversationId] ?? []

            // Check for duplicate message ID to prevent ForEach crashes
            if conversationMessages.contains(where: { $0.id == message.id }) {
                print("Duplicate local message ID detected, skipping: \(message.id)")
                return
            }

            conversationMessages.append(message)
            self.messages[message.conversationId] = conversationMessages

            // Generate preview based on content type
            var previewContent: String
            switch message.contentType {
            case "call":
                // Parse call info: "type|outcome|duration"
                let parts = message.content.split(separator: "|")
                let isVideo = parts.first == "video"
                let outcome = parts.count > 1 ? String(parts[1]) : "missed"
                switch outcome {
                case "completed":
                    previewContent = isVideo ? "Video call" : "Voice call"
                case "missed":
                    previewContent = isVideo ? "Missed video call" : "Missed call"
                case "declined":
                    previewContent = isVideo ? "Declined video call" : "Declined call"
                case "noAnswer":
                    previewContent = isVideo ? "Unanswered video call" : "Unanswered call"
                case "failed":
                    previewContent = "Call failed"
                default:
                    previewContent = isVideo ? "Video call" : "Voice call"
                }
            default:
                previewContent = message.content
            }

            self.updateConversation(for: message.conversationId, lastMessage: previewContent)
            self.saveMessagesToStorage()
            self.saveConversationsToStorage()
        }
    }

    // MARK: - Badge Management

    /// Update app badge with total unread count
    private func updateAppBadge() {
        let totalUnread = conversations.reduce(0) { $0 + $1.unreadCount }
        Task { @MainActor in
            pushService.updateBadgeCount(totalUnread)
        }
    }

    // MARK: - Clear All Data

    /// Clear all messaging data (for wipe data feature)
    /// Clear messages for a specific conversation
    func clearMessages(for conversationId: String) {
        messagesLock.lock()
        messages[conversationId] = []
        saveMessagesToStorage()
        messagesLock.unlock()

        // Update conversation to show no messages
        conversationsLock.lock()
        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            conversations[index].lastMessage = nil
            conversations[index].lastMessageTime = nil
            conversations[index].unreadCount = 0
            saveConversationsToStorage()
        }
        conversationsLock.unlock()
    }

    /// Delete a conversation completely (chat and messages)
    func deleteConversation(conversationId: String) {
        // Remove messages
        messagesLock.lock()
        messages.removeValue(forKey: conversationId)
        saveMessagesToStorage()
        messagesLock.unlock()

        // Remove conversation
        conversationsLock.lock()
        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            conversations.remove(at: index)
            saveConversationsToStorage()
            updateAppBadge()
        }
        conversationsLock.unlock()
    }

    /// Set chat theme for a conversation
    func setChatTheme(for conversationId: String, themeId: String) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            conversations[index].chatThemeId = themeId
        } else {
            // Create conversation if it doesn't exist
            let contact = contactsService.getContact(whisperId: conversationId)
            let conv = Conversation(
                peerId: conversationId,
                peerNickname: contact?.nickname,
                chatThemeId: themeId
            )
            conversations.append(conv)
        }
        saveConversationsToStorage()
    }

    /// Get chat theme for a conversation
    func getChatTheme(for conversationId: String) -> ChatTheme {
        conversationsLock.lock()
        let conversation = conversations.first { $0.peerId == conversationId }
        conversationsLock.unlock()
        return ChatTheme.getTheme(id: conversation?.chatThemeId)
    }

    // MARK: - Disappearing Messages

    /// Set disappearing message timer for a conversation
    func setDisappearingMessageTimer(for conversationId: String, timer: DisappearingMessageTimer) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        objectWillChange.send()
        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            conversations[index].disappearingMessageTimer = timer
        } else {
            // Create conversation if it doesn't exist
            let contact = contactsService.getContact(whisperId: conversationId)
            let conv = Conversation(
                peerId: conversationId,
                peerNickname: contact?.nickname,
                disappearingMessageTimer: timer
            )
            conversations.append(conv)
        }
        saveConversationsToStorage()
    }

    /// Get disappearing message timer for a conversation
    func getDisappearingMessageTimer(for conversationId: String) -> DisappearingMessageTimer {
        conversationsLock.lock()
        let conversation = conversations.first { $0.peerId == conversationId }
        conversationsLock.unlock()
        return conversation?.disappearingMessageTimer ?? .off
    }

    // MARK: - Mute Notifications

    /// Toggle mute status for a conversation
    func toggleMute(for conversationId: String) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        objectWillChange.send()
        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            conversations[index].isMuted.toggle()
        } else {
            // Create conversation if it doesn't exist
            let contact = contactsService.getContact(whisperId: conversationId)
            let conv = Conversation(
                peerId: conversationId,
                peerNickname: contact?.nickname,
                isMuted: true
            )
            conversations.append(conv)
        }
        saveConversationsToStorage()
    }

    /// Set mute status for a conversation
    func setMuted(_ muted: Bool, for conversationId: String) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        objectWillChange.send()
        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            conversations[index].isMuted = muted
        } else {
            // Create conversation if it doesn't exist
            let contact = contactsService.getContact(whisperId: conversationId)
            let conv = Conversation(
                peerId: conversationId,
                peerNickname: contact?.nickname,
                isMuted: muted
            )
            conversations.append(conv)
        }
        saveConversationsToStorage()
    }

    /// Check if a conversation is muted
    func isMuted(for conversationId: String) -> Bool {
        conversationsLock.lock()
        let conversation = conversations.first { $0.peerId == conversationId }
        conversationsLock.unlock()
        return conversation?.isMuted ?? false
    }

    /// Start the background timer that checks for expired messages
    private func startDisappearingMessagesTimer() {
        // Check every 30 seconds for expired messages
        disappearingMessagesTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.deleteExpiredMessages()
        }
        // Also run immediately on startup
        deleteExpiredMessages()
    }

    /// Delete all messages that have passed their disappearsAt time
    private func deleteExpiredMessages() {
        let now = Date()
        var hasChanges = false

        messagesLock.lock()
        for (convId, msgs) in messages {
            let expiredIds = msgs.compactMap { msg -> String? in
                guard let disappearsAt = msg.disappearsAt, disappearsAt <= now else { return nil }
                return msg.id
            }

            if !expiredIds.isEmpty {
                hasChanges = true
                messages[convId] = msgs.filter { msg in
                    !expiredIds.contains(msg.id)
                }
                print("Deleted \(expiredIds.count) expired messages from conversation \(convId)")
            }
        }
        messagesLock.unlock()

        if hasChanges {
            DispatchQueue.main.async {
                self.objectWillChange.send()
                self.saveMessagesToStorage()
            }
        }
    }

    /// Calculate the disappearsAt date for a new message based on conversation settings
    private func calculateDisappearsAt(for conversationId: String) -> Date? {
        let timer = getDisappearingMessageTimer(for: conversationId)
        guard let interval = timer.timeInterval else { return nil }
        return Date(timeIntervalSinceNow: interval)
    }

    func clearAllData() {
        messagesLock.lock()
        messages.removeAll()
        messagesLock.unlock()

        conversationsLock.lock()
        conversations.removeAll()
        conversationsLock.unlock()

        keychain.delete(key: messagesStorageKey)
        keychain.delete(key: conversationsStorageKey)
    }

    // MARK: - Pin/Unpin Conversations

    /// Toggle pin status for a conversation
    func togglePin(conversationId: String) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            objectWillChange.send()
            conversations[index].isPinned.toggle()
            saveConversationsToStorage()
        }
    }

    /// Set pin status for a conversation
    func setPin(conversationId: String, isPinned: Bool) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            if conversations[index].isPinned != isPinned {
                objectWillChange.send()
                conversations[index].isPinned = isPinned
                saveConversationsToStorage()
            }
        }
    }

    // MARK: - Mute/Unmute Conversations

    /// Toggle mute status for a conversation
    func toggleMute(conversationId: String) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            objectWillChange.send()
            conversations[index].isMuted.toggle()
            saveConversationsToStorage()
        }
    }

    /// Set mute status for a conversation
    func setMute(conversationId: String, isMuted: Bool) {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }

        if let index = conversations.firstIndex(where: { $0.peerId == conversationId }) {
            if conversations[index].isMuted != isMuted {
                objectWillChange.send()
                conversations[index].isMuted = isMuted
                saveConversationsToStorage()
            }
        }
    }

    // MARK: - Archive Conversations

    /// Archive a conversation (removes from main list but keeps data)
    /// For now, this is implemented as a delete since we don't have a separate archived list
    /// In a full implementation, archived conversations would be stored separately
    func archiveConversation(conversationId: String) {
        // For now, archiving just deletes the conversation
        // TODO: Implement proper archive storage when needed
        deleteConversation(conversationId: conversationId)
    }

    /// Get conversation for a peer ID
    func getConversation(for peerId: String) -> Conversation? {
        conversationsLock.lock()
        defer { conversationsLock.unlock() }
        return conversations.first { $0.peerId == peerId }
    }
}
