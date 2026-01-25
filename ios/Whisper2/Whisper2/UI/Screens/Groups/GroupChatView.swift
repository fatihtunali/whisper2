import SwiftUI

/// Group chat view
struct GroupChatView: View {
    @Bindable var viewModel: GroupViewModel
    let group: Group
    @Environment(\.dismiss) private var dismiss
    @State private var showingGroupInfo = false

    var body: some View {
        VStack(spacing: 0) {
            // Messages list
            messagesView

            // Input bar
            InputBar(
                text: $viewModel.messageText,
                isEnabled: !viewModel.isSending,
                onSend: {
                    viewModel.sendGroupMessage()
                },
                onAttachment: {
                    // Show attachment picker
                }
            )
        }
        .background(Theme.Colors.background)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                groupHeader
            }

            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showingGroupInfo = true
                } label: {
                    Image(systemName: "info.circle")
                }
            }
        }
        .sheet(isPresented: $showingGroupInfo) {
            GroupInfoView(viewModel: viewModel, group: group)
        }
        .onAppear {
            viewModel.loadGroupMessages(group)
        }
        .alert("Error", isPresented: Binding(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.clearError() } }
        )) {
            Button("OK") { viewModel.clearError() }
        } message: {
            Text(viewModel.error ?? "")
        }
    }

    private var groupHeader: some View {
        Button {
            showingGroupInfo = true
        } label: {
            HStack(spacing: Theme.Spacing.xs) {
                GroupAvatarView(
                    memberNames: group.members.map { $0.displayName },
                    size: 32
                )

                VStack(alignment: .leading, spacing: 0) {
                    Text(group.title)
                        .font(Theme.Typography.headline)
                        .foregroundColor(Theme.Colors.textPrimary)

                    let onlineCount = group.members.filter { $0.isOnline }.count
                    Text("\(group.memberCount) members, \(onlineCount) online")
                        .font(Theme.Typography.caption2)
                        .foregroundColor(Theme.Colors.textSecondary)
                }
            }
        }
    }

    private var messagesView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: Theme.Spacing.sm) {
                    ForEach(viewModel.messages) { message in
                        GroupMessageRow(
                            message: message,
                            senderName: getSenderName(for: message)
                        )
                        .id(message.id)
                    }
                }
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.bottom, Theme.Spacing.sm)
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                if let lastMessage = viewModel.messages.last {
                    withAnimation {
                        proxy.scrollTo(lastMessage.id, anchor: .bottom)
                    }
                }
            }
        }
    }

    private func getSenderName(for message: ChatMessage) -> String? {
        guard !message.isFromMe else { return nil }
        return group.members.first { $0.id == message.senderId }?.displayName
    }
}

// MARK: - Group Message Row

private struct GroupMessageRow: View {
    let message: ChatMessage
    let senderName: String?

    var body: some View {
        VStack(alignment: message.isFromMe ? .trailing : .leading, spacing: Theme.Spacing.xxs) {
            // Sender name for received messages
            if let name = senderName, !message.isFromMe {
                Text(name)
                    .font(Theme.Typography.caption2)
                    .foregroundColor(Theme.Colors.primary)
                    .padding(.leading, Theme.Spacing.sm)
            }

            MessageBubble(
                content: message.content,
                timestamp: message.timestamp,
                isSent: message.isFromMe,
                status: mapStatus(message.status)
            )
        }
    }

    private func mapStatus(_ status: ChatMessage.MessageStatus) -> MessageBubble.MessageStatus {
        switch status {
        case .sending: return .sending
        case .sent: return .sent
        case .delivered: return .delivered
        case .read: return .read
        case .failed: return .failed
        }
    }
}

// MARK: - Group Info View

private struct GroupInfoView: View {
    @Bindable var viewModel: GroupViewModel
    let group: Group
    @Environment(\.dismiss) private var dismiss
    @State private var showingEditName = false
    @State private var newTitle = ""
    @State private var showingAddMembers = false

    private var isOwner: Bool {
        // In real implementation, check if current user is owner
        true
    }

    var body: some View {
        NavigationStack {
            List {
                // Group header
                Section {
                    VStack(spacing: Theme.Spacing.md) {
                        GroupAvatarView(
                            memberNames: group.members.map { $0.displayName },
                            size: Theme.AvatarSize.xxl
                        )

                        VStack(spacing: Theme.Spacing.xxs) {
                            Text(group.title)
                                .font(Theme.Typography.title2)
                                .foregroundColor(Theme.Colors.textPrimary)

                            Text("Created \(group.createdAt.formatted(date: .abbreviated, time: .omitted))")
                                .font(Theme.Typography.caption1)
                                .foregroundColor(Theme.Colors.textTertiary)
                        }

                        if isOwner {
                            Button {
                                newTitle = group.title
                                showingEditName = true
                            } label: {
                                Text("Edit Name")
                                    .font(Theme.Typography.subheadline)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Theme.Spacing.md)
                }
                .listRowBackground(Color.clear)

                // Members
                Section {
                    ForEach(group.members.sorted { $0.role.rawValue < $1.role.rawValue }) { member in
                        MemberRow(member: member, isOwner: isOwner) {
                            viewModel.removeMember(member, from: group)
                        }
                    }

                    if isOwner {
                        Button {
                            showingAddMembers = true
                        } label: {
                            Label("Add Members", systemImage: "person.badge.plus")
                        }
                    }
                } header: {
                    Text("\(group.memberCount) Members")
                }

                // Actions
                Section {
                    Button {
                        // Mute notifications
                    } label: {
                        Label("Mute Notifications", systemImage: "bell.slash")
                    }

                    Button {
                        // Search in chat
                    } label: {
                        Label("Search in Chat", systemImage: "magnifyingglass")
                    }
                }

                // Danger zone
                Section {
                    Button(role: .destructive) {
                        viewModel.leaveGroup(group)
                        dismiss()
                    } label: {
                        Label("Leave Group", systemImage: "rectangle.portrait.and.arrow.right")
                            .foregroundColor(Theme.Colors.error)
                    }
                }
            }
            .navigationTitle("Group Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
            .alert("Edit Group Name", isPresented: $showingEditName) {
                TextField("Group Name", text: $newTitle)
                Button("Cancel", role: .cancel) {}
                Button("Save") {
                    if !newTitle.isEmpty {
                        viewModel.updateGroupTitle(group, newTitle: newTitle)
                    }
                }
            }
            .sheet(isPresented: $showingAddMembers) {
                AddGroupMembersView(viewModel: viewModel, group: group)
            }
        }
    }
}

// MARK: - Member Row

private struct MemberRow: View {
    let member: GroupMember
    let isOwner: Bool
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: Theme.Spacing.sm) {
            AvatarView(
                name: member.displayName,
                imageURL: member.avatarURL,
                size: Theme.AvatarSize.md,
                showOnlineIndicator: true,
                isOnline: member.isOnline
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(member.displayName)
                        .font(Theme.Typography.body)
                        .foregroundColor(Theme.Colors.textPrimary)

                    if member.role != .member {
                        Text(member.role.rawValue.capitalized)
                            .font(Theme.Typography.caption2)
                            .foregroundColor(Theme.Colors.primary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Theme.Colors.primary.opacity(0.1))
                            .clipShape(Capsule())
                    }
                }

                Text(member.whisperId)
                    .font(Theme.Typography.caption2)
                    .foregroundColor(Theme.Colors.textTertiary)
            }

            Spacer()

            if isOwner && member.role == .member {
                Button(role: .destructive) {
                    onRemove()
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .foregroundColor(Theme.Colors.error)
                }
            }
        }
    }
}

// MARK: - Add Group Members View

private struct AddGroupMembersView: View {
    @Bindable var viewModel: GroupViewModel
    let group: Group
    @Environment(\.dismiss) private var dismiss
    @State private var selectedContacts: Set<Contact> = []
    @State private var contacts: [Contact] = []
    @State private var searchText = ""

    private var filteredContacts: [Contact] {
        let existingIds = Set(group.members.map { $0.whisperId })
        let available = contacts.filter { !existingIds.contains($0.whisperId) }

        if searchText.isEmpty {
            return available
        }
        return available.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(filteredContacts) { contact in
                    ContactSelectRow(
                        contact: contact,
                        isSelected: selectedContacts.contains(contact),
                        onToggle: {
                            if selectedContacts.contains(contact) {
                                selectedContacts.remove(contact)
                            } else {
                                selectedContacts.insert(contact)
                            }
                        }
                    )
                }
            }
            .searchable(text: $searchText, prompt: "Search contacts")
            .navigationTitle("Add Members")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Add (\(selectedContacts.count))") {
                        for contact in selectedContacts {
                            viewModel.addMember(contact, to: group)
                        }
                        dismiss()
                    }
                    .disabled(selectedContacts.isEmpty)
                }
            }
            .onAppear {
                loadContacts()
            }
        }
    }

    private func loadContacts() {
        // Placeholder - in real implementation, get from ContactsService
        contacts = [
            Contact(id: "5", whisperId: "WH2-EVE000", displayName: "Eve", avatarURL: nil, isOnline: true, lastSeen: nil),
            Contact(id: "6", whisperId: "WH2-FRANK1", displayName: "Frank", avatarURL: nil, isOnline: false, lastSeen: nil)
        ]
    }
}

// MARK: - Contact Select Row (duplicated for Groups module)

private struct ContactSelectRow: View {
    let contact: Contact
    let isSelected: Bool
    let onToggle: () -> Void

    var body: some View {
        Button {
            onToggle()
        } label: {
            HStack(spacing: Theme.Spacing.sm) {
                AvatarView(
                    name: contact.displayName,
                    imageURL: contact.avatarURL,
                    size: Theme.AvatarSize.sm
                )

                Text(contact.displayName)
                    .font(Theme.Typography.body)
                    .foregroundColor(Theme.Colors.textPrimary)

                Spacer()

                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundColor(isSelected ? Theme.Colors.primary : Theme.Colors.textTertiary)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        GroupChatView(
            viewModel: GroupViewModel(),
            group: Group(
                id: "1",
                title: "Family",
                members: [
                    GroupMember(id: "m1", whisperId: "WH2-MOM123", displayName: "Mom", avatarURL: nil, isOnline: true, role: .member),
                    GroupMember(id: "m2", whisperId: "WH2-DAD456", displayName: "Dad", avatarURL: nil, isOnline: false, role: .member)
                ],
                avatarURL: nil,
                lastMessage: "Dinner at 7?",
                lastMessageTimestamp: Date(),
                unreadCount: 0,
                createdAt: Date(),
                ownerId: "me"
            )
        )
    }
}
