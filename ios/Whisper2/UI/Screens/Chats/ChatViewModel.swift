import SwiftUI
import Observation
import CoreLocation
import Contacts

/// Model representing a chat message
struct ChatMessage: Identifiable, Equatable {
    let id: String
    let senderId: String
    let content: String
    let timestamp: Date
    var status: MessageStatus
    let isFromMe: Bool

    enum MessageStatus {
        case sending
        case sent
        case delivered
        case read
        case failed
    }

    static func == (lhs: ChatMessage, rhs: ChatMessage) -> Bool {
        lhs.id == rhs.id
    }
}

/// Stored message for persistence
private struct StoredMessage: Codable {
    let id: String
    let senderId: String
    let content: String
    let timestamp: Date
    let status: String
    let isFromMe: Bool
}

/// Notifications for conversation updates
extension Notification.Name {
    static let conversationRead = Notification.Name("whisper2.conversationRead")
    static let messageSent = Notification.Name("whisper2.messageSent")
}

/// Info for message sent notification
struct MessageSentInfo {
    let conversationId: String
    let participantId: String
    let participantName: String
    let participantAvatarURL: URL?
    let participantEncPublicKey: String?
    let lastMessage: String
    let timestamp: Date
}

/// Manages a single chat state - connected to real server
@Observable
final class ChatViewModel {
    // MARK: - State

    let conversationId: String
    let participantId: String
    let participantName: String
    let participantAvatarURL: URL?
    var participantPublicKey: Data?  // var so we can fetch if missing

    var messages: [ChatMessage] = []
    var isLoading = false
    var isSending = false
    var error: String?

    var messageText: String = ""
    var isParticipantOnline = false
    var isParticipantTyping = false

    // Pagination
    var hasMoreMessages = false
    private var isLoadingMore = false

    // MARK: - Dependencies

    private let connectionManager = AppConnectionManager.shared
    private let keychain = KeychainService.shared
    private let dataStack = DataStack.shared

    // Local storage key for messages - use participantId to ensure consistency
    // across different entry points (contacts vs chats list)
    private var localStorageKey: String { "whisper2.messages.\(participantId)" }

    // MARK: - Init

    init(
        conversationId: String,
        participantId: String,
        participantName: String,
        participantAvatarURL: URL? = nil,
        participantPublicKey: Data? = nil
    ) {
        self.conversationId = conversationId
        self.participantId = participantId
        self.participantName = participantName
        self.participantAvatarURL = participantAvatarURL
        self.participantPublicKey = participantPublicKey
    }

    convenience init(conversation: ConversationUI) {
        self.init(
            conversationId: conversation.id,
            participantId: conversation.participantId,
            participantName: conversation.participantName,
            participantAvatarURL: conversation.participantAvatarURL,
            participantPublicKey: conversation.participantPublicKeyData
        )
        self.isParticipantOnline = conversation.isOnline
        self.isParticipantTyping = conversation.isTyping
    }

    // MARK: - Public Key Fetching

    /// Fetch participant's public key if not already available
    func ensurePublicKey() async -> Data? {
        if let key = participantPublicKey {
            return key
        }

        // Try to get session token from SessionManager (includes expiry check)
        // Fallback to keychain direct access
        let sessionToken = SessionManager.shared.sessionToken ?? keychain.sessionToken
        guard let token = sessionToken else {
            logger.warning("Cannot fetch public key: not authenticated", category: .network)
            return nil
        }

        let urlString = "\(Constants.Server.baseURL)/users/\(participantId)/keys"
        guard let url = URL(string: urlString) else {
            logger.warning("Invalid URL for public key fetch", category: .network)
            return nil
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                logger.warning("Invalid response type", category: .network)
                return nil
            }

            if httpResponse.statusCode == 401 {
                logger.warning("Auth failed when fetching public key - session may be expired", category: .network)
                return nil
            }

            guard httpResponse.statusCode == 200 else {
                logger.warning("Failed to fetch public key: HTTP \(httpResponse.statusCode)", category: .network)
                // Log the response body for debugging
                if let errorBody = String(data: data, encoding: .utf8) {
                    logger.debug("Error response: \(errorBody)", category: .network)
                }
                return nil
            }

            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                logger.warning("Failed to parse public key response as JSON", category: .network)
                return nil
            }

            guard let encPublicKeyB64 = json["encPublicKey"] as? String else {
                logger.warning("Response missing encPublicKey field. Keys: \(json.keys)", category: .network)
                return nil
            }

            guard let keyData = Data(base64Encoded: encPublicKeyB64) else {
                logger.warning("Failed to decode public key from base64", category: .network)
                return nil
            }

            guard keyData.count == 32 else {
                logger.warning("Public key has wrong length: \(keyData.count) (expected 32)", category: .network)
                return nil
            }

            // Cache it
            self.participantPublicKey = keyData
            logger.info("Fetched public key for \(participantId) (\(keyData.count) bytes)", category: .network)
            return keyData
        } catch {
            logger.error(error, message: "Network error fetching public key", category: .network)
            return nil
        }
    }

    // MARK: - Computed Properties

    var canSend: Bool {
        !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isSending
    }

    var groupedMessages: [(Date, [ChatMessage])] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: messages) { message in
            calendar.startOfDay(for: message.timestamp)
        }
        return grouped.sorted { $0.key < $1.key }
    }

    // MARK: - Actions

    func loadMessages() {
        isLoading = true
        error = nil

        Task { @MainActor in
            // Fetch recipient's public key early (needed for sending)
            _ = await ensurePublicKey()

            // Get my WhisperId
            let myWhisperId = keychain.whisperId ?? ""

            // 1. First load from local storage (persisted messages)
            loadMessagesFromLocalStorage()

            // 2. Then load any pending messages from server
            let pendingMessages = connectionManager.pendingMessages.filter { msg in
                msg.from == participantId || msg.to == participantId
            }

            // Convert and merge new messages
            for msg in pendingMessages {
                let isFromMe = msg.from == myWhisperId
                let chatMessage = ChatMessage(
                    id: msg.messageId,
                    senderId: msg.from,
                    content: msg.decodedText ?? "[Encrypted]",
                    timestamp: msg.timestampDate,
                    status: .delivered,
                    isFromMe: isFromMe
                )

                // Add if not already present
                if !messages.contains(where: { $0.id == chatMessage.id }) {
                    messages.append(chatMessage)
                    // Save new message to local storage
                    saveMessageToLocalStorage(chatMessage)
                }
            }

            // Sort by timestamp
            messages.sort { $0.timestamp < $1.timestamp }

            // Mark conversation as read
            markConversationAsRead()

            // Subscribe to new messages
            connectionManager.onMessageReceived = { [weak self] message in
                self?.handleNewMessage(message)
            }

            isLoading = false
        }
    }

    // MARK: - Local Storage

    private func loadMessagesFromLocalStorage() {
        guard let data = UserDefaults.standard.data(forKey: localStorageKey),
              let stored = try? JSONDecoder().decode([StoredMessage].self, from: data) else {
            return
        }

        messages = stored.map { stored in
            ChatMessage(
                id: stored.id,
                senderId: stored.senderId,
                content: stored.content,
                timestamp: stored.timestamp,
                status: statusFromString(stored.status),
                isFromMe: stored.isFromMe
            )
        }
    }

    private func saveMessageToLocalStorage(_ message: ChatMessage) {
        var stored = loadStoredMessages()

        // Check if already exists
        if stored.contains(where: { $0.id == message.id }) {
            return
        }

        stored.append(StoredMessage(
            id: message.id,
            senderId: message.senderId,
            content: message.content,
            timestamp: message.timestamp,
            status: statusToString(message.status),
            isFromMe: message.isFromMe
        ))

        // Keep only last 500 messages per conversation
        if stored.count > 500 {
            stored = Array(stored.suffix(500))
        }

        if let data = try? JSONEncoder().encode(stored) {
            UserDefaults.standard.set(data, forKey: localStorageKey)
            UserDefaults.standard.synchronize()  // Force immediate save
        }
    }

    private func loadStoredMessages() -> [StoredMessage] {
        guard let data = UserDefaults.standard.data(forKey: localStorageKey),
              let stored = try? JSONDecoder().decode([StoredMessage].self, from: data) else {
            return []
        }
        return stored
    }

    private func statusToString(_ status: ChatMessage.MessageStatus) -> String {
        switch status {
        case .sending: return "sending"
        case .sent: return "sent"
        case .delivered: return "delivered"
        case .read: return "read"
        case .failed: return "failed"
        }
    }

    private func statusFromString(_ string: String) -> ChatMessage.MessageStatus {
        switch string {
        case "sending": return .sending
        case "sent": return .sent
        case "delivered": return .delivered
        case "read": return .read
        case "failed": return .failed
        default: return .delivered
        }
    }

    private func markConversationAsRead() {
        // Update local storage for conversations
        let key = "whisper2.conversations.unread.\(conversationId)"
        UserDefaults.standard.set(0, forKey: key)

        // Notify chats list to refresh
        NotificationCenter.default.post(name: .conversationRead, object: conversationId)
    }

    private func handleNewMessage(_ message: PendingMessage) {
        // Only add if from this conversation participant
        guard message.from == participantId || message.to == participantId else { return }

        let myWhisperId = keychain.whisperId ?? ""
        let isFromMe = message.from == myWhisperId

        let chatMessage = ChatMessage(
            id: message.messageId,
            senderId: message.from,
            content: message.decodedText ?? "[Encrypted]",
            timestamp: message.timestampDate,
            status: .delivered,
            isFromMe: isFromMe
        )

        // Avoid duplicates
        if !messages.contains(where: { $0.id == chatMessage.id }) {
            messages.append(chatMessage)
            messages.sort { $0.timestamp < $1.timestamp }

            // Save to local storage
            saveMessageToLocalStorage(chatMessage)
        }
    }

    func loadMoreMessages() {
        // Pagination not yet implemented on server
        hasMoreMessages = false
    }

    func sendMessage() {
        guard canSend else { return }

        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = UUID().uuidString.lowercased()
        messageText = ""

        let newMessage = ChatMessage(
            id: messageId,
            senderId: keychain.whisperId ?? "me",
            content: text,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        saveMessageToLocalStorage(newMessage)  // Save immediately
        isSending = true

        Task { @MainActor in
            do {
                // Fetch public key if not available
                let recipientPubKey = await ensurePublicKey()

                // Send via AppConnectionManager (real server call) with encryption
                try await connectionManager.sendMessage(
                    to: participantId,
                    text: text,
                    recipientPublicKey: recipientPubKey
                )

                // Update status to sent
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                    // Update status in storage
                    updateMessageStatusInStorage(messageId: messageId, status: .sent)
                }

                isSending = false
                logger.info("Message sent to \(participantId)", category: .messaging)

                // Notify ChatsViewModel to add/update conversation
                let info = MessageSentInfo(
                    conversationId: conversationId,
                    participantId: participantId,
                    participantName: participantName,
                    participantAvatarURL: participantAvatarURL,
                    participantEncPublicKey: participantPublicKey?.base64EncodedString(),
                    lastMessage: text,
                    timestamp: Date()
                )
                NotificationCenter.default.post(name: .messageSent, object: info)
            } catch {
                // Mark as failed
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                    updateMessageStatusInStorage(messageId: messageId, status: .failed)
                }
                self.error = "Failed to send: \(error.localizedDescription)"
                isSending = false
                logger.error(error, message: "Failed to send message", category: .messaging)
            }
        }
    }

    private func updateMessageStatusInStorage(messageId: String, status: ChatMessage.MessageStatus) {
        var stored = loadStoredMessages()
        if let index = stored.firstIndex(where: { $0.id == messageId }) {
            stored[index] = StoredMessage(
                id: stored[index].id,
                senderId: stored[index].senderId,
                content: stored[index].content,
                timestamp: stored[index].timestamp,
                status: statusToString(status),
                isFromMe: stored[index].isFromMe
            )
            if let data = try? JSONEncoder().encode(stored) {
                UserDefaults.standard.set(data, forKey: localStorageKey)
                UserDefaults.standard.synchronize()  // Force immediate save
            }
        }
    }

    func retrySending(_ message: ChatMessage) {
        guard message.status == .failed else { return }

        // Update to sending
        if let index = messages.firstIndex(where: { $0.id == message.id }) {
            messages[index].status = .sending
        }

        Task { @MainActor in
            do {
                // Resend via server with encryption
                try await connectionManager.sendMessage(
                    to: participantId,
                    text: message.content,
                    recipientPublicKey: participantPublicKey
                )

                if let index = messages.firstIndex(where: { $0.id == message.id }) {
                    messages[index].status = .sent
                }
            } catch {
                if let index = messages.firstIndex(where: { $0.id == message.id }) {
                    messages[index].status = .failed
                }
                self.error = "Retry failed: \(error.localizedDescription)"
            }
        }
    }

    func deleteMessage(_ message: ChatMessage) {
        messages.removeAll { $0.id == message.id }
    }

    func setTyping(_ isTyping: Bool) {
        Task { @MainActor in
            await connectionManager.sendTypingIndicator(to: participantId, isTyping: isTyping)
        }
    }

    func clearError() {
        error = nil
    }

    func clearChat() {
        messages.removeAll()
    }

    // MARK: - Attachment Sending

    func sendImageMessage(_ image: UIImage) {
        // E2E Flow: Encrypt locally → Upload to S3 → Send only metadata via server
        // Server never sees the actual image content

        // Resize image for reasonable file size (max 2048px, up to ~5MB after compression)
        let resizedImage = resizeImageIfNeeded(image, maxDimension: 2048)

        // Compress with good quality
        guard let imageData = resizedImage.jpegData(compressionQuality: 0.8) else {
            error = "Failed to compress image"
            return
        }

        // Check against max attachment size (100MB limit from server)
        if imageData.count > Constants.Limits.maxAttachmentSize {
            error = "Image too large (\(imageData.count / 1_000_000)MB). Maximum is \(Constants.Limits.maxAttachmentSize / 1_000_000)MB."
            return
        }

        sendAttachmentData(imageData, contentType: "image/jpeg", displayText: "📷 Photo")
    }

    private func resizeImageIfNeeded(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let maxSize = max(size.width, size.height)

        if maxSize <= maxDimension {
            return image
        }

        let scale = maxDimension / maxSize
        let newSize = CGSize(width: size.width * scale, height: size.height * scale)

        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }

    /// Send attachment using proper E2E flow:
    /// 1. Encrypt file with random key (NaClSecretBox) → upload to S3
    /// 2. Encrypt file key for recipient (NaClBox)
    /// 3. Send only metadata (objectKey + encrypted key) via server
    /// Server never sees the actual file content!
    private func sendAttachmentData(_ data: Data, contentType: String, displayText: String) {
        let messageId = UUID().uuidString.lowercased()

        let newMessage = ChatMessage(
            id: messageId,
            senderId: keychain.whisperId ?? "me",
            content: displayText,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Get our encryption private key for creating attachment pointer
                guard let encPrivateKey = keychain.getData(forKey: Constants.StorageKey.encPrivateKey) else {
                    throw AuthError.notAuthenticated
                }

                // Get recipient's public key (fetch if not available)
                guard let recipientPubKey = await ensurePublicKey() else {
                    throw CryptoError.invalidPublicKey
                }

                // 1. Upload encrypted attachment to S3
                // AttachmentService encrypts with random fileKey and uploads ciphertext
                let uploadedAttachment = try await AttachmentService.shared.uploadAttachment(
                    data: data,
                    contentType: contentType
                )

                // 2. Create attachment pointer for recipient
                // This encrypts the fileKey using recipient's public key
                let attachmentPointer = try uploadedAttachment.pointer(
                    forRecipient: recipientPubKey,
                    senderPrivateKey: encPrivateKey
                )

                // 3. Send message with attachment metadata (not the actual file)
                try await connectionManager.sendMessageWithAttachment(
                    to: participantId,
                    text: displayText,
                    attachment: attachmentPointer,
                    recipientPublicKey: recipientPubKey
                )

                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                }
                isSending = false
                logger.info("Attachment sent to \(participantId) (E2E encrypted via S3)", category: .messaging)
            } catch {
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                }
                self.error = "Failed to send: \(error.localizedDescription)"
                isSending = false
                logger.error(error, message: "Failed to send attachment", category: .messaging)
            }
        }
    }

    func sendDocumentMessage(_ url: URL) {
        let filename = url.lastPathComponent
        let displayText = "📄 \(filename)"

        // Read document data
        guard let data = try? Data(contentsOf: url) else {
            error = "Failed to read document"
            return
        }

        // Check size limit
        if data.count > Constants.Limits.maxAttachmentSize {
            error = "Document too large (\(data.count / 1_000_000)MB). Maximum is \(Constants.Limits.maxAttachmentSize / 1_000_000)MB."
            return
        }

        // Determine content type
        let contentType: String
        switch url.pathExtension.lowercased() {
        case "pdf": contentType = "application/pdf"
        case "doc", "docx": contentType = "application/octet-stream"
        default: contentType = "application/octet-stream"
        }

        // Use the proper E2E attachment flow
        sendAttachmentData(data, contentType: contentType, displayText: displayText)
    }

    func sendLocationMessage(_ location: CLLocationCoordinate2D) {
        let messageId = UUID().uuidString.lowercased()
        let locationText = "📍 Location: \(location.latitude), \(location.longitude)"

        let newMessage = ChatMessage(
            id: messageId,
            senderId: keychain.whisperId ?? "me",
            content: locationText,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Send location as text message (could be JSON with coordinates)
                let locationJSON = """
                {"type":"location","lat":\(location.latitude),"lng":\(location.longitude)}
                """
                try await connectionManager.sendMessage(
                    to: participantId,
                    text: locationText,
                    recipientPublicKey: participantPublicKey
                )

                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                }
                isSending = false
                logger.info("Location sent to \(participantId)", category: .messaging)
            } catch {
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                }
                self.error = "Failed to send location: \(error.localizedDescription)"
                isSending = false
            }
        }
    }

    func sendContactMessage(_ contact: CNContact) {
        let messageId = UUID().uuidString.lowercased()
        let name = "\(contact.givenName) \(contact.familyName)".trimmingCharacters(in: .whitespaces)
        let phone = contact.phoneNumbers.first?.value.stringValue ?? ""
        let contactText = "👤 Contact: \(name)\n📱 \(phone)"

        let newMessage = ChatMessage(
            id: messageId,
            senderId: keychain.whisperId ?? "me",
            content: contactText,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Send contact as text message
                try await connectionManager.sendMessage(
                    to: participantId,
                    text: contactText,
                    recipientPublicKey: participantPublicKey
                )

                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                }
                isSending = false
                logger.info("Contact sent to \(participantId)", category: .messaging)
            } catch {
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                }
                self.error = "Failed to send contact: \(error.localizedDescription)"
                isSending = false
            }
        }
    }
}
