import SwiftUI

/// Connection status indicator
struct ConnectionStatusBar: View {
    @ObservedObject private var webSocket = WebSocketService.shared

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)

            Text(statusText)
                .font(.caption2)
                .foregroundColor(statusColor)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .background(statusColor.opacity(0.15))
        .cornerRadius(12)
        .opacity(webSocket.connectionState == .connected ? 0 : 1)
        .animation(.easeInOut(duration: 0.3), value: webSocket.connectionState)
    }

    private var statusColor: Color {
        switch webSocket.connectionState {
        case .connected:
            return .green
        case .connecting:
            return .orange
        case .reconnecting:
            return .orange
        case .disconnected:
            return .red
        }
    }

    private var statusText: String {
        switch webSocket.connectionState {
        case .connected:
            return "Connected"
        case .connecting:
            return "Connecting..."
        case .reconnecting:
            return "Reconnecting..."
        case .disconnected:
            return "Disconnected"
        }
    }
}

/// Conversation list view
struct ChatsListView: View {
    @ObservedObject var viewModel: ChatsViewModel
    @ObservedObject private var contactsService = ContactsService.shared
    @ObservedObject private var webSocket = WebSocketService.shared
    @State private var showNewChat = false
    @State private var showMessageRequests = false
    @State private var searchText = ""
    @State private var showDeleteConfirmation = false
    @State private var conversationToDelete: Conversation?
    @State private var showArchiveConfirmation = false
    @State private var conversationToArchive: Conversation?

    private var filteredConversations: [Conversation] {
        let convos: [Conversation]
        if searchText.isEmpty {
            convos = viewModel.conversations
        } else {
            convos = viewModel.conversations.filter {
                $0.displayName.localizedCaseInsensitiveContains(searchText)
            }
        }
        // Sort: pinned first, then by last message time
        return convos.sorted { lhs, rhs in
            if lhs.isPinned != rhs.isPinned {
                return lhs.isPinned
            }
            return (lhs.lastMessageTime ?? .distantPast) > (rhs.lastMessageTime ?? .distantPast)
        }
    }

    private var pendingRequestCount: Int {
        contactsService.messageRequests.values.filter { $0.status == .pending }.count
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 0) {
                    // Message requests banner (if any pending)
                    if pendingRequestCount > 0 {
                        Button(action: { showMessageRequests = true }) {
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle()
                                        .fill(Color.orange.opacity(0.2))
                                        .frame(width: 40, height: 40)
                                    Image(systemName: "envelope.badge.fill")
                                        .foregroundColor(.orange)
                                }

                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Message Requests")
                                        .font(.subheadline)
                                        .fontWeight(.semibold)
                                        .foregroundColor(.white)
                                    Text("\(pendingRequestCount) pending request\(pendingRequestCount == 1 ? "" : "s")")
                                        .font(.caption)
                                        .foregroundColor(.gray)
                                }

                                Spacer()

                                Image(systemName: "chevron.right")
                                    .foregroundColor(.gray)
                            }
                            .padding()
                            .background(Color.orange.opacity(0.1))
                        }
                    }

                    if viewModel.conversations.isEmpty && pendingRequestCount == 0 {
                        EmptyChatsView(showNewChat: $showNewChat)
                    } else if viewModel.conversations.isEmpty {
                        Spacer()
                        VStack(spacing: 16) {
                            Text("No conversations yet")
                                .font(.headline)
                                .foregroundColor(.white)
                            Text("Check your message requests or start a new chat")
                                .font(.subheadline)
                                .foregroundColor(.gray)
                        }
                        Spacer()
                    } else {
                        List {
                            ForEach(filteredConversations) { conversation in
                                NavigationLink(destination: ChatView(conversation: conversation)) {
                                    ChatRow(conversation: conversation)
                                }
                                .listRowBackground(Color.black)
                                .listRowSeparatorTint(Color.gray.opacity(0.3))
                                // Swipe left actions: Delete, Archive
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    Button(role: .destructive) {
                                        conversationToDelete = conversation
                                        showDeleteConfirmation = true
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }

                                    Button {
                                        viewModel.archiveConversation(conversation)
                                    } label: {
                                        Label("Archive", systemImage: "archivebox")
                                    }
                                    .tint(.orange)
                                }
                                // Swipe right actions: Pin, Mute
                                .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                    Button {
                                        viewModel.togglePin(conversation)
                                        // Haptic feedback
                                        let impact = UIImpactFeedbackGenerator(style: .medium)
                                        impact.impactOccurred()
                                    } label: {
                                        Label(
                                            conversation.isPinned ? "Unpin" : "Pin",
                                            systemImage: conversation.isPinned ? "pin.slash" : "pin"
                                        )
                                    }
                                    .tint(.blue)

                                    Button {
                                        viewModel.toggleMute(conversation)
                                        // Haptic feedback
                                        let impact = UIImpactFeedbackGenerator(style: .light)
                                        impact.impactOccurred()
                                    } label: {
                                        Label(
                                            conversation.isMuted ? "Unmute" : "Mute",
                                            systemImage: conversation.isMuted ? "bell" : "bell.slash"
                                        )
                                    }
                                    .tint(.purple)
                                }
                            }
                        }
                        .listStyle(.plain)
                        .searchable(text: $searchText, prompt: "Search chats")
                        .refreshable {
                            await viewModel.refreshConversations()
                        }
                    }
                }
            }
            .navigationTitle("Chats")
            .alert("Delete Conversation", isPresented: $showDeleteConfirmation) {
                Button("Cancel", role: .cancel) {
                    conversationToDelete = nil
                }
                Button("Delete", role: .destructive) {
                    if let conversation = conversationToDelete {
                        viewModel.deleteConversation(conversation)
                    }
                    conversationToDelete = nil
                }
            } message: {
                Text("Are you sure you want to delete this conversation? This action cannot be undone.")
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    HStack(spacing: 12) {
                        if pendingRequestCount > 0 {
                            Button(action: { showMessageRequests = true }) {
                                ZStack(alignment: .topTrailing) {
                                    Image(systemName: "tray.fill")
                                    Circle()
                                        .fill(Color.orange)
                                        .frame(width: 8, height: 8)
                                        .offset(x: 2, y: -2)
                                }
                            }
                        }

                        // Connection status (only show when not connected)
                        if webSocket.connectionState != .connected {
                            ConnectionStatusBar()
                        }
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showNewChat = true }) {
                        Image(systemName: "square.and.pencil")
                    }
                }
            }
            .sheet(isPresented: $showNewChat) {
                NewChatView()
            }
            .sheet(isPresented: $showMessageRequests) {
                MessageRequestsView()
            }
        }
    }

    private func deleteConversation(at offsets: IndexSet) {
        viewModel.deleteConversations(at: offsets)
    }
}

/// Empty state view
struct EmptyChatsView: View {
    @Binding var showNewChat: Bool
    
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 60))
                .foregroundColor(.gray)
            
            Text("No Conversations")
                .font(.title2)
                .fontWeight(.semibold)
                .foregroundColor(.white)
            
            Text("Start a new conversation with your contacts")
                .font(.subheadline)
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
            
            Button(action: { showNewChat = true }) {
                Text("New Chat")
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 32)
                    .padding(.vertical, 12)
                    .background(Color.blue)
                    .cornerRadius(25)
            }
            .padding(.top, 10)
        }
        .padding()
    }
}

/// New chat sheet - select from contacts
struct NewChatView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var contactsViewModel = ContactsViewModel()
    @State private var searchText = ""
    
    private var filteredContacts: [Contact] {
        if searchText.isEmpty {
            return contactsViewModel.contacts
        }
        return contactsViewModel.contacts.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText) ||
            $0.whisperId.localizedCaseInsensitiveContains(searchText)
        }
    }
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                
                if contactsViewModel.contacts.isEmpty {
                    VStack(spacing: 20) {
                        Image(systemName: "person.crop.circle.badge.plus")
                            .font(.system(size: 60))
                            .foregroundColor(.gray)
                        
                        Text("No Contacts")
                            .font(.title2)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                        
                        Text("Add contacts first to start chatting")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    }
                } else {
                    List {
                        ForEach(filteredContacts) { contact in
                            Button(action: { startChat(with: contact) }) {
                                HStack(spacing: 12) {
                                    // Avatar
                                    Circle()
                                        .fill(Color.gray.opacity(0.3))
                                        .frame(width: 50, height: 50)
                                        .overlay(
                                            Text(String(contact.displayName.prefix(1)).uppercased())
                                                .font(.title2)
                                                .foregroundColor(.white)
                                        )
                                    
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(contact.displayName)
                                            .font(.headline)
                                            .foregroundColor(.white)
                                        
                                        Text(contact.whisperId)
                                            .font(.caption)
                                            .foregroundColor(.gray)
                                        
                                        // Show warning if no public key
                                        if !ContactsService.shared.hasValidPublicKey(for: contact.whisperId) {
                                            HStack(spacing: 4) {
                                                Image(systemName: "exclamationmark.triangle.fill")
                                                    .font(.caption2)
                                                Text("Scan QR to enable messaging")
                                                    .font(.caption2)
                                            }
                                            .foregroundColor(.orange)
                                        }
                                    }
                                    
                                    Spacer()
                                    
                                    Image(systemName: "chevron.right")
                                        .foregroundColor(.gray)
                                }
                                .padding(.vertical, 4)
                            }
                            .listRowBackground(Color.black)
                            .listRowSeparatorTint(Color.gray.opacity(0.3))
                        }
                    }
                    .listStyle(.plain)
                    .searchable(text: $searchText, prompt: "Search contacts")
                }
            }
            .navigationTitle("New Chat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
    
    private func startChat(with contact: Contact) {
        // The conversation will be created automatically when sending first message
        // For now, just dismiss and the user can navigate to the contact
        dismiss()
        
        // Post notification to navigate to chat
        NotificationCenter.default.post(
            name: NSNotification.Name("OpenChat"),
            object: nil,
            userInfo: ["peerId": contact.whisperId]
        )
    }
}

#Preview {
    ChatsListView(viewModel: ChatsViewModel())
}
