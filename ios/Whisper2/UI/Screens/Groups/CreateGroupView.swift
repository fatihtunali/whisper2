import SwiftUI

/// Create new group
struct CreateGroupView: View {
    @Bindable var viewModel: GroupViewModel
    @Binding var isPresented: Bool
    @State private var contacts: [ContactUI] = []
    @State private var searchText = ""
    @FocusState private var isNameFocused: Bool

    private var filteredContacts: [ContactUI] {
        if searchText.isEmpty {
            return contacts
        }
        return contacts.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationStack {
            formContent
                .navigationTitle("New Group")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        cancelButton
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        createButton
                    }
                }
                .loading(viewModel.isCreatingGroup, message: "Creating group...")
                .alert("Error", isPresented: errorBinding) {
                    Button("OK") { viewModel.clearCreateGroupError() }
                } message: {
                    Text(viewModel.createGroupError ?? "")
                }
                .onChange(of: viewModel.groups.count) { oldValue, newValue in
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

    private var cancelButton: some View {
        Button("Cancel") {
            viewModel.resetCreateGroupForm()
            isPresented = false
        }
    }

    private var createButton: some View {
        Button("Create") {
            viewModel.createGroupUI()
        }
        .disabled(!viewModel.canCreateGroup || viewModel.isCreatingGroup)
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.createGroupError != nil },
            set: { if !$0 { viewModel.clearCreateGroupError() } }
        )
    }

    private var formContent: some View {
        VStack(spacing: 0) {
            Form {
                groupNameSection
                selectedMembersSection
                addMembersSection
            }
            .searchable(text: $searchText, prompt: "Search contacts")
        }
    }

    private var groupNameSection: some View {
        Section("Group Name") {
            TextField("Enter group name", text: $viewModel.newGroupTitle)
                .focused($isNameFocused)
        }
    }

    @ViewBuilder
    private var selectedMembersSection: some View {
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
    }

    private var addMembersSection: some View {
        Section {
            ForEach(filteredContacts) { contact in
                ContactSelectRow(
                    contact: contact,
                    isSelected: viewModel.selectedMembers.contains(contact),
                    onToggle: { toggleContact(contact) }
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

    private func toggleContact(_ contact: ContactUI) {
        if viewModel.selectedMembers.contains(contact) {
            viewModel.selectedMembers.remove(contact)
        } else {
            viewModel.selectedMembers.insert(contact)
        }
    }

    private func loadContacts() {
        // Load contacts from ContactsBackupService
        let backupService = ContactsBackupService.shared
        let backupContacts = backupService.getLocalContacts()

        // Convert BackupContact to ContactUI
        contacts = backupContacts.map { backup in
            ContactUI(
                id: backup.whisperId,
                whisperId: backup.whisperId,
                displayName: backup.displayName ?? backup.whisperId,
                avatarURL: nil,
                isOnline: false,
                lastSeen: nil,
                encPublicKey: nil
            )
        }

        // Fetch public keys in background
        Task { @MainActor in
            for i in contacts.indices {
                if let publicKey = await fetchUserPublicKey(contacts[i].whisperId) {
                    contacts[i].encPublicKey = publicKey
                }
            }
        }
    }

    /// Fetches user's public key from server
    private func fetchUserPublicKey(_ whisperId: String) async -> String? {
        let urlString = "\(Constants.Server.baseURL)/users/\(whisperId)/keys"
        guard let url = URL(string: urlString) else { return nil }

        guard let sessionToken = SessionManager.shared.sessionToken else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                return nil
            }

            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let encPublicKey = json["encPublicKey"] as? String else {
                return nil
            }

            return encPublicKey
        } catch {
            return nil
        }
    }
}

// MARK: - Selected Member Chip

private struct SelectedMemberChip: View {
    let contact: ContactUI
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
    let contact: ContactUI
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
