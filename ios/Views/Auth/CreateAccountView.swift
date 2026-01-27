import SwiftUI

/// Create account screen - generates mnemonic
struct CreateAccountView: View {
    @ObservedObject var viewModel: AuthViewModel
    @State private var showSeedPhrase = false
    @Environment(\.dismiss) private var dismiss
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            VStack(spacing: 32) {
                // Header
                VStack(spacing: 12) {
                    Image(systemName: "key.fill")
                        .font(.system(size: 50))
                        .foregroundColor(.blue)
                    
                    Text("Create Your Account")
                        .font(.title)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                    
                    Text("We will generate a secure seed phrase for you. This is the only way to recover your account.")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
                .padding(.top, 40)
                
                Spacer()
                
                // Info cards
                VStack(spacing: 16) {
                    InfoCard(
                        icon: "lock.shield.fill",
                        title: "End-to-End Encrypted",
                        description: "Your messages are encrypted on your device"
                    )
                    
                    InfoCard(
                        icon: "key.horizontal.fill",
                        title: "You Own Your Keys",
                        description: "Only you can access your account"
                    )
                    
                    InfoCard(
                        icon: "exclamationmark.triangle.fill",
                        title: "Backup Required",
                        description: "Write down your seed phrase securely"
                    )
                }
                .padding(.horizontal)
                
                Spacer()
                
                // Generate button
                Button(action: generateAccount) {
                    if viewModel.isLoading {
                        ProgressView()
                            .tint(.white)
                    } else {
                        Text("Generate Seed Phrase")
                            .font(.headline)
                    }
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding()
                .background(
                    LinearGradient(
                        colors: [.blue, .purple],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .cornerRadius(12)
                .padding(.horizontal, 32)
                .padding(.bottom, 32)
                .disabled(viewModel.isLoading)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(viewModel.isLoading)
        .alert("Error", isPresented: .constant(viewModel.error != nil)) {
            Button("OK") { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
        .navigationDestination(isPresented: $showSeedPhrase) {
            SeedPhraseView(viewModel: viewModel, isNewAccount: true)
        }
    }
    
    private func generateAccount() {
        Task {
            await viewModel.generateMnemonic()
            if viewModel.mnemonic != nil {
                showSeedPhrase = true
            }
        }
    }
}

struct InfoCard: View {
    let icon: String
    let title: String
    let description: String
    
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(.blue)
                .frame(width: 40)
            
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(.white)
                
                Text(description)
                    .font(.caption)
                    .foregroundColor(.gray)
            }
            
            Spacer()
        }
        .padding()
        .background(Color.gray.opacity(0.2))
        .cornerRadius(12)
    }
}

#Preview {
    NavigationStack {
        CreateAccountView(viewModel: AuthViewModel())
    }
}
