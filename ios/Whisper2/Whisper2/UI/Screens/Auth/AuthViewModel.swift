import SwiftUI
import Observation

/// Auth flow state management
@Observable
final class AuthViewModel {
    // MARK: - State

    enum AuthState {
        case initial
        case creatingAccount
        case recoveringAccount
        case authenticated
    }

    enum AuthStep {
        case welcome
        case generateMnemonic
        case confirmMnemonic
        case enterMnemonic
        case registering
        case complete
    }

    var state: AuthState = .initial
    var currentStep: AuthStep = .welcome
    var isLoading = false
    var error: String?

    // Mnemonic handling
    var generatedMnemonic: [String] = []
    var enteredMnemonic: [String] = Array(repeating: "", count: 12)
    var selectedConfirmationWords: Set<Int> = []
    var confirmationIndices: [Int] = []

    // WhisperID after registration
    var whisperId: String?

    // MARK: - Dependencies

    // These will be injected when actual services are implemented
    // private let cryptoService: CryptoServiceProtocol
    // private let authService: AuthServiceProtocol
    // private let storageService: StorageServiceProtocol

    // MARK: - Computed Properties

    var isValidMnemonic: Bool {
        enteredMnemonic.allSatisfy { !$0.isEmpty }
    }

    var confirmationWordsSelected: Bool {
        selectedConfirmationWords.count == confirmationIndices.count
    }

    var canProceed: Bool {
        switch currentStep {
        case .welcome:
            return true
        case .generateMnemonic:
            return !generatedMnemonic.isEmpty
        case .confirmMnemonic:
            return confirmationWordsSelected && verifyConfirmation()
        case .enterMnemonic:
            return isValidMnemonic
        case .registering:
            return false
        case .complete:
            return true
        }
    }

    // MARK: - Actions

    func startCreateAccount() {
        state = .creatingAccount
        currentStep = .generateMnemonic
        generateNewMnemonic()
    }

    func startRecoverAccount() {
        state = .recoveringAccount
        currentStep = .enterMnemonic
        enteredMnemonic = Array(repeating: "", count: 12)
    }

    func generateNewMnemonic() {
        isLoading = true
        error = nil

        // Simulate mnemonic generation (will be replaced with actual crypto)
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(500))

            // Placeholder words - actual implementation will use BIP39
            generatedMnemonic = [
                "apple", "banana", "cherry", "dragon",
                "eagle", "forest", "garden", "harbor",
                "island", "jungle", "kitten", "lemon"
            ]

            // Select 3 random indices for confirmation
            confirmationIndices = Array(0..<12).shuffled().prefix(3).sorted()
            selectedConfirmationWords.removeAll()

            isLoading = false
        }
    }

    func proceedToConfirmation() {
        currentStep = .confirmMnemonic
    }

    func selectConfirmationWord(at index: Int, word: String) {
        if generatedMnemonic[index] == word {
            selectedConfirmationWords.insert(index)
        }
    }

    func verifyConfirmation() -> Bool {
        confirmationIndices.allSatisfy { selectedConfirmationWords.contains($0) }
    }

    func completeAccountCreation() {
        currentStep = .registering
        isLoading = true
        error = nil

        Task { @MainActor in
            do {
                // Simulate registration
                try await Task.sleep(for: .seconds(2))

                // Generate WhisperID (placeholder)
                whisperId = "WH2-\(UUID().uuidString.prefix(8).uppercased())"

                currentStep = .complete
                state = .authenticated
                isLoading = false
            } catch {
                self.error = "Failed to create account. Please try again."
                currentStep = .confirmMnemonic
                isLoading = false
            }
        }
    }

    func recoverAccount() {
        guard isValidMnemonic else {
            error = "Please enter all 12 words"
            return
        }

        currentStep = .registering
        isLoading = true
        error = nil

        Task { @MainActor in
            do {
                // Simulate recovery
                try await Task.sleep(for: .seconds(2))

                // Verify mnemonic and recover (placeholder)
                whisperId = "WH2-\(UUID().uuidString.prefix(8).uppercased())"

                currentStep = .complete
                state = .authenticated
                isLoading = false
            } catch {
                self.error = "Invalid recovery phrase. Please check and try again."
                currentStep = .enterMnemonic
                isLoading = false
            }
        }
    }

    func reset() {
        state = .initial
        currentStep = .welcome
        isLoading = false
        error = nil
        generatedMnemonic = []
        enteredMnemonic = Array(repeating: "", count: 12)
        selectedConfirmationWords.removeAll()
        confirmationIndices = []
        whisperId = nil
    }

    func clearError() {
        error = nil
    }
}
