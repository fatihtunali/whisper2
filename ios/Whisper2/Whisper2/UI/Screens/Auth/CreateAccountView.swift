import SwiftUI

/// Account creation flow - generate mnemonic, show to user, confirm
struct CreateAccountView: View {
    @Bindable var viewModel: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack {
            switch viewModel.currentStep {
            case .generateMnemonic:
                MnemonicDisplayView(viewModel: viewModel)
            case .confirmMnemonic:
                MnemonicConfirmView(viewModel: viewModel)
            case .registering:
                RegistrationProgressView()
            case .complete:
                AccountCreatedView(viewModel: viewModel)
            default:
                EmptyView()
            }
        }
        .navigationBarBackButtonHidden(viewModel.currentStep != .generateMnemonic)
        .toolbar {
            if viewModel.currentStep == .confirmMnemonic {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Back") {
                        viewModel.currentStep = .generateMnemonic
                    }
                }
            }
        }
        .alert("Error", isPresented: Binding(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.clearError() } }
        )) {
            Button("OK") { viewModel.clearError() }
        } message: {
            Text(viewModel.error ?? "")
        }
    }
}

// MARK: - Mnemonic Display View

private struct MnemonicDisplayView: View {
    @Bindable var viewModel: AuthViewModel
    @State private var hasCopied = false

    var body: some View {
        ScrollView {
            VStack(spacing: Theme.Spacing.xl) {
                // Header
                VStack(spacing: Theme.Spacing.sm) {
                    Image(systemName: "key.fill")
                        .font(.system(size: 48))
                        .foregroundColor(Theme.Colors.primary)

                    Text("Your Recovery Phrase")
                        .font(Theme.Typography.title2)
                        .foregroundColor(Theme.Colors.textPrimary)

                    Text("Write down these 12 words in order. This is the only way to recover your account.")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, Theme.Spacing.xl)

                // Warning
                HStack(spacing: Theme.Spacing.sm) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(Theme.Colors.warning)

                    Text("Never share this phrase. Anyone with it can access your account.")
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.textSecondary)
                }
                .padding(Theme.Spacing.md)
                .background(Theme.Colors.warning.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.md))

                // Mnemonic grid
                if viewModel.isLoading {
                    ProgressView()
                        .padding(Theme.Spacing.xxl)
                } else {
                    MnemonicGridView(words: viewModel.generatedMnemonic)
                }

                // Copy button
                Button {
                    UIPasteboard.general.string = viewModel.generatedMnemonic.joined(separator: " ")
                    hasCopied = true

                    // Clear clipboard after 60 seconds
                    Task {
                        try? await Task.sleep(for: .seconds(60))
                        if UIPasteboard.general.string == viewModel.generatedMnemonic.joined(separator: " ") {
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
                .disabled(viewModel.generatedMnemonic.isEmpty)

                Spacer(minLength: Theme.Spacing.xl)

                // Continue button
                Button {
                    viewModel.proceedToConfirmation()
                } label: {
                    Text("I've Written It Down")
                }
                .buttonStyle(.primary)
                .disabled(!viewModel.canProceed)
            }
            .padding(.horizontal, Theme.Spacing.xl)
        }
        .background(Theme.Colors.background)
        .navigationTitle("Create Account")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Mnemonic Grid

private struct MnemonicGridView: View {
    let words: [String]
    var highlightIndices: Set<Int> = []

    private let columns = [
        GridItem(.flexible()),
        GridItem(.flexible())
    ]

    var body: some View {
        LazyVGrid(columns: columns, spacing: Theme.Spacing.sm) {
            ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                HStack(spacing: Theme.Spacing.xs) {
                    Text("\(index + 1).")
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.textTertiary)
                        .frame(width: 24, alignment: .trailing)

                    Text(word)
                        .font(Theme.Typography.monospaced)
                        .foregroundColor(
                            highlightIndices.contains(index)
                                ? Theme.Colors.primary
                                : Theme.Colors.textPrimary
                        )

                    Spacer()
                }
                .padding(Theme.Spacing.sm)
                .background(
                    highlightIndices.contains(index)
                        ? Theme.Colors.primary.opacity(0.1)
                        : Theme.Colors.surface
                )
                .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.sm))
            }
        }
    }
}

// MARK: - Mnemonic Confirm View

private struct MnemonicConfirmView: View {
    @Bindable var viewModel: AuthViewModel
    @State private var selectedWords: [Int: String] = [:]

    var body: some View {
        ScrollView {
            VStack(spacing: Theme.Spacing.xl) {
                // Header
                VStack(spacing: Theme.Spacing.sm) {
                    Image(systemName: "checkmark.shield.fill")
                        .font(.system(size: 48))
                        .foregroundColor(Theme.Colors.primary)

                    Text("Confirm Your Phrase")
                        .font(Theme.Typography.title2)
                        .foregroundColor(Theme.Colors.textPrimary)

                    Text("Select the correct words to verify you've saved your recovery phrase.")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, Theme.Spacing.xl)

                // Confirmation prompts
                VStack(spacing: Theme.Spacing.md) {
                    ForEach(viewModel.confirmationIndices, id: \.self) { index in
                        ConfirmationWordRow(
                            wordNumber: index + 1,
                            correctWord: viewModel.generatedMnemonic[index],
                            allWords: viewModel.generatedMnemonic.shuffled(),
                            selectedWord: selectedWords[index],
                            onSelect: { word in
                                selectedWords[index] = word
                                viewModel.selectConfirmationWord(at: index, word: word)
                            }
                        )
                    }
                }

                Spacer(minLength: Theme.Spacing.xl)

                // Continue button
                Button {
                    viewModel.completeAccountCreation()
                } label: {
                    Text("Create Account")
                }
                .buttonStyle(.primary)
                .disabled(!viewModel.canProceed)
            }
            .padding(.horizontal, Theme.Spacing.xl)
        }
        .background(Theme.Colors.background)
        .navigationTitle("Confirm Phrase")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Confirmation Word Row

private struct ConfirmationWordRow: View {
    let wordNumber: Int
    let correctWord: String
    let allWords: [String]
    let selectedWord: String?
    let onSelect: (String) -> Void

    @State private var isExpanded = false

    private var isCorrect: Bool {
        selectedWord == correctWord
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            Text("Word #\(wordNumber)")
                .font(Theme.Typography.caption1)
                .foregroundColor(Theme.Colors.textSecondary)

            Menu {
                ForEach(allWords.prefix(6), id: \.self) { word in
                    Button(word) {
                        onSelect(word)
                    }
                }
            } label: {
                HStack {
                    Text(selectedWord ?? "Select word...")
                        .font(Theme.Typography.body)
                        .foregroundColor(
                            selectedWord == nil
                                ? Theme.Colors.textTertiary
                                : (isCorrect ? Theme.Colors.success : Theme.Colors.error)
                        )

                    Spacer()

                    if let _ = selectedWord {
                        Image(systemName: isCorrect ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundColor(isCorrect ? Theme.Colors.success : Theme.Colors.error)
                    } else {
                        Image(systemName: "chevron.down")
                            .foregroundColor(Theme.Colors.textTertiary)
                    }
                }
                .padding(Theme.Spacing.md)
                .background(Theme.Colors.surface)
                .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.md))
            }
        }
    }
}

// MARK: - Registration Progress View

private struct RegistrationProgressView: View {
    var body: some View {
        VStack(spacing: Theme.Spacing.xl) {
            Spacer()

            ProgressView()
                .scaleEffect(2)

            VStack(spacing: Theme.Spacing.sm) {
                Text("Creating Your Account")
                    .font(Theme.Typography.title3)
                    .foregroundColor(Theme.Colors.textPrimary)

                Text("Generating keys and registering with the server...")
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textSecondary)
                    .multilineTextAlignment(.center)
            }

            Spacer()
        }
        .padding(Theme.Spacing.xl)
        .background(Theme.Colors.background)
    }
}

// MARK: - Account Created View

private struct AccountCreatedView: View {
    @Bindable var viewModel: AuthViewModel

    var body: some View {
        VStack(spacing: Theme.Spacing.xl) {
            Spacer()

            // Success icon
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 80))
                .foregroundColor(Theme.Colors.success)

            // Title
            VStack(spacing: Theme.Spacing.sm) {
                Text("Account Created!")
                    .font(Theme.Typography.title2)
                    .foregroundColor(Theme.Colors.textPrimary)

                Text("Your secure identity is ready")
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textSecondary)
            }

            // WhisperID display
            if let whisperId = viewModel.whisperId {
                VStack(spacing: Theme.Spacing.xs) {
                    Text("Your WhisperID")
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.textSecondary)

                    Text(whisperId)
                        .font(Theme.Typography.monospaced)
                        .foregroundColor(Theme.Colors.primary)
                        .padding(Theme.Spacing.md)
                        .background(Theme.Colors.surface)
                        .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.md))

                    Button {
                        UIPasteboard.general.string = whisperId
                    } label: {
                        HStack {
                            Image(systemName: "doc.on.doc")
                            Text("Copy")
                        }
                        .font(Theme.Typography.caption1)
                    }
                }
            }

            Spacer()

            // Continue button
            Button {
                // Navigate to main app
            } label: {
                Text("Get Started")
            }
            .buttonStyle(.primary)
        }
        .padding(Theme.Spacing.xl)
        .background(Theme.Colors.background)
        .navigationBarBackButtonHidden(true)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        CreateAccountView(viewModel: {
            let vm = AuthViewModel()
            vm.startCreateAccount()
            return vm
        }())
    }
}
