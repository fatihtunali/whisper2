import SwiftUI

/// Settings menu
struct SettingsView: View {
    @State private var showingProfile = false
    @State private var showingSecurity = false
    @State private var showingBackup = false
    @State private var showingAbout = false
    @State private var showingLogoutConfirmation = false

    // Placeholder settings
    @State private var notificationsEnabled = true
    @State private var soundEnabled = true
    @State private var vibrationEnabled = true

    var body: some View {
        NavigationStack {
            List {
                // Profile section
                Section {
                    Button {
                        showingProfile = true
                    } label: {
                        HStack(spacing: Theme.Spacing.md) {
                            AvatarView(
                                name: "John Doe",
                                imageURL: nil,
                                size: Theme.AvatarSize.lg
                            )

                            VStack(alignment: .leading, spacing: Theme.Spacing.xxs) {
                                Text("John Doe")
                                    .font(Theme.Typography.headline)
                                    .foregroundColor(Theme.Colors.textPrimary)

                                Text("WH2-JOHN1234")
                                    .font(Theme.Typography.caption1)
                                    .foregroundColor(Theme.Colors.textSecondary)
                            }

                            Spacer()

                            Image(systemName: "chevron.right")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(Theme.Colors.textTertiary)
                        }
                    }
                }

                // Notifications
                Section("Notifications") {
                    Toggle(isOn: $notificationsEnabled) {
                        Label("Push Notifications", systemImage: "bell")
                    }

                    Toggle(isOn: $soundEnabled) {
                        Label("Sound", systemImage: "speaker.wave.2")
                    }
                    .disabled(!notificationsEnabled)

                    Toggle(isOn: $vibrationEnabled) {
                        Label("Vibration", systemImage: "iphone.radiowaves.left.and.right")
                    }
                    .disabled(!notificationsEnabled)
                }

                // Privacy & Security
                Section("Privacy & Security") {
                    Button {
                        showingSecurity = true
                    } label: {
                        SettingsRow(
                            icon: "lock.shield",
                            iconColor: Theme.Colors.primary,
                            title: "App Lock"
                        )
                    }

                    Button {
                        showingBackup = true
                    } label: {
                        SettingsRow(
                            icon: "arrow.triangle.2.circlepath",
                            iconColor: Theme.Colors.success,
                            title: "Backup & Recovery"
                        )
                    }

                    Button {
                        // Show blocked contacts
                    } label: {
                        SettingsRow(
                            icon: "hand.raised",
                            iconColor: Theme.Colors.error,
                            title: "Blocked Contacts"
                        )
                    }
                }

                // Storage
                Section("Storage") {
                    Button {
                        // Manage storage
                    } label: {
                        SettingsRow(
                            icon: "internaldrive",
                            iconColor: .orange,
                            title: "Manage Storage",
                            subtitle: "23.5 MB used"
                        )
                    }

                    Button {
                        // Clear cache
                    } label: {
                        SettingsRow(
                            icon: "trash",
                            iconColor: Theme.Colors.textTertiary,
                            title: "Clear Cache"
                        )
                    }
                }

                // Help & Info
                Section("Help & Info") {
                    Button {
                        // Open help
                    } label: {
                        SettingsRow(
                            icon: "questionmark.circle",
                            iconColor: Theme.Colors.primary,
                            title: "Help & Support"
                        )
                    }

                    Button {
                        // Open privacy policy
                    } label: {
                        SettingsRow(
                            icon: "doc.text",
                            iconColor: Theme.Colors.textTertiary,
                            title: "Privacy Policy"
                        )
                    }

                    Button {
                        showingAbout = true
                    } label: {
                        SettingsRow(
                            icon: "info.circle",
                            iconColor: Theme.Colors.secondary,
                            title: "About Whisper2",
                            subtitle: "Version 1.0.0"
                        )
                    }
                }

                // Account
                Section {
                    Button(role: .destructive) {
                        showingLogoutConfirmation = true
                    } label: {
                        HStack {
                            Label("Log Out", systemImage: "rectangle.portrait.and.arrow.right")
                                .foregroundColor(Theme.Colors.error)
                            Spacer()
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showingProfile) {
                ProfileView()
            }
            .sheet(isPresented: $showingSecurity) {
                SecurityView()
            }
            .sheet(isPresented: $showingBackup) {
                BackupView()
            }
            .sheet(isPresented: $showingAbout) {
                AboutView()
            }
            .confirmationDialog(
                "Log Out",
                isPresented: $showingLogoutConfirmation,
                titleVisibility: .visible
            ) {
                Button("Log Out", role: .destructive) {
                    // Perform logout
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Are you sure you want to log out? You will need your recovery phrase to log back in.")
            }
        }
    }
}

// MARK: - Settings Row

private struct SettingsRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    var subtitle: String? = nil

    var body: some View {
        HStack(spacing: Theme.Spacing.sm) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(iconColor)
                .frame(width: 28, height: 28)
                .background(iconColor.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Theme.Typography.body)
                    .foregroundColor(Theme.Colors.textPrimary)

                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.textTertiary)
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(Theme.Colors.textTertiary)
        }
    }
}

// MARK: - About View

private struct AboutView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: Theme.Spacing.xl) {
                Spacer()

                // Logo
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 80))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [Theme.Colors.primary, Theme.Colors.secondary],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )

                VStack(spacing: Theme.Spacing.xs) {
                    Text("Whisper2")
                        .font(Theme.Typography.title1)
                        .foregroundColor(Theme.Colors.textPrimary)

                    Text("Version 1.0.0 (Build 1)")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textSecondary)
                }

                Text("End-to-end encrypted messaging with self-custody identity.")
                    .font(Theme.Typography.body)
                    .foregroundColor(Theme.Colors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, Theme.Spacing.xl)

                Spacer()

                VStack(spacing: Theme.Spacing.sm) {
                    Text("Made with privacy in mind")
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.textTertiary)

                    Text("2024 Whisper2")
                        .font(Theme.Typography.caption2)
                        .foregroundColor(Theme.Colors.textTertiary)
                }
                .padding(.bottom, Theme.Spacing.xl)
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Preview

#Preview {
    SettingsView()
}
