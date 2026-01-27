import SwiftUI
import UIKit

/// Chat row for conversation list
struct ChatRow: View {
    let conversation: Conversation
    @ObservedObject private var contactsService = ContactsService.shared
    @ObservedObject private var avatarService = AvatarService.shared

    /// Get display name from ContactsService (for live updates) or fallback to conversation
    private var displayName: String {
        if let contact = contactsService.getContact(whisperId: conversation.peerId) {
            return contact.displayName
        }
        return conversation.displayName
    }

    /// Get avatar for this contact
    private var contactAvatar: UIImage? {
        avatarService.getContactAvatar(for: conversation.peerId)
    }

    var body: some View {
        HStack(spacing: 12) {
            // Avatar
            AvatarView(
                image: contactAvatar,
                name: displayName,
                size: 50
            )

            // Content
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(displayName)
                        .font(.headline)
                        .foregroundColor(.white)
                        .lineLimit(1)
                    
                    Spacer()
                    
                    if let timeString = conversation.lastMessageTimeString {
                        Text(timeString)
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                }
                
                HStack {
                    if conversation.isTyping {
                        Text("typing...")
                            .font(.subheadline)
                            .foregroundColor(.blue)
                            .italic()
                    } else if let lastMessage = conversation.lastMessage {
                        Text(lastMessage)
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .lineLimit(1)
                    }
                    
                    Spacer()
                    
                    if conversation.hasUnread {
                        Text("\(conversation.unreadCount)")
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.blue)
                            .clipShape(Capsule())
                    }
                }
            }
        }
        .padding(.vertical, 8)
    }
}

#Preview {
    List {
        ChatRow(conversation: Conversation(
            peerId: "WSP-TEST-1234-5678",
            peerNickname: "Alice",
            lastMessage: "Hey, how are you?",
            lastMessageTime: Date(),
            unreadCount: 3
        ))
        .listRowBackground(Color.black)
        
        ChatRow(conversation: Conversation(
            peerId: "WSP-TEST-ABCD-EFGH",
            peerNickname: nil,
            lastMessage: "See you tomorrow!",
            lastMessageTime: Date().addingTimeInterval(-3600),
            unreadCount: 0,
            isTyping: true
        ))
        .listRowBackground(Color.black)
    }
    .listStyle(.plain)
    .background(Color.black)
}
