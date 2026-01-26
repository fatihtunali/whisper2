import SwiftUI
import PhotosUI
import CoreLocation
import ContactsUI

/// Attachment sheet type for single sheet presentation
enum AttachmentSheetType: Identifiable {
    case camera
    case document
    case location
    case contact

    var id: Int { hashValue }
}

/// Single chat with messages
struct ChatView: View {
    @Bindable var viewModel: ChatViewModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var isInputFocused: Bool
    @FocusState private var isSearchFocused: Bool

    // Search states
    @State private var isSearching = false
    @State private var searchText = ""

    // Attachment picker states
    @State private var showingPhotoPicker = false
    @State private var activeSheet: AttachmentSheetType?

    // Selected items
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var selectedImages: [UIImage] = []

    // Call state
    @State private var showingCallView = false
    @State private var callViewModel = CallViewModel()

    // Filtered messages for search
    private var filteredMessages: [ChatMessage] {
        if searchText.isEmpty {
            return viewModel.messages
        }
        return viewModel.messages.filter { message in
            message.content.localizedCaseInsensitiveContains(searchText)
        }
    }

    // Grouped filtered messages for search
    private var filteredGroupedMessages: [(Date, [ChatMessage])] {
        let grouped = Dictionary(grouping: filteredMessages) { message in
            Calendar.current.startOfDay(for: message.timestamp)
        }
        return grouped.sorted { $0.key < $1.key }
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
                        viewModel.sendMessage()
                    },
                    onAttachment: { option in
                        handleAttachment(option)
                    }
                )
            }
        }
        .background(Color.whisperBackground)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                chatHeader
            }

            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        // Start voice call
                        startVoiceCall()
                    } label: {
                        Label("Voice Call", systemImage: "phone")
                    }

                    Button {
                        // Start video call
                        startVideoCall()
                    } label: {
                        Label("Video Call", systemImage: "video")
                    }

                    Divider()

                    Button {
                        isSearching = true
                    } label: {
                        Label("Search in Chat", systemImage: "magnifyingglass")
                    }

                    Button(role: .destructive) {
                        viewModel.clearChat()
                    } label: {
                        Label("Clear Chat", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .foregroundColor(.whisperPrimary)
                }
            }
        }
        .onAppear {
            viewModel.loadMessages()
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
                CameraView { image in
                    if let image = image {
                        sendImage(image)
                    }
                }
            case .document:
                DocumentPickerView { urls in
                    for url in urls {
                        sendDocument(url)
                    }
                }
            case .location:
                LocationPickerView { location in
                    if let location = location {
                        sendLocation(location)
                    }
                }
            case .contact:
                ContactPickerView { contact in
                    if let contact = contact {
                        sendContact(contact)
                    }
                }
            }
        }
        .fullScreenCover(isPresented: $showingCallView) {
            CallView(viewModel: callViewModel)
        }
    }

    private var chatHeader: some View {
        HStack(spacing: WhisperSpacing.xs) {
            AvatarView(
                name: viewModel.participantName,
                imageURL: viewModel.participantAvatarURL,
                size: 32
            )

            VStack(alignment: .leading, spacing: 0) {
                Text(viewModel.participantName)
                    .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                    .foregroundColor(.whisperText)

                if viewModel.isParticipantTyping {
                    Text("typing...")
                        .font(.whisper(size: WhisperFontSize.xs))
                        .foregroundColor(.whisperPrimary)
                } else if viewModel.isParticipantOnline {
                    Text("online")
                        .font(.whisper(size: WhisperFontSize.xs))
                        .foregroundColor(.whisperSuccess)
                }
            }
        }
    }

    // MARK: - Search Bar

    private var searchBar: some View {
        HStack(spacing: WhisperSpacing.sm) {
            HStack(spacing: WhisperSpacing.xs) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.whisperTextMuted)

                TextField("Search messages...", text: $searchText)
                    .textFieldStyle(.plain)
                    .focused($isSearchFocused)
                    .submitLabel(.search)

                if !searchText.isEmpty {
                    Button {
                        searchText = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.whisperTextMuted)
                    }
                }
            }
            .padding(WhisperSpacing.sm)
            .background(Color.whisperSurface)
            .cornerRadius(WhisperRadius.md)

            Button("Cancel") {
                searchText = ""
                isSearching = false
                isSearchFocused = false
            }
            .foregroundColor(.whisperPrimary)
        }
        .padding(.horizontal, WhisperSpacing.md)
        .padding(.vertical, WhisperSpacing.sm)
        .background(Color.whisperBackground)
        .onAppear {
            isSearchFocused = true
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
        viewModel.sendImageMessage(image)
    }

    private func sendDocument(_ url: URL) {
        viewModel.sendDocumentMessage(url)
    }

    private func sendLocation(_ location: CLLocationCoordinate2D) {
        let locationText = "📍 Location: \(location.latitude), \(location.longitude)"
        viewModel.sendLocationMessage(location)
    }

    private func sendContact(_ contact: CNContact) {
        let name = "\(contact.givenName) \(contact.familyName)"
        viewModel.sendContactMessage(contact)
    }

    private func startVoiceCall() {
        let recipientId = viewModel.participantId
        guard !recipientId.isEmpty else {
            logger.error("Cannot start call - no participant ID", category: .calls)
            return
        }

        logger.info("Starting voice call to \(recipientId)", category: .calls)

        // Set up call view model with participant info
        callViewModel.participantId = recipientId
        callViewModel.participantName = viewModel.participantName
        callViewModel.participantAvatarURL = viewModel.participantAvatarURL

        // Show call screen immediately
        showingCallView = true

        // Then initiate the actual call
        callViewModel.initiateCall(
            to: recipientId,
            name: viewModel.participantName,
            avatarURL: viewModel.participantAvatarURL,
            isVideo: false
        )
    }

    private func startVideoCall() {
        let recipientId = viewModel.participantId
        guard !recipientId.isEmpty else {
            logger.error("Cannot start call - no participant ID", category: .calls)
            return
        }

        logger.info("Starting video call to \(recipientId)", category: .calls)

        // Set up call view model with participant info
        callViewModel.participantId = recipientId
        callViewModel.participantName = viewModel.participantName
        callViewModel.participantAvatarURL = viewModel.participantAvatarURL

        // Show call screen immediately
        showingCallView = true

        // Then initiate the actual call
        callViewModel.initiateCall(
            to: recipientId,
            name: viewModel.participantName,
            avatarURL: viewModel.participantAvatarURL,
            isVideo: true
        )
    }

    private var messagesView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                if isSearching && filteredMessages.isEmpty && !searchText.isEmpty {
                    // No search results
                    VStack(spacing: WhisperSpacing.md) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 40))
                            .foregroundColor(.whisperTextMuted)
                        Text("No messages found")
                            .font(.whisper(size: WhisperFontSize.md))
                            .foregroundColor(.whisperTextSecondary)
                        Text("Try a different search term")
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperTextMuted)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, WhisperSpacing.xxl)
                } else {
                    LazyVStack(spacing: WhisperSpacing.sm) {
                        // Search results count (when searching)
                        if isSearching && !searchText.isEmpty {
                            Text("\(filteredMessages.count) result\(filteredMessages.count == 1 ? "" : "s")")
                                .font(.whisper(size: WhisperFontSize.xs))
                                .foregroundColor(.whisperTextMuted)
                                .padding(.top, WhisperSpacing.sm)
                        }

                        // Load more button (only when not searching)
                        if !isSearching && viewModel.hasMoreMessages {
                            Button {
                                viewModel.loadMoreMessages()
                            } label: {
                                Text("Load earlier messages")
                                    .font(.whisper(size: WhisperFontSize.sm))
                                    .foregroundColor(.whisperPrimary)
                            }
                            .padding(.top, WhisperSpacing.md)
                        }

                        // Messages grouped by date
                        let messagesToShow = isSearching ? filteredGroupedMessages : viewModel.groupedMessages
                        ForEach(messagesToShow, id: \.0) { date, messages in
                            DateDivider(date: date)

                            ForEach(messages) { message in
                                MessageRow(
                                    message: message,
                                    onRetry: { viewModel.retrySending(message) },
                                    onDelete: { viewModel.deleteMessage(message) },
                                    highlightText: isSearching ? searchText : nil
                                )
                                .id(message.id)
                            }
                        }
                    }
                    .padding(.horizontal, WhisperSpacing.md)
                    .padding(.bottom, WhisperSpacing.sm)
                }
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                if !isSearching, let lastMessage = viewModel.messages.last {
                    withAnimation {
                        proxy.scrollTo(lastMessage.id, anchor: .bottom)
                    }
                }
            }
            .onTapGesture {
                isInputFocused = false
                if isSearching {
                    isSearchFocused = false
                }
            }
        }
    }
}

// MARK: - Date Divider

private struct DateDivider: View {
    let date: Date

    private var dateString: String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return "Today"
        } else if calendar.isDateInYesterday(date) {
            return "Yesterday"
        } else {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
    }

    var body: some View {
        HStack {
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)

            Text(dateString)
                .font(.whisper(size: WhisperFontSize.xs))
                .foregroundColor(.whisperTextMuted)
                .padding(.horizontal, WhisperSpacing.xs)

            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)
        }
        .padding(.vertical, WhisperSpacing.sm)
    }
}

// MARK: - Message Row

private struct MessageRow: View {
    let message: ChatMessage
    let onRetry: () -> Void
    let onDelete: () -> Void
    var highlightText: String? = nil

    @State private var showActions = false

    var body: some View {
        SwiftUI.Group {
            if let highlight = highlightText, !highlight.isEmpty {
                // Highlighted message bubble for search results
                MessageBubble(
                    content: message.content,
                    timestamp: message.timestamp,
                    isSent: message.isFromMe,
                    status: mapStatus(message.status)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: WhisperRadius.lg)
                        .stroke(Color.whisperPrimary, lineWidth: 2)
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
        .contextMenu {
            Button {
                UIPasteboard.general.string = message.content
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }

            if message.status == .failed {
                Button {
                    onRetry()
                } label: {
                    Label("Retry", systemImage: "arrow.clockwise")
                }
            }

            if message.isFromMe {
                Button(role: .destructive) {
                    onDelete()
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            }
        }
        .onTapGesture {
            if message.status == .failed {
                showActions = true
            }
        }
        .confirmationDialog("Message failed to send", isPresented: $showActions) {
            Button("Retry") {
                onRetry()
            }
            Button("Delete", role: .destructive) {
                onDelete()
            }
            Button("Cancel", role: .cancel) {}
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

// MARK: - Camera View

struct CameraView: UIViewControllerRepresentable {
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
        let parent: CameraView

        init(_ parent: CameraView) {
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

// MARK: - Document Picker View

struct DocumentPickerView: UIViewControllerRepresentable {
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
        let parent: DocumentPickerView

        init(_ parent: DocumentPickerView) {
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

// MARK: - Location Picker View

struct LocationPickerView: View {
    let onLocationPicked: (CLLocationCoordinate2D?) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var locationManager = LocationManager()

    var body: some View {
        NavigationStack {
            VStack(spacing: WhisperSpacing.lg) {
                Spacer()

                if locationManager.isLoading {
                    ProgressView("Getting location...")
                        .foregroundColor(.whisperText)
                } else if let location = locationManager.currentLocation {
                    VStack(spacing: WhisperSpacing.md) {
                        Image(systemName: "location.circle.fill")
                            .font(.system(size: 64))
                            .foregroundColor(.whisperPrimary)

                        Text("Your Current Location")
                            .font(.whisper(size: WhisperFontSize.lg, weight: .semibold))
                            .foregroundColor(.whisperText)

                        Text("\(location.latitude, specifier: "%.4f"), \(location.longitude, specifier: "%.4f")")
                            .font(.system(size: WhisperFontSize.md, design: .monospaced))
                            .foregroundColor(.whisperTextSecondary)

                        Button {
                            onLocationPicked(location)
                            dismiss()
                        } label: {
                            Text("Share This Location")
                                .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, WhisperSpacing.xl)
                                .padding(.vertical, WhisperSpacing.sm)
                                .background(Color.whisperPrimary)
                                .cornerRadius(WhisperRadius.md)
                        }
                    }
                } else if let error = locationManager.error {
                    VStack(spacing: WhisperSpacing.md) {
                        Image(systemName: "location.slash")
                            .font(.system(size: 64))
                            .foregroundColor(.whisperError)

                        Text("Location Error")
                            .font(.whisper(size: WhisperFontSize.lg, weight: .semibold))
                            .foregroundColor(.whisperText)

                        Text(error)
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperTextSecondary)
                            .multilineTextAlignment(.center)

                        Button("Try Again") {
                            locationManager.requestLocation()
                        }
                        .foregroundColor(.whisperPrimary)
                    }
                }

                Spacer()
            }
            .padding()
            .background(Color.whisperBackground)
            .navigationTitle("Share Location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        onLocationPicked(nil)
                        dismiss()
                    }
                    .foregroundColor(.whisperPrimary)
                }
            }
            .onAppear {
                locationManager.requestLocation()
            }
        }
    }
}

// MARK: - Location Manager

class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
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

// MARK: - Contact Picker View

struct ContactPickerView: UIViewControllerRepresentable {
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
        let parent: ContactPickerView

        init(_ parent: ContactPickerView) {
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
        ChatView(viewModel: ChatViewModel(
            conversationId: "1",
            participantId: "user1",
            participantName: "Alice Smith"
        ))
    }
}
