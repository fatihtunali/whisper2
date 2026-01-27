import Foundation
import Combine

/// View model for chat/message thread
@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [Message] = []
    @Published var messageText = ""
    @Published var isLoading = false
    @Published var error: String?
    @Published var canSendMessages = false
    @Published var contactName: String = ""
    
    let conversationId: String
    
    private let messagingService = MessagingService.shared
    private let contactsService = ContactsService.shared
    private let authService = AuthService.shared
    private var cancellables = Set<AnyCancellable>()
    private var typingTimer: Timer?
    private var isTyping = false
    
    init(conversationId: String) {
        self.conversationId = conversationId
        setupBindings()
        loadMessages()
        checkCanSendMessages()
        loadContactInfo()
    }
    
    private func setupBindings() {
        // Subscribe to messages for this conversation
        messagingService.$messages
            .receive(on: DispatchQueue.main)
            .map { [weak self] allMessages in
                allMessages[self?.conversationId ?? ""] ?? []
            }
            .sink { [weak self] messages in
                self?.messages = messages.sorted { $0.timestamp < $1.timestamp }
            }
            .store(in: &cancellables)
        
        // Subscribe to new message notifications
        messagingService.messageReceivedPublisher
            .receive(on: DispatchQueue.main)
            .filter { [weak self] message in
                message.conversationId == self?.conversationId
            }
            .sink { [weak self] _ in
                self?.markAsRead()
            }
            .store(in: &cancellables)
        
        // Subscribe to contacts changes (for public key updates)
        contactsService.$contacts
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.checkCanSendMessages()
                self?.loadContactInfo()
            }
            .store(in: &cancellables)
    }
    
    private func loadContactInfo() {
        if let contact = contactsService.getContact(whisperId: conversationId) {
            contactName = contact.displayName
        } else {
            contactName = conversationId
        }
    }
    
    private func checkCanSendMessages() {
        canSendMessages = contactsService.hasValidPublicKey(for: conversationId)
    }
    
    func loadMessages() {
        messages = messagingService.getMessages(for: conversationId)
            .sorted { $0.timestamp < $1.timestamp }
    }
    
    func sendMessage() {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        
        guard canSendMessages else {
            error = "Cannot send messages. Scan contact's QR code to get their public key."
            return
        }
        
        messageText = ""
        stopTypingIndicator()
        
        Task {
            do {
                _ = try await messagingService.sendMessage(to: conversationId, content: text)
            } catch {
                self.error = error.localizedDescription
                // Restore text on error
                self.messageText = text
            }
        }
    }
    
    func markAsRead() {
        messagingService.markAsRead(conversationId: conversationId)
    }
    
    // MARK: - Typing Indicator
    
    func textChanged() {
        // Send typing indicator when user starts typing
        if !isTyping {
            isTyping = true
            sendTypingIndicator(true)
        }
        
        // Reset timer
        typingTimer?.invalidate()
        typingTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: false) { [weak self] _ in
            self?.stopTypingIndicator()
        }
    }
    
    private func sendTypingIndicator(_ typing: Bool) {
        Task {
            try? await messagingService.sendTypingIndicator(to: conversationId, isTyping: typing)
        }
    }
    
    private func stopTypingIndicator() {
        if isTyping {
            isTyping = false
            sendTypingIndicator(false)
        }
        typingTimer?.invalidate()
        typingTimer = nil
    }

    // MARK: - Voice Messages

    func sendVoiceMessage(url: URL, duration: TimeInterval) {
        guard canSendMessages else {
            error = "Cannot send messages. Scan contact's QR code to get their public key."
            return
        }

        Task {
            do {
                // Upload voice file first
                guard let contact = contactsService.getContact(whisperId: conversationId) else {
                    error = "Contact not found"
                    return
                }
                let publicKey = contact.encPublicKey

                let messageId = UUID().uuidString.lowercased()
                let pointer = try await AttachmentService.shared.uploadFile(
                    url,
                    recipientPublicKey: publicKey,
                    messageId: messageId
                )

                // Send message with audio content type
                let content = "\(Int(duration))|\(pointer.objectKey)"
                _ = try await messagingService.sendMessage(
                    to: conversationId,
                    content: content,
                    contentType: "audio"
                )

                // Clean up local file
                try? FileManager.default.removeItem(at: url)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    // MARK: - Location Messages

    func sendLocationMessage(location: LocationData) {
        guard canSendMessages else {
            error = "Cannot send messages. Scan contact's QR code to get their public key."
            return
        }

        Task {
            do {
                // Encode location as JSON
                let encoder = JSONEncoder()
                let data = try encoder.encode(location)
                let content = String(data: data, encoding: .utf8) ?? "\(location.latitude),\(location.longitude)"

                _ = try await messagingService.sendMessage(
                    to: conversationId,
                    content: content,
                    contentType: "location"
                )
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    // MARK: - Attachments

    func sendAttachment(url: URL) {
        guard canSendMessages else {
            error = "Cannot send messages. Scan contact's QR code to get their public key."
            return
        }

        Task {
            do {
                guard let contact = contactsService.getContact(whisperId: conversationId) else {
                    error = "Contact not found"
                    return
                }
                let publicKey = contact.encPublicKey

                let messageId = UUID().uuidString.lowercased()
                let pointer = try await AttachmentService.shared.uploadFile(
                    url,
                    recipientPublicKey: publicKey,
                    messageId: messageId
                )

                // Determine content type
                let contentType = getContentType(for: url)
                let messageContentType: String
                if contentType.hasPrefix("image/") {
                    messageContentType = "image"
                } else if contentType.hasPrefix("video/") {
                    messageContentType = "video"
                } else {
                    messageContentType = "file"
                }

                _ = try await messagingService.sendMessage(
                    to: conversationId,
                    content: pointer.objectKey,
                    contentType: messageContentType
                )

                // Clean up local file
                try? FileManager.default.removeItem(at: url)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func getContentType(for url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        case "m4a": return "audio/m4a"
        case "mp3": return "audio/mpeg"
        case "pdf": return "application/pdf"
        default: return "application/octet-stream"
        }
    }

    // MARK: - Calls

    func startVoiceCall() {
        guard canSendMessages else {
            error = "Cannot call. Add contact's public key first."
            return
        }

        Task {
            do {
                try await CallService.shared.initiateCall(to: conversationId, isVideo: false)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func startVideoCall() {
        guard canSendMessages else {
            error = "Cannot call. Add contact's public key first."
            return
        }

        Task {
            do {
                try await CallService.shared.initiateCall(to: conversationId, isVideo: true)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    // MARK: - Delete Message

    func deleteMessage(messageId: String, deleteForEveryone: Bool) {
        Task {
            do {
                try await MessagingService.shared.deleteMessage(
                    messageId: messageId,
                    conversationId: conversationId,
                    deleteForEveryone: deleteForEveryone
                )
            } catch {
                await MainActor.run {
                    self.error = "Failed to delete message"
                }
            }
        }
    }

    deinit {
        typingTimer?.invalidate()
        // Send typing stopped when leaving chat
        if isTyping {
            Task { @MainActor in
                try? await MessagingService.shared.sendTypingIndicator(to: conversationId, isTyping: false)
            }
        }
    }
}
