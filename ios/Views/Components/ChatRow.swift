import SwiftUI

/// Chat row for conversation list
struct ChatRow: View {
    let conversation: Conversation
    
    var body: some View {
        HStack(spacing: 12) {
            // Avatar
            Circle()
                .fill(
                    LinearGradient(
                        colors: [.blue, .purple],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: 50, height: 50)
                .overlay(
                    Text(conversation.displayName.prefix(1).uppercased())
                        .font(.title2)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                )
            
            // Content
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.displayName)
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
