import SwiftUI

/// Main tab bar view
struct MainTabView: View {
    @StateObject private var chatsViewModel = ChatsViewModel()
    @StateObject private var outgoingCallState = OutgoingCallState.shared
    @State private var selectedTab = 0

    var body: some View {
        ZStack {
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

            // Outgoing call UI (CallKit only shows UI for incoming calls)
            if outgoingCallState.isShowingOutgoingCall {
                OutgoingCallView(
                    peerName: outgoingCallState.peerName,
                    peerId: outgoingCallState.peerId,
                    isVideo: outgoingCallState.isVideo,
                    onEndCall: {
                        Task {
                            try? await CallService.shared.endCall()
                        }
                    }
                )
                .transition(.opacity)
            }
        }
        // NOTE: Incoming calls are handled by CallKit natively
        // Outgoing calls need custom UI since CallKit doesn't provide one
    }
}

#Preview {
    MainTabView()
}
