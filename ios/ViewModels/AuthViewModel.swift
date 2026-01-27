import Foundation
import SwiftUI

@MainActor
final class AuthViewModel: ObservableObject {
    @Published var mnemonic: String?
    @Published var isLoading = false
    @Published var error: String?
    @Published var isAuthenticated = false
    
    private let authService = AuthService.shared
    
    init() {
        checkExistingSession()
    }
    
    private func checkExistingSession() {
        if KeychainService.shared.isRegistered {
            Task { await reconnect() }
        }
    }
    
    func generateMnemonic() async {
        isLoading = true
        error = nil
        
        do {
            mnemonic = try KeyDerivation.generateMnemonic()
        } catch {
            self.error = error.localizedDescription
        }
        
        isLoading = false
    }
    
    func registerAccount() async {
        guard let mnemonic = mnemonic else {
            error = "No seed phrase generated"
            return
        }
        
        isLoading = true
        error = nil
        
        do {
            try await authService.registerNewAccount(mnemonic: mnemonic)
            isAuthenticated = true
        } catch {
            self.error = error.localizedDescription
        }
        
        isLoading = false
    }
    
    func recoverAccount() async {
        guard let mnemonic = mnemonic else {
            error = "No seed phrase entered"
            return
        }
        
        isLoading = true
        error = nil
        
        do {
            guard KeyDerivation.isValidMnemonic(mnemonic) else {
                throw CryptoError.invalidMnemonic
            }
            try await authService.recoverAccount(mnemonic: mnemonic)
            isAuthenticated = true
        } catch {
            self.error = error.localizedDescription
        }
        
        isLoading = false
    }
    
    func reconnect() async {
        isLoading = true
        error = nil
        
        do {
            try await authService.reconnect()
            isAuthenticated = true
        } catch {
            // Silent fail for auto-reconnect
        }
        
        isLoading = false
    }
    
    func logout() {
        authService.logout()
        mnemonic = nil
        isAuthenticated = false
    }
}
