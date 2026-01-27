import SwiftUI

/// Main tab bar view
struct MainTabView: View {
    @StateObject private var chatsViewModel = ChatsViewModel()
    @StateObject private var incomingCallViewModel = IncomingCallViewModel()
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
            case .initiating, .connecting, .connected, .reconnecting:
                showCallView = true
                incomingCallViewModel.showIncomingCall = false
            case .ringing:
                if callService.activeCall?.isOutgoing == true {
                    showCallView = true
                }
                // Incoming calls are handled by IncomingCallViewModel
            case .ended:
                // Keep showing for a moment to display end reason
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    showCallView = false
                    incomingCallViewModel.showIncomingCall = false
                }
            case .idle:
                showCallView = false
                incomingCallViewModel.showIncomingCall = false
            }
        }
        .fullScreenCover(isPresented: $showCallView) {
            CallView()
        }
        .fullScreenCover(isPresented: $incomingCallViewModel.showIncomingCall) {
            if let payload = incomingCallViewModel.incomingCall {
                IncomingCallView(
                    callPayload: payload,
                    onAnswer: {
                        incomingCallViewModel.answerCall()
                        showCallView = true
                    },
                    onDecline: {
                        incomingCallViewModel.declineCall()
                    }
                )
            }
        }
    }
}

#Preview {
    MainTabView()
}
