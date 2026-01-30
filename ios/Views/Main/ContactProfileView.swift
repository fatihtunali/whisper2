import SwiftUI
import UIKit

/// View showing contact profile details
struct ContactProfileView: View {
    let peerId: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var contactsService = ContactsService.shared
    @ObservedObject private var messagingService = MessagingService.shared
    @ObservedObject private var avatarService = AvatarService.shared
    @State private var showEditNickname = false
    @State private var newNickname = ""
    @State private var showBlockConfirm = false
    @State private var showDeleteConfirm = false
    @State private var showClearChatConfirm = false
    @State private var navigateToChat = false
    @State private var showChatTheme = false
    @State private var showImagePicker = false
    @State private var showImageSourcePicker = false
    @State private var selectedImage: UIImage?
    @State private var imagePickerSource: UIImagePickerController.SourceType = .photoLibrary
    @State private var contactAvatar: UIImage?

    private var contact: Contact? {
        contactsService.getContact(whisperId: peerId)
    }

    private var canCommunicate: Bool {
        contactsService.hasValidPublicKey(for: peerId)
    }

    private var isMuted: Bool {
        messagingService.isMuted(for: peerId)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 24) {
                    // Avatar with edit option
                    Button(action: { showImageSourcePicker = true }) {
                        AvatarView(
                            image: contactAvatar,
                            name: contact?.displayName ?? peerId,
                            size: 120,
                            showEditBadge: true
                        )
                    }
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

                    // Action buttons (Message, Voice Call, Video Call)
                    HStack(spacing: 40) {
                        // Message button
                        VStack(spacing: 8) {
                            Button(action: { navigateToChat = true }) {
                                Circle()
                                    .fill(Color.blue)
                                    .frame(width: 56, height: 56)
                                    .overlay(
                                        Image(systemName: "message.fill")
                                            .font(.title2)
                                            .foregroundColor(.white)
                                    )
                            }
                            .disabled(!canCommunicate)
                            .opacity(canCommunicate ? 1.0 : 0.5)

                            Text("Message")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }

                        // Voice call button
                        VStack(spacing: 8) {
                            Button(action: startVoiceCall) {
                                Circle()
                                    .fill(Color.green)
                                    .frame(width: 56, height: 56)
                                    .overlay(
                                        Image(systemName: "phone.fill")
                                            .font(.title2)
                                            .foregroundColor(.white)
                                    )
                            }
                            .disabled(!canCommunicate)
                            .opacity(canCommunicate ? 1.0 : 0.5)

                            Text("Call")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }

                        // Video call button
                        VStack(spacing: 8) {
                            Button(action: startVideoCall) {
                                Circle()
                                    .fill(Color.purple)
                                    .frame(width: 56, height: 56)
                                    .overlay(
                                        Image(systemName: "video.fill")
                                            .font(.title2)
                                            .foregroundColor(.white)
                                    )
                            }
                            .disabled(!canCommunicate)
                            .opacity(canCommunicate ? 1.0 : 0.5)

                            Text("Video")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                    .padding(.vertical, 10)

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

                    // Settings Section
                    VStack(spacing: 12) {
                        // Edit nickname
                        Button(action: {
                            newNickname = contact?.nickname ?? ""
                            showEditNickname = true
                        }) {
                            HStack {
                                Image(systemName: "pencil")
                                    .frame(width: 24)
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

                        // Chat theme
                        NavigationLink(destination: ChatThemePickerView(peerId: peerId)) {
                            HStack {
                                Image(systemName: "paintpalette")
                                    .frame(width: 24)
                                Text("Chat Theme")
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

                        // Mute notifications toggle
                        Button(action: { messagingService.toggleMute(for: peerId) }) {
                            HStack {
                                Image(systemName: isMuted ? "bell.slash.fill" : "bell.fill")
                                    .frame(width: 24)
                                Text(isMuted ? "Unmute Notifications" : "Mute Notifications")
                                Spacer()
                                if isMuted {
                                    Text("Muted")
                                        .font(.caption)
                                        .foregroundColor(.orange)
                                }
                            }
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.gray.opacity(0.2))
                            .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.top, 10)

                    // Danger Zone
                    VStack(spacing: 12) {
                        // Clear chat
                        Button(action: { showClearChatConfirm = true }) {
                            HStack {
                                Image(systemName: "eraser")
                                    .frame(width: 24)
                                Text("Clear Chat History")
                                Spacer()
                            }
                            .foregroundColor(.orange)
                            .padding()
                            .background(Color.gray.opacity(0.2))
                            .cornerRadius(12)
                        }

                        // Block/Unblock
                        Button(action: { showBlockConfirm = true }) {
                            HStack {
                                Image(systemName: contact?.isBlocked == true ? "hand.raised.slash" : "hand.raised")
                                    .frame(width: 24)
                                Text(contact?.isBlocked == true ? "Unblock Contact" : "Block Contact")
                                Spacer()
                            }
                            .foregroundColor(contact?.isBlocked == true ? .blue : .orange)
                            .padding()
                            .background(Color.gray.opacity(0.2))
                            .cornerRadius(12)
                        }

                        // Delete contact
                        Button(action: { showDeleteConfirm = true }) {
                            HStack {
                                Image(systemName: "trash")
                                    .frame(width: 24)
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
                    .padding(.top, 10)

                    Spacer(minLength: 40)
                }
            }
        }
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
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
        .alert("Clear Chat History?", isPresented: $showClearChatConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Clear", role: .destructive) {
                clearChatHistory()
            }
        } message: {
            Text("This will delete all messages in this conversation. This cannot be undone.")
        }
        .alert("Delete Contact?", isPresented: $showDeleteConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                deleteContact()
            }
        } message: {
            Text("This will remove the contact and all conversation history.")
        }
        .navigationDestination(isPresented: $navigateToChat) {
            ChatView(conversation: Conversation(
                peerId: peerId,
                peerNickname: contact?.nickname
            ))
        }
        .confirmationDialog("Change Contact Photo", isPresented: $showImageSourcePicker) {
            Button("Take Photo") {
                imagePickerSource = .camera
                showImagePicker = true
            }
            Button("Choose from Library") {
                imagePickerSource = .photoLibrary
                showImagePicker = true
            }
            if contactAvatar != nil {
                Button("Remove Photo", role: .destructive) {
                    avatarService.deleteContactAvatar(for: peerId)
                    contactAvatar = nil
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $showImagePicker) {
            AvatarImagePicker(image: $selectedImage, sourceType: imagePickerSource)
        }
        .onChange(of: selectedImage) { _, newImage in
            if let image = newImage {
                avatarService.saveContactAvatar(image, for: peerId)
                contactAvatar = image
                selectedImage = nil
            }
        }
        .onAppear {
            contactAvatar = avatarService.getContactAvatar(for: peerId)
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

    private func clearChatHistory() {
        messagingService.clearMessages(for: peerId)
    }

    private func deleteContact() {
        // Delete the conversation and messages first
        messagingService.deleteConversation(conversationId: peerId)
        // Delete the contact
        contactsService.deleteContact(whisperId: peerId)
        // Delete contact avatar
        AvatarService.shared.deleteContactAvatar(for: peerId)
        dismiss()
    }

    private func startVoiceCall() {
        Task {
            do {
                try await CallService.shared.initiateCall(to: peerId, isVideo: false)
            } catch {
                print("Failed to start voice call: \(error)")
            }
        }
    }

    private func startVideoCall() {
        Task {
            do {
                try await CallService.shared.initiateCall(to: peerId, isVideo: true)
            } catch {
                print("Failed to start video call: \(error)")
            }
        }
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
    NavigationStack {
        ContactProfileView(peerId: "WSP-TEST-1234-5678")
    }
}
