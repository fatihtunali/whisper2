import SwiftUI

/// Settings view
struct SettingsView: View {
    @StateObject private var viewModel = SettingsViewModel()
    @State private var showSeedPhrase = false
    @State private var showLogoutAlert = false
    @State private var showWipeDataAlert = false
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                
                List {
                    // Profile section
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
                    
                    // Security section
                    Section("Security") {
                        Button(action: { showSeedPhrase = true }) {
                            HStack {
                                Image(systemName: "key.fill")
                                    .foregroundColor(.orange)
                                Text("View Seed Phrase")
                                    .foregroundColor(.white)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundColor(.gray)
                            }
                        }
                    }
                    .listRowBackground(Color.gray.opacity(0.15))

                    // Privacy section
                    Section("Privacy") {
                        NavigationLink(destination: BlockedUsersView()) {
                            HStack {
                                Image(systemName: "hand.raised.fill")
                                    .foregroundColor(.red)
                                Text("Blocked Users")
                                    .foregroundColor(.white)
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
                    
                    // About section
                    Section("About") {
                        HStack {
                            Image(systemName: "info.circle.fill")
                                .foregroundColor(.blue)
                            Text("Version")
                                .foregroundColor(.white)
                            Spacer()
                            Text("1.0.0")
                                .foregroundColor(.gray)
                        }
                        
                        Link(destination: URL(string: "https://whisper2.aiakademiturkiye.com")!) {
                            HStack {
                                Image(systemName: "globe")
                                    .foregroundColor(.blue)
                                Text("Website")
                                    .foregroundColor(.white)
                                Spacer()
                                Image(systemName: "arrow.up.right")
                                    .foregroundColor(.gray)
                            }
                        }
                    }
                    .listRowBackground(Color.gray.opacity(0.15))
                    
                    // Danger Zone section
                    Section("Danger Zone") {
                        Button(action: { showLogoutAlert = true }) {
                            HStack {
                                Image(systemName: "rectangle.portrait.and.arrow.right")
                                    .foregroundColor(.orange)
                                Text("Logout")
                                    .foregroundColor(.orange)
                            }
                        }

                        Button(action: { showWipeDataAlert = true }) {
                            HStack {
                                Image(systemName: "trash.fill")
                                    .foregroundColor(.red)
                                Text("Wipe All Data")
                                    .foregroundColor(.red)
                            }
                        }
                    }
                    .listRowBackground(Color.gray.opacity(0.15))
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
                }
            } message: {
                Text("Are you sure you want to logout? Make sure you have backed up your seed phrase.")
            }
            .alert("Wipe All Data", isPresented: $showWipeDataAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Wipe Everything", role: .destructive) {
                    viewModel.wipeAllData()
                }
            } message: {
                Text("This will permanently delete ALL local data including messages, contacts, call history, files, and your account keys. This action cannot be undone. Make sure you have backed up your seed phrase!")
            }
        }
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
                        // Show seed phrase
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
                        // Hidden state
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
                    
                    // Reveal button
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
