import SwiftUI
import CoreImage.CIFilterBuiltins

/// Settings menu matching original Whisper UI
struct SettingsView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(AppCoordinator.self) private var coordinator
    @EnvironmentObject var themeManager: ThemeManager

    @State private var showingProfile = false
    @State private var showingQRCode = false
    @State private var showingRecovery = false
    @State private var showingAbout = false
    @State private var showingLogoutConfirmation = false
    @State private var showingDeleteConfirmation = false
    @State private var showingChangePIN = false
    @State private var isDeletingAccount = false
    @State private var deleteError: String?
    @State private var showingDeleteError = false

    // Privacy settings
    @State private var readReceiptsEnabled = true
    @State private var typingIndicatorEnabled = true
    @State private var hideOnlineStatus = false

    // Notification settings
    @State private var notificationsEnabled = true
    @State private var vibrateEnabled = true
    @State private var previewEnabled = true

    // Security settings
    @State private var appLockEnabled = false
    @State private var biometricEnabled = false

    private var whisperId: String {
        KeychainService.shared.whisperId ?? "Not registered"
    }

    var body: some View {
        ZStack {
            Color.whisperBackground.ignoresSafeArea()

            ScrollView {
                VStack(spacing: WhisperSpacing.lg) {
                    // Header
                    headerView

                    // Profile Section
                    profileSection

                    // Account Section
                    accountSection

                    // Security Section
                    securitySection

                    // Privacy Section
                    privacySection

                    // Notifications Section
                    notificationsSection

                    // Appearance Section
                    appearanceSection

                    // About Section
                    aboutSection

                    // Danger Zone
                    dangerZoneSection

                    // Footer
                    footerView
                }
                .padding(.horizontal, WhisperSpacing.md)
                .padding(.bottom, WhisperSpacing.xxl)
            }
        }
        .id(themeManager.themeMode)
        .sheet(isPresented: $showingProfile) {
            ProfileView()
        }
        .sheet(isPresented: $showingQRCode) {
            QRCodeView()
        }
        .sheet(isPresented: $showingRecovery) {
            RecoveryPhraseView()
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
                Task {
                    await environment.logout()
                    await MainActor.run {
                        coordinator.transitionToUnauthenticated()
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to log out? You will need your recovery phrase to log back in.")
        }
        .confirmationDialog(
            "Delete Account",
            isPresented: $showingDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete Account", role: .destructive) {
                deleteAccount()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This action cannot be undone. All your data will be permanently deleted.")
        }
        .sheet(isPresented: $showingChangePIN) {
            ChangePINView()
        }
        .alert("Error", isPresented: $showingDeleteError) {
            Button("OK") { deleteError = nil }
        } message: {
            Text(deleteError ?? "Failed to delete account")
        }
        .overlay {
            if isDeletingAccount {
                ZStack {
                    Color.black.opacity(0.5).ignoresSafeArea()
                    VStack(spacing: WhisperSpacing.md) {
                        ProgressView()
                            .scaleEffect(1.5)
                            .tint(.white)
                        Text("Deleting account...")
                            .font(.whisper(size: WhisperFontSize.md))
                            .foregroundColor(.white)
                    }
                    .padding(WhisperSpacing.xl)
                    .background(Color.whisperSurface)
                    .cornerRadius(WhisperRadius.md)
                }
            }
        }
    }

    // MARK: - Header

    private var headerView: some View {
        HStack {
            Text("Settings")
                .font(.whisper(size: WhisperFontSize.xxl, weight: .bold))
                .foregroundColor(.whisperText)
            Spacer()
        }
        .padding(.horizontal, WhisperSpacing.sm)
        .padding(.top, WhisperSpacing.md)
    }

    // MARK: - Profile Section

    private var profileSection: some View {
        Button(action: { showingProfile = true }) {
            HStack(spacing: WhisperSpacing.md) {
                // Avatar
                ZStack {
                    Circle()
                        .fill(Color.whisperPrimary.opacity(0.15))
                        .frame(width: 60, height: 60)

                    Text("👤")
                        .font(.system(size: 30))
                }

                VStack(alignment: .leading, spacing: WhisperSpacing.xs) {
                    Text(whisperId)
                        .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                        .foregroundColor(.whisperText)

                    Text("View Profile")
                        .font(.whisper(size: WhisperFontSize.sm))
                        .foregroundColor(.whisperTextSecondary)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.whisperTextMuted)
            }
            .padding(WhisperSpacing.md)
            .background(Color.whisperSurface)
            .cornerRadius(WhisperRadius.md)
        }
    }

    // MARK: - Account Section

    private var accountSection: some View {
        SettingsSection(title: "ACCOUNT") {
            SettingsButton(icon: "qrcode", title: "My QR Code", color: .whisperPrimary) {
                showingQRCode = true
            }

            SettingsButton(icon: "key.fill", title: "Recovery Phrase", color: .whisperWarning) {
                showingRecovery = true
            }
        }
    }

    // MARK: - Security Section

    private var securitySection: some View {
        SettingsSection(title: "SECURITY") {
            SettingsToggle(icon: "lock.fill", title: "App Lock", color: .whisperPrimary, isOn: $appLockEnabled)

            if appLockEnabled {
                SettingsToggle(icon: "faceid", title: "Face ID / Touch ID", color: .whisperSuccess, isOn: $biometricEnabled)
            }

            SettingsButton(icon: "lock.rotation", title: "Change PIN", color: .whisperTextSecondary) {
                showingChangePIN = true
            }

            HStack {
                Image(systemName: "checkmark.shield.fill")
                    .foregroundColor(.whisperSuccess)
                    .frame(width: 28, height: 28)
                    .background(Color.whisperSuccess.opacity(0.15))
                    .cornerRadius(6)

                Text("End-to-End Encrypted")
                    .font(.whisper(size: WhisperFontSize.md))
                    .foregroundColor(.whisperText)

                Spacer()

                Text("Always On")
                    .font(.whisper(size: WhisperFontSize.sm))
                    .foregroundColor(.whisperSuccess)
            }
            .padding(WhisperSpacing.md)
        }
    }

    // MARK: - Privacy Section

    private var privacySection: some View {
        SettingsSection(title: "PRIVACY") {
            SettingsToggle(icon: "eye.fill", title: "Read Receipts", color: .whisperPrimary, isOn: $readReceiptsEnabled)
            SettingsToggle(icon: "ellipsis.bubble.fill", title: "Typing Indicator", color: .whisperPrimary, isOn: $typingIndicatorEnabled)
            SettingsToggle(icon: "circle.slash", title: "Hide Online Status", color: .whisperTextMuted, isOn: $hideOnlineStatus)
        }
    }

    // MARK: - Notifications Section

    private var notificationsSection: some View {
        SettingsSection(title: "NOTIFICATIONS") {
            SettingsToggle(icon: "bell.fill", title: "Enable Notifications", color: .whisperPrimary, isOn: $notificationsEnabled)

            if notificationsEnabled {
                SettingsToggle(icon: "iphone.radiowaves.left.and.right", title: "Vibrate", color: .whisperTextSecondary, isOn: $vibrateEnabled)
                SettingsToggle(icon: "eye.fill", title: "Show Preview", color: .whisperTextSecondary, isOn: $previewEnabled)
            }
        }
    }

    // MARK: - Appearance Section

    private var appearanceSection: some View {
        SettingsSection(title: "APPEARANCE") {
            VStack(spacing: WhisperSpacing.sm) {
                HStack {
                    Image(systemName: "paintbrush.fill")
                        .foregroundColor(.whisperPrimary)
                        .frame(width: 28, height: 28)
                        .background(Color.whisperPrimary.opacity(0.15))
                        .cornerRadius(6)

                    Text("Theme")
                        .font(.whisper(size: WhisperFontSize.md))
                        .foregroundColor(.whisperText)

                    Spacer()
                }
                .padding(.horizontal, WhisperSpacing.md)
                .padding(.top, WhisperSpacing.md)

                // Theme Picker
                HStack(spacing: WhisperSpacing.sm) {
                    ForEach(ThemeMode.allCases, id: \.self) { mode in
                        Button {
                            withAnimation {
                                themeManager.themeMode = mode
                            }
                        } label: {
                            Text(mode.displayName)
                                .font(.whisper(size: WhisperFontSize.sm, weight: .medium))
                                .foregroundColor(themeManager.themeMode == mode ? .white : .whisperText)
                                .padding(.horizontal, WhisperSpacing.md)
                                .padding(.vertical, WhisperSpacing.sm)
                                .background(themeManager.themeMode == mode ? Color.whisperPrimary : Color.whisperSurfaceLight)
                                .cornerRadius(WhisperRadius.sm)
                        }
                    }
                }
                .padding(.horizontal, WhisperSpacing.md)
                .padding(.bottom, WhisperSpacing.md)
            }
        }
    }

    // MARK: - About Section

    private var aboutSection: some View {
        SettingsSection(title: "ABOUT") {
            SettingsButton(icon: "info.circle.fill", title: "About Whisper", color: .whisperPrimary) {
                showingAbout = true
            }

            SettingsButton(icon: "doc.text.fill", title: "Privacy Policy", color: .whisperTextSecondary) {
                openURL("https://whisper2.aiakademiturkiye.com/privacy")
            }

            SettingsButton(icon: "doc.text.fill", title: "Terms of Service", color: .whisperTextSecondary) {
                openURL("https://whisper2.aiakademiturkiye.com/terms")
            }

            SettingsButton(icon: "shield.fill", title: "Child Safety Policy", color: .whisperSuccess) {
                openURL("https://whisper2.aiakademiturkiye.com/child-safety")
            }
        }
    }

    // MARK: - Danger Zone

    private var dangerZoneSection: some View {
        SettingsSection(title: "DANGER ZONE") {
            Button(action: { showingLogoutConfirmation = true }) {
                HStack {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .foregroundColor(.whisperError)
                        .frame(width: 28, height: 28)
                        .background(Color.whisperError.opacity(0.15))
                        .cornerRadius(6)

                    Text("Log Out")
                        .font(.whisper(size: WhisperFontSize.md))
                        .foregroundColor(.whisperError)

                    Spacer()
                }
                .padding(WhisperSpacing.md)
            }

            Button(action: { showingDeleteConfirmation = true }) {
                HStack {
                    Image(systemName: "trash.fill")
                        .foregroundColor(.whisperError)
                        .frame(width: 28, height: 28)
                        .background(Color.whisperError.opacity(0.15))
                        .cornerRadius(6)

                    Text("Delete Account")
                        .font(.whisper(size: WhisperFontSize.md))
                        .foregroundColor(.whisperError)

                    Spacer()
                }
                .padding(WhisperSpacing.md)
            }
        }
    }

    // MARK: - Footer

    private var footerView: some View {
        VStack(spacing: WhisperSpacing.sm) {
            Text("Whisper - Private. Secure. Anonymous.")
                .font(.whisper(size: WhisperFontSize.sm))
                .foregroundColor(.whisperTextMuted)

            Text("Version 1.0.0")
                .font(.whisper(size: WhisperFontSize.xs))
                .foregroundColor(.whisperTextMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, WhisperSpacing.lg)
    }

    // MARK: - Helper Methods

    private func openURL(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        UIApplication.shared.open(url)
    }

    private func deleteAccount() {
        isDeletingAccount = true

        Task { @MainActor in
            do {
                // Get session token
                guard let sessionToken = KeychainService.shared.sessionToken else {
                    throw NSError(domain: "SettingsView", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not authenticated"])
                }

                // Send delete_account message to server via WebSocket
                let payload: [String: Any] = [
                    "protocolVersion": Constants.protocolVersion,
                    "sessionToken": sessionToken
                ]

                let frame: [String: Any] = [
                    "type": "delete_account",
                    "payload": payload
                ]

                let data = try JSONSerialization.data(withJSONObject: frame)
                try await environment.wsClient.send(data)

                // Clear all local data
                KeychainService.shared.clearAll()
                UserDefaults.standard.removePersistentDomain(forName: Bundle.main.bundleIdentifier ?? "")

                // Logout and transition to unauthenticated state
                await environment.logout()

                isDeletingAccount = false

                await MainActor.run {
                    coordinator.transitionToUnauthenticated()
                }

                logger.info("Account deleted successfully", category: .auth)
            } catch {
                isDeletingAccount = false
                deleteError = "Failed to delete account: \(error.localizedDescription)"
                showingDeleteError = true
                logger.error(error, message: "Failed to delete account", category: .auth)
            }
        }
    }
}

// MARK: - Settings Section

private struct SettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: WhisperSpacing.sm) {
            Text(title)
                .font(.whisper(size: WhisperFontSize.xs, weight: .semibold))
                .foregroundColor(.whisperTextMuted)
                .padding(.horizontal, WhisperSpacing.sm)

            VStack(spacing: 0) {
                content
            }
            .background(Color.whisperSurface)
            .cornerRadius(WhisperRadius.md)
        }
    }
}

// MARK: - Settings Button

private struct SettingsButton: View {
    let icon: String
    let title: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon)
                    .foregroundColor(color)
                    .frame(width: 28, height: 28)
                    .background(color.opacity(0.15))
                    .cornerRadius(6)

                Text(title)
                    .font(.whisper(size: WhisperFontSize.md))
                    .foregroundColor(.whisperText)

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.whisperTextMuted)
            }
            .padding(WhisperSpacing.md)
        }
    }
}

// MARK: - Settings Toggle

private struct SettingsToggle: View {
    let icon: String
    let title: String
    let color: Color
    @Binding var isOn: Bool

    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundColor(color)
                .frame(width: 28, height: 28)
                .background(color.opacity(0.15))
                .cornerRadius(6)

            Text(title)
                .font(.whisper(size: WhisperFontSize.md))
                .foregroundColor(.whisperText)

            Spacer()

            Toggle("", isOn: $isOn)
                .tint(.whisperPrimary)
        }
        .padding(WhisperSpacing.md)
    }
}

// MARK: - About View

private struct AboutView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.whisperBackground.ignoresSafeArea()

                VStack(spacing: WhisperSpacing.xl) {
                    Spacer()

                    // Logo
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 80))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.whisperPrimary, .whisperPrimaryLight],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )

                    VStack(spacing: WhisperSpacing.sm) {
                        Text("Whisper")
                            .font(.whisper(size: WhisperFontSize.xxxl, weight: .bold))
                            .foregroundColor(.whisperText)

                        Text("Version 1.0.0 (Build 1)")
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperTextSecondary)
                    }

                    Text("End-to-end encrypted messaging with self-custody identity.")
                        .font(.whisper(size: WhisperFontSize.md))
                        .foregroundColor(.whisperTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, WhisperSpacing.xl)

                    Spacer()

                    VStack(spacing: WhisperSpacing.sm) {
                        Text("Private. Secure. Anonymous.")
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperTextMuted)

                        Text("© 2025 Whisper")
                            .font(.whisper(size: WhisperFontSize.xs))
                            .foregroundColor(.whisperTextMuted)
                    }
                    .padding(.bottom, WhisperSpacing.xl)
                }
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                    .foregroundColor(.whisperPrimary)
                }
            }
        }
    }
}

// MARK: - QR Code View

private struct QRCodeView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var qrCodeImage: UIImage?
    @State private var showingShareSheet = false
    @State private var copied = false

    private var whisperId: String {
        KeychainService.shared.whisperId ?? "Not registered"
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.whisperBackground.ignoresSafeArea()

                VStack(spacing: WhisperSpacing.xl) {
                    Spacer()

                    // QR Code
                    VStack(spacing: WhisperSpacing.md) {
                        if let qrImage = qrCodeImage {
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 200, height: 200)
                                .background(Color.white)
                                .cornerRadius(WhisperRadius.lg)
                        } else {
                            RoundedRectangle(cornerRadius: WhisperRadius.lg)
                                .fill(Color.whisperSurface)
                                .frame(width: 200, height: 200)
                                .overlay(
                                    ProgressView()
                                        .tint(.whisperPrimary)
                                )
                        }

                        Text(whisperId)
                            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                            .foregroundColor(.whisperPrimary)
                    }

                    Text("Others can scan this QR code to add you as a contact")
                        .font(.whisper(size: WhisperFontSize.sm))
                        .foregroundColor(.whisperTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, WhisperSpacing.xl)

                    Spacer()

                    // Actions
                    VStack(spacing: WhisperSpacing.sm) {
                        Button {
                            UIPasteboard.general.string = whisperId
                            copied = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                copied = false
                            }
                        } label: {
                            HStack {
                                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                                Text(copied ? "Copied!" : "Copy WhisperID")
                            }
                            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(WhisperSpacing.md)
                            .background(Color.whisperPrimary)
                            .cornerRadius(WhisperRadius.md)
                        }

                        Button {
                            showingShareSheet = true
                        } label: {
                            HStack {
                                Image(systemName: "square.and.arrow.up")
                                Text("Share")
                            }
                            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                            .foregroundColor(.whisperPrimary)
                            .frame(maxWidth: .infinity)
                            .padding(WhisperSpacing.md)
                            .background(Color.whisperSurface)
                            .cornerRadius(WhisperRadius.md)
                        }
                    }
                    .padding(.horizontal, WhisperSpacing.xl)
                    .padding(.bottom, WhisperSpacing.xl)
                }
            }
            .navigationTitle("My QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundColor(.whisperPrimary)
                }
            }
            .onAppear {
                generateQRCode()
            }
            .sheet(isPresented: $showingShareSheet) {
                if let qrImage = qrCodeImage {
                    SettingsShareSheet(items: [qrImage, "Add me on Whisper2! My WhisperID: \(whisperId)"])
                }
            }
        }
    }

    private func generateQRCode() {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()

        filter.message = Data(whisperId.utf8)
        filter.correctionLevel = "M"

        if let outputImage = filter.outputImage {
            // Scale up the QR code
            let transform = CGAffineTransform(scaleX: 10, y: 10)
            let scaledImage = outputImage.transformed(by: transform)

            if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                qrCodeImage = UIImage(cgImage: cgImage)
            }
        }
    }
}

// MARK: - Recovery Phrase View

private struct RecoveryPhraseView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isRevealed = false
    @State private var hasCopied = false

    private var words: [String] {
        if let mnemonic = KeychainService.shared.getString(forKey: Constants.StorageKey.mnemonic) {
            return mnemonic.components(separatedBy: " ")
        }
        return []
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.whisperBackground.ignoresSafeArea()

                VStack(spacing: WhisperSpacing.xl) {
                    // Warning header
                    VStack(spacing: WhisperSpacing.sm) {
                        Image(systemName: "exclamationmark.shield.fill")
                            .font(.system(size: 48))
                            .foregroundColor(.whisperWarning)

                        Text("Keep This Private")
                            .font(.whisper(size: WhisperFontSize.xl, weight: .bold))
                            .foregroundColor(.whisperText)

                        Text("Anyone with this phrase can access your account. Never share it.")
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperTextSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, WhisperSpacing.xl)

                    if isRevealed {
                        // Show recovery phrase
                        LazyVGrid(
                            columns: [GridItem(.flexible()), GridItem(.flexible())],
                            spacing: WhisperSpacing.sm
                        ) {
                            ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                                HStack(spacing: WhisperSpacing.xs) {
                                    Text("\(index + 1).")
                                        .font(.whisper(size: WhisperFontSize.xs))
                                        .foregroundColor(.whisperTextMuted)
                                        .frame(width: 24, alignment: .trailing)

                                    Text(word)
                                        .font(.system(.body, design: .monospaced))
                                        .foregroundColor(.whisperText)

                                    Spacer()
                                }
                                .padding(WhisperSpacing.sm)
                                .background(Color.whisperSurface)
                                .cornerRadius(WhisperRadius.sm)
                            }
                        }
                        .padding(.horizontal, WhisperSpacing.md)

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
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperPrimary)
                        }
                    } else {
                        // Hidden state
                        VStack(spacing: WhisperSpacing.md) {
                            RoundedRectangle(cornerRadius: WhisperRadius.md)
                                .fill(Color.whisperSurface)
                                .frame(height: 200)
                                .overlay(
                                    VStack(spacing: WhisperSpacing.sm) {
                                        Image(systemName: "eye.slash")
                                            .font(.system(size: 32))
                                            .foregroundColor(.whisperTextMuted)

                                        Text("Tap to reveal")
                                            .font(.whisper(size: WhisperFontSize.sm))
                                            .foregroundColor(.whisperTextSecondary)
                                    }
                                )

                            Button {
                                isRevealed = true
                            } label: {
                                Text("Reveal Recovery Phrase")
                                    .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding(WhisperSpacing.md)
                                    .background(Color.whisperPrimary)
                                    .cornerRadius(WhisperRadius.md)
                            }
                        }
                        .padding(.horizontal, WhisperSpacing.md)
                    }

                    Spacer()

                    // Security note
                    HStack(spacing: WhisperSpacing.xs) {
                        Image(systemName: "lock.fill")
                            .foregroundColor(.whisperSuccess)
                        Text("Screen recording is blocked")
                            .font(.whisper(size: WhisperFontSize.xs))
                            .foregroundColor(.whisperTextMuted)
                    }
                    .padding(.bottom, WhisperSpacing.lg)
                }
            }
            .navigationTitle("Recovery Phrase")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundColor(.whisperPrimary)
                }
            }
        }
    }
}

// MARK: - Change PIN View

private struct ChangePINView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var currentPIN = ""
    @State private var newPIN = ""
    @State private var confirmPIN = ""
    @State private var errorMessage: String?
    @State private var showingError = false
    @State private var showingSuccess = false
    @FocusState private var focusedField: Field?

    enum Field {
        case current, new, confirm
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.whisperBackground.ignoresSafeArea()

                VStack(spacing: WhisperSpacing.xl) {
                    // Header
                    VStack(spacing: WhisperSpacing.sm) {
                        Image(systemName: "lock.rotation")
                            .font(.system(size: 48))
                            .foregroundColor(.whisperPrimary)

                        Text("Change PIN")
                            .font(.whisper(size: WhisperFontSize.xl, weight: .bold))
                            .foregroundColor(.whisperText)

                        Text("Enter your current PIN and choose a new one")
                            .font(.whisper(size: WhisperFontSize.sm))
                            .foregroundColor(.whisperTextSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, WhisperSpacing.xl)

                    // PIN Fields
                    VStack(spacing: WhisperSpacing.md) {
                        PINField(title: "Current PIN", text: $currentPIN)
                            .focused($focusedField, equals: .current)

                        PINField(title: "New PIN", text: $newPIN)
                            .focused($focusedField, equals: .new)

                        PINField(title: "Confirm New PIN", text: $confirmPIN)
                            .focused($focusedField, equals: .confirm)
                    }
                    .padding(.horizontal, WhisperSpacing.xl)

                    Spacer()

                    // Save button
                    Button {
                        changePIN()
                    } label: {
                        Text("Save New PIN")
                            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(WhisperSpacing.md)
                            .background(canSave ? Color.whisperPrimary : Color.whisperPrimary.opacity(0.5))
                            .cornerRadius(WhisperRadius.md)
                    }
                    .disabled(!canSave)
                    .padding(.horizontal, WhisperSpacing.xl)
                    .padding(.bottom, WhisperSpacing.xl)
                }
            }
            .navigationTitle("Change PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.whisperPrimary)
                }
            }
            .onAppear {
                focusedField = .current
            }
            .alert("Error", isPresented: $showingError) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "An error occurred")
            }
            .alert("Success", isPresented: $showingSuccess) {
                Button("OK") { dismiss() }
            } message: {
                Text("Your PIN has been changed successfully")
            }
        }
    }

    private var canSave: Bool {
        currentPIN.count >= 4 && newPIN.count >= 4 && newPIN == confirmPIN
    }

    private func changePIN() {
        // Verify current PIN
        guard let storedPIN = KeychainService.shared.getString(forKey: "app_pin") else {
            errorMessage = "No PIN is currently set"
            showingError = true
            return
        }

        guard currentPIN == storedPIN else {
            errorMessage = "Current PIN is incorrect"
            showingError = true
            return
        }

        guard newPIN == confirmPIN else {
            errorMessage = "New PINs do not match"
            showingError = true
            return
        }

        // Save new PIN
        KeychainService.shared.setString(newPIN, forKey: "app_pin")
        showingSuccess = true
    }
}

// MARK: - PIN Field

private struct PINField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: WhisperSpacing.xs) {
            Text(title)
                .font(.whisper(size: WhisperFontSize.sm))
                .foregroundColor(.whisperTextSecondary)

            SecureField("", text: $text)
                .keyboardType(.numberPad)
                .font(.system(.title2, design: .monospaced))
                .padding(WhisperSpacing.md)
                .background(Color.whisperSurface)
                .cornerRadius(WhisperRadius.md)
        }
    }
}

// MARK: - Settings Share Sheet

private struct SettingsShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - Preview

#Preview {
    SettingsView()
        .environmentObject(ThemeManager.shared)
}
