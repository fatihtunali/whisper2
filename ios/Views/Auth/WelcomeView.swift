import SwiftUI

/// Welcome/Landing screen
struct WelcomeView: View {
    @StateObject private var viewModel = AuthViewModel()
    @State private var showCreateAccount = false
    @State private var showRecoverAccount = false
    
    var body: some View {
        NavigationStack {
            ZStack {
                // Background
                LinearGradient(
                    colors: [Color.black, Color(red: 0.1, green: 0.1, blue: 0.2)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
                
                VStack(spacing: 40) {
                    Spacer()
                    
                    // Logo
                    VStack(spacing: 16) {
                        Image(systemName: "bubble.left.and.bubble.right.fill")
                            .font(.system(size: 80))
                            .foregroundStyle(
                                LinearGradient(
                                    colors: [.blue, .purple],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                        
                        Text("Whisper2")
                            .font(.largeTitle)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        
                        Text("End-to-end encrypted messaging")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    }
                    
                    Spacer()
                    
                    // Buttons
                    VStack(spacing: 16) {
                        Button(action: { showCreateAccount = true }) {
                            Text("Create Account")
                                .font(.headline)
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
                        }
                        
                        Button(action: { showRecoverAccount = true }) {
                            Text("Recover Account")
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.gray.opacity(0.3))
                                .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal, 32)
                    .padding(.bottom, 60)
                }
            }
            .navigationDestination(isPresented: $showCreateAccount) {
                CreateAccountView(viewModel: viewModel)
            }
            .navigationDestination(isPresented: $showRecoverAccount) {
                RecoverAccountView(viewModel: viewModel)
            }
        }
    }
}

#Preview {
    WelcomeView()
}
