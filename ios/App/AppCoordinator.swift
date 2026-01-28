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

    init() {
        setupBindings()
        checkAuthState()
    }

    private func setupBindings() {
        // Only update isAuthenticated to false if we're not already registered locally
        // This prevents showing welcome screen during reconnection attempts
        authService.$isAuthenticated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isAuth in
                guard let self = self else { return }
                if isAuth {
                    self.isAuthenticated = true
                } else if !self.isRegisteredLocally {
                    // Only show welcome if not registered locally
                    self.isAuthenticated = false
                }
                // If registered locally but auth failed, keep showing main view
                // and let reconnection happen in background
            }
            .store(in: &cancellables)
    }

    /// Check if user has registration data stored locally
    private var isRegisteredLocally: Bool {
        keychain.isRegistered && keychain.mnemonic != nil
    }

    private func checkAuthState() {
        Task {
            // Check if we have saved credentials
            if isRegisteredLocally {
                // User is registered - show main view immediately
                isAuthenticated = true

                // Try to reconnect in background
                do {
                    try await authService.reconnect()
                } catch {
                    // Failed to reconnect, but still show main view
                    // User can use offline features, reconnection will retry automatically
                    print("Auto-reconnect failed: \(error)")
                }
            }
            isLoading = false
        }
    }
}
