import SwiftUI

/// Root content view - routes between auth and main app
struct ContentView: View {
    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        SwiftUI.Group {
            if coordinator.isAuthenticated {
                MainTabView()
            } else {
                WelcomeView()
            }
        }
        .animation(.easeInOut, value: coordinator.isAuthenticated)
    }
}

#Preview {
    ContentView()
        .environmentObject(AppCoordinator())
}
