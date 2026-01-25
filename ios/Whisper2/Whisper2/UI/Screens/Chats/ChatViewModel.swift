import SwiftUI
import Observation

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

/// Manages a single chat state
@Observable
final class ChatViewModel {
    // MARK: - State

    let conversationId: String
    let participantId: String
    let participantName: String
    let participantAvatarURL: URL?

    var messages: [ChatMessage] = []
    var isLoading = false
    var isSending = false
    var error: String?

    var messageText: String = ""
    var isParticipantOnline = false
    var isParticipantTyping = false

    // Pagination
    var hasMoreMessages = true
    private var isLoadingMore = false

    // MARK: - Dependencies

    // These will be injected when actual services are implemented
    // private let messagingService: MessagingServiceProtocol
    // private let cryptoService: CryptoServiceProtocol

    // MARK: - Init

    init(
        conversationId: String,
        participantId: String,
        participantName: String,
        participantAvatarURL: URL? = nil
    ) {
        self.conversationId = conversationId
        self.participantId = participantId
        self.participantName = participantName
        self.participantAvatarURL = participantAvatarURL
    }

    convenience init(conversation: Conversation) {
        self.init(
            conversationId: conversation.id,
            participantId: conversation.participantId,
            participantName: conversation.participantName,
            participantAvatarURL: conversation.participantAvatarURL
        )
        self.isParticipantOnline = conversation.isOnline
        self.isParticipantTyping = conversation.isTyping
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
            do {
                // Simulate loading
                try await Task.sleep(for: .milliseconds(500))

                // Placeholder data
                let now = Date()
                messages = [
                    ChatMessage(
                        id: "1",
                        senderId: participantId,
                        content: "Hey there!",
                        timestamp: now.addingTimeInterval(-3600),
                        status: .read,
                        isFromMe: false
                    ),
                    ChatMessage(
                        id: "2",
                        senderId: "me",
                        content: "Hi! How are you?",
                        timestamp: now.addingTimeInterval(-3500),
                        status: .read,
                        isFromMe: true
                    ),
                    ChatMessage(
                        id: "3",
                        senderId: participantId,
                        content: "I'm doing great, thanks for asking! What about you?",
                        timestamp: now.addingTimeInterval(-3400),
                        status: .read,
                        isFromMe: false
                    ),
                    ChatMessage(
                        id: "4",
                        senderId: "me",
                        content: "Pretty good! Want to grab coffee later?",
                        timestamp: now.addingTimeInterval(-300),
                        status: .delivered,
                        isFromMe: true
                    )
                ]

                isLoading = false
            } catch {
                self.error = "Failed to load messages"
                isLoading = false
            }
        }
    }

    func loadMoreMessages() {
        guard hasMoreMessages, !isLoadingMore else { return }
        isLoadingMore = true

        Task { @MainActor in
            // Simulate loading more
            try? await Task.sleep(for: .milliseconds(500))

            // In real implementation, fetch older messages
            hasMoreMessages = false
            isLoadingMore = false
        }
    }

    func sendMessage() {
        guard canSend else { return }

        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        messageText = ""

        let newMessage = ChatMessage(
            id: UUID().uuidString,
            senderId: "me",
            content: text,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Simulate sending
                try await Task.sleep(for: .milliseconds(500))

                // Update status to sent
                if let index = messages.firstIndex(where: { $0.id == newMessage.id }) {
                    messages[index].status = .sent
                }

                isSending = false
            } catch {
                // Mark as failed
                if let index = messages.firstIndex(where: { $0.id == newMessage.id }) {
                    messages[index].status = .failed
                }
                isSending = false
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
                // Simulate retry
                try await Task.sleep(for: .milliseconds(500))

                if let index = messages.firstIndex(where: { $0.id == message.id }) {
                    messages[index].status = .sent
                }
            } catch {
                if let index = messages.firstIndex(where: { $0.id == message.id }) {
                    messages[index].status = .failed
                }
            }
        }
    }

    func deleteMessage(_ message: ChatMessage) {
        messages.removeAll { $0.id == message.id }
    }

    func setTyping(_ isTyping: Bool) {
        // Send typing indicator to server
    }

    func clearError() {
        error = nil
    }
}
