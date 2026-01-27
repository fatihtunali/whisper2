import SwiftUI

/// Main tab bar view
struct MainTabView: View {
    @StateObject private var chatsViewModel = ChatsViewModel()
    @StateObject private var outgoingCallState = OutgoingCallState.shared
    @StateObject private var videoCallState = ActiveVideoCallState.shared
    @StateObject private var audioCallState = ActiveAudioCallState.shared
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
                            try? await CallService.shared.endCall(reason: .cancelled)
                        }
                    }
                )
                .transition(.opacity.combined(with: .scale(scale: 1.05)))
                .zIndex(10) // Ensure it's above tabs
            }

            // Active video call UI - shown when video call connects
            if videoCallState.isShowingVideoCall {
                VideoCallView(
                    state: videoCallState,
                    onEndCall: {
                        CallService.shared.handleVideoCallEnd()
                    },
                    onToggleMute: {
                        CallService.shared.handleVideoCallMute()
                    },
                    onToggleCamera: {
                        CallService.shared.handleVideoCallCamera()
                    },
                    onSwitchCamera: {
                        CallService.shared.handleVideoCallSwitchCamera()
                    },
                    onToggleSpeaker: {
                        CallService.shared.handleVideoCallSpeaker()
                    }
                )
                .transition(.opacity)
                .zIndex(11) // Above outgoing call UI
            }

            // Active audio call UI - shown when audio call connects
            if audioCallState.isShowingAudioCall {
                AudioCallView(
                    state: audioCallState,
                    onEndCall: {
                        CallService.shared.handleAudioCallEnd()
                    },
                    onToggleMute: {
                        CallService.shared.handleAudioCallMute()
                    },
                    onToggleSpeaker: {
                        CallService.shared.handleAudioCallSpeaker()
                    }
                )
                .transition(.opacity)
                .zIndex(12) // Above video call UI
            }
        }
        // NOTE: Incoming calls are handled by CallKit natively
        // Outgoing calls need custom UI since CallKit doesn't provide one
        // Video calls show custom UI when connected
    }
}

#Preview {
    MainTabView()
}
