import SwiftUI

/// Avatar view with image or initials fallback
struct AvatarView: View {
    let name: String
    let imageURL: URL?
    var size: CGFloat = Theme.AvatarSize.md
    var showOnlineIndicator: Bool = false
    var isOnline: Bool = false

    private var initials: String {
        let components = name.split(separator: " ")
        if components.count >= 2 {
            let first = components[0].prefix(1)
            let last = components[1].prefix(1)
            return "\(first)\(last)".uppercased()
        } else if let first = name.first {
            return String(first).uppercased()
        }
        return "?"
    }

    private var backgroundColor: Color {
        // Generate consistent color based on name
        let hash = name.hashValue
        let hue = Double(abs(hash) % 360) / 360.0
        return Color(hue: hue, saturation: 0.5, brightness: 0.8)
    }

    private var fontSize: CGFloat {
        size * 0.4
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            // Main avatar
            Group {
                if let url = imageURL {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .empty:
                            initialsView
                        case .success(let image):
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        case .failure:
                            initialsView
                        @unknown default:
                            initialsView
                        }
                    }
                } else {
                    initialsView
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())

            // Online indicator
            if showOnlineIndicator && isOnline {
                Circle()
                    .fill(Theme.Colors.success)
                    .frame(width: size * 0.3, height: size * 0.3)
                    .overlay(
                        Circle()
                            .stroke(Theme.Colors.background, lineWidth: 2)
                    )
                    .offset(x: 2, y: 2)
            }
        }
    }

    private var initialsView: some View {
        ZStack {
            Circle()
                .fill(backgroundColor)

            Text(initials)
                .font(.system(size: fontSize, weight: .semibold))
                .foregroundColor(.white)
        }
    }
}

// MARK: - Group Avatar

struct GroupAvatarView: View {
    let memberNames: [String]
    var size: CGFloat = Theme.AvatarSize.md

    private var displayNames: [String] {
        Array(memberNames.prefix(4))
    }

    var body: some View {
        if displayNames.count == 1 {
            AvatarView(name: displayNames[0], imageURL: nil, size: size)
        } else if displayNames.count == 2 {
            twoAvatars
        } else if displayNames.count == 3 {
            threeAvatars
        } else {
            fourAvatars
        }
    }

    private var twoAvatars: some View {
        ZStack {
            AvatarView(name: displayNames[0], imageURL: nil, size: size * 0.65)
                .offset(x: -size * 0.15, y: -size * 0.15)
            AvatarView(name: displayNames[1], imageURL: nil, size: size * 0.65)
                .offset(x: size * 0.15, y: size * 0.15)
        }
        .frame(width: size, height: size)
    }

    private var threeAvatars: some View {
        ZStack {
            AvatarView(name: displayNames[0], imageURL: nil, size: size * 0.5)
                .offset(x: 0, y: -size * 0.2)
            AvatarView(name: displayNames[1], imageURL: nil, size: size * 0.5)
                .offset(x: -size * 0.2, y: size * 0.15)
            AvatarView(name: displayNames[2], imageURL: nil, size: size * 0.5)
                .offset(x: size * 0.2, y: size * 0.15)
        }
        .frame(width: size, height: size)
    }

    private var fourAvatars: some View {
        ZStack {
            AvatarView(name: displayNames[0], imageURL: nil, size: size * 0.45)
                .offset(x: -size * 0.15, y: -size * 0.15)
            AvatarView(name: displayNames[1], imageURL: nil, size: size * 0.45)
                .offset(x: size * 0.15, y: -size * 0.15)
            AvatarView(name: displayNames[2], imageURL: nil, size: size * 0.45)
                .offset(x: -size * 0.15, y: size * 0.15)
            AvatarView(name: displayNames[3], imageURL: nil, size: size * 0.45)
                .offset(x: size * 0.15, y: size * 0.15)
        }
        .frame(width: size, height: size)
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: Theme.Spacing.lg) {
        HStack(spacing: Theme.Spacing.md) {
            AvatarView(name: "John Doe", imageURL: nil, size: Theme.AvatarSize.sm)
            AvatarView(name: "Jane Smith", imageURL: nil, size: Theme.AvatarSize.md)
            AvatarView(name: "Bob", imageURL: nil, size: Theme.AvatarSize.lg)
        }

        HStack(spacing: Theme.Spacing.md) {
            AvatarView(
                name: "Online User",
                imageURL: nil,
                size: Theme.AvatarSize.lg,
                showOnlineIndicator: true,
                isOnline: true
            )
            AvatarView(
                name: "Offline User",
                imageURL: nil,
                size: Theme.AvatarSize.lg,
                showOnlineIndicator: true,
                isOnline: false
            )
        }

        HStack(spacing: Theme.Spacing.md) {
            GroupAvatarView(memberNames: ["Alice"], size: Theme.AvatarSize.lg)
            GroupAvatarView(memberNames: ["Alice", "Bob"], size: Theme.AvatarSize.lg)
            GroupAvatarView(memberNames: ["Alice", "Bob", "Charlie"], size: Theme.AvatarSize.lg)
            GroupAvatarView(memberNames: ["Alice", "Bob", "Charlie", "Diana"], size: Theme.AvatarSize.lg)
        }
    }
    .padding()
}
