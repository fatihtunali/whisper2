import SwiftUI
import UIKit

/// Chat view for a group conversation
struct GroupChatView: View {
    let group: ChatGroup
    @StateObject private var viewModel: GroupChatViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showGroupInfo = false
    @FocusState private var isInputFocused: Bool

    init(group: ChatGroup) {
        self.group = group
        _viewModel = StateObject(wrappedValue: GroupChatViewModel(groupId: group.id))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                // Messages
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(viewModel.messages) { message in
                                GroupMessageBubble(
                                    message: message,
                                    senderName: viewModel.getSenderName(message.from)
                                ) { deleteForAll in
                                    viewModel.deleteMessage(messageId: message.id, deleteForAll: deleteForAll)
                                }
                                .id(message.id)
                            }
                        }
                        .padding()
                    }
                    .onChange(of: viewModel.messages.count) { _, _ in
                        if let lastId = viewModel.messages.last?.id {
                            withAnimation {
                                proxy.scrollTo(lastId, anchor: .bottom)
                            }
                        }
                    }
                }

                // Input bar
                MessageInputBar(
                    text: $viewModel.messageText,
                    isFocused: $isInputFocused,
                    isEnabled: true,
                    onSend: { viewModel.sendMessage() }
                )
            }
        }
        .navigationTitle(group.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { showGroupInfo = true }) {
                    Image(systemName: "info.circle")
                }
            }
        }
        .sheet(isPresented: $showGroupInfo) {
            GroupInfoView(group: group)
        }
        .onAppear {
            viewModel.markAsRead()
        }
    }
}

/// Message bubble for group chat (shows sender name)
struct GroupMessageBubble: View {
    let message: Message
    let senderName: String
    var onDelete: ((Bool) -> Void)? = nil  // Bool = deleteForAll

    private var isOutgoing: Bool {
        message.direction == .outgoing
    }

    var body: some View {
        HStack {
            if isOutgoing { Spacer() }

            VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 4) {
                // Sender name for incoming messages
                if !isOutgoing {
                    Text(senderName)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.blue)
                }

                // Message content
                Text(message.content)
                    .font(.body)
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        isOutgoing ?
                        LinearGradient(colors: [.blue, .purple], startPoint: .leading, endPoint: .trailing) :
                        LinearGradient(colors: [Color.gray.opacity(0.3), Color.gray.opacity(0.3)], startPoint: .leading, endPoint: .trailing)
                    )
                    .cornerRadius(16)

                // Timestamp
                Text(formatTime(message.timestamp))
                    .font(.caption2)
                    .foregroundColor(.gray)
            }
            .contextMenu {
                // Copy
                Button(action: {
                    UIPasteboard.general.string = message.content
                }) {
                    Label("Copy", systemImage: "doc.on.doc")
                }

                // Delete for me
                Button(role: .destructive, action: {
                    onDelete?(false)
                }) {
                    Label("Delete for Me", systemImage: "trash")
                }

                // Delete for all (only for outgoing messages)
                if isOutgoing {
                    Button(role: .destructive, action: {
                        onDelete?(true)
                    }) {
                        Label("Delete for All", systemImage: "trash.fill")
                    }
                }
            }

            if !isOutgoing { Spacer() }
        }
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

/// ViewModel for group chat
@MainActor
class GroupChatViewModel: ObservableObject {
    @Published var messages: [Message] = []
    @Published var messageText = ""

    let groupId: String
    private let groupService = GroupService.shared
    private let contacts = ContactsService.shared

    init(groupId: String) {
        self.groupId = groupId
        loadMessages()
    }

    func loadMessages() {
        messages = groupService.getMessages(for: groupId)
    }

    func sendMessage() {
        guard !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        let text = messageText
        messageText = ""

        Task {
            do {
                try await groupService.sendMessage(to: groupId, content: text)
                loadMessages()
            } catch {
                print("Failed to send group message: \(error)")
            }
        }
    }

    func getSenderName(_ senderId: String) -> String {
        if senderId == AuthService.shared.currentUser?.whisperId {
            return "You"
        }
        return contacts.getContact(whisperId: senderId)?.displayName ?? senderId
    }

    func markAsRead() {
        groupService.markAsRead(groupId: groupId)
    }

    func deleteMessage(messageId: String, deleteForAll: Bool) {
        if deleteForAll {
            // Delete for everyone - send to server
            Task {
                do {
                    try await MessagingService.shared.deleteMessage(
                        messageId: messageId,
                        conversationId: groupId,
                        deleteForEveryone: true
                    )
                    await MainActor.run {
                        groupService.deleteMessage(messageId: messageId, groupId: groupId)
                        loadMessages()
                    }
                } catch {
                    print("Failed to delete message: \(error)")
                }
            }
        } else {
            // Delete locally only
            groupService.deleteMessage(messageId: messageId, groupId: groupId)
            loadMessages()
        }
    }
}

/// Group info view
struct GroupInfoView: View {
    let group: ChatGroup
    @Environment(\.dismiss) private var dismiss
    @State private var showAddMembers = false
    @State private var showLeaveConfirm = false
    private let contacts = ContactsService.shared

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                List {
                    // Group header
                    Section {
                        VStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(Color.purple.opacity(0.3))
                                    .frame(width: 80, height: 80)

                                Image(systemName: "person.3.fill")
                                    .font(.title)
                                    .foregroundColor(.purple)
                            }

                            Text(group.title)
                                .font(.title2)
                                .fontWeight(.semibold)
                                .foregroundColor(.white)

                            Text("\(group.memberIds.count) members")
                                .font(.subheadline)
                                .foregroundColor(.gray)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .listRowBackground(Color.black)
                    }

                    // Members
                    Section("Members") {
                        ForEach(group.memberIds, id: \.self) { memberId in
                            HStack {
                                Circle()
                                    .fill(Color.gray.opacity(0.3))
                                    .frame(width: 40, height: 40)
                                    .overlay(
                                        Text(String(getMemberName(memberId).prefix(1)).uppercased())
                                            .font(.headline)
                                            .foregroundColor(.white)
                                    )

                                VStack(alignment: .leading) {
                                    Text(getMemberName(memberId))
                                        .foregroundColor(.white)

                                    if memberId == group.creatorId {
                                        Text("Admin")
                                            .font(.caption)
                                            .foregroundColor(.blue)
                                    }
                                }

                                Spacer()
                            }
                            .listRowBackground(Color.black)
                        }

                        Button(action: { showAddMembers = true }) {
                            HStack {
                                Image(systemName: "person.badge.plus")
                                    .foregroundColor(.blue)
                                Text("Add Members")
                                    .foregroundColor(.blue)
                            }
                        }
                        .listRowBackground(Color.black)
                    }

                    // Actions
                    Section {
                        Button(role: .destructive, action: { showLeaveConfirm = true }) {
                            HStack {
                                Image(systemName: "rectangle.portrait.and.arrow.right")
                                Text("Leave Group")
                            }
                            .foregroundColor(.red)
                        }
                        .listRowBackground(Color.black)
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("Group Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .alert("Leave Group?", isPresented: $showLeaveConfirm) {
                Button("Leave", role: .destructive) {
                    leaveGroup()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("You will no longer receive messages from this group.")
            }
        }
    }

    private func getMemberName(_ memberId: String) -> String {
        if memberId == AuthService.shared.currentUser?.whisperId {
            return "You"
        }
        return contacts.getContact(whisperId: memberId)?.displayName ?? memberId
    }

    private func leaveGroup() {
        Task {
            try? await GroupService.shared.leaveGroup(group.id)
            await MainActor.run {
                dismiss()
            }
        }
    }
}

#Preview {
    GroupChatView(group: ChatGroup(
        id: "test",
        title: "Test Group",
        memberIds: ["WSP-1234", "WSP-5678"],
        creatorId: "WSP-1234"
    ))
}
