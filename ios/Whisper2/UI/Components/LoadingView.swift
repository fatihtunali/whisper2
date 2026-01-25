import SwiftUI

/// Loading spinner view
struct LoadingView: View {
    var message: String? = nil
    var size: LoadingSize = .medium

    enum LoadingSize {
        case small
        case medium
        case large

        var scale: CGFloat {
            switch self {
            case .small: return 1.0
            case .medium: return 1.5
            case .large: return 2.0
            }
        }

        var spacing: CGFloat {
            switch self {
            case .small: return Theme.Spacing.xs
            case .medium: return Theme.Spacing.sm
            case .large: return Theme.Spacing.md
            }
        }
    }

    var body: some View {
        VStack(spacing: size.spacing) {
            ProgressView()
                .scaleEffect(size.scale)

            if let message = message {
                Text(message)
                    .font(size == .small ? Theme.Typography.caption1 : Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textSecondary)
                    .multilineTextAlignment(.center)
            }
        }
    }
}

// MARK: - Full Screen Loading

struct FullScreenLoadingView: View {
    var message: String? = nil
    var showBackground: Bool = true

    var body: some View {
        ZStack {
            if showBackground {
                Theme.Colors.background
                    .ignoresSafeArea()
            }

            LoadingView(message: message, size: .large)
        }
    }
}

// MARK: - Overlay Loading

struct LoadingOverlay: View {
    var message: String? = nil

    var body: some View {
        ZStack {
            Theme.Colors.overlay
                .ignoresSafeArea()

            VStack(spacing: Theme.Spacing.md) {
                ProgressView()
                    .scaleEffect(1.5)
                    .tint(.white)

                if let message = message {
                    Text(message)
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(Theme.Spacing.xl)
            .background(Color.black.opacity(0.7))
            .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.lg))
        }
    }
}

// MARK: - Skeleton Loading

struct SkeletonView: View {
    var width: CGFloat? = nil
    var height: CGFloat = 20

    @State private var isAnimating = false

    var body: some View {
        RoundedRectangle(cornerRadius: Theme.CornerRadius.xs)
            .fill(
                LinearGradient(
                    colors: [
                        Theme.Colors.surface,
                        Theme.Colors.surface.opacity(0.5),
                        Theme.Colors.surface
                    ],
                    startPoint: isAnimating ? .leading : .trailing,
                    endPoint: isAnimating ? .trailing : .leading
                )
            )
            .frame(width: width, height: height)
            .onAppear {
                withAnimation(.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                    isAnimating = true
                }
            }
    }
}

// MARK: - Chat List Skeleton

struct ChatListSkeletonView: View {
    var itemCount: Int = 5

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<itemCount, id: \.self) { _ in
                HStack(spacing: Theme.Spacing.sm) {
                    // Avatar skeleton
                    Circle()
                        .fill(Theme.Colors.surface)
                        .frame(width: Theme.AvatarSize.md, height: Theme.AvatarSize.md)

                    VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
                        // Name skeleton
                        SkeletonView(width: CGFloat.random(in: 80...150), height: 16)

                        // Message skeleton
                        SkeletonView(width: CGFloat.random(in: 150...250), height: 14)
                    }

                    Spacer()

                    // Time skeleton
                    SkeletonView(width: 40, height: 12)
                }
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.vertical, Theme.Spacing.sm)

                Divider()
                    .padding(.leading, Theme.Spacing.md + Theme.AvatarSize.md + Theme.Spacing.sm)
            }
        }
    }
}

// MARK: - View Extension

extension View {
    func loading(_ isLoading: Bool, message: String? = nil) -> some View {
        ZStack {
            self
                .disabled(isLoading)
                .blur(radius: isLoading ? 2 : 0)

            if isLoading {
                LoadingOverlay(message: message)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: Theme.Spacing.xl) {
        LoadingView(message: "Loading...", size: .small)

        LoadingView(message: "Please wait", size: .medium)

        LoadingView(message: "This might take a moment", size: .large)

        Divider()

        VStack(alignment: .leading, spacing: Theme.Spacing.sm) {
            SkeletonView(width: 200, height: 20)
            SkeletonView(width: 150, height: 16)
            SkeletonView(width: 180, height: 16)
        }

        Divider()

        ChatListSkeletonView(itemCount: 3)
    }
    .padding()
}
