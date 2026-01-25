import SwiftUI

/// Create new group
struct CreateGroupView: View {
    @Bindable var viewModel: GroupViewModel
    @Binding var isPresented: Bool
    @State private var contacts: [Contact] = []
    @State private var searchText = ""
    @FocusState private var isNameFocused: Bool

    private var filteredContacts: [Contact] {
        if searchText.isEmpty {
            return contacts
        }
        return contacts.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Form {
                    // Group name
                    Section("Group Name") {
                        TextField("Enter group name", text: $viewModel.newGroupTitle)
                            .focused($isNameFocused)
                    }

                    // Selected members
                    if !viewModel.selectedMembers.isEmpty {
                        Section("Selected Members (\(viewModel.selectedMembers.count))") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: Theme.Spacing.sm) {
                                    ForEach(Array(viewModel.selectedMembers)) { contact in
                                        SelectedMemberChip(contact: contact) {
                                            viewModel.selectedMembers.remove(contact)
                                        }
                                    }
                                }
                                .padding(.vertical, Theme.Spacing.xs)
                            }
                        }
                    }

                    // Add members
                    Section {
                        ForEach(filteredContacts) { contact in
                            ContactSelectRow(
                                contact: contact,
                                isSelected: viewModel.selectedMembers.contains(contact),
                                onToggle: {
                                    if viewModel.selectedMembers.contains(contact) {
                                        viewModel.selectedMembers.remove(contact)
                                    } else {
                                        viewModel.selectedMembers.insert(contact)
                                    }
                                }
                            )
                        }
                    } header: {
                        Text("Add Members")
                    } footer: {
                        if contacts.isEmpty {
                            Text("No contacts available. Add contacts first.")
                        }
                    }
                }
                .searchable(text: $searchText, prompt: "Search contacts")
            }
            .navigationTitle("New Group")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        viewModel.resetCreateGroupForm()
                        isPresented = false
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Create") {
                        viewModel.createGroup()
                    }
                    .disabled(!viewModel.canCreateGroup || viewModel.isCreatingGroup)
                }
            }
            .loading(viewModel.isCreatingGroup, message: "Creating group...")
            .alert("Error", isPresented: Binding(
                get: { viewModel.createGroupError != nil },
                set: { if !$0 { viewModel.clearCreateGroupError() } }
            )) {
                Button("OK") { viewModel.clearCreateGroupError() }
            } message: {
                Text(viewModel.createGroupError ?? "")
            }
            .onChange(of: viewModel.groups.count) { oldValue, newValue in
                // Group was created successfully
                if newValue > oldValue && !viewModel.isCreatingGroup {
                    isPresented = false
                }
            }
            .onAppear {
                loadContacts()
                isNameFocused = true
            }
        }
    }

    private func loadContacts() {
        // Placeholder contacts - in real implementation, get from ContactsService
        contacts = [
            Contact(id: "1", whisperId: "WH2-ALICE1", displayName: "Alice", avatarURL: nil, isOnline: true, lastSeen: nil),
            Contact(id: "2", whisperId: "WH2-BOB234", displayName: "Bob", avatarURL: nil, isOnline: false, lastSeen: nil),
            Contact(id: "3", whisperId: "WH2-CAROL3", displayName: "Carol", avatarURL: nil, isOnline: true, lastSeen: nil),
            Contact(id: "4", whisperId: "WH2-DAVID4", displayName: "David", avatarURL: nil, isOnline: false, lastSeen: nil)
        ]
    }
}

// MARK: - Selected Member Chip

private struct SelectedMemberChip: View {
    let contact: Contact
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: Theme.Spacing.xxs) {
            AvatarView(
                name: contact.displayName,
                imageURL: contact.avatarURL,
                size: 24
            )

            Text(contact.displayName)
                .font(Theme.Typography.caption1)
                .foregroundColor(Theme.Colors.textPrimary)

            Button {
                onRemove()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 16))
                    .foregroundColor(Theme.Colors.textTertiary)
            }
        }
        .padding(.horizontal, Theme.Spacing.xs)
        .padding(.vertical, Theme.Spacing.xxs)
        .background(Theme.Colors.surface)
        .clipShape(Capsule())
    }
}

// MARK: - Contact Select Row

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

                VStack(alignment: .leading, spacing: 2) {
                    Text(contact.displayName)
                        .font(Theme.Typography.body)
                        .foregroundColor(Theme.Colors.textPrimary)

                    Text(contact.whisperId)
                        .font(Theme.Typography.caption2)
                        .foregroundColor(Theme.Colors.textTertiary)
                }

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
    CreateGroupView(
        viewModel: GroupViewModel(),
        isPresented: .constant(true)
    )
}
