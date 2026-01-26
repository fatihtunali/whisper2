import SwiftUI
import Observation

/// Auth flow state management with real server integration
@Observable
final class AuthViewModel {
    // MARK: - State

    enum AuthState: Equatable {
        case initial
        case creatingAccount
        case recoveringAccount
        case authenticated
    }

    enum AuthStep: Equatable {
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

    private let cryptoService = CryptoService.shared
    private let authService = AuthService.shared
    private let keychain = KeychainService.shared

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

        Task { @MainActor in
            do {
                // Generate real BIP39 mnemonic
                let mnemonic = cryptoService.generateMnemonic()
                generatedMnemonic = mnemonic.split(separator: " ").map(String.init)

                // Select 3 random indices for confirmation
                confirmationIndices = Array(0..<12).shuffled().prefix(3).sorted()
                selectedConfirmationWords.removeAll()

                isLoading = false
                logger.info("Generated new mnemonic", category: .auth)
            } catch {
                self.error = "Failed to generate recovery phrase"
                isLoading = false
                logger.error(error, message: "Mnemonic generation failed", category: .auth)
            }
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

        let mnemonicString = generatedMnemonic.joined(separator: " ")

        Task { @MainActor in
            do {
                // Connect to WebSocket first with timeout
                let wsClient = WSClient()
                logger.info("Connecting to WebSocket server...", category: .auth)

                let connected = await wsClient.connectAndWait(timeout: 15)

                guard connected else {
                    throw AuthError.registrationFailed(reason: "Failed to connect to server. Please check your internet connection.")
                }

                logger.info("WebSocket connected, starting registration...", category: .auth)

                // Create connection adapter
                let connection = WSClientConnectionAdapter(client: wsClient)

                // Register with server
                let result = try await authService.register(mnemonic: mnemonicString, ws: connection)

                // Disconnect after registration
                await wsClient.disconnect()

                whisperId = result.whisperId
                currentStep = .complete
                state = .authenticated
                isLoading = false

                logger.info("Account created successfully: \(result.whisperId)", category: .auth)

            } catch let authError as AuthError {
                self.error = authError.localizedDescription
                currentStep = .confirmMnemonic
                isLoading = false
                logger.error(authError, message: "Registration failed", category: .auth)
            } catch {
                self.error = "Failed to create account: \(error.localizedDescription)"
                currentStep = .confirmMnemonic
                isLoading = false
                logger.error(error, message: "Registration failed", category: .auth)
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

        let mnemonicString = enteredMnemonic.joined(separator: " ")

        Task { @MainActor in
            do {
                // Connect to WebSocket first with timeout
                let wsClient = WSClient()
                logger.info("Connecting to WebSocket server...", category: .auth)

                let connected = await wsClient.connectAndWait(timeout: 15)

                guard connected else {
                    throw AuthError.registrationFailed(reason: "Failed to connect to server. Please check your internet connection.")
                }

                logger.info("WebSocket connected, starting recovery...", category: .auth)

                // Create connection adapter
                let connection = WSClientConnectionAdapter(client: wsClient)

                // Register/recover with server
                let result = try await authService.register(mnemonic: mnemonicString, ws: connection)

                // Disconnect after registration
                await wsClient.disconnect()

                whisperId = result.whisperId
                currentStep = .complete
                state = .authenticated
                isLoading = false

                logger.info("Account recovered successfully: \(result.whisperId)", category: .auth)

            } catch let authError as AuthError {
                self.error = authError.localizedDescription
                currentStep = .enterMnemonic
                isLoading = false
                logger.error(authError, message: "Recovery failed", category: .auth)
            } catch {
                self.error = "Invalid recovery phrase. Please check and try again."
                currentStep = .enterMnemonic
                isLoading = false
                logger.error(error, message: "Recovery failed", category: .auth)
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
