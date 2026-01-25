import SwiftUI

/// List of groups
struct GroupsListView: View {
    @Bindable var viewModel: GroupViewModel
    @State private var showingCreateGroup = false
    @State private var selectedGroup: Group?

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.groups.isEmpty {
                    ChatListSkeletonView()
                } else if viewModel.filteredGroups.isEmpty {
                    emptyState
                } else {
                    groupsList
                }
            }
            .navigationTitle("Groups")
            .searchable(text: $viewModel.searchText, prompt: "Search groups")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingCreateGroup = true
                    } label: {
                        Image(systemName: "plus.circle")
                    }
                }
            }
            .refreshable {
                await viewModel.refreshGroups()
            }
            .sheet(isPresented: $showingCreateGroup) {
                CreateGroupView(viewModel: viewModel, isPresented: $showingCreateGroup)
            }
            .navigationDestination(item: $selectedGroup) { group in
                GroupChatView(viewModel: viewModel, group: group)
            }
        }
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

    private var groupsList: some View {
        List {
            ForEach(viewModel.filteredGroups) { group in
                GroupRow(group: group)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        selectedGroup = group
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            viewModel.leaveGroup(group)
                        } label: {
                            Label("Leave", systemImage: "rectangle.portrait.and.arrow.right")
                        }
                    }
            }
        }
        .listStyle(.plain)
    }

    private var emptyState: some View {
        Group {
            if viewModel.searchText.isEmpty {
                CenteredEmptyStateView(
                    emptyState: .noGroups {
                        showingCreateGroup = true
                    }
                )
            } else {
                CenteredEmptyStateView(
                    emptyState: .noSearchResults()
                )
            }
        }
    }
}

// MARK: - Group Row

private struct GroupRow: View {
    let group: Group

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
        HStack(spacing: Theme.Spacing.sm) {
            // Group avatar
            GroupAvatarView(
                memberNames: group.members.map { $0.displayName },
                size: Theme.AvatarSize.md
            )

            // Content
            VStack(alignment: .leading, spacing: Theme.Spacing.xxs) {
                HStack {
                    Text(group.title)
                        .font(Theme.Typography.headline)
                        .foregroundColor(Theme.Colors.textPrimary)
                        .lineLimit(1)

                    Spacer()

                    Text(timeString)
                        .font(Theme.Typography.caption1)
                        .foregroundColor(
                            group.unreadCount > 0
                                ? Theme.Colors.primary
                                : Theme.Colors.textTertiary
                        )
                }

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        if let lastMessage = group.lastMessage {
                            Text(lastMessage)
                                .font(Theme.Typography.subheadline)
                                .foregroundColor(Theme.Colors.textSecondary)
                                .lineLimit(1)
                        }

                        Text("\(group.memberCount) members \(onlineCount > 0 ? "(\(onlineCount) online)" : "")")
                            .font(Theme.Typography.caption2)
                            .foregroundColor(Theme.Colors.textTertiary)
                    }

                    Spacer()

                    if group.unreadCount > 0 {
                        Text("\(group.unreadCount)")
                            .font(Theme.Typography.caption2)
                            .foregroundColor(.white)
                            .padding(.horizontal, Theme.Spacing.xs)
                            .padding(.vertical, 2)
                            .background(Theme.Colors.primary)
                            .clipShape(Capsule())
                    }
                }
            }
        }
        .padding(.vertical, Theme.Spacing.xs)
    }
}

// MARK: - Preview

#Preview {
    GroupsListView(viewModel: GroupViewModel())
}
