import SwiftUI

/// Contact row component
struct ContactRow: View {
    let contact: Contact
    
    var body: some View {
        NavigationLink(destination: ChatView(conversation: Conversation(
            peerId: contact.whisperId,
            peerNickname: contact.nickname
        ))) {
            HStack(spacing: 12) {
                // Avatar
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [.green, .blue],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 50, height: 50)
                    .overlay(
                        Text(contact.displayName.prefix(1).uppercased())
                            .font(.title2)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                    )
                    .overlay(
                        Circle()
                            .fill(contact.isOnline ? Color.green : Color.gray)
                            .frame(width: 14, height: 14)
                            .overlay(
                                Circle()
                                    .stroke(Color.black, lineWidth: 2)
                            )
                            .offset(x: 18, y: 18)
                    )
                
                // Info
                VStack(alignment: .leading, spacing: 4) {
                    Text(contact.displayName)
                        .font(.headline)
                        .foregroundColor(.white)
                    
                    Text(contact.whisperId)
                        .font(.caption)
                        .foregroundColor(.gray)
                        .lineLimit(1)
                }
                
                Spacer()
                
                // Message button
                Button(action: {}) {
                    Image(systemName: "message.fill")
                        .foregroundColor(.blue)
                }
            }
            .padding(.vertical, 8)
        }
    }
}

#Preview {
    List {
        ContactRow(contact: Contact(
            whisperId: "WSP-TEST-1234-5678",
            encPublicKey: Data(),
            nickname: "Alice",
            isOnline: true
        ))
        .listRowBackground(Color.black)
        
        ContactRow(contact: Contact(
            whisperId: "WSP-TEST-ABCD-EFGH",
            encPublicKey: Data(),
            nickname: nil,
            isOnline: false
        ))
        .listRowBackground(Color.black)
    }
    .listStyle(.plain)
    .background(Color.black)
}
