import SwiftUI

/// Root content view - routes between auth and main app
struct ContentView: View {
    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        SwiftUI.Group {
            if coordinator.isLoading {
                // Show splash/loading while checking auth state
                ZStack {
                    Color.black.ignoresSafeArea()
                    VStack(spacing: 16) {
                        Image(systemName: "bubble.left.and.bubble.right.fill")
                            .font(.system(size: 60))
                            .foregroundStyle(
                                LinearGradient(
                                    colors: [.blue, .purple],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                        Text("Whisper2")
                            .font(.title)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                    }
                }
            } else if coordinator.isAuthenticated {
                MainTabView()
            } else {
                WelcomeView()
            }
        }
        .animation(.easeInOut, value: coordinator.isAuthenticated)
        .animation(.easeInOut, value: coordinator.isLoading)
    }
}

#Preview {
    ContentView()
        .environmentObject(AppCoordinator())
}
