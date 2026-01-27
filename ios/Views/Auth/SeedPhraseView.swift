import SwiftUI

/// Display seed phrase for backup
struct SeedPhraseView: View {
    @ObservedObject var viewModel: AuthViewModel
    let isNewAccount: Bool
    
    @State private var hasConfirmedBackup = false
    @State private var showCopiedAlert = false
    @Environment(\.dismiss) private var dismiss
    
    private var words: [String] {
        viewModel.mnemonic?.components(separatedBy: " ") ?? []
    }
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 24) {
                    // Warning header
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.shield.fill")
                            .font(.system(size: 50))
                            .foregroundColor(.orange)
                        
                        Text("Your Seed Phrase")
                            .font(.title)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        
                        Text("Write down these 12 words in order. This is the ONLY way to recover your account. Never share it with anyone.")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .padding(.top, 20)
                    
                    // Seed phrase grid
                    LazyVGrid(columns: [
                        GridItem(.flexible()),
                        GridItem(.flexible()),
                        GridItem(.flexible())
                    ], spacing: 12) {
                        ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                            WordCell(number: index + 1, word: word)
                        }
                    }
                    .padding()
                    .background(Color.gray.opacity(0.15))
                    .cornerRadius(16)
                    .padding(.horizontal)
                    
                    // Copy button
                    Button(action: copyToClipboard) {
                        HStack {
                            Image(systemName: "doc.on.doc")
                            Text("Copy to Clipboard")
                        }
                        .foregroundColor(.blue)
                    }
                    
                    // Warning box
                    HStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                        
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Warning")
                                .font(.headline)
                                .foregroundColor(.red)
                            Text("If you lose this seed phrase, you will permanently lose access to your account and all messages.")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                    .padding()
                    .background(Color.red.opacity(0.1))
                    .cornerRadius(12)
                    .padding(.horizontal)
                    
                    // Confirmation checkbox
                    if isNewAccount {
                        Toggle(isOn: $hasConfirmedBackup) {
                            Text("I have securely backed up my seed phrase")
                                .font(.subheadline)
                                .foregroundColor(.white)
                        }
                        .toggleStyle(CheckboxToggleStyle())
                        .padding(.horizontal)
                    }
                    
                    // Continue button
                    Button(action: continueAction) {
                        if viewModel.isLoading {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Text(isNewAccount ? "Continue" : "Done")
                                .font(.headline)
                        }
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(
                        RoundedRectangle(cornerRadius: 0)
                            .fill(
                                (isNewAccount && !hasConfirmedBackup) ?
                                AnyShapeStyle(Color.gray) :
                                AnyShapeStyle(LinearGradient(
                                    colors: [.blue, .purple],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                ))
                            )
                    )
                    .cornerRadius(12)
                    .padding(.horizontal, 32)
                    .disabled(isNewAccount && !hasConfirmedBackup)
                    .disabled(viewModel.isLoading)
                    
                    Spacer(minLength: 40)
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(viewModel.isLoading)
        .alert("Copied", isPresented: $showCopiedAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Seed phrase copied to clipboard. Make sure to save it securely and clear your clipboard.")
        }
        .alert("Error", isPresented: .constant(viewModel.error != nil)) {
            Button("OK") { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
    }
    
    private func copyToClipboard() {
        UIPasteboard.general.string = viewModel.mnemonic
        showCopiedAlert = true
    }
    
    private func continueAction() {
        if isNewAccount {
            Task {
                await viewModel.registerAccount()
            }
        } else {
            dismiss()
        }
    }
}

struct WordCell: View {
    let number: Int
    let word: String
    
    var body: some View {
        HStack(spacing: 8) {
            Text("\(number)")
                .font(.caption)
                .foregroundColor(.gray)
                .frame(width: 20)
            
            Text(word)
                .font(.system(.body, design: .monospaced))
                .foregroundColor(.white)
            
            Spacer()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.gray.opacity(0.2))
        .cornerRadius(8)
    }
}

struct CheckboxToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack {
            Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                .foregroundColor(configuration.isOn ? .blue : .gray)
                .onTapGesture {
                    configuration.isOn.toggle()
                }
            
            configuration.label
        }
    }
}

#Preview {
    NavigationStack {
        SeedPhraseView(viewModel: {
            let vm = AuthViewModel()
            vm.mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            return vm
        }(), isNewAccount: true)
    }
}
