import SwiftUI

/// List of groups matching original Whisper UI
struct GroupsListView: View {
    @State private var viewModel = GroupViewModel()
    @EnvironmentObject var themeManager: ThemeManager
    @State private var showingCreateGroup = false
    @State private var selectedGroup: GroupUI?

    var body: some View {
        ZStack {
            Color.whisperBackground.ignoresSafeArea()

            VStack(spacing: 0) {
                // Header (matching Expo)
                headerView

                // Groups List
                if viewModel.isLoading && viewModel.groups.isEmpty {
                    loadingView
                } else if viewModel.filteredGroups.isEmpty {
                    emptyStateView
                } else {
                    ScrollView {
                        LazyVStack(spacing: WhisperSpacing.sm) {
                            ForEach(viewModel.filteredGroups) { group in
                                NavigationLink(value: group) {
                                    GroupRowView(group: group)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, WhisperSpacing.md)
                        .padding(.top, WhisperSpacing.sm)
                    }
                    .refreshable {
                        await viewModel.refreshGroups()
                    }
                }
            }
        }
        .navigationDestination(for: GroupUI.self) { group in
            GroupChatView(viewModel: viewModel, group: group)
        }
        .sheet(isPresented: $showingCreateGroup) {
            CreateGroupView(viewModel: viewModel, isPresented: $showingCreateGroup)
        }
        .id(themeManager.themeMode)
        .onAppear {
            if viewModel.groups.isEmpty {
                viewModel.loadGroups()
            }
        }
        .alert("Error", isPresented: Binding(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.clearError() } }
        )) {
            Button("OK") { viewModel.clearError() }
        } message: {
            Text(viewModel.error ?? "")
        }
    }

    // MARK: - Header

    private var headerView: some View {
        HStack {
            Text("Groups")
                .font(.whisper(size: WhisperFontSize.xxl, weight: .bold))
                .foregroundColor(.whisperText)

            Spacer()

            // Create Group Button
            Button(action: { showingCreateGroup = true }) {
                Image(systemName: "plus")
                    .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                    .foregroundColor(.whisperText)
                    .frame(width: 36, height: 36)
                    .background(Color.whisperPrimary)
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal, WhisperSpacing.lg)
        .padding(.vertical, WhisperSpacing.md)
        .background(Color.whisperBackground)
        .overlay(
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1),
            alignment: .bottom
        )
    }

    // MARK: - Loading View

    private var loadingView: some View {
        VStack(spacing: WhisperSpacing.md) {
            Spacer()
            ProgressView()
                .scaleEffect(1.5)
                .tint(.whisperPrimary)
            Text("Loading groups...")
                .font(.whisper(size: WhisperFontSize.md))
                .foregroundColor(.whisperTextSecondary)
            Spacer()
        }
    }

    // MARK: - Empty State

    private var emptyStateView: some View {
        VStack(spacing: WhisperSpacing.lg) {
            Spacer()

            Image(systemName: "person.3.fill")
                .font(.system(size: 64))
                .foregroundColor(.whisperTextMuted)

            Text("No groups yet")
                .font(.whisper(size: WhisperFontSize.xl, weight: .semibold))
                .foregroundColor(.whisperText)

            Text("Create a group to start messaging with multiple people")
                .font(.whisper(size: WhisperFontSize.md))
                .foregroundColor(.whisperTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, WhisperSpacing.xl)

            Button(action: { showingCreateGroup = true }) {
                HStack(spacing: WhisperSpacing.sm) {
                    Image(systemName: "plus")
                    Text("Create Group")
                }
                .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                .foregroundColor(.whisperText)
                .padding(.horizontal, WhisperSpacing.lg)
                .padding(.vertical, WhisperSpacing.sm)
                .background(Color.whisperPrimary)
                .cornerRadius(WhisperRadius.md)
            }

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Group Row (Matching Expo)

struct GroupRowView: View {
    let group: GroupUI
    @EnvironmentObject var themeManager: ThemeManager

    private var timeString: String {
        guard let timestamp = group.lastMessageTimestamp else { return "" }

        let calendar = Calendar.current
        if calendar.isDateInToday(timestamp) {
            return timestamp.formatted(date: .omitted, time: .shortened)
        } else if calendar.isDateInYesterday(timestamp) {
            return "Yesterday"
        } else {
            return timestamp.formatted(date: .abbreviated, time: .omitted)
        }
    }

    private var onlineCount: Int {
        group.members.filter { $0.isOnline }.count
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top border line
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)

            // Main content
            HStack(spacing: WhisperSpacing.md) {
                // Group avatar
                groupAvatarView

                // Content
                VStack(alignment: .leading, spacing: WhisperSpacing.xs) {
                    // Top row: Name + Time
                    HStack(alignment: .center) {
                        Text(group.title)
                            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                            .foregroundColor(.whisperText)
                            .lineLimit(1)

                        Spacer()

                        Text(timeString)
                            .font(.whisper(size: WhisperFontSize.xs))
                            .foregroundColor(group.unreadCount > 0 ? .whisperPrimary : .whisperTextMuted)
                    }

                    // Bottom row: Message preview + Unread count
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 2) {
                            if let lastMessage = group.lastMessage {
                                Text(lastMessage)
                                    .font(.whisper(size: WhisperFontSize.sm))
                                    .foregroundColor(.whisperTextSecondary)
                                    .lineLimit(1)
                            }

                            Text("\(group.memberCount) members")
                                .font(.whisper(size: WhisperFontSize.xs))
                                .foregroundColor(.whisperTextMuted)
                        }

                        Spacer()

                        if group.unreadCount > 0 {
                            Text("\(group.unreadCount)")
                                .font(.whisper(size: WhisperFontSize.xs, weight: .bold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .frame(minWidth: 22, minHeight: 22)
                                .background(Color.whisperPrimary)
                                .clipShape(Capsule())
                        }
                    }
                }
            }
            .padding(.horizontal, WhisperSpacing.md)
            .padding(.vertical, WhisperSpacing.md)

            // Bottom border line
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)
        }
        .background(Color.whisperSurface)
        .cornerRadius(WhisperRadius.md)
        .shadow(color: Color.black.opacity(0.05), radius: 2, x: 0, y: 1)
        .contentShape(Rectangle())
    }

    private var groupAvatarView: some View {
        ZStack {
            Circle()
                .fill(Color.whisperSurfaceLight)
                .frame(width: 54, height: 54)

            Image(systemName: "person.3.fill")
                .font(.system(size: 24))
                .foregroundColor(.whisperTextMuted)
        }
    }
}

// MARK: - Preview

#Preview {
    GroupsListView()
        .environmentObject(ThemeManager.shared)
}
