import SwiftUI
import Combine

/// View for managing message requests from unknown senders
struct MessageRequestsView: View {
    @StateObject private var viewModel = MessageRequestsViewModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if viewModel.requests.isEmpty {
                    EmptyRequestsView()
                } else {
                    List {
                        ForEach(viewModel.requests) { request in
                            MessageRequestRow(
                                request: request,
                                onPreview: { viewModel.showPreviewSheet(for: request) },
                                onDecline: { viewModel.declineRequest(request) },
                                onBlock: { viewModel.blockRequest(request) }
                            )
                            .listRowBackground(Color.black)
                            .listRowSeparatorTint(Color.gray.opacity(0.3))
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Message Requests")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $viewModel.showQRScanner) {
                if let request = viewModel.selectedRequest {
                    PreviewRequestScannerView(
                        request: request,
                        onScanned: { publicKey in
                            viewModel.handleScannedKey(publicKey, for: request)
                        }
                    )
                }
            }
            .sheet(isPresented: $viewModel.showPreviewMessages) {
                if let request = viewModel.selectedRequest,
                   let publicKey = viewModel.scannedPublicKey {
                    MessagePreviewView(
                        request: request,
                        senderPublicKey: publicKey,
                        onAccept: { viewModel.acceptRequest(request, publicKey: publicKey) },
                        onBlock: { viewModel.blockFromPreview(request) }
                    )
                }
            }
            .alert("Block User?", isPresented: $viewModel.showBlockConfirm) {
                Button("Block", role: .destructive) {
                    if let request = viewModel.requestToBlock {
                        viewModel.confirmBlock(request)
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("You won't receive messages from this user anymore. You can unblock them later in Settings.")
            }
        }
    }
}

/// Empty state
struct EmptyRequestsView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "tray")
                .font(.system(size: 60))
                .foregroundColor(.gray)
            
            Text("No Message Requests")
                .font(.title2)
                .fontWeight(.semibold)
                .foregroundColor(.white)
            
            Text("When someone not in your contacts messages you, their request will appear here")
                .font(.subheadline)
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
    }
}

/// Row for a message request
struct MessageRequestRow: View {
    let request: MessageRequest
    let onPreview: () -> Void
    let onDecline: () -> Void
    let onBlock: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                // Avatar
                Circle()
                    .fill(Color.orange.opacity(0.3))
                    .frame(width: 50, height: 50)
                    .overlay(
                        Image(systemName: "questionmark")
                            .font(.title2)
                            .foregroundColor(.orange)
                    )

                VStack(alignment: .leading, spacing: 4) {
                    Text(request.senderId)
                        .font(.headline)
                        .foregroundColor(.white)

                    HStack(spacing: 4) {
                        Image(systemName: "envelope.fill")
                            .font(.caption2)
                        Text("\(request.messageCount) message\(request.messageCount == 1 ? "" : "s")")
                            .font(.caption)
                    }
                    .foregroundColor(.gray)

                    Text(timeAgo(from: request.lastReceivedAt))
                        .font(.caption2)
                        .foregroundColor(.gray.opacity(0.7))
                }

                Spacer()
            }

            // Info banner - updated message
            HStack(spacing: 8) {
                Image(systemName: "lock.shield.fill")
                    .foregroundColor(.orange)
                Text("Scan QR to preview messages, then decide to accept or block")
                    .font(.caption)
                    .foregroundColor(.gray)
            }
            .padding(10)
            .background(Color.orange.opacity(0.1))
            .cornerRadius(8)

            // Action buttons
            HStack(spacing: 12) {
                Button(action: onBlock) {
                    HStack {
                        Image(systemName: "hand.raised.fill")
                        Text("Block")
                    }
                    .font(.subheadline)
                    .foregroundColor(.red)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color.red.opacity(0.15))
                    .cornerRadius(8)
                }

                Button(action: onDecline) {
                    Text("Ignore")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Color.gray.opacity(0.2))
                        .cornerRadius(8)
                }

                Button(action: onPreview) {
                    HStack {
                        Image(systemName: "qrcode.viewfinder")
                        Text("Preview")
                    }
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(
                        LinearGradient(
                            colors: [.blue, .purple],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(8)
                }
            }
        }
        .padding(.vertical, 8)
    }

    private func timeAgo(from date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

/// Scanner view for previewing a request (scan first to get public key)
struct PreviewRequestScannerView: View {
    let request: MessageRequest
    let onScanned: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ZStack {
                QRScannerView { result in
                    handleScan(result)
                }

                VStack {
                    // Info header
                    VStack(spacing: 8) {
                        Text("Scan to Preview Messages")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Ask \(request.senderId) to show their QR code so you can read their messages")
                            .font(.caption)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                    .background(Color.black.opacity(0.7))
                    .cornerRadius(12)
                    .padding(.top, 60)

                    Spacer()

                    if let error = error {
                        Text(error)
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.red.opacity(0.8))
                            .cornerRadius(8)
                            .padding()
                    }
                }
            }
            .navigationTitle("Preview Messages")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.white)
                }
            }
        }
    }

    private func handleScan(_ result: String) {
        // Parse QR code
        guard let url = URL(string: result),
              url.scheme == "whisper2",
              url.host == "add",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            error = "Invalid QR code"
            return
        }

        guard let idItem = queryItems.first(where: { $0.name == "id" }),
              let scannedId = idItem.value else {
            error = "QR code missing Whisper ID"
            return
        }

        // Verify it matches the request sender
        guard scannedId == request.senderId else {
            error = "QR code is for \(scannedId), not \(request.senderId)"
            return
        }

        guard let keyItem = queryItems.first(where: { $0.name == "key" }),
              let keyBase64 = keyItem.value,
              let publicKey = Data(base64Encoded: keyBase64),
              publicKey.count == 32 else {
            error = "Invalid public key in QR code"
            return
        }

        onScanned(publicKey)
        dismiss()
    }
}

/// View showing decrypted message preview before accepting/blocking
struct MessagePreviewView: View {
    let request: MessageRequest
    let senderPublicKey: Data
    let onAccept: () -> Void
    let onBlock: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var decryptedMessages: [DecryptedPreviewMessage] = []
    @State private var isLoading = true
    @State private var decryptionError: String?

    private let contactsService = ContactsService.shared
    private let crypto = CryptoService.shared
    private let auth = AuthService.shared

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if isLoading {
                    ProgressView("Decrypting messages...")
                        .foregroundColor(.white)
                } else if let error = decryptionError {
                    VStack(spacing: 16) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 50))
                            .foregroundColor(.orange)
                        Text("Decryption Error")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text(error)
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                } else {
                    VStack(spacing: 0) {
                        // Sender info header
                        VStack(spacing: 8) {
                            Circle()
                                .fill(Color.orange.opacity(0.3))
                                .frame(width: 60, height: 60)
                                .overlay(
                                    Image(systemName: "person.fill")
                                        .font(.title)
                                        .foregroundColor(.orange)
                                )

                            Text(request.senderId)
                                .font(.headline)
                                .foregroundColor(.white)

                            Text("\(decryptedMessages.count) message\(decryptedMessages.count == 1 ? "" : "s")")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(Color.gray.opacity(0.1))

                        // Messages list
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 12) {
                                ForEach(decryptedMessages) { message in
                                    PreviewMessageBubble(message: message)
                                }
                            }
                            .padding()
                        }

                        // Action buttons
                        VStack(spacing: 12) {
                            Button(action: {
                                onAccept()
                                dismiss()
                            }) {
                                HStack {
                                    Image(systemName: "checkmark.circle.fill")
                                    Text("Accept & Add to Contacts")
                                }
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(
                                    LinearGradient(
                                        colors: [.green, .green.opacity(0.7)],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .cornerRadius(12)
                            }

                            Button(action: {
                                onBlock()
                                dismiss()
                            }) {
                                HStack {
                                    Image(systemName: "hand.raised.fill")
                                    Text("Block This User")
                                }
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.red.opacity(0.8))
                                .cornerRadius(12)
                            }

                            Button("Decide Later") {
                                dismiss()
                            }
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .padding(.top, 4)
                        }
                        .padding()
                        .background(Color.black)
                    }
                }
            }
            .navigationTitle("Message Preview")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .onAppear {
            decryptMessages()
        }
    }

    private func decryptMessages() {
        guard let user = auth.currentUser else {
            decryptionError = "Not authenticated"
            isLoading = false
            return
        }

        let pendingPayloads = contactsService.getPendingMessages(for: request.senderId)

        if pendingPayloads.isEmpty {
            decryptionError = "No messages found"
            isLoading = false
            return
        }

        var messages: [DecryptedPreviewMessage] = []

        for payload in pendingPayloads {
            guard let ciphertextData = Data(base64Encoded: payload.ciphertext),
                  let nonceData = Data(base64Encoded: payload.nonce) else {
                continue
            }

            do {
                let decrypted = try crypto.decryptMessage(
                    ciphertext: ciphertextData,
                    nonce: nonceData,
                    senderPublicKey: senderPublicKey,
                    recipientPrivateKey: user.encPrivateKey
                )

                let message = DecryptedPreviewMessage(
                    id: payload.messageId,
                    content: decrypted,
                    timestamp: Date(timeIntervalSince1970: Double(payload.timestamp) / 1000),
                    msgType: payload.msgType
                )
                messages.append(message)
            } catch {
                let failedMessage = DecryptedPreviewMessage(
                    id: payload.messageId,
                    content: "[Decryption failed]",
                    timestamp: Date(timeIntervalSince1970: Double(payload.timestamp) / 1000),
                    msgType: payload.msgType
                )
                messages.append(failedMessage)
            }
        }

        decryptedMessages = messages.sorted { $0.timestamp < $1.timestamp }
        isLoading = false
    }
}

/// A single decrypted message for preview
struct DecryptedPreviewMessage: Identifiable {
    let id: String
    let content: String
    let timestamp: Date
    let msgType: String
}

/// Message bubble for preview
struct PreviewMessageBubble: View {
    let message: DecryptedPreviewMessage

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(message.content)
                .font(.body)
                .foregroundColor(.white)
                .padding(12)
                .background(Color.gray.opacity(0.3))
                .cornerRadius(16)

            Text(formatTime(message.timestamp))
                .font(.caption2)
                .foregroundColor(.gray)
                .padding(.leading, 4)
        }
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

/// ViewModel for message requests
@MainActor
class MessageRequestsViewModel: ObservableObject {
    @Published var requests: [MessageRequest] = []
    @Published var showQRScanner = false
    @Published var showPreviewMessages = false
    @Published var selectedRequest: MessageRequest?
    @Published var scannedPublicKey: Data?
    @Published var showBlockConfirm = false
    @Published var requestToBlock: MessageRequest?

    private let contactsService = ContactsService.shared
    private var cancellables = Set<AnyCancellable>()

    init() {
        loadRequests()
        setupBindings()
    }

    private func setupBindings() {
        contactsService.$messageRequests
            .receive(on: DispatchQueue.main)
            .map { requests in
                Array(requests.values)
                    .filter { $0.status == .pending }
                    .sorted { $0.lastReceivedAt > $1.lastReceivedAt }
            }
            .sink { [weak self] requests in
                self?.requests = requests
            }
            .store(in: &cancellables)
    }

    func loadRequests() {
        requests = contactsService.getMessageRequests()
    }

    /// Show QR scanner to preview messages
    func showPreviewSheet(for request: MessageRequest) {
        selectedRequest = request
        scannedPublicKey = nil
        showQRScanner = true
    }

    /// Called after QR is scanned - show message preview
    func handleScannedKey(_ publicKey: Data, for request: MessageRequest) {
        scannedPublicKey = publicKey
        showQRScanner = false

        // Small delay to allow sheet dismissal animation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.showPreviewMessages = true
        }
    }

    /// Accept the request and add to contacts (called from preview)
    func acceptRequest(_ request: MessageRequest, publicKey: Data) {
        contactsService.acceptMessageRequest(senderId: request.senderId, publicKey: publicKey)
        showPreviewMessages = false
        selectedRequest = nil
        scannedPublicKey = nil
    }

    /// Block from preview screen
    func blockFromPreview(_ request: MessageRequest) {
        contactsService.declineMessageRequest(senderId: request.senderId, block: true)
        showPreviewMessages = false
        selectedRequest = nil
        scannedPublicKey = nil
    }

    /// Decline without blocking (ignore)
    func declineRequest(_ request: MessageRequest) {
        contactsService.declineMessageRequest(senderId: request.senderId, block: false)
    }

    /// Show block confirmation dialog
    func blockRequest(_ request: MessageRequest) {
        requestToBlock = request
        showBlockConfirm = true
    }

    /// Confirm block from dialog
    func confirmBlock(_ request: MessageRequest) {
        contactsService.declineMessageRequest(senderId: request.senderId, block: true)
        requestToBlock = nil
    }
}

#Preview {
    MessageRequestsView()
}
