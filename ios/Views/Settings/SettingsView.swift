import SwiftUI

/// Settings view
struct SettingsView: View {
    @EnvironmentObject private var coordinator: AppCoordinator
    @StateObject private var viewModel = SettingsViewModel()
    @State private var showSeedPhrase = false
    @State private var showLogoutAlert = false
    @State private var showWipeDataAlert = false
    @State private var showClearCacheAlert = false
    @State private var showResetSettingsAlert = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                List {
                    // Profile section
                    profileSection

                    // Notifications section
                    notificationsSection

                    // Privacy section
                    privacySection

                    // Chat section
                    chatSection

                    // Appearance section
                    appearanceSection

                    // Storage section
                    storageSection

                    // Security section
                    securitySection

                    // About section
                    aboutSection

                    // Danger Zone section
                    dangerZoneSection
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showSeedPhrase) {
                SeedPhraseRevealView()
            }
            .alert("Logout", isPresented: $showLogoutAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Logout", role: .destructive) {
                    viewModel.logout()
                    coordinator.logout()
                }
            } message: {
                Text("Are you sure you want to logout? Make sure you have backed up your seed phrase.")
            }
            .alert("Wipe All Data", isPresented: $showWipeDataAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Wipe Everything", role: .destructive) {
                    Task { @MainActor in
                        _ = await viewModel.wipeAllData()
                        coordinator.logout()
                    }
                }
            } message: {
                Text("This will permanently delete ALL local data including messages, contacts, call history, files, and your account keys. This action cannot be undone.")
            }
            .alert("Clear Cache", isPresented: $showClearCacheAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Clear", role: .destructive) {
                    viewModel.clearCache()
                }
            } message: {
                Text("This will clear cached files and temporary data. Your messages and contacts will not be affected.")
            }
            .alert("Reset Settings", isPresented: $showResetSettingsAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Reset", role: .destructive) {
                    viewModel.resetSettingsToDefaults()
                }
            } message: {
                Text("This will reset all settings to their default values.")
            }
            .onAppear {
                viewModel.calculateStorageUsage()
            }
        }
    }

    // MARK: - Profile Section

    private var profileSection: some View {
        Section {
            NavigationLink(destination: ProfileView()) {
                HStack(spacing: 16) {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [.blue, .purple],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 60, height: 60)
                        .overlay(
                            Image(systemName: "person.fill")
                                .font(.title)
                                .foregroundColor(.white)
                        )

                    VStack(alignment: .leading, spacing: 4) {
                        Text("My Profile")
                            .font(.headline)
                            .foregroundColor(.white)

                        Text(viewModel.whisperId ?? "Not registered")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                }
                .padding(.vertical, 8)
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - Notifications Section

    private var notificationsSection: some View {
        Section("Notifications") {
            Toggle(isOn: $viewModel.appSettings.notificationsEnabled) {
                SettingsRow(icon: "bell.fill", iconColor: .red, title: "Enable Notifications")
            }
            .tint(.blue)

            if viewModel.appSettings.notificationsEnabled {
                Toggle(isOn: $viewModel.appSettings.messagePreviewEnabled) {
                    SettingsRow(icon: "eye.fill", iconColor: .blue, title: "Show Message Preview")
                }
                .tint(.blue)

                Toggle(isOn: $viewModel.appSettings.soundEnabled) {
                    SettingsRow(icon: "speaker.wave.2.fill", iconColor: .purple, title: "Sound")
                }
                .tint(.blue)

                Toggle(isOn: $viewModel.appSettings.vibrationEnabled) {
                    SettingsRow(icon: "iphone.radiowaves.left.and.right", iconColor: .orange, title: "Vibration")
                }
                .tint(.blue)
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - Privacy Section

    private var privacySection: some View {
        Section("Privacy") {
            Toggle(isOn: $viewModel.appSettings.sendReadReceipts) {
                SettingsRow(icon: "checkmark.circle.fill", iconColor: .blue, title: "Send Read Receipts")
            }
            .tint(.blue)

            Toggle(isOn: $viewModel.appSettings.showTypingIndicators) {
                SettingsRow(icon: "ellipsis.bubble.fill", iconColor: .green, title: "Typing Indicators")
            }
            .tint(.blue)

            Toggle(isOn: $viewModel.appSettings.showOnlineStatus) {
                SettingsRow(icon: "circle.fill", iconColor: .green, title: "Show Online Status")
            }
            .tint(.blue)

            NavigationLink(destination: BlockedUsersView()) {
                HStack {
                    SettingsRow(icon: "hand.raised.fill", iconColor: .red, title: "Blocked Users")
                    Spacer()
                    if viewModel.blockedCount > 0 {
                        Text("\(viewModel.blockedCount)")
                            .font(.caption)
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.red.opacity(0.3))
                            .cornerRadius(10)
                    }
                }
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - Chat Section

    private var chatSection: some View {
        Section("Chats") {
            NavigationLink {
                DefaultDisappearingTimerView(selectedTimer: $viewModel.appSettings.defaultDisappearingTimer)
            } label: {
                HStack {
                    SettingsRow(icon: "timer", iconColor: .orange, title: "Default Disappearing Timer")
                    Spacer()
                    Text(viewModel.appSettings.defaultDisappearingTimer.displayName)
                        .foregroundColor(.gray)
                }
            }

            Toggle(isOn: $viewModel.appSettings.enterToSend) {
                SettingsRow(icon: "return", iconColor: .blue, title: "Enter Key Sends Message")
            }
            .tint(.blue)
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - Appearance Section

    private var appearanceSection: some View {
        Section("Appearance") {
            NavigationLink {
                FontSizePickerView(selectedSize: $viewModel.appSettings.fontSize)
            } label: {
                HStack {
                    SettingsRow(icon: "textformat.size", iconColor: .purple, title: "Font Size")
                    Spacer()
                    Text(viewModel.appSettings.fontSize.displayName)
                        .foregroundColor(.gray)
                }
            }

            NavigationLink {
                DefaultThemePickerView(selectedThemeId: $viewModel.appSettings.defaultChatThemeId)
            } label: {
                SettingsRow(icon: "paintpalette.fill", iconColor: .pink, title: "Default Chat Theme")
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - Storage Section

    private var storageSection: some View {
        Section("Storage & Data") {
            // Storage usage
            HStack {
                SettingsRow(icon: "internaldrive.fill", iconColor: .gray, title: "Storage Used")
                Spacer()
                if viewModel.isCalculatingStorage {
                    ProgressView()
                        .scaleEffect(0.8)
                } else {
                    Text(viewModel.storageUsage.formatted(viewModel.storageUsage.total))
                        .foregroundColor(.gray)
                }
            }

            // Auto-download settings
            NavigationLink {
                AutoDownloadSettingsView(
                    photos: $viewModel.appSettings.autoDownloadPhotos,
                    videos: $viewModel.appSettings.autoDownloadVideos,
                    audio: $viewModel.appSettings.autoDownloadAudio
                )
            } label: {
                SettingsRow(icon: "arrow.down.circle.fill", iconColor: .blue, title: "Auto-Download Media")
            }

            // Clear cache button
            Button(action: { showClearCacheAlert = true }) {
                HStack {
                    SettingsRow(icon: "trash.circle.fill", iconColor: .orange, title: "Clear Cache")
                    Spacer()
                    Text(viewModel.storageUsage.formatted(viewModel.storageUsage.cache))
                        .foregroundColor(.gray)
                }
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - Security Section

    private var securitySection: some View {
        Section("Security") {
            Button(action: { showSeedPhrase = true }) {
                HStack {
                    SettingsRow(icon: "key.fill", iconColor: .orange, title: "View Seed Phrase")
                    Spacer()
                    Image(systemName: "chevron.right")
                        .foregroundColor(.gray)
                }
            }

            if viewModel.biometricType != .none {
                Toggle(isOn: $viewModel.appSettings.appLockEnabled) {
                    SettingsRow(
                        icon: viewModel.biometricIcon,
                        iconColor: .green,
                        title: "Lock with \(viewModel.biometricName)"
                    )
                }
                .tint(.blue)

                if viewModel.appSettings.appLockEnabled {
                    NavigationLink {
                        AppLockTimeoutView(selectedTimeout: $viewModel.appSettings.appLockTimeout)
                    } label: {
                        HStack {
                            SettingsRow(icon: "clock.fill", iconColor: .blue, title: "Lock Timeout")
                            Spacer()
                            Text(viewModel.appSettings.appLockTimeout.displayName)
                                .foregroundColor(.gray)
                        }
                    }
                }
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - About Section

    private var aboutSection: some View {
        Section("About") {
            HStack {
                SettingsRow(icon: "info.circle.fill", iconColor: .blue, title: "Version")
                Spacer()
                Text(viewModel.appVersion)
                    .foregroundColor(.gray)
            }

            Link(destination: URL(string: "https://whisper2.aiakademiturkiye.com")!) {
                HStack {
                    SettingsRow(icon: "globe", iconColor: .blue, title: "Website")
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .foregroundColor(.gray)
                }
            }

            Button(action: { showResetSettingsAlert = true }) {
                SettingsRow(icon: "arrow.counterclockwise", iconColor: .gray, title: "Reset Settings to Default")
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }

    // MARK: - Danger Zone Section

    private var dangerZoneSection: some View {
        Section("Danger Zone") {
            Button(action: { showLogoutAlert = true }) {
                SettingsRow(icon: "rectangle.portrait.and.arrow.right", iconColor: .orange, title: "Logout")
            }

            Button(action: { showWipeDataAlert = true }) {
                SettingsRow(icon: "trash.fill", iconColor: .red, title: "Wipe All Data")
            }
        }
        .listRowBackground(Color.gray.opacity(0.15))
    }
}

// MARK: - Settings Row Component

struct SettingsRow: View {
    let icon: String
    let iconColor: Color
    let title: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16))
                .foregroundColor(iconColor)
                .frame(width: 28, height: 28)
                .background(iconColor.opacity(0.15))
                .cornerRadius(6)

            Text(title)
                .foregroundColor(.white)
        }
    }
}

// MARK: - Sub-Views

struct DefaultDisappearingTimerView: View {
    @Binding var selectedTimer: DisappearingMessageTimer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            List {
                ForEach(DisappearingMessageTimer.allCases, id: \.self) { timer in
                    Button(action: {
                        selectedTimer = timer
                        dismiss()
                    }) {
                        HStack {
                            Image(systemName: timer.icon)
                                .foregroundColor(.orange)
                            Text(timer.displayName)
                                .foregroundColor(.white)
                            Spacer()
                            if selectedTimer == timer {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.blue)
                            }
                        }
                    }
                    .listRowBackground(Color.gray.opacity(0.15))
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Default Timer")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct FontSizePickerView: View {
    @Binding var selectedSize: FontSize
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            List {
                ForEach(FontSize.allCases, id: \.self) { size in
                    Button(action: {
                        selectedSize = size
                        dismiss()
                    }) {
                        HStack {
                            Text("Aa")
                                .font(.system(size: 16 * size.scaleFactor))
                                .foregroundColor(.gray)
                                .frame(width: 40)
                            Text(size.displayName)
                                .foregroundColor(.white)
                            Spacer()
                            if selectedSize == size {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.blue)
                            }
                        }
                    }
                    .listRowBackground(Color.gray.opacity(0.15))
                }

                Section {
                    Text("Preview: This is how your messages will look with the selected font size.")
                        .font(.system(size: 16 * selectedSize.scaleFactor))
                        .foregroundColor(.gray)
                        .padding(.vertical, 8)
                }
                .listRowBackground(Color.gray.opacity(0.15))
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Font Size")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct DefaultThemePickerView: View {
    @Binding var selectedThemeId: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                    ForEach(ChatTheme.themes) { theme in
                        ThemePreviewCard(
                            theme: theme,
                            isSelected: (selectedThemeId ?? "default") == theme.id
                        ) {
                            selectedThemeId = theme.id
                        }
                    }
                }
                .padding()
            }
        }
        .navigationTitle("Default Theme")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AutoDownloadSettingsView: View {
    @Binding var photos: AutoDownloadOption
    @Binding var videos: AutoDownloadOption
    @Binding var audio: AutoDownloadOption

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            List {
                Section("Photos") {
                    ForEach(AutoDownloadOption.allCases, id: \.self) { option in
                        Button(action: { photos = option }) {
                            HStack {
                                Text(option.displayName)
                                    .foregroundColor(.white)
                                Spacer()
                                if photos == option {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.blue)
                                }
                            }
                        }
                        .listRowBackground(Color.gray.opacity(0.15))
                    }
                }

                Section("Videos") {
                    ForEach(AutoDownloadOption.allCases, id: \.self) { option in
                        Button(action: { videos = option }) {
                            HStack {
                                Text(option.displayName)
                                    .foregroundColor(.white)
                                Spacer()
                                if videos == option {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.blue)
                                }
                            }
                        }
                        .listRowBackground(Color.gray.opacity(0.15))
                    }
                }

                Section("Voice Messages") {
                    ForEach(AutoDownloadOption.allCases, id: \.self) { option in
                        Button(action: { audio = option }) {
                            HStack {
                                Text(option.displayName)
                                    .foregroundColor(.white)
                                Spacer()
                                if audio == option {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.blue)
                                }
                            }
                        }
                        .listRowBackground(Color.gray.opacity(0.15))
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Auto-Download")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AppLockTimeoutView: View {
    @Binding var selectedTimeout: AppLockTimeout
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            List {
                ForEach(AppLockTimeout.allCases, id: \.self) { timeout in
                    Button(action: {
                        selectedTimeout = timeout
                        dismiss()
                    }) {
                        HStack {
                            Text(timeout.displayName)
                                .foregroundColor(.white)
                            Spacer()
                            if selectedTimeout == timeout {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.blue)
                            }
                        }
                    }
                    .listRowBackground(Color.gray.opacity(0.15))
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Lock Timeout")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Seed phrase reveal view with security warning
struct SeedPhraseRevealView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isRevealed = false
    @State private var seedPhrase: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 24) {
                    // Warning
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.shield.fill")
                            .font(.system(size: 50))
                            .foregroundColor(.red)

                        Text("Security Warning")
                            .font(.title2)
                            .fontWeight(.bold)
                            .foregroundColor(.white)

                        Text("Never share your seed phrase with anyone. Anyone with your seed phrase can access your account.")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .padding(.top, 40)

                    Spacer()

                    if isRevealed, let phrase = seedPhrase {
                        let words = phrase.components(separatedBy: " ")
                        LazyVGrid(columns: [
                            GridItem(.flexible()),
                            GridItem(.flexible()),
                            GridItem(.flexible())
                        ], spacing: 12) {
                            ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                                WordCell(number: index + 1, word: word)
                            }
                        }
                        .padding()
                        .background(Color.gray.opacity(0.15))
                        .cornerRadius(16)
                        .padding(.horizontal)
                    } else {
                        VStack(spacing: 16) {
                            Image(systemName: "eye.slash.fill")
                                .font(.system(size: 40))
                                .foregroundColor(.gray)

                            Text("Tap below to reveal")
                                .foregroundColor(.gray)
                        }
                        .frame(height: 200)
                    }

                    Spacer()

                    Button(action: revealSeedPhrase) {
                        Text(isRevealed ? "Hide Seed Phrase" : "Reveal Seed Phrase")
                            .font(.headline)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(isRevealed ? Color.gray : Color.red)
                            .cornerRadius(12)
                    }
                    .padding(.horizontal, 32)
                    .padding(.bottom, 32)
                }
            }
            .navigationTitle("Seed Phrase")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func revealSeedPhrase() {
        if isRevealed {
            isRevealed = false
            seedPhrase = nil
        } else {
            seedPhrase = AuthService.shared.currentUser?.seedPhrase
            isRevealed = true
        }
    }
}

#Preview {
    SettingsView()
}
