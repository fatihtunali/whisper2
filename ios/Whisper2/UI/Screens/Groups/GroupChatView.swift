import SwiftUI
import PhotosUI
import CoreLocation
import ContactsUI

/// Attachment sheet type for group chat
enum GroupAttachmentSheetType: Identifiable {
    case camera
    case document
    case location
    case contact

    var id: Int { hashValue }
}

/// Group chat view
struct GroupChatView: View {
    @Bindable var viewModel: GroupViewModel
    let group: GroupUI
    @Environment(\.dismiss) private var dismiss
    @State private var showingGroupInfo = false

    // Search states
    @State private var isSearching = false
    @State private var searchText = ""
    @FocusState private var isSearchFocused: Bool

    // Attachment picker states
    @State private var showingPhotoPicker = false
    @State private var activeSheet: GroupAttachmentSheetType?

    // Selected items
    @State private var selectedPhotoItems: [PhotosPickerItem] = []

    // Filtered messages for search
    private var filteredMessages: [ChatMessage] {
        if searchText.isEmpty {
            return viewModel.messages
        }
        return viewModel.messages.filter { message in
            message.content.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Search bar (when active)
            if isSearching {
                searchBar
            }

            // Messages list
            messagesView

            // Input bar (hidden when searching)
            if !isSearching {
                InputBar(
                    text: $viewModel.messageText,
                    isEnabled: !viewModel.isSending,
                    onSend: {
                        viewModel.sendGroupMessage()
                    },
                    onAttachment: { attachmentType in
                        handleAttachment(attachmentType)
                    }
                )
            }
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
            GroupInfoView(viewModel: viewModel, group: group, isSearching: $isSearching)
        }
        .onAppear {
            viewModel.loadGroupMessagesUI(group)
        }
        .alert("Error", isPresented: Binding(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.clearError() } }
        )) {
            Button("OK") { viewModel.clearError() }
        } message: {
            Text(viewModel.error ?? "")
        }
        // Photo Picker
        .photosPicker(
            isPresented: $showingPhotoPicker,
            selection: $selectedPhotoItems,
            maxSelectionCount: 10,
            matching: .any(of: [.images, .videos])
        )
        .onChange(of: selectedPhotoItems) { _, newItems in
            Task {
                await loadSelectedPhotos(newItems)
            }
        }
        // Single sheet for all attachment pickers
        .sheet(item: $activeSheet) { sheetType in
            switch sheetType {
            case .camera:
                GroupCameraView { image in
                    if let image = image {
                        sendImage(image)
                    }
                }
            case .document:
                GroupDocumentPickerView { urls in
                    for url in urls {
                        sendDocument(url)
                    }
                }
            case .location:
                GroupLocationPickerView { location in
                    if let location = location {
                        sendLocation(location)
                    }
                }
            case .contact:
                GroupContactPickerView { contact in
                    if let contact = contact {
                        sendContact(contact)
                    }
                }
            }
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

    private func handleAttachment(_ option: ShareMenuOption) {
        switch option {
        case .photoLibrary:
            showingPhotoPicker = true
        case .camera:
            activeSheet = .camera
        case .document:
            activeSheet = .document
        case .location:
            activeSheet = .location
        case .contact:
            activeSheet = .contact
        }
    }

    // MARK: - Attachment Handlers

    private func loadSelectedPhotos(_ items: [PhotosPickerItem]) async {
        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self),
               let image = UIImage(data: data) {
                await MainActor.run {
                    sendImage(image)
                }
            }
        }
        await MainActor.run {
            selectedPhotoItems = []
        }
    }

    private func sendImage(_ image: UIImage) {
        viewModel.sendGroupImageMessage(image, to: group)
    }

    private func sendDocument(_ url: URL) {
        viewModel.sendGroupDocumentMessage(url, to: group)
    }

    private func sendLocation(_ location: CLLocationCoordinate2D) {
        viewModel.sendGroupLocationMessage(location, to: group)
    }

    private func sendContact(_ contact: CNContact) {
        viewModel.sendGroupContactMessage(contact, to: group)
    }

    // MARK: - Search Bar

    private var searchBar: some View {
        HStack(spacing: Theme.Spacing.sm) {
            HStack(spacing: Theme.Spacing.xs) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(Theme.Colors.textTertiary)

                TextField("Search messages...", text: $searchText)
                    .textFieldStyle(.plain)
                    .focused($isSearchFocused)
                    .submitLabel(.search)

                if !searchText.isEmpty {
                    Button {
                        searchText = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(Theme.Colors.textTertiary)
                    }
                }
            }
            .padding(Theme.Spacing.sm)
            .background(Theme.Colors.surface)
            .cornerRadius(WhisperRadius.md)

            Button("Cancel") {
                searchText = ""
                isSearching = false
                isSearchFocused = false
            }
            .foregroundColor(Theme.Colors.primary)
        }
        .padding(.horizontal, Theme.Spacing.md)
        .padding(.vertical, Theme.Spacing.sm)
        .background(Theme.Colors.background)
        .onAppear {
            isSearchFocused = true
        }
    }

    // MARK: - Messages View

    private var messagesView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                if isSearching && filteredMessages.isEmpty && !searchText.isEmpty {
                    // No search results
                    VStack(spacing: Theme.Spacing.md) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 40))
                            .foregroundColor(Theme.Colors.textTertiary)
                        Text("No messages found")
                            .font(Theme.Typography.body)
                            .foregroundColor(Theme.Colors.textSecondary)
                        Text("Try a different search term")
                            .font(Theme.Typography.caption1)
                            .foregroundColor(Theme.Colors.textTertiary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, Theme.Spacing.huge)
                } else {
                    LazyVStack(spacing: Theme.Spacing.sm) {
                        // Search results count
                        if isSearching && !searchText.isEmpty {
                            Text("\(filteredMessages.count) result\(filteredMessages.count == 1 ? "" : "s")")
                                .font(Theme.Typography.caption1)
                                .foregroundColor(Theme.Colors.textTertiary)
                                .padding(.top, Theme.Spacing.sm)
                        }

                        ForEach(filteredMessages) { message in
                            GroupMessageRow(
                                message: message,
                                senderName: getSenderName(for: message),
                                highlightText: isSearching ? searchText : nil
                            )
                            .id(message.id)
                        }
                    }
                    .padding(.horizontal, Theme.Spacing.md)
                    .padding(.bottom, Theme.Spacing.sm)
                }
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                if let lastMessage = viewModel.messages.last, !isSearching {
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
    var highlightText: String? = nil

    var body: some View {
        VStack(alignment: message.isFromMe ? .trailing : .leading, spacing: Theme.Spacing.xxs) {
            // Sender name for received messages
            if let name = senderName, !message.isFromMe {
                Text(name)
                    .font(Theme.Typography.caption2)
                    .foregroundColor(Theme.Colors.primary)
                    .padding(.leading, Theme.Spacing.sm)
            }

            if let highlight = highlightText, !highlight.isEmpty {
                // Highlighted message bubble for search
                MessageBubble(
                    content: message.content,
                    timestamp: message.timestamp,
                    isSent: message.isFromMe,
                    status: mapStatus(message.status)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: WhisperRadius.lg)
                        .stroke(Theme.Colors.primary, lineWidth: 2)
                )
            } else {
                MessageBubble(
                    content: message.content,
                    timestamp: message.timestamp,
                    isSent: message.isFromMe,
                    status: mapStatus(message.status)
                )
            }
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
    let group: GroupUI
    @Binding var isSearching: Bool
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
                            viewModel.removeMemberUI(member, from: group)
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
                        // Toggle mute status
                        let key = "whisper2.group.\(group.id).muted"
                        let isMuted = UserDefaults.standard.bool(forKey: key)
                        UserDefaults.standard.set(!isMuted, forKey: key)
                    } label: {
                        let isMuted = UserDefaults.standard.bool(forKey: "whisper2.group.\(group.id).muted")
                        Label(isMuted ? "Unmute Notifications" : "Mute Notifications",
                              systemImage: isMuted ? "bell.fill" : "bell.slash")
                    }

                    Button {
                        isSearching = true
                        dismiss()
                    } label: {
                        Label("Search in Chat", systemImage: "magnifyingglass")
                    }
                }

                // Danger zone
                Section {
                    Button(role: .destructive) {
                        viewModel.leaveGroupUI(group)
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
                        viewModel.updateGroupTitleUI(group, newTitle: newTitle)
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
    let member: GroupMemberUI
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
    let group: GroupUI
    @Environment(\.dismiss) private var dismiss
    @State private var selectedContacts: Set<ContactUI> = []
    @State private var contacts: [ContactUI] = []
    @State private var searchText = ""

    private var filteredContacts: [ContactUI] {
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
                    ContactSelectRowUI(
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
                            viewModel.addMemberUI(contact, to: group)
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

// MARK: - Contact Select Row (duplicated for Groups module)

private struct ContactSelectRowUI: View {
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

// MARK: - Group Camera View

struct GroupCameraView: UIViewControllerRepresentable {
    let onImageCaptured: (UIImage?) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: GroupCameraView

        init(_ parent: GroupCameraView) {
            self.parent = parent
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            let image = info[.originalImage] as? UIImage
            parent.onImageCaptured(image)
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.onImageCaptured(nil)
            parent.dismiss()
        }
    }
}

// MARK: - Group Document Picker View

struct GroupDocumentPickerView: UIViewControllerRepresentable {
    let onDocumentsPicked: ([URL]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let parent: GroupDocumentPickerView

        init(_ parent: GroupDocumentPickerView) {
            self.parent = parent
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            parent.onDocumentsPicked(urls)
            parent.dismiss()
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            parent.dismiss()
        }
    }
}

// MARK: - Group Location Picker View

struct GroupLocationPickerView: View {
    let onLocationPicked: (CLLocationCoordinate2D?) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var locationManager = GroupLocationManager()

    var body: some View {
        NavigationStack {
            VStack(spacing: Theme.Spacing.lg) {
                Spacer()

                if locationManager.isLoading {
                    ProgressView("Getting location...")
                        .foregroundColor(Theme.Colors.textPrimary)
                } else if let location = locationManager.currentLocation {
                    VStack(spacing: Theme.Spacing.md) {
                        Image(systemName: "location.circle.fill")
                            .font(.system(size: 64))
                            .foregroundColor(Theme.Colors.primary)

                        Text("Your Current Location")
                            .font(Theme.Typography.title3)
                            .foregroundColor(Theme.Colors.textPrimary)

                        Text("\(location.latitude, specifier: "%.4f"), \(location.longitude, specifier: "%.4f")")
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundColor(Theme.Colors.textSecondary)

                        Button {
                            onLocationPicked(location)
                            dismiss()
                        } label: {
                            Text("Share This Location")
                                .font(Theme.Typography.headline)
                                .foregroundColor(.white)
                                .padding(.horizontal, Theme.Spacing.xl)
                                .padding(.vertical, Theme.Spacing.sm)
                                .background(Theme.Colors.primary)
                                .cornerRadius(WhisperRadius.md)
                        }
                    }
                } else if let error = locationManager.error {
                    VStack(spacing: Theme.Spacing.md) {
                        Image(systemName: "location.slash")
                            .font(.system(size: 64))
                            .foregroundColor(Theme.Colors.error)

                        Text("Location Error")
                            .font(Theme.Typography.title3)
                            .foregroundColor(Theme.Colors.textPrimary)

                        Text(error)
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(Theme.Colors.textSecondary)
                            .multilineTextAlignment(.center)

                        Button("Try Again") {
                            locationManager.requestLocation()
                        }
                        .foregroundColor(Theme.Colors.primary)
                    }
                }

                Spacer()
            }
            .padding()
            .background(Theme.Colors.background)
            .navigationTitle("Share Location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        onLocationPicked(nil)
                        dismiss()
                    }
                    .foregroundColor(Theme.Colors.primary)
                }
            }
            .onAppear {
                locationManager.requestLocation()
            }
        }
    }
}

// MARK: - Group Location Manager

class GroupLocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()

    @Published var currentLocation: CLLocationCoordinate2D?
    @Published var isLoading = false
    @Published var error: String?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func requestLocation() {
        isLoading = true
        error = nil

        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .restricted, .denied:
            isLoading = false
            error = "Location access denied. Please enable in Settings."
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        @unknown default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        isLoading = false
        currentLocation = locations.first?.coordinate
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        isLoading = false
        self.error = error.localizedDescription
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.requestLocation()
        }
    }
}

// MARK: - Group Contact Picker View

struct GroupContactPickerView: UIViewControllerRepresentable {
    let onContactPicked: (CNContact?) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let picker = CNContactPickerViewController()
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: CNContactPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, CNContactPickerDelegate {
        let parent: GroupContactPickerView

        init(_ parent: GroupContactPickerView) {
            self.parent = parent
        }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            parent.onContactPicked(contact)
            parent.dismiss()
        }

        func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
            parent.onContactPicked(nil)
            parent.dismiss()
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        GroupChatView(
            viewModel: GroupViewModel(),
            group: GroupUI(
                id: "1",
                title: "Family",
                members: [
                    GroupMemberUI(id: "m1", whisperId: "WSP-MOM1-2345-6789", displayName: "Mom", avatarURL: nil, isOnline: true, role: .member),
                    GroupMemberUI(id: "m2", whisperId: "WSP-DAD4-5678-9012", displayName: "Dad", avatarURL: nil, isOnline: false, role: .member),
                    GroupMemberUI(id: "m3", whisperId: "WSP-ME00-0000-0000", displayName: "Me", avatarURL: nil, isOnline: true, role: .owner)
                ],
                avatarURL: nil,
                lastMessage: nil,
                lastMessageTimestamp: nil,
                unreadCount: 0,
                createdAt: Date(),
                ownerId: "m3"
            )
        )
    }
}
