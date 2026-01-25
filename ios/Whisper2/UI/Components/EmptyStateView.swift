import SwiftUI

/// Empty state placeholder view
struct EmptyStateView: View {
    let icon: String
    let title: String
    var message: String? = nil
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: Theme.Spacing.lg) {
            Image(systemName: icon)
                .font(.system(size: 64))
                .foregroundColor(Theme.Colors.textTertiary)

            VStack(spacing: Theme.Spacing.xs) {
                Text(title)
                    .font(Theme.Typography.title3)
                    .foregroundColor(Theme.Colors.textPrimary)
                    .multilineTextAlignment(.center)

                if let message = message {
                    Text(message)
                        .font(Theme.Typography.body)
                        .foregroundColor(Theme.Colors.textSecondary)
                        .multilineTextAlignment(.center)
                }
            }

            if let actionTitle = actionTitle, let action = action {
                Button(actionTitle, action: action)
                    .buttonStyle(.primary)
                    .frame(maxWidth: 200)
            }
        }
        .padding(Theme.Spacing.xl)
    }
}

// MARK: - Predefined Empty States

extension EmptyStateView {
    static func noChats(action: @escaping () -> Void) -> EmptyStateView {
        EmptyStateView(
            icon: "bubble.left.and.bubble.right",
            title: "No Conversations",
            message: "Start a conversation with your contacts",
            actionTitle: "Start Chat",
            action: action
        )
    }

    static func noContacts(action: @escaping () -> Void) -> EmptyStateView {
        EmptyStateView(
            icon: "person.2",
            title: "No Contacts",
            message: "Add contacts to start messaging",
            actionTitle: "Add Contact",
            action: action
        )
    }

    static func noGroups(action: @escaping () -> Void) -> EmptyStateView {
        EmptyStateView(
            icon: "person.3",
            title: "No Groups",
            message: "Create a group to chat with multiple people",
            actionTitle: "Create Group",
            action: action
        )
    }

    static func noMessages() -> EmptyStateView {
        EmptyStateView(
            icon: "text.bubble",
            title: "No Messages Yet",
            message: "Send a message to start the conversation"
        )
    }

    static func noSearchResults() -> EmptyStateView {
        EmptyStateView(
            icon: "magnifyingglass",
            title: "No Results",
            message: "Try a different search term"
        )
    }

    static func error(message: String, retryAction: @escaping () -> Void) -> EmptyStateView {
        EmptyStateView(
            icon: "exclamationmark.triangle",
            title: "Something Went Wrong",
            message: message,
            actionTitle: "Try Again",
            action: retryAction
        )
    }

    static func networkError(retryAction: @escaping () -> Void) -> EmptyStateView {
        EmptyStateView(
            icon: "wifi.slash",
            title: "No Connection",
            message: "Check your internet connection and try again",
            actionTitle: "Retry",
            action: retryAction
        )
    }
}

// MARK: - Centered Empty State

struct CenteredEmptyStateView: View {
    let emptyState: EmptyStateView

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                emptyState
                    .frame(width: geometry.size.width)
                    .frame(minHeight: geometry.size.height)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    ScrollView {
        VStack(spacing: Theme.Spacing.xxl) {
            EmptyStateView.noChats {}

            Divider()

            EmptyStateView.noContacts {}

            Divider()

            EmptyStateView.noSearchResults()

            Divider()

            EmptyStateView.error(message: "Failed to load data") {}

            Divider()

            EmptyStateView.networkError {}
        }
    }
}
