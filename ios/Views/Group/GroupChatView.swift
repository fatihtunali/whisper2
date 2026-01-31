import SwiftUI
import UIKit

/// Chat view for a group conversation
struct GroupChatView: View {
    let group: ChatGroup
    @StateObject private var viewModel: GroupChatViewModel
    @ObservedObject private var settingsManager = AppSettingsManager.shared
    @Environment(\.dismiss) private var dismiss
    @State private var showGroupInfo = false
    @FocusState private var isInputFocused: Bool

    init(group: ChatGroup) {
        self.group = group
        _viewModel = StateObject(wrappedValue: GroupChatViewModel(groupId: group.id))
    }

    /// Initialize with just a group ID - fetches group from service
    init(groupId: String) {
        // Get group from service
        if let existingGroup = GroupService.shared.groups[groupId] {
            self.group = existingGroup
        } else {
            // Create a placeholder group if not found
            self.group = ChatGroup(
                id: groupId,
                title: "Group",
                memberIds: [],
                creatorId: ""
            )
        }
        _viewModel = StateObject(wrappedValue: GroupChatViewModel(groupId: groupId))
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
                    enterToSend: settingsManager.settings.enterToSend,
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
    @ObservedObject private var groupService = GroupService.shared
    @State private var showAddMembers = false
    @State private var showLeaveConfirm = false
    @State private var memberToKick: String?
    @State private var showKickConfirm = false
    @State private var isProcessing = false
    private let contacts = ContactsService.shared

    private var isOwner: Bool {
        groupService.isOwner(of: group.id)
    }

    private var currentGroup: ChatGroup {
        groupService.getGroup(group.id) ?? group
    }

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

                            Text(currentGroup.title)
                                .font(.title2)
                                .fontWeight(.semibold)
                                .foregroundColor(.white)

                            Text("\(currentGroup.memberIds.count) members")
                                .font(.subheadline)
                                .foregroundColor(.gray)

                            if isOwner {
                                Text("You are the admin")
                                    .font(.caption)
                                    .foregroundColor(.blue)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .listRowBackground(Color.black)
                    }

                    // Members
                    Section("Members") {
                        ForEach(currentGroup.memberIds, id: \.self) { memberId in
                            MemberRow(
                                memberId: memberId,
                                isCreator: memberId == currentGroup.creatorId,
                                isCurrentUser: memberId == AuthService.shared.currentUser?.whisperId,
                                canKick: isOwner && memberId != currentGroup.creatorId && memberId != AuthService.shared.currentUser?.whisperId,
                                onKick: {
                                    memberToKick = memberId
                                    showKickConfirm = true
                                }
                            )
                            .listRowBackground(Color.black)
                        }

                        if isOwner {
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

                if isProcessing {
                    Color.black.opacity(0.5)
                        .ignoresSafeArea()
                    ProgressView()
                        .tint(.white)
                }
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
            .alert("Remove Member?", isPresented: $showKickConfirm) {
                Button("Remove", role: .destructive) {
                    if let memberId = memberToKick {
                        kickMember(memberId)
                    }
                }
                Button("Cancel", role: .cancel) {
                    memberToKick = nil
                }
            } message: {
                if let memberId = memberToKick {
                    Text("Remove \(getMemberName(memberId)) from the group?")
                }
            }
            .sheet(isPresented: $showAddMembers) {
                AddMembersView(groupId: group.id)
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
        isProcessing = true
        Task {
            try? await GroupService.shared.leaveGroup(group.id)
            await MainActor.run {
                isProcessing = false
                dismiss()
            }
        }
    }

    private func kickMember(_ memberId: String) {
        isProcessing = true
        Task {
            do {
                try await GroupService.shared.kickMember(memberId, from: group.id)
            } catch {
                print("[GroupInfo] Failed to kick member: \(error)")
            }
            await MainActor.run {
                isProcessing = false
                memberToKick = nil
            }
        }
    }
}

/// Row for a member in group info
struct MemberRow: View {
    let memberId: String
    let isCreator: Bool
    let isCurrentUser: Bool
    let canKick: Bool
    let onKick: () -> Void

    private let contacts = ContactsService.shared

    var body: some View {
        HStack {
            Circle()
                .fill(Color.gray.opacity(0.3))
                .frame(width: 40, height: 40)
                .overlay(
                    Text(String(getMemberName().prefix(1)).uppercased())
                        .font(.headline)
                        .foregroundColor(.white)
                )

            VStack(alignment: .leading) {
                Text(getMemberName())
                    .foregroundColor(.white)

                if isCreator {
                    Text("Admin")
                        .font(.caption)
                        .foregroundColor(.blue)
                } else if isCurrentUser {
                    Text("You")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }

            Spacer()

            if canKick {
                Button(action: onKick) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.red.opacity(0.7))
                        .font(.title3)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func getMemberName() -> String {
        if isCurrentUser {
            return "You"
        }
        return contacts.getContact(whisperId: memberId)?.displayName ?? memberId
    }
}

/// View for adding members to existing group
struct AddMembersView: View {
    let groupId: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var contactsService = ContactsService.shared
    @ObservedObject var groupService = GroupService.shared
    @State private var selectedMembers: Set<String> = []
    @State private var isAdding = false
    @State private var error: String?

    private var currentMembers: Set<String> {
        Set(groupService.getGroup(groupId)?.memberIds ?? [])
    }

    private var availableContacts: [Contact] {
        contactsService.getAllContacts().filter { !currentMembers.contains($0.whisperId) }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if availableContacts.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "person.crop.circle.badge.checkmark")
                            .font(.system(size: 50))
                            .foregroundColor(.gray)

                        Text("All contacts are already members")
                            .foregroundColor(.gray)
                    }
                } else {
                    List {
                        ForEach(availableContacts) { contact in
                            Button(action: { toggleMember(contact.whisperId) }) {
                                HStack {
                                    Circle()
                                        .fill(Color.gray.opacity(0.3))
                                        .frame(width: 40, height: 40)
                                        .overlay(
                                            Text(String(contact.displayName.prefix(1)).uppercased())
                                                .font(.headline)
                                                .foregroundColor(.white)
                                        )

                                    VStack(alignment: .leading) {
                                        Text(contact.displayName)
                                            .foregroundColor(.white)

                                        if !contactsService.hasValidPublicKey(for: contact.whisperId) {
                                            Text("No encryption key")
                                                .font(.caption)
                                                .foregroundColor(.orange)
                                        }
                                    }

                                    Spacer()

                                    if selectedMembers.contains(contact.whisperId) {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundColor(.blue)
                                    } else {
                                        Image(systemName: "circle")
                                            .foregroundColor(.gray)
                                    }
                                }
                            }
                            .disabled(!contactsService.hasValidPublicKey(for: contact.whisperId))
                            .listRowBackground(Color.black)
                        }
                    }
                    .listStyle(.plain)
                }

                if let error = error {
                    VStack {
                        Spacer()
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.red)
                            .padding()
                    }
                }
            }
            .navigationTitle("Add Members")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { addMembers() }
                        .disabled(selectedMembers.isEmpty || isAdding)
                }
            }
        }
    }

    private func toggleMember(_ whisperId: String) {
        if selectedMembers.contains(whisperId) {
            selectedMembers.remove(whisperId)
        } else {
            selectedMembers.insert(whisperId)
        }
    }

    private func addMembers() {
        isAdding = true
        error = nil

        Task {
            do {
                try await groupService.addMembers(Array(selectedMembers), to: groupId)
                await MainActor.run {
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                    isAdding = false
                }
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
