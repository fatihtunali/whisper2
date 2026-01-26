import SwiftUI

/// Account recovery - enter mnemonic to recover
struct RecoverAccountView: View {
    @Bindable var viewModel: AuthViewModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focusedField: Int?

    private let primaryGreen = Color(red: 7/255, green: 94/255, blue: 84/255)
    private let secondaryGreen = Color(red: 18/255, green: 140/255, blue: 126/255)
    private let accentGreen = Color(red: 37/255, green: 211/255, blue: 102/255)

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                // WhatsApp-style gradient background
                LinearGradient(
                    colors: [primaryGreen, secondaryGreen],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea(.all)

                VStack(spacing: 0) {
                    switch viewModel.currentStep {
                    case .enterMnemonic:
                        MnemonicEntryView(
                            viewModel: viewModel,
                            primaryGreen: primaryGreen,
                            accentGreen: accentGreen,
                            focusedField: $focusedField,
                            safeAreaBottom: geometry.safeAreaInsets.bottom
                        )
                    case .registering:
                        RecoveryProgressView()
                    case .complete:
                        AccountRecoveredView(
                            viewModel: viewModel,
                            primaryGreen: primaryGreen,
                            accentGreen: accentGreen,
                            safeAreaBottom: geometry.safeAreaInsets.bottom
                        )
                    default:
                        EmptyView()
                    }
                }
            }
        }
        .ignoresSafeArea(.all)
        .navigationBarBackButtonHidden(false)
        .toolbarBackground(.hidden, for: .navigationBar)
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

// MARK: - Mnemonic Entry View

private struct MnemonicEntryView: View {
    @Bindable var viewModel: AuthViewModel
    let primaryGreen: Color
    let accentGreen: Color
    var focusedField: FocusState<Int?>.Binding
    let safeAreaBottom: CGFloat

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 20) {
                // Top spacing
                Spacer()
                    .frame(height: 60)

                // Header
                VStack(spacing: 10) {
                    Image(systemName: "arrow.counterclockwise.circle.fill")
                        .font(.system(size: 50, weight: .medium))
                        .foregroundColor(.white)

                    Text("Recover Your Account")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)

                    Text("Enter your 12-word recovery phrase\nto restore your account.")
                        .font(.system(size: 14, weight: .regular))
                        .foregroundColor(.white.opacity(0.85))
                        .multilineTextAlignment(.center)
                        .lineSpacing(2)
                }

                // Paste button
                Button {
                    pasteFromClipboard()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.on.clipboard")
                        Text("Paste from Clipboard")
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(Color.white.opacity(0.2))
                    .clipShape(Capsule())
                }

                // Word entry grid
                MnemonicEntryGrid(
                    words: $viewModel.enteredMnemonic,
                    focusedField: focusedField,
                    accentGreen: accentGreen
                )

                // Clear button
                if viewModel.enteredMnemonic.contains(where: { !$0.isEmpty }) {
                    Button {
                        viewModel.enteredMnemonic = Array(repeating: "", count: 12)
                        focusedField.wrappedValue = 0
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "xmark.circle")
                            Text("Clear All")
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.red.opacity(0.9))
                    }
                }

                Spacer()
                    .frame(height: 20)

                // Recover button
                Button {
                    focusedField.wrappedValue = nil
                    viewModel.recoverAccount()
                } label: {
                    Text("Recover Account")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(primaryGreen)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(viewModel.canProceed ? Color.white : Color.white.opacity(0.5))
                        .clipShape(Capsule())
                }
                .disabled(!viewModel.canProceed)

                Spacer()
                    .frame(height: safeAreaBottom + 20)
            }
            .padding(.horizontal, 24)
        }
        .onTapGesture {
            focusedField.wrappedValue = nil
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

        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
}

// MARK: - Mnemonic Entry Grid

private struct MnemonicEntryGrid: View {
    @Binding var words: [String]
    var focusedField: FocusState<Int?>.Binding
    let accentGreen: Color

    private let columns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10)
    ]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 8) {
            ForEach(0..<12, id: \.self) { index in
                MnemonicWordField(
                    wordNumber: index + 1,
                    word: $words[index],
                    isFocused: focusedField.wrappedValue == index,
                    accentGreen: accentGreen,
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
    let accentGreen: Color
    let onSubmit: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text("\(wordNumber)")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(.white.opacity(0.6))
                .frame(width: 18)

            TextField("", text: $word)
                .font(.system(size: 15, weight: .semibold, design: .monospaced))
                .foregroundColor(.white)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(wordNumber < 12 ? .next : .done)
                .onSubmit(onSubmit)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 12)
        .background(Color.white.opacity(0.15))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(isFocused ? accentGreen : Color.clear, lineWidth: 2)
        )
    }
}

// MARK: - Recovery Progress View

private struct RecoveryProgressView: View {
    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            ProgressView()
                .scaleEffect(1.5)
                .tint(.white)

            VStack(spacing: 8) {
                Text("Recovering Your Account")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(.white)

                Text("Verifying your recovery phrase...")
                    .font(.system(size: 15, weight: .regular))
                    .foregroundColor(.white.opacity(0.85))
            }

            Spacer()
        }
        .padding(24)
    }
}

// MARK: - Account Recovered View

private struct AccountRecoveredView: View {
    @Bindable var viewModel: AuthViewModel
    let primaryGreen: Color
    let accentGreen: Color
    let safeAreaBottom: CGFloat
    @State private var copied = false

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 80))
                .foregroundColor(accentGreen)

            VStack(spacing: 8) {
                Text("Account Recovered!")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(.white)

                Text("Welcome back to Whisper2")
                    .font(.system(size: 15, weight: .regular))
                    .foregroundColor(.white.opacity(0.85))
            }

            if let whisperId = viewModel.whisperId {
                VStack(spacing: 10) {
                    Text("Your WhisperID")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.white.opacity(0.7))

                    Button {
                        UIPasteboard.general.string = whisperId
                        copied = true
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    } label: {
                        HStack {
                            Text(whisperId)
                                .font(.system(size: 14, weight: .semibold, design: .monospaced))
                                .foregroundColor(.white)

                            Spacer()

                            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                                .font(.system(size: 14))
                                .foregroundColor(.white.opacity(0.7))
                        }
                        .padding(14)
                        .background(Color.white.opacity(0.15))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }

                    if copied {
                        Text("Copied!")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(accentGreen)
                    }
                }
                .padding(.horizontal, 24)
            }

            Spacer()

            Button {
                // Ensure state is authenticated to trigger transition
                viewModel.state = .authenticated
            } label: {
                Text("Continue")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(primaryGreen)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.white)
                    .clipShape(Capsule())
            }
            .padding(.horizontal, 24)

            Spacer()
                .frame(height: safeAreaBottom + 20)
        }
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
