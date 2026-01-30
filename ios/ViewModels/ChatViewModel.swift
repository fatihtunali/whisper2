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

                // Cache outgoing file so sender can view it later
                try? AttachmentService.shared.cacheOutgoingFile(url, forObjectKey: pointer.objectKey)

                // Content contains duration for display purposes
                let content = "\(Int(duration))"

                // Send message with audio content type and attachment pointer
                _ = try await messagingService.sendMessage(
                    to: conversationId,
                    content: content,
                    contentType: "voice",
                    attachment: pointer
                )

                // Clean up local file (cached copy already saved)
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

                // Cache outgoing file so sender can view it later
                try? AttachmentService.shared.cacheOutgoingFile(url, forObjectKey: pointer.objectKey)

                // Determine content type
                let mimeType = getContentType(for: url)
                let messageContentType: String
                if mimeType.hasPrefix("image/") {
                    messageContentType = "image"
                } else if mimeType.hasPrefix("video/") {
                    messageContentType = "video"
                } else if mimeType.hasPrefix("audio/") {
                    messageContentType = "voice"
                } else {
                    messageContentType = "file"
                }

                // Content is the filename for display
                let filename = url.lastPathComponent

                _ = try await messagingService.sendMessage(
                    to: conversationId,
                    content: filename,
                    contentType: messageContentType,
                    attachment: pointer
                )

                // Clean up local file (cached copy already saved)
                try? FileManager.default.removeItem(at: url)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func getContentType(for url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        switch ext {
        // Images
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "heic": return "image/heic"
        case "heif": return "image/heif"

        // Videos
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        case "m4v": return "video/x-m4v"
        case "3gp": return "video/3gpp"
        case "webm": return "video/webm"
        case "avi": return "video/x-msvideo"
        case "mkv": return "video/x-matroska"

        // Audio
        case "m4a": return "audio/m4a"
        case "mp3": return "audio/mpeg"
        case "aac": return "audio/aac"
        case "wav": return "audio/wav"
        case "ogg": return "audio/ogg"
        case "flac": return "audio/flac"

        // Documents
        case "pdf": return "application/pdf"
        case "doc": return "application/msword"
        case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        case "txt": return "text/plain"
        case "zip": return "application/zip"

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

    // MARK: - Clear Chat

    func clearChat() {
        messagingService.clearMessages(for: conversationId)
    }

    deinit {
        typingTimer?.invalidate()
        // Capture conversationId before self is deallocated
        // and only send if we were actually typing
        let wasTyping = isTyping
        let convId = conversationId
        if wasTyping {
            Task {
                try? await MessagingService.shared.sendTypingIndicator(to: convId, isTyping: false)
            }
        }
    }
}
