import SwiftUI

/// Main tab bar view
struct MainTabView: View {
    @StateObject private var chatsViewModel = ChatsViewModel()
    @ObservedObject private var callService = CallService.shared
    @State private var selectedTab = 0
    @State private var showCallView = false

    var body: some View {
        TabView(selection: $selectedTab) {
            ChatsListView(viewModel: chatsViewModel)
                .tabItem {
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                    Text("Chats")
                }
                .tag(0)

            GroupListView()
                .tabItem {
                    Image(systemName: "person.3.fill")
                    Text("Groups")
                }
                .tag(1)

            ContactsListView()
                .tabItem {
                    Image(systemName: "person.2.fill")
                    Text("Contacts")
                }
                .tag(2)

            SettingsView()
                .tabItem {
                    Image(systemName: "gearshape.fill")
                    Text("Settings")
                }
                .tag(3)
        }
        .tint(.blue)
        .preferredColorScheme(.dark)
        .onChange(of: callService.callState) { _, newState in
            switch newState {
            case .initiating:
                // Outgoing call started - show CallView
                showCallView = true
            case .ringing:
                // For outgoing calls: show CallView (waiting for answer)
                // For incoming calls: CallKit handles the UI, don't show CallView yet
                if callService.activeCall?.isOutgoing == true {
                    showCallView = true
                }
            case .connecting, .connected, .reconnecting:
                // Call is being connected or active - show CallView
                showCallView = true
            case .ended:
                // Keep showing for a moment to display end reason
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    showCallView = false
                }
            case .idle:
                showCallView = false
            }
        }
        .fullScreenCover(isPresented: $showCallView) {
            CallView()
        }
        // NOTE: IncomingCallView removed - CallKit handles incoming call UI natively on iOS
    }
}

#Preview {
    MainTabView()
}
