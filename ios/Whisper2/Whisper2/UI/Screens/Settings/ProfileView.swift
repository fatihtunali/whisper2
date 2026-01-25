import SwiftUI

/// View/edit profile
struct ProfileView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var displayName = "John Doe"
    @State private var isEditing = false
    @State private var showingQRCode = false

    // Placeholder WhisperID
    private let whisperId = "WH2-JOHN1234"

    var body: some View {
        NavigationStack {
            List {
                // Avatar and name
                Section {
                    VStack(spacing: Theme.Spacing.md) {
                        // Avatar
                        ZStack(alignment: .bottomTrailing) {
                            AvatarView(
                                name: displayName,
                                imageURL: nil,
                                size: Theme.AvatarSize.xxl
                            )

                            Button {
                                // Change avatar
                            } label: {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 14))
                                    .foregroundColor(.white)
                                    .padding(8)
                                    .background(Theme.Colors.primary)
                                    .clipShape(Circle())
                            }
                        }

                        // Display name
                        if isEditing {
                            TextField("Display Name", text: $displayName)
                                .font(Theme.Typography.title2)
                                .multilineTextAlignment(.center)
                                .textFieldStyle(.roundedBorder)
                                .frame(maxWidth: 200)
                        } else {
                            Text(displayName)
                                .font(Theme.Typography.title2)
                                .foregroundColor(Theme.Colors.textPrimary)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Theme.Spacing.md)
                }
                .listRowBackground(Color.clear)

                // WhisperID
                Section("WhisperID") {
                    HStack {
                        Text(whisperId)
                            .font(Theme.Typography.monospaced)
                            .foregroundColor(Theme.Colors.textPrimary)

                        Spacer()

                        Button {
                            UIPasteboard.general.string = whisperId
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .foregroundColor(Theme.Colors.primary)
                        }
                    }

                    Button {
                        showingQRCode = true
                    } label: {
                        HStack {
                            Image(systemName: "qrcode")
                            Text("Show QR Code")
                        }
                    }
                }

                // Account info
                Section("Account Information") {
                    InfoRow(title: "Account Created", value: "January 15, 2024")
                    InfoRow(title: "Device", value: UIDevice.current.name)
                    InfoRow(title: "Messages Sent", value: "1,234")
                }

                // Keys (for advanced users)
                Section {
                    NavigationLink {
                        KeysInfoView()
                    } label: {
                        HStack {
                            Image(systemName: "key.fill")
                                .foregroundColor(Theme.Colors.primary)
                            Text("Encryption Keys")
                        }
                    }
                } footer: {
                    Text("Advanced: View your public keys for verification.")
                }
            }
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(isEditing ? "Done" : "Edit") {
                        if isEditing {
                            // Save changes
                        }
                        isEditing.toggle()
                    }
                }
            }
            .sheet(isPresented: $showingQRCode) {
                ShareWhisperIDView(whisperId: whisperId)
            }
        }
    }
}

// MARK: - Info Row

private struct InfoRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
                .foregroundColor(Theme.Colors.textSecondary)
            Spacer()
            Text(value)
                .foregroundColor(Theme.Colors.textPrimary)
        }
    }
}

// MARK: - Keys Info View

private struct KeysInfoView: View {
    // Placeholder keys
    private let encryptionPublicKey = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    private let signingPublicKey = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"

    var body: some View {
        List {
            Section {
                Text("Your public keys can be used by others to verify your identity. Never share your private keys.")
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textSecondary)
            }

            Section("Encryption Public Key") {
                VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
                    Text(encryptionPublicKey)
                        .font(Theme.Typography.monospaced)
                        .foregroundColor(Theme.Colors.textPrimary)
                        .lineLimit(nil)

                    Button {
                        UIPasteboard.general.string = encryptionPublicKey
                    } label: {
                        HStack {
                            Image(systemName: "doc.on.doc")
                            Text("Copy")
                        }
                        .font(Theme.Typography.caption1)
                    }
                }
            }

            Section("Signing Public Key") {
                VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
                    Text(signingPublicKey)
                        .font(Theme.Typography.monospaced)
                        .foregroundColor(Theme.Colors.textPrimary)
                        .lineLimit(nil)

                    Button {
                        UIPasteboard.general.string = signingPublicKey
                    } label: {
                        HStack {
                            Image(systemName: "doc.on.doc")
                            Text("Copy")
                        }
                        .font(Theme.Typography.caption1)
                    }
                }
            }

            Section {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(Theme.Colors.warning)
                    Text("Your private keys are stored securely in the device keychain and never leave your device.")
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.textSecondary)
                }
            }
        }
        .navigationTitle("Encryption Keys")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Preview

#Preview {
    ProfileView()
}
