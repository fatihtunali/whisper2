import Foundation
import Combine

/// App-wide coordinator for navigation and state
@MainActor
final class AppCoordinator: ObservableObject {
    @Published var isAuthenticated = false
    @Published var isLoading = true
    
    private let authService = AuthService.shared
    private var cancellables = Set<AnyCancellable>()
    
    init() {
        setupBindings()
        checkAuthState()
    }
    
    private func setupBindings() {
        authService.$isAuthenticated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isAuth in
                self?.isAuthenticated = isAuth
            }
            .store(in: &cancellables)
    }
    
    private func checkAuthState() {
        Task {
            // Check if we have saved credentials
            if KeychainService.shared.mnemonic != nil {
                // Try to reconnect
                do {
                    try await authService.reconnect()
                } catch {
                    // Failed to reconnect, user will see welcome screen
                    print("Auto-reconnect failed: \(error)")
                }
            }
            isLoading = false
        }
    }
}
