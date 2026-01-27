import SwiftUI

/// View showing contact profile details
struct ContactProfileView: View {
    let peerId: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var contactsService = ContactsService.shared
    @State private var showEditNickname = false
    @State private var newNickname = ""
    @State private var showBlockConfirm = false
    @State private var showDeleteConfirm = false

    private var contact: Contact? {
        contactsService.getContact(whisperId: peerId)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        // Avatar
                        Circle()
                            .fill(
                                LinearGradient(
                                    colors: [.blue, .purple],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 120, height: 120)
                            .overlay(
                                Text(String((contact?.displayName ?? "?").prefix(1)).uppercased())
                                    .font(.system(size: 50, weight: .semibold))
                                    .foregroundColor(.white)
                            )
                            .padding(.top, 20)

                        // Name and status
                        VStack(spacing: 8) {
                            Text(contact?.displayName ?? peerId)
                                .font(.title)
                                .fontWeight(.bold)
                                .foregroundColor(.white)

                            if let contact = contact {
                                HStack(spacing: 6) {
                                    Circle()
                                        .fill(contact.isOnline ? Color.green : Color.gray)
                                        .frame(width: 8, height: 8)
                                    Text(contact.isOnline ? "Online" : lastSeenText)
                                        .font(.subheadline)
                                        .foregroundColor(.gray)
                                }
                            }
                        }

                        // Info cards
                        VStack(spacing: 16) {
                            // Whisper ID
                            ProfileInfoCard(
                                icon: "person.text.rectangle",
                                title: "Whisper ID",
                                value: peerId,
                                copyable: true
                            )

                            // Nickname
                            if let nickname = contact?.nickname {
                                ProfileInfoCard(
                                    icon: "pencil",
                                    title: "Nickname",
                                    value: nickname
                                )
                            }

                            // Added date
                            if let contact = contact {
                                ProfileInfoCard(
                                    icon: "calendar",
                                    title: "Added",
                                    value: formatDate(contact.addedAt)
                                )
                            }

                            // Encryption status
                            ProfileInfoCard(
                                icon: contactsService.hasValidPublicKey(for: peerId) ? "lock.fill" : "lock.open",
                                title: "Encryption",
                                value: contactsService.hasValidPublicKey(for: peerId) ? "End-to-end encrypted" : "Key not available",
                                valueColor: contactsService.hasValidPublicKey(for: peerId) ? .green : .orange
                            )
                        }
                        .padding(.horizontal)

                        // Actions
                        VStack(spacing: 12) {
                            // Edit nickname
                            Button(action: {
                                newNickname = contact?.nickname ?? ""
                                showEditNickname = true
                            }) {
                                HStack {
                                    Image(systemName: "pencil")
                                    Text("Edit Nickname")
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption)
                                        .foregroundColor(.gray)
                                }
                                .foregroundColor(.white)
                                .padding()
                                .background(Color.gray.opacity(0.2))
                                .cornerRadius(12)
                            }

                            // Block/Unblock
                            Button(action: { showBlockConfirm = true }) {
                                HStack {
                                    Image(systemName: contact?.isBlocked == true ? "hand.raised.slash" : "hand.raised")
                                    Text(contact?.isBlocked == true ? "Unblock Contact" : "Block Contact")
                                    Spacer()
                                }
                                .foregroundColor(contact?.isBlocked == true ? .blue : .orange)
                                .padding()
                                .background(Color.gray.opacity(0.2))
                                .cornerRadius(12)
                            }

                            // Delete
                            Button(action: { showDeleteConfirm = true }) {
                                HStack {
                                    Image(systemName: "trash")
                                    Text("Delete Contact")
                                    Spacer()
                                }
                                .foregroundColor(.red)
                                .padding()
                                .background(Color.gray.opacity(0.2))
                                .cornerRadius(12)
                            }
                        }
                        .padding(.horizontal)
                        .padding(.top, 20)

                        Spacer()
                    }
                }
            }
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .alert("Edit Nickname", isPresented: $showEditNickname) {
                TextField("Nickname", text: $newNickname)
                Button("Cancel", role: .cancel) {}
                Button("Save") {
                    saveNickname()
                }
            } message: {
                Text("Enter a nickname for this contact")
            }
            .alert(contact?.isBlocked == true ? "Unblock Contact?" : "Block Contact?", isPresented: $showBlockConfirm) {
                Button("Cancel", role: .cancel) {}
                Button(contact?.isBlocked == true ? "Unblock" : "Block", role: contact?.isBlocked == true ? .none : .destructive) {
                    toggleBlock()
                }
            } message: {
                Text(contact?.isBlocked == true ?
                    "You will be able to receive messages from this contact again." :
                    "You will no longer receive messages from this contact.")
            }
            .alert("Delete Contact?", isPresented: $showDeleteConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) {
                    deleteContact()
                }
            } message: {
                Text("This will remove the contact and all conversation history.")
            }
        }
    }

    private var lastSeenText: String {
        guard let lastSeen = contact?.lastSeen else {
            return "Last seen unknown"
        }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return "Last seen \(formatter.localizedString(for: lastSeen, relativeTo: Date()))"
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    private func saveNickname() {
        contactsService.updateNickname(for: peerId, nickname: newNickname.isEmpty ? nil : newNickname)
    }

    private func toggleBlock() {
        if contact?.isBlocked == true {
            contactsService.unblockContact(whisperId: peerId)
        } else {
            contactsService.blockContact(whisperId: peerId)
        }
    }

    private func deleteContact() {
        contactsService.deleteContact(whisperId: peerId)
        dismiss()
    }
}

/// Info card for profile details
struct ProfileInfoCard: View {
    let icon: String
    let title: String
    let value: String
    var copyable: Bool = false
    var valueColor: Color = .white

    @State private var copied = false

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundColor(.blue)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.caption)
                    .foregroundColor(.gray)
                Text(value)
                    .font(.subheadline)
                    .foregroundColor(valueColor)
                    .lineLimit(1)
            }

            Spacer()

            if copyable {
                Button(action: copyToClipboard) {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .foregroundColor(copied ? .green : .gray)
                }
            }
        }
        .padding()
        .background(Color.gray.opacity(0.15))
        .cornerRadius(12)
    }

    private func copyToClipboard() {
        UIPasteboard.general.string = value
        copied = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            copied = false
        }
    }
}

#Preview {
    ContactProfileView(peerId: "WSP-TEST-1234-5678")
}
