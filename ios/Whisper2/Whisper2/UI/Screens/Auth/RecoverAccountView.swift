import SwiftUI

/// Account recovery - enter mnemonic to recover
struct RecoverAccountView: View {
    @Bindable var viewModel: AuthViewModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focusedField: Int?

    var body: some View {
        Group {
            switch viewModel.currentStep {
            case .enterMnemonic:
                mnemonicEntryView
            case .registering:
                RecoveryProgressView()
            case .complete:
                AccountRecoveredView(viewModel: viewModel)
            default:
                EmptyView()
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

    private var mnemonicEntryView: some View {
        ScrollView {
            VStack(spacing: Theme.Spacing.xl) {
                // Header
                VStack(spacing: Theme.Spacing.sm) {
                    Image(systemName: "arrow.counterclockwise.circle.fill")
                        .font(.system(size: 48))
                        .foregroundColor(Theme.Colors.primary)

                    Text("Recover Your Account")
                        .font(Theme.Typography.title2)
                        .foregroundColor(Theme.Colors.textPrimary)

                    Text("Enter your 12-word recovery phrase to restore your account.")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, Theme.Spacing.xl)

                // Paste button
                Button {
                    pasteFromClipboard()
                } label: {
                    HStack {
                        Image(systemName: "doc.on.clipboard")
                        Text("Paste from Clipboard")
                    }
                    .font(Theme.Typography.subheadline)
                }

                // Word entry grid
                MnemonicEntryGrid(
                    words: $viewModel.enteredMnemonic,
                    focusedField: $focusedField
                )

                // Clear button
                if viewModel.enteredMnemonic.contains(where: { !$0.isEmpty }) {
                    Button {
                        viewModel.enteredMnemonic = Array(repeating: "", count: 12)
                        focusedField = 0
                    } label: {
                        HStack {
                            Image(systemName: "xmark.circle")
                            Text("Clear All")
                        }
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.error)
                    }
                }

                Spacer(minLength: Theme.Spacing.xl)

                // Recover button
                Button {
                    focusedField = nil
                    viewModel.recoverAccount()
                } label: {
                    Text("Recover Account")
                }
                .buttonStyle(.primary)
                .disabled(!viewModel.canProceed)
            }
            .padding(.horizontal, Theme.Spacing.xl)
        }
        .background(Theme.Colors.background)
        .navigationTitle("Recover Account")
        .navigationBarTitleDisplayMode(.inline)
        .onTapGesture {
            focusedField = nil
        }
    }

    private func pasteFromClipboard() {
        guard let text = UIPasteboard.general.string else { return }

        let words = text
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }

        guard words.count == 12 else { return }

        for (index, word) in words.enumerated() {
            viewModel.enteredMnemonic[index] = word
        }
    }
}

// MARK: - Mnemonic Entry Grid

private struct MnemonicEntryGrid: View {
    @Binding var words: [String]
    var focusedField: FocusState<Int?>.Binding

    private let columns = [
        GridItem(.flexible()),
        GridItem(.flexible())
    ]

    var body: some View {
        LazyVGrid(columns: columns, spacing: Theme.Spacing.sm) {
            ForEach(0..<12, id: \.self) { index in
                MnemonicWordField(
                    wordNumber: index + 1,
                    word: $words[index],
                    isFocused: focusedField.wrappedValue == index,
                    onSubmit: {
                        if index < 11 {
                            focusedField.wrappedValue = index + 1
                        } else {
                            focusedField.wrappedValue = nil
                        }
                    }
                )
                .focused(focusedField, equals: index)
            }
        }
    }
}

// MARK: - Mnemonic Word Field

private struct MnemonicWordField: View {
    let wordNumber: Int
    @Binding var word: String
    let isFocused: Bool
    let onSubmit: () -> Void

    var body: some View {
        HStack(spacing: Theme.Spacing.xs) {
            Text("\(wordNumber).")
                .font(Theme.Typography.caption1)
                .foregroundColor(Theme.Colors.textTertiary)
                .frame(width: 24, alignment: .trailing)

            TextField("", text: $word)
                .font(Theme.Typography.monospaced)
                .foregroundColor(Theme.Colors.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(wordNumber < 12 ? .next : .done)
                .onSubmit(onSubmit)
        }
        .padding(Theme.Spacing.sm)
        .background(Theme.Colors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: Theme.CornerRadius.sm)
                .stroke(
                    isFocused ? Theme.Colors.primary : Color.clear,
                    lineWidth: 2
                )
        )
        .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.sm))
    }
}

// MARK: - Recovery Progress View

private struct RecoveryProgressView: View {
    var body: some View {
        VStack(spacing: Theme.Spacing.xl) {
            Spacer()

            ProgressView()
                .scaleEffect(2)

            VStack(spacing: Theme.Spacing.sm) {
                Text("Recovering Your Account")
                    .font(Theme.Typography.title3)
                    .foregroundColor(Theme.Colors.textPrimary)

                Text("Verifying your recovery phrase and restoring your identity...")
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

// MARK: - Account Recovered View

private struct AccountRecoveredView: View {
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
                Text("Account Recovered!")
                    .font(Theme.Typography.title2)
                    .foregroundColor(Theme.Colors.textPrimary)

                Text("Welcome back to Whisper2")
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
                }
            }

            Spacer()

            // Continue button
            Button {
                // Navigate to main app
            } label: {
                Text("Continue")
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
        RecoverAccountView(viewModel: {
            let vm = AuthViewModel()
            vm.startRecoverAccount()
            return vm
        }())
    }
}
