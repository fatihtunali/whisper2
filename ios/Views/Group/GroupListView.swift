import SwiftUI

/// View showing list of group conversations
struct GroupListView: View {
    @ObservedObject var groupService = GroupService.shared
    @State private var showCreateGroup = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if groupService.getAllGroups().isEmpty {
                    EmptyGroupsView(showCreateGroup: $showCreateGroup)
                } else {
                    List {
                        ForEach(groupService.getAllGroups()) { group in
                            NavigationLink(destination: GroupChatView(group: group)) {
                                GroupRow(group: group)
                            }
                            .listRowBackground(Color.black)
                            .listRowSeparatorTint(Color.gray.opacity(0.3))
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Groups")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showCreateGroup = true }) {
                        Image(systemName: "plus.circle.fill")
                    }
                }
            }
            .sheet(isPresented: $showCreateGroup) {
                CreateGroupView()
            }
        }
    }
}

/// Empty state for groups
struct EmptyGroupsView: View {
    @Binding var showCreateGroup: Bool

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "person.3.fill")
                .font(.system(size: 60))
                .foregroundColor(.gray)

            Text("No Groups")
                .font(.title2)
                .fontWeight(.semibold)
                .foregroundColor(.white)

            Text("Create a group to chat with multiple people at once")
                .font(.subheadline)
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Button(action: { showCreateGroup = true }) {
                Text("Create Group")
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 32)
                    .padding(.vertical, 12)
                    .background(Color.blue)
                    .cornerRadius(25)
            }
            .padding(.top, 10)
        }
    }
}

/// Row for a group in the list
struct GroupRow: View {
    let group: ChatGroup
    private let contacts = ContactsService.shared

    var body: some View {
        HStack(spacing: 12) {
            // Group avatar
            ZStack {
                Circle()
                    .fill(Color.purple.opacity(0.3))
                    .frame(width: 56, height: 56)

                Image(systemName: "person.3.fill")
                    .font(.title3)
                    .foregroundColor(.purple)
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(group.title)
                        .font(.headline)
                        .foregroundColor(.white)

                    Spacer()

                    if let time = group.lastMessageTime {
                        Text(formatTime(time))
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                }

                HStack {
                    if let lastMessage = group.lastMessage {
                        Text(lastMessage)
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .lineLimit(1)
                    } else {
                        Text("\(group.memberIds.count) members")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    }

                    Spacer()

                    if group.unreadCount > 0 {
                        Text("\(group.unreadCount)")
                            .font(.caption2)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.blue)
                            .cornerRadius(10)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func formatTime(_ date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm"
            return formatter.string(from: date)
        } else if calendar.isDateInYesterday(date) {
            return "Yesterday"
        } else {
            let formatter = DateFormatter()
            formatter.dateFormat = "dd/MM"
            return formatter.string(from: date)
        }
    }
}

/// View for creating a new group
struct CreateGroupView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var contactsService = ContactsService.shared
    @State private var groupTitle = ""
    @State private var selectedMembers: Set<String> = []
    @State private var isCreating = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 20) {
                    // Group title
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Group Name")
                            .font(.headline)
                            .foregroundColor(.white)

                        TextField("Enter group name", text: $groupTitle)
                            .textFieldStyle(.plain)
                            .padding()
                            .background(Color.gray.opacity(0.2))
                            .cornerRadius(10)
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal)

                    // Member selection
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Select Members")
                                .font(.headline)
                                .foregroundColor(.white)

                            Spacer()

                            Text("\(selectedMembers.count) selected")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                        .padding(.horizontal)

                        List {
                            ForEach(contactsService.getAllContacts()) { contact in
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
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.red)
                            .padding(.horizontal)
                    }

                    Spacer()
                }
                .padding(.top)
            }
            .navigationTitle("New Group")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { createGroup() }
                        .disabled(groupTitle.isEmpty || selectedMembers.isEmpty || isCreating)
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

    private func createGroup() {
        isCreating = true
        error = nil

        Task {
            do {
                _ = try await GroupService.shared.createGroup(
                    title: groupTitle,
                    memberIds: Array(selectedMembers)
                )
                await MainActor.run {
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                    isCreating = false
                }
            }
        }
    }
}

#Preview {
    GroupListView()
}
