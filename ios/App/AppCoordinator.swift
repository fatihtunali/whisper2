import Foundation
import Combine

/// App-wide coordinator for navigation and state
@MainActor
final class AppCoordinator: ObservableObject {
    @Published var isAuthenticated = false
    @Published var isLoading = true

    private let authService = AuthService.shared
    private let keychain = KeychainService.shared
    private var cancellables = Set<AnyCancellable>()

    /// Once set to true, never go back to false (prevents flashing welcome screen)
    private var hasRegistration = false

    init() {
        // Check registration FIRST, synchronously, before any async operations
        hasRegistration = keychain.isRegistered && keychain.mnemonic != nil

        // If registered, show main view immediately (no loading screen needed)
        if hasRegistration {
            isAuthenticated = true
            isLoading = false

            // Connect in background
            Task {
                do {
                    try await authService.reconnect()
                } catch {
                    print("Auto-reconnect failed: \(error)")
                }
            }
        } else {
            // Not registered - show welcome screen
            isLoading = false
        }

        // Only listen for NEW authentications (after registration)
        setupBindings()
    }

    private func setupBindings() {
        // This sink only updates to TRUE when auth succeeds
        // It NEVER sets isAuthenticated to false - that only happens on logout
        authService.$isAuthenticated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isAuth in
                guard let self = self else { return }
                if isAuth {
                    self.hasRegistration = true
                    self.isAuthenticated = true
                }
                // Never set isAuthenticated = false here
                // The welcome screen should only show if user was never registered
            }
            .store(in: &cancellables)
    }

    /// Call this when user explicitly logs out
    func logout() {
        hasRegistration = false
        isAuthenticated = false
    }
}
