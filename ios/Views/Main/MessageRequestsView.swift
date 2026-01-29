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
                                onAccept: {
                                    print(">>> ACCEPT button tapped for: \(request.senderId)")
                                    viewModel.acceptRequest(request)
                                },
                                onBlock: {
                                    print(">>> BLOCK button tapped for: \(request.senderId)")
                                    viewModel.blockRequest(request)
                                }
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
    let onAccept: () -> Void
    let onBlock: () -> Void

    /// Whether we have the sender's public key (can accept directly without QR scan)
    private var hasPublicKey: Bool {
        request.senderEncPublicKey != nil && request.senderEncPublicKey!.count == 32
    }

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

            // Info banner
            HStack(spacing: 8) {
                Image(systemName: hasPublicKey ? "checkmark.shield.fill" : "lock.shield.fill")
                    .foregroundColor(hasPublicKey ? .green : .orange)
                Text(hasPublicKey
                    ? "Ready to accept - messages will be decrypted"
                    : "Accept to add contact, then scan their QR to enable messaging")
                    .font(.caption)
                    .foregroundColor(.gray)
            }
            .padding(10)
            .background(hasPublicKey ? Color.green.opacity(0.1) : Color.orange.opacity(0.1))
            .cornerRadius(8)

            // Action buttons - just Accept and Block
            HStack(spacing: 12) {
                Button(action: onBlock) {
                    HStack {
                        Image(systemName: "hand.raised.fill")
                        Text("Block")
                    }
                    .font(.subheadline)
                    .foregroundColor(.red)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.red.opacity(0.15))
                    .cornerRadius(8)
                }

                Button(action: onAccept) {
                    HStack {
                        Image(systemName: "checkmark.circle.fill")
                        Text("Accept")
                    }
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        LinearGradient(
                            colors: [.green, .green.opacity(0.7)],
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

/// ViewModel for message requests
@MainActor
class MessageRequestsViewModel: ObservableObject {
    @Published var requests: [MessageRequest] = []
    @Published var showBlockConfirm = false
    @Published var requestToBlock: MessageRequest?

    private let contactsService = ContactsService.shared
    private var cancellables = Set<AnyCancellable>()

    init() {
        loadRequests()
        setupBindings()
    }

    deinit {
        cancellables.removeAll()
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
                guard let self = self else { return }
                self.requests = requests
            }
            .store(in: &cancellables)
    }

    func loadRequests() {
        requests = contactsService.getMessageRequests()
    }

    /// Accept the request - add sender to contacts
    func acceptRequest(_ request: MessageRequest) {
        print("=== acceptRequest called ===")
        print("  senderId: \(request.senderId)")
        print("  hasPublicKey: \(request.senderEncPublicKey != nil)")
        print("  publicKeyCount: \(request.senderEncPublicKey?.count ?? 0)")

        // Get public key - use from request if available, otherwise use placeholder
        let publicKey: Data
        if let key = request.senderEncPublicKey, key.count == 32 {
            publicKey = key
            print("  -> Using public key from request")
        } else {
            // No public key yet - add contact with placeholder, they'll need to scan QR later
            publicKey = Data(repeating: 0, count: 32)
            print("  -> No public key, using placeholder (will need QR scan for messaging)")
        }

        print("  -> Calling contactsService.acceptMessageRequest")
        contactsService.acceptMessageRequest(senderId: request.senderId, publicKey: publicKey)
        print("  -> acceptMessageRequest completed")
    }

    /// Show block confirmation dialog
    func blockRequest(_ request: MessageRequest) {
        print("=== blockRequest called ===")
        print("  senderId: \(request.senderId)")
        requestToBlock = request
        showBlockConfirm = true
    }

    /// Confirm block from dialog
    func confirmBlock(_ request: MessageRequest) {
        print("=== confirmBlock called ===")
        print("  senderId: \(request.senderId)")
        contactsService.declineMessageRequest(senderId: request.senderId, block: true)
        requestToBlock = nil
    }
}

#Preview {
    MessageRequestsView()
}
