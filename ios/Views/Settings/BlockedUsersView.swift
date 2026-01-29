import SwiftUI

/// View for managing blocked users
struct BlockedUsersView: View {
    @ObservedObject private var contactsService = ContactsService.shared
    @State private var showUnblockConfirm = false
    @State private var userToUnblock: BlockedUser?

    private var blockedUsers: [BlockedUser] {
        contactsService.getBlockedUsers()
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if blockedUsers.isEmpty {
                VStack(spacing: 20) {
                    Image(systemName: "hand.raised.slash")
                        .font(.system(size: 60))
                        .foregroundColor(.gray)

                    Text("No Blocked Users")
                        .font(.title2)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)

                    Text("Users you block will appear here")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                }
            } else {
                List {
                    ForEach(blockedUsers, id: \.whisperId) { user in
                        BlockedUserRow(
                            user: user,
                            onUnblock: {
                                userToUnblock = user
                                showUnblockConfirm = true
                            }
                        )
                        .listRowBackground(Color.gray.opacity(0.15))
                        .listRowSeparatorTint(Color.gray.opacity(0.3))
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Blocked Users")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Unblock User?", isPresented: $showUnblockConfirm) {
            Button("Cancel", role: .cancel) {
                userToUnblock = nil
            }
            Button("Unblock") {
                if let user = userToUnblock {
                    contactsService.unblockUser(whisperId: user.whisperId)
                    userToUnblock = nil
                }
            }
        } message: {
            if let user = userToUnblock {
                Text("Do you want to unblock \(user.whisperId)? They will be able to send you messages again.")
            }
        }
    }
}

/// Row for a blocked user
struct BlockedUserRow: View {
    let user: BlockedUser
    let onUnblock: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            // Avatar
            Circle()
                .fill(Color.red.opacity(0.2))
                .frame(width: 50, height: 50)
                .overlay(
                    Image(systemName: "hand.raised.fill")
                        .font(.title3)
                        .foregroundColor(.red)
                )

            VStack(alignment: .leading, spacing: 4) {
                Text(user.whisperId)
                    .font(.headline)
                    .foregroundColor(.white)

                if let reason = user.reason {
                    Text(reason)
                        .font(.caption)
                        .foregroundColor(.gray)
                }

                Text("Blocked \(formatDate(user.blockedAt))")
                    .font(.caption2)
                    .foregroundColor(.gray.opacity(0.7))
            }

            Spacer()

            Button(action: onUnblock) {
                Text("Unblock")
                    .font(.subheadline)
                    .foregroundColor(.blue)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.blue.opacity(0.15))
                    .cornerRadius(8)
            }
        }
        .padding(.vertical, 8)
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

#Preview {
    NavigationStack {
        BlockedUsersView()
    }
}
