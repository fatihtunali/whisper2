import SwiftUI

/// Manual backup trigger
struct BackupView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showingRecoveryPhrase = false
    @State private var showingExportContacts = false
    @State private var isBackingUp = false
    @State private var lastBackupDate: Date?

    // Placeholder recovery phrase
    private let recoveryPhrase = [
        "apple", "banana", "cherry", "dragon",
        "eagle", "forest", "garden", "harbor",
        "island", "jungle", "kitten", "lemon"
    ]

    var body: some View {
        NavigationStack {
            List {
                // Recovery phrase section
                Section {
                    VStack(alignment: .leading, spacing: Theme.Spacing.md) {
                        HStack {
                            Image(systemName: "key.fill")
                                .font(.system(size: 32))
                                .foregroundColor(Theme.Colors.primary)

                            VStack(alignment: .leading, spacing: Theme.Spacing.xxs) {
                                Text("Recovery Phrase")
                                    .font(Theme.Typography.headline)
                                    .foregroundColor(Theme.Colors.textPrimary)

                                Text("Your master key for account recovery")
                                    .font(Theme.Typography.caption1)
                                    .foregroundColor(Theme.Colors.textSecondary)
                            }
                        }

                        Text("Your recovery phrase is the only way to restore your account if you lose access to this device. Make sure you have it written down in a safe place.")
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(Theme.Colors.textSecondary)

                        Button {
                            showingRecoveryPhrase = true
                        } label: {
                            Text("View Recovery Phrase")
                        }
                        .buttonStyle(.primary)
                    }
                    .padding(.vertical, Theme.Spacing.sm)
                }

                // Warning
                Section {
                    HStack(spacing: Theme.Spacing.sm) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(Theme.Colors.warning)

                        Text("Never share your recovery phrase with anyone. Whisper2 will never ask for it.")
                            .font(Theme.Typography.caption1)
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                }

                // Contacts backup
                Section("Contacts Backup") {
                    VStack(alignment: .leading, spacing: Theme.Spacing.sm) {
                        HStack {
                            Image(systemName: "person.2.fill")
                                .foregroundColor(Theme.Colors.secondary)

                            VStack(alignment: .leading, spacing: 2) {
                                Text("Export Contacts")
                                    .font(Theme.Typography.body)
                                    .foregroundColor(Theme.Colors.textPrimary)

                                if let date = lastBackupDate {
                                    Text("Last backup: \(date.formatted(date: .abbreviated, time: .shortened))")
                                        .font(Theme.Typography.caption2)
                                        .foregroundColor(Theme.Colors.textTertiary)
                                }
                            }
                        }

                        Text("Your contacts are encrypted with your contacts key derived from your recovery phrase. Export creates an encrypted backup file.")
                            .font(Theme.Typography.caption1)
                            .foregroundColor(Theme.Colors.textSecondary)

                        Button {
                            exportContacts()
                        } label: {
                            HStack {
                                if isBackingUp {
                                    ProgressView()
                                        .scaleEffect(0.8)
                                } else {
                                    Image(systemName: "square.and.arrow.up")
                                }
                                Text("Export Encrypted Backup")
                            }
                        }
                        .disabled(isBackingUp)
                    }
                    .padding(.vertical, Theme.Spacing.xs)
                }

                // Restore contacts
                Section {
                    Button {
                        // Import contacts
                    } label: {
                        HStack {
                            Image(systemName: "square.and.arrow.down")
                                .foregroundColor(Theme.Colors.primary)
                            Text("Import Contacts Backup")
                                .foregroundColor(Theme.Colors.textPrimary)
                        }
                    }
                } footer: {
                    Text("Import a previously exported contacts backup. The backup must have been created with your recovery phrase.")
                }

                // Cloud backup info
                Section("Cloud Backup") {
                    HStack(spacing: Theme.Spacing.sm) {
                        Image(systemName: "icloud.slash")
                            .font(.system(size: 32))
                            .foregroundColor(Theme.Colors.textTertiary)

                        VStack(alignment: .leading, spacing: Theme.Spacing.xxs) {
                            Text("Not Available")
                                .font(Theme.Typography.headline)
                                .foregroundColor(Theme.Colors.textPrimary)

                            Text("For maximum security, Whisper2 does not use cloud backup. Your keys never leave your device.")
                                .font(Theme.Typography.caption1)
                                .foregroundColor(Theme.Colors.textSecondary)
                        }
                    }
                    .padding(.vertical, Theme.Spacing.xs)
                }
            }
            .navigationTitle("Backup & Recovery")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
            .sheet(isPresented: $showingRecoveryPhrase) {
                RecoveryPhraseView(words: recoveryPhrase)
            }
        }
    }

    private func exportContacts() {
        isBackingUp = true

        Task { @MainActor in
            // Simulate export
            try? await Task.sleep(for: .seconds(2))

            lastBackupDate = Date()
            isBackingUp = false

            // In real implementation, show share sheet with backup file
        }
    }
}

// MARK: - Recovery Phrase View

private struct RecoveryPhraseView: View {
    let words: [String]
    @Environment(\.dismiss) private var dismiss
    @State private var isRevealed = false
    @State private var hasCopied = false

    var body: some View {
        NavigationStack {
            VStack(spacing: Theme.Spacing.xl) {
                // Warning header
                VStack(spacing: Theme.Spacing.sm) {
                    Image(systemName: "exclamationmark.shield.fill")
                        .font(.system(size: 48))
                        .foregroundColor(Theme.Colors.warning)

                    Text("Keep This Private")
                        .font(Theme.Typography.title3)
                        .foregroundColor(Theme.Colors.textPrimary)

                    Text("Anyone with this phrase can access your account. Never share it.")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, Theme.Spacing.xl)

                if isRevealed {
                    // Show recovery phrase
                    LazyVGrid(
                        columns: [GridItem(.flexible()), GridItem(.flexible())],
                        spacing: Theme.Spacing.sm
                    ) {
                        ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                            HStack(spacing: Theme.Spacing.xs) {
                                Text("\(index + 1).")
                                    .font(Theme.Typography.caption1)
                                    .foregroundColor(Theme.Colors.textTertiary)
                                    .frame(width: 24, alignment: .trailing)

                                Text(word)
                                    .font(Theme.Typography.monospaced)
                                    .foregroundColor(Theme.Colors.textPrimary)

                                Spacer()
                            }
                            .padding(Theme.Spacing.sm)
                            .background(Theme.Colors.surface)
                            .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.sm))
                        }
                    }
                    .padding(.horizontal, Theme.Spacing.md)

                    // Copy button
                    Button {
                        UIPasteboard.general.string = words.joined(separator: " ")
                        hasCopied = true

                        // Clear clipboard after 60 seconds
                        Task {
                            try? await Task.sleep(for: .seconds(60))
                            if UIPasteboard.general.string == words.joined(separator: " ") {
                                UIPasteboard.general.string = ""
                            }
                        }
                    } label: {
                        HStack {
                            Image(systemName: hasCopied ? "checkmark" : "doc.on.doc")
                            Text(hasCopied ? "Copied!" : "Copy to Clipboard")
                        }
                        .font(Theme.Typography.subheadline)
                    }
                } else {
                    // Hidden state
                    VStack(spacing: Theme.Spacing.md) {
                        RoundedRectangle(cornerRadius: Theme.CornerRadius.md)
                            .fill(Theme.Colors.surface)
                            .frame(height: 200)
                            .overlay(
                                VStack(spacing: Theme.Spacing.sm) {
                                    Image(systemName: "eye.slash")
                                        .font(.system(size: 32))
                                        .foregroundColor(Theme.Colors.textTertiary)

                                    Text("Tap to reveal")
                                        .font(Theme.Typography.subheadline)
                                        .foregroundColor(Theme.Colors.textSecondary)
                                }
                            )

                        Button {
                            isRevealed = true
                        } label: {
                            Text("Reveal Recovery Phrase")
                        }
                        .buttonStyle(.primary)
                    }
                    .padding(.horizontal, Theme.Spacing.md)
                }

                Spacer()

                // Security note
                HStack(spacing: Theme.Spacing.xs) {
                    Image(systemName: "lock.fill")
                        .foregroundColor(Theme.Colors.success)
                    Text("Screen recording is blocked")
                        .font(Theme.Typography.caption2)
                        .foregroundColor(Theme.Colors.textTertiary)
                }
                .padding(.bottom, Theme.Spacing.lg)
            }
            .navigationTitle("Recovery Phrase")
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
    BackupView()
}
