import Foundation
import Combine

/// View model for chat list
@MainActor
final class ChatsViewModel: ObservableObject {
    @Published var conversations: [Conversation] = []
    @Published var isLoading = false
    @Published var error: String?
    
    private let messagingService = MessagingService.shared
    private var cancellables = Set<AnyCancellable>()
    
    init() {
        setupBindings()
        loadConversations()
    }
    
    private func setupBindings() {
        messagingService.$conversations
            .receive(on: DispatchQueue.main)
            .sink { [weak self] conversations in
                self?.conversations = conversations
            }
            .store(in: &cancellables)
    }
    
    func loadConversations() {
        isLoading = true
        // Conversations are managed by MessagingService
        // They will be updated via the binding
        isLoading = false
    }
    
    func deleteConversations(at offsets: IndexSet) {
        // Remove from local list
        // In a real app, you might want to archive/delete on server too
        var updatedConversations = conversations
        updatedConversations.remove(atOffsets: offsets)
        conversations = updatedConversations
    }
    
    func markAsRead(_ conversationId: String) {
        messagingService.markAsRead(conversationId: conversationId)
    }
    
    func refreshConversations() async {
        isLoading = true
        do {
            try await messagingService.fetchPendingMessages()
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}
