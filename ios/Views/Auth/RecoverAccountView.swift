import SwiftUI

/// Recover account by entering seed phrase
struct RecoverAccountView: View {
    @ObservedObject var viewModel: AuthViewModel
    @State private var seedWords: [String] = Array(repeating: "", count: 12)
    @State private var focusedIndex: Int? = 0
    @FocusState private var focusedField: Int?
    @Environment(\.dismiss) private var dismiss
    
    private var isValidInput: Bool {
        seedWords.allSatisfy { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 24) {
                    // Header
                    VStack(spacing: 12) {
                        Image(systemName: "arrow.counterclockwise.circle.fill")
                            .font(.system(size: 50))
                            .foregroundColor(.green)
                        
                        Text("Recover Account")
                            .font(.title)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        
                        Text("Enter your 12-word seed phrase to recover your account.")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .padding(.top, 20)
                    
                    // Seed phrase input grid
                    LazyVGrid(columns: [
                        GridItem(.flexible()),
                        GridItem(.flexible()),
                        GridItem(.flexible())
                    ], spacing: 12) {
                        ForEach(0..<12, id: \.self) { index in
                            WordInputCell(
                                number: index + 1,
                                word: $seedWords[index],
                                isFocused: focusedField == index
                            )
                            .focused($focusedField, equals: index)
                            .onSubmit {
                                if index < 11 {
                                    focusedField = index + 1
                                } else {
                                    focusedField = nil
                                }
                            }
                        }
                    }
                    .padding()
                    .background(Color.gray.opacity(0.15))
                    .cornerRadius(16)
                    .padding(.horizontal)
                    
                    // Paste button
                    Button(action: pasteFromClipboard) {
                        HStack {
                            Image(systemName: "doc.on.clipboard")
                            Text("Paste from Clipboard")
                        }
                        .foregroundColor(.blue)
                    }
                    
                    // Recover button
                    Button(action: recoverAccount) {
                        if viewModel.isLoading {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Text("Recover Account")
                                .font(.headline)
                        }
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(
                        isValidInput ?
                        LinearGradient(
                            colors: [.green, .blue],
                            startPoint: .leading,
                            endPoint: .trailing
                        ) :
                        LinearGradient(
                            colors: [.gray, .gray],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(12)
                    .padding(.horizontal, 32)
                    .disabled(!isValidInput || viewModel.isLoading)
                    
                    Spacer(minLength: 40)
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(viewModel.isLoading)
        .alert("Error", isPresented: .constant(viewModel.error != nil)) {
            Button("OK") { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
        .onAppear {
            focusedField = 0
        }
    }
    
    private func pasteFromClipboard() {
        guard let text = UIPasteboard.general.string else { return }
        
        let words = text
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
        
        for (index, word) in words.prefix(12).enumerated() {
            seedWords[index] = word
        }
    }
    
    private func recoverAccount() {
        let mnemonic = seedWords
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .joined(separator: " ")
        
        viewModel.mnemonic = mnemonic
        
        Task {
            await viewModel.recoverAccount()
        }
    }
}

struct WordInputCell: View {
    let number: Int
    @Binding var word: String
    let isFocused: Bool
    
    var body: some View {
        HStack(spacing: 8) {
            Text("\(number)")
                .font(.caption)
                .foregroundColor(.gray)
                .frame(width: 20)
            
            TextField("", text: $word)
                .font(.system(.body, design: .monospaced))
                .foregroundColor(.white)
                .autocapitalization(.none)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(isFocused ? Color.blue.opacity(0.2) : Color.gray.opacity(0.2))
        .cornerRadius(8)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(isFocused ? Color.blue : Color.clear, lineWidth: 1)
        )
    }
}

#Preview {
    NavigationStack {
        RecoverAccountView(viewModel: AuthViewModel())
    }
}
