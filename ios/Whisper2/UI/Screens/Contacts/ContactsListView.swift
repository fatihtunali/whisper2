import SwiftUI

/// List of contacts matching original Whisper UI
struct ContactsListView: View {
    @State private var viewModel = ContactsViewModel()
    @EnvironmentObject var themeManager: ThemeManager
    @State private var showingAddContact = false
    @State private var showingQRCode = false
    @State private var selectedContact: ContactUI?

    // Call state
    @State private var showingCallView = false
    @State private var callViewModel = CallViewModel()

    // Chat navigation
    @State private var chatContact: ContactUI?

    var body: some View {
        ZStack {
            Color.whisperBackground.ignoresSafeArea()

            VStack(spacing: 0) {
                // Header
                headerView

                // Contacts List
                if viewModel.isLoading && viewModel.contacts.isEmpty {
                    loadingView
                } else if viewModel.filteredContacts.isEmpty {
                    emptyStateView
                } else {
                    ScrollView {
                        LazyVStack(spacing: WhisperSpacing.sm) {
                            // Online count header
                            if viewModel.onlineCount > 0 {
                                HStack {
                                    Circle()
                                        .fill(Color.whisperSuccess)
                                        .frame(width: 8, height: 8)
                                    Text("\(viewModel.onlineCount) online")
                                        .font(.whisper(size: WhisperFontSize.sm))
                                        .foregroundColor(.whisperTextSecondary)
                                }
                                .padding(.horizontal, WhisperSpacing.md)
                                .padding(.vertical, WhisperSpacing.sm)
                            }

                            ForEach(viewModel.filteredContacts) { contact in
                                ContactRowView(contact: contact)
                                    .onTapGesture {
                                        selectedContact = contact
                                    }
                                    .contextMenu {
                                        Button {
                                            chatContact = contact
                                        } label: {
                                            Label("Send Message", systemImage: "message")
                                        }

                                        Button {
                                            UIPasteboard.general.string = contact.whisperId
                                        } label: {
                                            Label("Copy Whisper ID", systemImage: "doc.on.doc")
                                        }

                                        Divider()

                                        Button {
                                            if contact.isBlocked {
                                                viewModel.unblockContact(contact)
                                            } else {
                                                viewModel.blockContact(contact)
                                            }
                                        } label: {
                                            Label(
                                                contact.isBlocked ? "Unblock" : "Block",
                                                systemImage: contact.isBlocked ? "hand.raised.slash" : "hand.raised"
                                            )
                                        }

                                        Button(role: .destructive) {
                                            viewModel.deleteContact(contact)
                                        } label: {
                                            Label("Delete", systemImage: "trash")
                                        }
                                    }
                            }
                        }
                        .padding(.horizontal, WhisperSpacing.md)
                        .padding(.top, WhisperSpacing.sm)
                    }
                    .refreshable {
                        await viewModel.refreshContacts()
                    }
                }
            }
        }
        .sheet(isPresented: $showingAddContact) {
            AddContactView(viewModel: viewModel, isPresented: $showingAddContact)
        }
        .sheet(item: $selectedContact) { contact in
            ContactDetailView(contact: contact, viewModel: viewModel)
        }
        .sheet(isPresented: $showingQRCode) {
            if let whisperId = KeychainService.shared.whisperId {
                ShareWhisperIDView(whisperId: whisperId)
            }
        }
        .sheet(item: $chatContact) { contact in
            NavigationStack {
                // Create a conversation for this contact and show ChatView
                let conversation = ConversationUI(
                    id: contact.whisperId,
                    participantId: contact.whisperId,
                    participantName: contact.displayName,
                    participantAvatarURL: contact.avatarURL,
                    lastMessage: nil,
                    lastMessageTimestamp: nil,
                    unreadCount: 0,
                    isOnline: contact.isOnline,
                    isTyping: false,
                    participantEncPublicKey: contact.encPublicKey
                )
                ChatView(viewModel: ChatViewModel(conversation: conversation))
            }
        }
        .id(themeManager.themeMode)
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

    // MARK: - Header

    private var headerView: some View {
        HStack {
            Text("Contacts")
                .font(.whisper(size: WhisperFontSize.xxl, weight: .bold))
                .foregroundColor(.whisperText)

            Spacer()

            // QR Code Button
            Button(action: { showingQRCode = true }) {
                Image(systemName: "qrcode")
                    .font(.whisper(size: WhisperFontSize.lg))
                    .foregroundColor(.whisperText)
                    .frame(width: 36, height: 36)
            }

            // Add Contact Button
            Button(action: { showingAddContact = true }) {
                Image(systemName: "plus")
                    .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                    .foregroundColor(.whisperText)
                    .frame(width: 36, height: 36)
                    .background(Color.whisperPrimary)
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal, WhisperSpacing.lg)
        .padding(.vertical, WhisperSpacing.md)
        .background(Color.whisperBackground)
        .overlay(
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1),
            alignment: .bottom
        )
    }

    // MARK: - Loading View

    private var loadingView: some View {
        VStack(spacing: WhisperSpacing.md) {
            Spacer()
            ProgressView()
                .scaleEffect(1.5)
                .tint(.whisperPrimary)
            Text("Loading contacts...")
                .font(.whisper(size: WhisperFontSize.md))
                .foregroundColor(.whisperTextSecondary)
            Spacer()
        }
    }

    // MARK: - Empty State

    private var emptyStateView: some View {
        VStack(spacing: WhisperSpacing.lg) {
            Spacer()

            Text("👥")
                .font(.system(size: 64))

            Text("No contacts yet")
                .font(.whisper(size: WhisperFontSize.xl, weight: .semibold))
                .foregroundColor(.whisperText)

            Text("Add contacts to start messaging")
                .font(.whisper(size: WhisperFontSize.md))
                .foregroundColor(.whisperTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, WhisperSpacing.xl)

            Button(action: { showingAddContact = true }) {
                HStack(spacing: WhisperSpacing.sm) {
                    Image(systemName: "plus")
                    Text("Add Contact")
                }
                .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                .foregroundColor(.whisperText)
                .padding(.horizontal, WhisperSpacing.lg)
                .padding(.vertical, WhisperSpacing.sm)
                .background(Color.whisperPrimary)
                .cornerRadius(WhisperRadius.md)
            }

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Contact Row (Matching Expo)

struct ContactRowView: View {
    let contact: ContactUI
    @EnvironmentObject var themeManager: ThemeManager

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
        VStack(spacing: 0) {
            // Top border line
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)

            // Main content
            HStack(spacing: WhisperSpacing.md) {
                // Avatar
                avatarView

                // Content
                VStack(alignment: .leading, spacing: WhisperSpacing.xs) {
                    Text(contact.displayName)
                        .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                        .foregroundColor(.whisperText)

                    if contact.isOnline {
                        Text("online")
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperSuccess)
                    } else if let lastSeen = lastSeenString {
                        Text(lastSeen)
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperTextMuted)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.whisperTextMuted)
            }
            .padding(.horizontal, WhisperSpacing.md)
            .padding(.vertical, WhisperSpacing.md)

            // Bottom border line
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)
        }
        .background(Color.whisperSurface)
        .cornerRadius(WhisperRadius.md)
        .shadow(color: Color.black.opacity(0.05), radius: 2, x: 0, y: 1)
    }

    private var avatarView: some View {
        ZStack {
            Circle()
                .fill(gradientForName(contact.displayName))
                .frame(width: 48, height: 48)

            Text(String(contact.displayName.prefix(1)).uppercased())
                .font(.whisper(size: WhisperFontSize.lg, weight: .medium))
                .foregroundColor(.white)

            // Online indicator
            if contact.isOnline {
                Circle()
                    .fill(Color.whisperSuccess)
                    .frame(width: 14, height: 14)
                    .overlay(
                        Circle()
                            .stroke(Color.whisperSurface, lineWidth: 2)
                    )
                    .offset(x: 16, y: 16)
            }
        }
    }

    private func gradientForName(_ name: String) -> LinearGradient {
        let hash = abs(name.hashValue)
        let colorPairs: [[Color]] = [
            [Color(red: 0.2, green: 0.6, blue: 0.9), Color(red: 0.1, green: 0.4, blue: 0.7)],
            [Color(red: 0.9, green: 0.3, blue: 0.4), Color(red: 0.7, green: 0.2, blue: 0.3)],
            [Color(red: 0.3, green: 0.8, blue: 0.5), Color(red: 0.2, green: 0.6, blue: 0.4)],
            [Color(red: 0.9, green: 0.6, blue: 0.2), Color(red: 0.8, green: 0.4, blue: 0.1)],
            [Color(red: 0.6, green: 0.3, blue: 0.9), Color(red: 0.4, green: 0.2, blue: 0.7)],
            [Color(red: 0.3, green: 0.7, blue: 0.9), Color(red: 0.2, green: 0.5, blue: 0.7)],
            [Color(red: 0.9, green: 0.4, blue: 0.6), Color(red: 0.7, green: 0.3, blue: 0.5)],
            [Color(red: 0.4, green: 0.8, blue: 0.7), Color(red: 0.3, green: 0.6, blue: 0.5)]
        ]
        let colors = colorPairs[hash % colorPairs.count]
        return LinearGradient(
            colors: colors,
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

// MARK: - Contact Detail View

struct ContactDetailView: View {
    let contact: ContactUI
    @Bindable var viewModel: ContactsViewModel
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var themeManager: ThemeManager
    @State private var editingName = false
    @State private var newName: String = ""

    // Call state
    @State private var showingCallView = false
    @State private var callViewModel = CallViewModel()

    var body: some View {
        NavigationStack {
            ZStack {
                Color.whisperBackground.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: WhisperSpacing.lg) {
                        // Profile header
                        VStack(spacing: WhisperSpacing.md) {
                            // Avatar
                            ZStack {
                                Circle()
                                    .fill(gradientForName(contact.displayName))
                                    .frame(width: 100, height: 100)

                                Text(String(contact.displayName.prefix(1)).uppercased())
                                    .font(.system(size: 40, weight: .medium))
                                    .foregroundColor(.white)
                            }

                            Text(contact.displayName)
                                .font(.whisper(size: WhisperFontSize.xxl, weight: .bold))
                                .foregroundColor(.whisperText)

                            HStack {
                                Circle()
                                    .fill(contact.isOnline ? Color.whisperSuccess : Color.whisperTextMuted)
                                    .frame(width: 8, height: 8)
                                Text(contact.isOnline ? "Online" : "Offline")
                                    .font(.whisper(size: WhisperFontSize.sm))
                                    .foregroundColor(.whisperTextSecondary)
                            }
                        }
                        .padding(.top, WhisperSpacing.xl)

                        // Quick Actions
                        HStack(spacing: WhisperSpacing.xl) {
                            ActionButton(icon: "message.fill", label: "Message") {
                                dismiss()
                            }

                            ActionButton(icon: "phone.fill", label: "Voice") {
                                startCall(isVideo: false)
                            }

                            ActionButton(icon: "video.fill", label: "Video") {
                                startCall(isVideo: true)
                            }
                        }
                        .padding(.vertical, WhisperSpacing.md)

                        // WhisperID Section
                        VStack(alignment: .leading, spacing: WhisperSpacing.sm) {
                            Text("WHISPER ID")
                                .font(.whisper(size: WhisperFontSize.xs, weight: .semibold))
                                .foregroundColor(.whisperTextMuted)

                            HStack {
                                Text(contact.whisperId)
                                    .font(.system(size: WhisperFontSize.md, design: .monospaced))
                                    .foregroundColor(.whisperText)

                                Spacer()

                                Button {
                                    UIPasteboard.general.string = contact.whisperId
                                } label: {
                                    Image(systemName: "doc.on.doc")
                                        .foregroundColor(.whisperPrimary)
                                }
                            }
                            .padding(WhisperSpacing.md)
                            .background(Color.whisperSurface)
                            .cornerRadius(WhisperRadius.md)
                        }
                        .padding(.horizontal, WhisperSpacing.md)

                        // Actions
                        VStack(spacing: WhisperSpacing.sm) {
                            Button {
                                newName = contact.displayName
                                editingName = true
                            } label: {
                                HStack {
                                    Label("Edit Nickname", systemImage: "pencil")
                                        .foregroundColor(.whisperText)
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .foregroundColor(.whisperTextMuted)
                                }
                                .padding(WhisperSpacing.md)
                                .background(Color.whisperSurface)
                                .cornerRadius(WhisperRadius.md)
                            }

                            // Block/Unblock button
                            Button {
                                if contact.isBlocked {
                                    viewModel.unblockContact(contact)
                                } else {
                                    viewModel.blockContact(contact)
                                }
                                dismiss()
                            } label: {
                                HStack {
                                    Label(
                                        contact.isBlocked ? "Unblock Contact" : "Block Contact",
                                        systemImage: contact.isBlocked ? "hand.raised.slash" : "hand.raised"
                                    )
                                    .foregroundColor(contact.isBlocked ? .whisperSuccess : .whisperWarning)
                                    Spacer()
                                }
                                .padding(WhisperSpacing.md)
                                .background(Color.whisperSurface)
                                .cornerRadius(WhisperRadius.md)
                            }

                            Button(role: .destructive) {
                                viewModel.deleteContact(contact)
                                dismiss()
                            } label: {
                                HStack {
                                    Label("Delete Contact", systemImage: "trash")
                                        .foregroundColor(.whisperError)
                                    Spacer()
                                }
                                .padding(WhisperSpacing.md)
                                .background(Color.whisperSurface)
                                .cornerRadius(WhisperRadius.md)
                            }
                        }
                        .padding(.horizontal, WhisperSpacing.md)

                        Spacer()
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
                    .foregroundColor(.whisperPrimary)
                }
            }
            .alert("Edit Nickname", isPresented: $editingName) {
                TextField("Nickname", text: $newName)
                Button("Cancel", role: .cancel) {}
                Button("Save") {
                    if !newName.isEmpty {
                        viewModel.updateContactName(contact, newName: newName)
                    }
                }
            } message: {
                Text("Enter a nickname for this contact")
            }
            .fullScreenCover(isPresented: $showingCallView) {
                CallView(viewModel: callViewModel)
            }
        }
    }

    private func startCall(isVideo: Bool) {
        // Set up call view model
        callViewModel.participantId = contact.whisperId
        callViewModel.participantName = contact.displayName
        callViewModel.participantAvatarURL = nil

        // Show call screen
        showingCallView = true

        // Initiate call
        callViewModel.initiateCall(
            to: contact.whisperId,
            name: contact.displayName,
            avatarURL: nil,
            isVideo: isVideo
        )
    }

    private func gradientForName(_ name: String) -> LinearGradient {
        let hash = abs(name.hashValue)
        let colorPairs: [[Color]] = [
            [Color(red: 0.2, green: 0.6, blue: 0.9), Color(red: 0.1, green: 0.4, blue: 0.7)],
            [Color(red: 0.9, green: 0.3, blue: 0.4), Color(red: 0.7, green: 0.2, blue: 0.3)],
            [Color(red: 0.3, green: 0.8, blue: 0.5), Color(red: 0.2, green: 0.6, blue: 0.4)],
            [Color(red: 0.9, green: 0.6, blue: 0.2), Color(red: 0.8, green: 0.4, blue: 0.1)],
            [Color(red: 0.6, green: 0.3, blue: 0.9), Color(red: 0.4, green: 0.2, blue: 0.7)],
        ]
        let colors = colorPairs[hash % colorPairs.count]
        return LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

// MARK: - Action Button

private struct ActionButton: View {
    let icon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: WhisperSpacing.sm) {
                Image(systemName: icon)
                    .font(.system(size: 24))
                    .foregroundColor(.whisperPrimary)
                    .frame(width: 56, height: 56)
                    .background(Color.whisperPrimary.opacity(0.15))
                    .clipShape(Circle())

                Text(label)
                    .font(.whisper(size: WhisperFontSize.sm))
                    .foregroundColor(.whisperTextSecondary)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    ContactsListView()
        .environmentObject(ThemeManager.shared)
}
