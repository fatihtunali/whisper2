import SwiftUI

/// Account creation flow - generate mnemonic, show to user, confirm
struct CreateAccountView: View {
    @Bindable var viewModel: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    // WhatsApp color palette
    private let primaryGreen = Color(red: 7/255, green: 94/255, blue: 84/255)
    private let secondaryGreen = Color(red: 18/255, green: 140/255, blue: 126/255)
    private let accentGreen = Color(red: 37/255, green: 211/255, blue: 102/255)
    private let lightGreen = Color(red: 220/255, green: 248/255, blue: 198/255)

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // Background gradient
                LinearGradient(
                    colors: [primaryGreen, secondaryGreen],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                // Content
                switch viewModel.currentStep {
                case .generateMnemonic:
                    mnemonicDisplayContent(geo: geo)
                case .confirmMnemonic:
                    mnemonicConfirmContent(geo: geo)
                case .registering:
                    registrationProgressContent()
                case .complete:
                    accountCreatedContent(geo: geo)
                default:
                    EmptyView()
                }
            }
        }
        .ignoresSafeArea()
        .navigationBarBackButtonHidden(viewModel.currentStep != .generateMnemonic)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar {
            if viewModel.currentStep == .confirmMnemonic {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        viewModel.currentStep = .generateMnemonic
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 16, weight: .semibold))
                            Text("Back")
                                .font(.system(size: 16, weight: .medium))
                        }
                        .foregroundColor(.white)
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

    // MARK: - Mnemonic Display Content

    @ViewBuilder
    private func mnemonicDisplayContent(geo: GeometryProxy) -> some View {
        let padding = max(16, geo.size.width * 0.05)

        ScrollView(showsIndicators: false) {
            VStack(spacing: 0) {
                // Top safe area
                Color.clear.frame(height: geo.safeAreaInsets.top + 16)

                // Header Card
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.white.opacity(0.15))
                            .frame(width: 72, height: 72)

                        Image(systemName: "key.fill")
                            .font(.system(size: 32, weight: .medium))
                            .foregroundColor(.white)
                    }

                    Text("Recovery Phrase")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(.white)

                    Text("Write down these 12 words in order.\nYou'll need them to recover your account.")
                        .font(.system(size: 14, weight: .regular))
                        .foregroundColor(.white.opacity(0.85))
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                        .padding(.horizontal, 8)
                }
                .padding(.top, 8)
                .padding(.bottom, 20)

                // Warning Banner
                HStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 16))
                        .foregroundColor(.orange)

                    Text("Never share this phrase with anyone")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white)

                    Spacer()
                }
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.black.opacity(0.25))
                )
                .padding(.horizontal, padding)
                .padding(.bottom, 16)

                // Mnemonic Words Card
                if viewModel.isLoading {
                    VStack {
                        ProgressView()
                            .tint(.white)
                            .scaleEffect(1.3)
                    }
                    .frame(height: 240)
                } else {
                    mnemonicWordsCard(geo: geo, padding: padding)
                }

                // Copy Button
                copyButton
                    .padding(.top, 16)

                Spacer(minLength: 24)

                // Continue Button
                continueButton(geo: geo, padding: padding) {
                    viewModel.proceedToConfirmation()
                }

                // Bottom safe area
                Color.clear.frame(height: max(geo.safeAreaInsets.bottom, 16) + 8)
            }
        }
    }

    @ViewBuilder
    private func mnemonicWordsCard(geo: GeometryProxy, padding: CGFloat) -> some View {
        let columns = [
            GridItem(.flexible(), spacing: 8),
            GridItem(.flexible(), spacing: 8)
        ]

        VStack(spacing: 0) {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(Array(viewModel.generatedMnemonic.enumerated()), id: \.offset) { index, word in
                    HStack(spacing: 8) {
                        Text("\(index + 1)")
                            .font(.system(size: 12, weight: .bold, design: .rounded))
                            .foregroundColor(.white.opacity(0.5))
                            .frame(width: 20, alignment: .trailing)

                        Text(word)
                            .font(.system(size: 15, weight: .semibold, design: .monospaced))
                            .foregroundColor(.white)

                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 14)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Color.white.opacity(0.12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
                            )
                    )
                }
            }
        }
        .padding(padding)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(Color.white.opacity(0.08))
        )
        .padding(.horizontal, padding)
    }

    @ViewBuilder
    private var copyButton: some View {
        Button {
            UIPasteboard.general.string = viewModel.generatedMnemonic.joined(separator: " ")
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()

            // Auto-clear clipboard after 60 seconds
            Task {
                try? await Task.sleep(for: .seconds(60))
                if UIPasteboard.general.string == viewModel.generatedMnemonic.joined(separator: " ") {
                    UIPasteboard.general.string = ""
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 13, weight: .medium))
                Text("Copy to Clipboard")
                    .font(.system(size: 13, weight: .semibold))
            }
            .foregroundColor(.white.opacity(0.9))
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(
                Capsule()
                    .fill(Color.white.opacity(0.15))
                    .overlay(
                        Capsule()
                            .stroke(Color.white.opacity(0.2), lineWidth: 1)
                    )
            )
        }
        .disabled(viewModel.generatedMnemonic.isEmpty)
    }

    // MARK: - Mnemonic Confirm Content

    @ViewBuilder
    private func mnemonicConfirmContent(geo: GeometryProxy) -> some View {
        let padding = max(16, geo.size.width * 0.05)

        ScrollView(showsIndicators: false) {
            VStack(spacing: 0) {
                // Top safe area
                Color.clear.frame(height: geo.safeAreaInsets.top + 16)

                // Header
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.white.opacity(0.15))
                            .frame(width: 72, height: 72)

                        Image(systemName: "checkmark.shield.fill")
                            .font(.system(size: 32, weight: .medium))
                            .foregroundColor(.white)
                    }

                    Text("Verify Your Phrase")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(.white)

                    Text("Select the correct words to confirm\nyou've saved your recovery phrase.")
                        .font(.system(size: 14, weight: .regular))
                        .foregroundColor(.white.opacity(0.85))
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                }
                .padding(.top, 8)
                .padding(.bottom, 24)

                // Word Selection Cards
                WordSelectionView(
                    viewModel: viewModel,
                    accentGreen: accentGreen,
                    padding: padding
                )

                Spacer(minLength: 24)

                // Create Account Button
                continueButton(geo: geo, padding: padding, title: "Create Account", enabled: viewModel.canProceed) {
                    viewModel.completeAccountCreation()
                }

                // Bottom safe area
                Color.clear.frame(height: max(geo.safeAreaInsets.bottom, 16) + 8)
            }
        }
    }

    // MARK: - Registration Progress Content

    @ViewBuilder
    private func registrationProgressContent() -> some View {
        VStack(spacing: 24) {
            Spacer()

            ZStack {
                Circle()
                    .fill(Color.white.opacity(0.1))
                    .frame(width: 100, height: 100)

                ProgressView()
                    .tint(.white)
                    .scaleEffect(1.5)
            }

            VStack(spacing: 8) {
                Text("Creating Account")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(.white)

                Text("Connecting securely...")
                    .font(.system(size: 15, weight: .regular))
                    .foregroundColor(.white.opacity(0.8))
            }

            Spacer()
        }
    }

    // MARK: - Account Created Content

    @ViewBuilder
    private func accountCreatedContent(geo: GeometryProxy) -> some View {
        let padding = max(16, geo.size.width * 0.05)

        VStack(spacing: 0) {
            Spacer()

            // Success Icon
            ZStack {
                Circle()
                    .fill(accentGreen.opacity(0.2))
                    .frame(width: 120, height: 120)

                Circle()
                    .fill(accentGreen)
                    .frame(width: 90, height: 90)

                Image(systemName: "checkmark")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundColor(.white)
            }
            .padding(.bottom, 24)

            Text("You're All Set!")
                .font(.system(size: 26, weight: .bold))
                .foregroundColor(.white)
                .padding(.bottom, 8)

            Text("Your secure identity is ready")
                .font(.system(size: 15, weight: .regular))
                .foregroundColor(.white.opacity(0.8))
                .padding(.bottom, 32)

            // WhisperID Card
            if let whisperId = viewModel.whisperId {
                whisperIdCard(whisperId: whisperId, padding: padding)
            }

            Spacer()

            // Get Started Button
            continueButton(geo: geo, padding: padding, title: "Get Started") {
                // Already authenticated
            }

            // Bottom safe area
            Color.clear.frame(height: max(geo.safeAreaInsets.bottom, 16) + 8)
        }
    }

    @ViewBuilder
    private func whisperIdCard(whisperId: String, padding: CGFloat) -> some View {
        VStack(spacing: 8) {
            Text("YOUR WHISPER ID")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.white.opacity(0.6))
                .tracking(1)

            Button {
                UIPasteboard.general.string = whisperId
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            } label: {
                HStack {
                    Text(whisperId)
                        .font(.system(size: 14, weight: .semibold, design: .monospaced))
                        .foregroundColor(.white)

                    Spacer()

                    Image(systemName: "doc.on.doc")
                        .font(.system(size: 14))
                        .foregroundColor(.white.opacity(0.6))
                }
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(Color.white.opacity(0.12))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color.white.opacity(0.15), lineWidth: 1)
                        )
                )
            }
        }
        .padding(.horizontal, padding)
    }

    // MARK: - Shared Components

    @ViewBuilder
    private func continueButton(geo: GeometryProxy, padding: CGFloat, title: String = "Continue", enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(primaryGreen)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(
                    Capsule()
                        .fill(enabled ? Color.white : Color.white.opacity(0.5))
                )
        }
        .disabled(!enabled)
        .padding(.horizontal, padding)
    }
}

// MARK: - Word Selection View

private struct WordSelectionView: View {
    @Bindable var viewModel: AuthViewModel
    let accentGreen: Color
    let padding: CGFloat

    @State private var selectedWords: [Int: String] = [:]
    @State private var wordOptions: [Int: [String]] = [:]

    var body: some View {
        VStack(spacing: 12) {
            ForEach(viewModel.confirmationIndices, id: \.self) { index in
                wordSelectionCard(for: index)
            }
        }
        .padding(.horizontal, padding)
        .onAppear {
            generateWordOptions()
        }
    }

    @ViewBuilder
    private func wordSelectionCard(for index: Int) -> some View {
        let correctWord = viewModel.generatedMnemonic[index]
        let options = wordOptions[index] ?? []
        let selected = selectedWords[index]

        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Word #\(index + 1)")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white.opacity(0.7))

                Spacer()

                if let selected = selected {
                    if selected == correctWord {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 16))
                            .foregroundColor(accentGreen)
                    } else {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 16))
                            .foregroundColor(.red)
                    }
                }
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(options, id: \.self) { word in
                    wordOptionButton(word: word, correctWord: correctWord, selected: selected, index: index)
                }
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.black.opacity(0.2))
        )
    }

    @ViewBuilder
    private func wordOptionButton(word: String, correctWord: String, selected: String?, index: Int) -> some View {
        let isSelected = selected == word
        let isCorrect = word == correctWord

        Button {
            selectedWords[index] = word
            viewModel.selectConfirmationWord(at: index, word: word)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            Text(word)
                .font(.system(size: 14, weight: .semibold, design: .monospaced))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(backgroundColor(isSelected: isSelected, isCorrect: isCorrect))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(borderColor(isSelected: isSelected, isCorrect: isCorrect), lineWidth: 2)
                )
        }
    }

    private func backgroundColor(isSelected: Bool, isCorrect: Bool) -> Color {
        if isSelected {
            return isCorrect ? accentGreen : Color.red.opacity(0.7)
        }
        return Color.white.opacity(0.1)
    }

    private func borderColor(isSelected: Bool, isCorrect: Bool) -> Color {
        if isSelected {
            return isCorrect ? accentGreen : Color.red
        }
        return Color.clear
    }

    private func generateWordOptions() {
        for index in viewModel.confirmationIndices {
            let correctWord = viewModel.generatedMnemonic[index]
            let otherWords = viewModel.generatedMnemonic.filter { $0 != correctWord }.shuffled().prefix(3)
            var options = Array(otherWords) + [correctWord]
            options.shuffle()
            wordOptions[index] = options
        }
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
