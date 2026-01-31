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
    @State private var itemToDelete: ChatItem?
    @State private var showArchiveConfirmation = false
    @State private var itemToArchive: ChatItem?

    private var filteredChatItems: [ChatItem] {
        let items: [ChatItem]
        if searchText.isEmpty {
            items = viewModel.chatItems
        } else {
            items = viewModel.chatItems.filter {
                $0.name.localizedCaseInsensitiveContains(searchText)
            }
        }
        // Sort: pinned first, then by last message time
        return items.sorted { lhs, rhs in
            if lhs.isPinned != rhs.isPinned {
                return lhs.isPinned
            }
            return lhs.lastMessageTimestamp > rhs.lastMessageTimestamp
        }
    }

    private var pendingRequestCount: Int {
        contactsService.messageRequests.values.filter { $0.status == .pending }.count
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                mainContent
            }
            .navigationTitle("Chats")
            .alert(deleteAlertTitle, isPresented: $showDeleteConfirmation) {
                deleteAlertButtons
            } message: {
                Text(deleteAlertMessage)
            }
            .toolbar { toolbarContent }
            .sheet(isPresented: $showNewChat) {
                NewChatView()
            }
            .sheet(isPresented: $showMessageRequests) {
                MessageRequestsView()
            }
        }
    }

    // MARK: - Extracted Views

    private var deleteAlertTitle: String {
        itemToDelete?.isGroup == true ? "Delete Group" : "Delete Conversation"
    }

    private var deleteAlertMessage: String {
        itemToDelete?.isGroup == true
            ? "Are you sure you want to leave this group? This action cannot be undone."
            : "Are you sure you want to delete this conversation? This action cannot be undone."
    }

    @ViewBuilder
    private var deleteAlertButtons: some View {
        Button("Cancel", role: .cancel) {
            itemToDelete = nil
        }
        Button("Delete", role: .destructive) {
            performDelete()
        }
    }

    private func performDelete() {
        if let item = itemToDelete {
            if item.isGroup {
                Task {
                    try? await GroupService.shared.leaveGroup(item.id)
                }
            } else {
                if let conv = viewModel.conversations.first(where: { $0.peerId == item.id }) {
                    viewModel.deleteConversation(conv)
                }
            }
        }
        itemToDelete = nil
    }

    @ViewBuilder
    private var mainContent: some View {
        VStack(spacing: 0) {
            messageRequestsBanner
            chatListContent
        }
    }

    @ViewBuilder
    private var messageRequestsBanner: some View {
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
    }

    @ViewBuilder
    private var chatListContent: some View {
        if viewModel.chatItems.isEmpty && pendingRequestCount == 0 {
            EmptyChatsView(showNewChat: $showNewChat)
        } else if viewModel.chatItems.isEmpty {
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
            chatsList
        }
    }

    private var chatsList: some View {
        List {
            ForEach(filteredChatItems) { item in
                chatRow(for: item)
            }
        }
        .listStyle(.plain)
        .searchable(text: $searchText, prompt: "Search chats")
        .refreshable {
            await viewModel.refreshConversations()
        }
    }

    @ViewBuilder
    private func chatRow(for item: ChatItem) -> some View {
        ChatItemRowLink(item: item)
            .listRowBackground(Color.black)
            .listRowSeparatorTint(Color.gray.opacity(0.3))
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) {
                    itemToDelete = item
                    showDeleteConfirmation = true
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            }
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                if !item.isGroup {
                    pinButton(for: item)
                    muteButton(for: item)
                }
            }
    }

    private func pinButton(for item: ChatItem) -> some View {
        Button {
            if let conv = viewModel.conversations.first(where: { $0.peerId == item.id }) {
                viewModel.togglePin(conv)
            }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        } label: {
            Label(item.isPinned ? "Unpin" : "Pin", systemImage: item.isPinned ? "pin.slash" : "pin")
        }
        .tint(.blue)
    }

    private func muteButton(for item: ChatItem) -> some View {
        Button {
            if let conv = viewModel.conversations.first(where: { $0.peerId == item.id }) {
                viewModel.toggleMute(conv)
            }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            Label(item.isMuted ? "Unmute" : "Mute", systemImage: item.isMuted ? "bell" : "bell.slash")
        }
        .tint(.purple)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            leadingToolbarContent
        }
        ToolbarItem(placement: .navigationBarTrailing) {
            Button(action: { showNewChat = true }) {
                Image(systemName: "square.and.pencil")
            }
        }
    }

    @ViewBuilder
    private var leadingToolbarContent: some View {
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

            if webSocket.connectionState != .connected {
                ConnectionStatusBar()
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

/// Chat item row that links to either ChatView or GroupChatView
struct ChatItemRowLink: View {
    let item: ChatItem

    var body: some View {
        if item.isGroup {
            NavigationLink(destination: GroupChatView(groupId: item.id)) {
                ChatItemRow(item: item)
            }
        } else {
            NavigationLink(destination: ChatView(peerId: item.id)) {
                ChatItemRow(item: item)
            }
        }
    }
}

/// Chat item row for display
struct ChatItemRow: View {
    let item: ChatItem

    var body: some View {
        HStack(spacing: 12) {
            // Avatar
            if item.isGroup {
                // Group avatar with purple background
                ZStack {
                    Circle()
                        .fill(Color.purple.opacity(0.3))
                        .frame(width: 52, height: 52)
                    Image(systemName: "person.3.fill")
                        .foregroundColor(.purple)
                        .font(.title3)
                }
            } else {
                // Contact avatar
                Circle()
                    .fill(Color.gray.opacity(0.3))
                    .frame(width: 52, height: 52)
                    .overlay(
                        Text(String(item.name.prefix(1)).uppercased())
                            .font(.title2)
                            .foregroundColor(.white)
                    )
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(item.name)
                        .font(.headline)
                        .foregroundColor(.white)
                        .lineLimit(1)

                    Spacer()

                    Text(formatTimestamp(item.lastMessageTimestamp))
                        .font(.caption)
                        .foregroundColor(.gray)
                }

                HStack {
                    if item.isTyping {
                        Text("typing...")
                            .font(.subheadline)
                            .foregroundColor(.blue)
                    } else if let lastMessage = item.lastMessage {
                        Text(lastMessage)
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .lineLimit(1)
                    } else if item.isGroup {
                        Text("\(item.memberCount ?? 0) members")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    } else {
                        Text("Start a conversation")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    }

                    Spacer()

                    if item.unreadCount > 0 {
                        Text("\(item.unreadCount)")
                            .font(.caption2)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .frame(minWidth: 20, minHeight: 20)
                            .background(Color.blue)
                            .clipShape(Circle())
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func formatTimestamp(_ date: Date) -> String {
        let now = Date()
        let diff = now.timeIntervalSince(date)
        let seconds = diff
        let minutes = seconds / 60
        let hours = minutes / 60
        let days = hours / 24

        if days > 7 {
            let formatter = DateFormatter()
            formatter.dateFormat = "MMM d"
            return formatter.string(from: date)
        } else if days >= 1 {
            return "\(Int(days))d"
        } else if hours >= 1 {
            return "\(Int(hours))h"
        } else if minutes >= 1 {
            return "\(Int(minutes))m"
        } else {
            return "now"
        }
    }
}

#Preview {
    ChatsListView(viewModel: ChatsViewModel())
}
