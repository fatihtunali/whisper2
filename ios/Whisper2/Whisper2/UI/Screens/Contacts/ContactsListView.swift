import SwiftUI

/// List of contacts
struct ContactsListView: View {
    @Bindable var viewModel: ContactsViewModel
    @State private var showingAddContact = false
    @State private var selectedContact: Contact?

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.contacts.isEmpty {
                    ChatListSkeletonView()
                } else if viewModel.filteredContacts.isEmpty {
                    emptyState
                } else {
                    contactsList
                }
            }
            .navigationTitle("Contacts")
            .searchable(text: $viewModel.searchText, prompt: "Search contacts")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingAddContact = true
                    } label: {
                        Image(systemName: "person.badge.plus")
                    }
                }
            }
            .refreshable {
                await viewModel.refreshContacts()
            }
            .sheet(isPresented: $showingAddContact) {
                AddContactView(viewModel: viewModel, isPresented: $showingAddContact)
            }
            .sheet(item: $selectedContact) { contact in
                ContactDetailView(contact: contact, viewModel: viewModel)
            }
        }
        .onAppear {
            if viewModel.contacts.isEmpty {
                viewModel.loadContacts()
            }
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

    private var contactsList: some View {
        List {
            // Online count header
            if viewModel.onlineCount > 0 {
                Section {
                    HStack {
                        Circle()
                            .fill(Theme.Colors.success)
                            .frame(width: 8, height: 8)
                        Text("\(viewModel.onlineCount) online")
                            .font(Theme.Typography.caption1)
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                }
            }

            // Grouped contacts
            ForEach(viewModel.groupedContacts, id: \.0) { letter, contacts in
                Section(header: Text(letter)) {
                    ForEach(contacts) { contact in
                        ContactRow(contact: contact)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                selectedContact = contact
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    viewModel.deleteContact(contact)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var emptyState: some View {
        Group {
            if viewModel.searchText.isEmpty {
                CenteredEmptyStateView(
                    emptyState: .noContacts {
                        showingAddContact = true
                    }
                )
            } else {
                CenteredEmptyStateView(
                    emptyState: .noSearchResults()
                )
            }
        }
    }
}

// MARK: - Contact Row

private struct ContactRow: View {
    let contact: Contact

    private var lastSeenString: String? {
        guard !contact.isOnline, let lastSeen = contact.lastSeen else { return nil }

        let calendar = Calendar.current
        if calendar.isDateInToday(lastSeen) {
            return "Last seen today at \(lastSeen.formatted(date: .omitted, time: .shortened))"
        } else if calendar.isDateInYesterday(lastSeen) {
            return "Last seen yesterday"
        } else {
            return "Last seen \(lastSeen.formatted(date: .abbreviated, time: .omitted))"
        }
    }

    var body: some View {
        HStack(spacing: Theme.Spacing.sm) {
            AvatarView(
                name: contact.displayName,
                imageURL: contact.avatarURL,
                size: Theme.AvatarSize.md,
                showOnlineIndicator: true,
                isOnline: contact.isOnline
            )

            VStack(alignment: .leading, spacing: Theme.Spacing.xxs) {
                Text(contact.displayName)
                    .font(Theme.Typography.headline)
                    .foregroundColor(Theme.Colors.textPrimary)

                if contact.isOnline {
                    Text("online")
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.success)
                } else if let lastSeen = lastSeenString {
                    Text(lastSeen)
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.textTertiary)
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(Theme.Colors.textTertiary)
        }
        .padding(.vertical, Theme.Spacing.xxs)
    }
}

// MARK: - Contact Detail View

private struct ContactDetailView: View {
    let contact: Contact
    @Bindable var viewModel: ContactsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var editingName = false
    @State private var newName: String = ""

    var body: some View {
        NavigationStack {
            List {
                // Profile header
                Section {
                    VStack(spacing: Theme.Spacing.md) {
                        AvatarView(
                            name: contact.displayName,
                            imageURL: contact.avatarURL,
                            size: Theme.AvatarSize.xxl
                        )

                        VStack(spacing: Theme.Spacing.xxs) {
                            Text(contact.displayName)
                                .font(Theme.Typography.title2)
                                .foregroundColor(Theme.Colors.textPrimary)

                            HStack {
                                Circle()
                                    .fill(contact.isOnline ? Theme.Colors.success : Theme.Colors.textTertiary)
                                    .frame(width: 8, height: 8)
                                Text(contact.isOnline ? "Online" : "Offline")
                                    .font(Theme.Typography.caption1)
                                    .foregroundColor(Theme.Colors.textSecondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Theme.Spacing.md)
                }
                .listRowBackground(Color.clear)

                // WhisperID
                Section("WhisperID") {
                    HStack {
                        Text(contact.whisperId)
                            .font(Theme.Typography.monospaced)
                            .foregroundColor(Theme.Colors.textPrimary)

                        Spacer()

                        Button {
                            UIPasteboard.general.string = contact.whisperId
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .foregroundColor(Theme.Colors.primary)
                        }
                    }
                }

                // Actions
                Section {
                    Button {
                        // Start chat
                        dismiss()
                    } label: {
                        Label("Send Message", systemImage: "message")
                    }

                    Button {
                        // Start call
                    } label: {
                        Label("Voice Call", systemImage: "phone")
                    }

                    Button {
                        newName = contact.displayName
                        editingName = true
                    } label: {
                        Label("Edit Name", systemImage: "pencil")
                    }
                }

                // Danger zone
                Section {
                    Button(role: .destructive) {
                        viewModel.deleteContact(contact)
                        dismiss()
                    } label: {
                        Label("Delete Contact", systemImage: "trash")
                            .foregroundColor(Theme.Colors.error)
                    }
                }
            }
            .navigationTitle("Contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
            .alert("Edit Name", isPresented: $editingName) {
                TextField("Display Name", text: $newName)
                Button("Cancel", role: .cancel) {}
                Button("Save") {
                    if !newName.isEmpty {
                        viewModel.updateContactName(contact, newName: newName)
                    }
                }
            } message: {
                Text("Enter a new display name for this contact")
            }
        }
    }
}

// MARK: - Preview

#Preview {
    ContactsListView(viewModel: ContactsViewModel())
}
