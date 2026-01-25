import SwiftUI

/// Whisper2 Design System
/// Centralized theme configuration for consistent UI

// MARK: - Colors

extension Color {
    /// Primary brand color
    static let whisperPrimary = Color("Primary", bundle: nil)
    /// Secondary accent color
    static let whisperSecondary = Color("Secondary", bundle: nil)
    /// Background color
    static let whisperBackground = Color("Background", bundle: nil)
    /// Surface color for cards, sheets
    static let whisperSurface = Color("Surface", bundle: nil)
    /// Error color
    static let whisperError = Color("Error", bundle: nil)

    /// Text colors
    static let whisperTextPrimary = Color("TextPrimary", bundle: nil)
    static let whisperTextSecondary = Color("TextSecondary", bundle: nil)
    static let whisperTextTertiary = Color("TextTertiary", bundle: nil)

    /// Message bubble colors
    static let whisperBubbleSent = Color("BubbleSent", bundle: nil)
    static let whisperBubbleReceived = Color("BubbleReceived", bundle: nil)
}

/// Fallback colors when asset catalog is not configured
extension Color {
    static var primaryColor: Color {
        Color(red: 0.0, green: 0.48, blue: 1.0) // #007AFF
    }

    static var secondaryColor: Color {
        Color(red: 0.35, green: 0.78, blue: 0.98) // #5AC8FA
    }

    static var backgroundColor: Color {
        Color(uiColor: .systemBackground)
    }

    static var surfaceColor: Color {
        Color(uiColor: .secondarySystemBackground)
    }

    static var errorColor: Color {
        Color(red: 1.0, green: 0.23, blue: 0.19) // #FF3B30
    }

    static var textPrimaryColor: Color {
        Color(uiColor: .label)
    }

    static var textSecondaryColor: Color {
        Color(uiColor: .secondaryLabel)
    }

    static var textTertiaryColor: Color {
        Color(uiColor: .tertiaryLabel)
    }

    static var bubbleSentColor: Color {
        Color(red: 0.0, green: 0.48, blue: 1.0) // #007AFF
    }

    static var bubbleReceivedColor: Color {
        Color(uiColor: .systemGray5)
    }
}

// MARK: - Theme

enum Theme {
    // MARK: - Colors
    enum Colors {
        static let primary = Color.primaryColor
        static let secondary = Color.secondaryColor
        static let background = Color.backgroundColor
        static let surface = Color.surfaceColor
        static let error = Color.errorColor

        static let textPrimary = Color.textPrimaryColor
        static let textSecondary = Color.textSecondaryColor
        static let textTertiary = Color.textTertiaryColor

        static let bubbleSent = Color.bubbleSentColor
        static let bubbleReceived = Color.bubbleReceivedColor

        static let success = Color(red: 0.20, green: 0.78, blue: 0.35) // #34C759
        static let warning = Color(red: 1.0, green: 0.80, blue: 0.0) // #FFCC00

        static let separator = Color(uiColor: .separator)
        static let overlay = Color.black.opacity(0.4)
    }

    // MARK: - Typography
    enum Typography {
        /// Large title - 34pt Bold
        static let largeTitle = Font.system(size: 34, weight: .bold, design: .default)

        /// Title 1 - 28pt Bold
        static let title1 = Font.system(size: 28, weight: .bold, design: .default)

        /// Title 2 - 22pt Bold
        static let title2 = Font.system(size: 22, weight: .bold, design: .default)

        /// Title 3 - 20pt Semibold
        static let title3 = Font.system(size: 20, weight: .semibold, design: .default)

        /// Headline - 17pt Semibold
        static let headline = Font.system(size: 17, weight: .semibold, design: .default)

        /// Body - 17pt Regular
        static let body = Font.system(size: 17, weight: .regular, design: .default)

        /// Callout - 16pt Regular
        static let callout = Font.system(size: 16, weight: .regular, design: .default)

        /// Subheadline - 15pt Regular
        static let subheadline = Font.system(size: 15, weight: .regular, design: .default)

        /// Footnote - 13pt Regular
        static let footnote = Font.system(size: 13, weight: .regular, design: .default)

        /// Caption 1 - 12pt Regular
        static let caption1 = Font.system(size: 12, weight: .regular, design: .default)

        /// Caption 2 - 11pt Regular
        static let caption2 = Font.system(size: 11, weight: .regular, design: .default)

        /// Monospaced for WhisperID, mnemonic
        static let monospaced = Font.system(size: 14, weight: .medium, design: .monospaced)
    }

    // MARK: - Spacing
    enum Spacing {
        /// 4pt
        static let xxs: CGFloat = 4
        /// 8pt
        static let xs: CGFloat = 8
        /// 12pt
        static let sm: CGFloat = 12
        /// 16pt
        static let md: CGFloat = 16
        /// 20pt
        static let lg: CGFloat = 20
        /// 24pt
        static let xl: CGFloat = 24
        /// 32pt
        static let xxl: CGFloat = 32
        /// 40pt
        static let xxxl: CGFloat = 40
        /// 48pt
        static let huge: CGFloat = 48
    }

    // MARK: - Corner Radius
    enum CornerRadius {
        /// 4pt
        static let xs: CGFloat = 4
        /// 8pt
        static let sm: CGFloat = 8
        /// 12pt
        static let md: CGFloat = 12
        /// 16pt
        static let lg: CGFloat = 16
        /// 20pt
        static let xl: CGFloat = 20
        /// Full circle
        static let full: CGFloat = 9999
    }

    // MARK: - Shadows
    enum Shadow {
        static let small = ShadowStyle(color: .black.opacity(0.1), radius: 2, x: 0, y: 1)
        static let medium = ShadowStyle(color: .black.opacity(0.15), radius: 4, x: 0, y: 2)
        static let large = ShadowStyle(color: .black.opacity(0.2), radius: 8, x: 0, y: 4)
    }

    // MARK: - Animation
    enum Animation {
        static let fast = SwiftUI.Animation.easeInOut(duration: 0.15)
        static let normal = SwiftUI.Animation.easeInOut(duration: 0.25)
        static let slow = SwiftUI.Animation.easeInOut(duration: 0.4)
        static let spring = SwiftUI.Animation.spring(response: 0.3, dampingFraction: 0.7)
    }

    // MARK: - Icon Sizes
    enum IconSize {
        /// 16pt
        static let sm: CGFloat = 16
        /// 20pt
        static let md: CGFloat = 20
        /// 24pt
        static let lg: CGFloat = 24
        /// 32pt
        static let xl: CGFloat = 32
        /// 48pt
        static let xxl: CGFloat = 48
    }

    // MARK: - Avatar Sizes
    enum AvatarSize {
        /// 32pt - Small list items
        static let sm: CGFloat = 32
        /// 40pt - Standard list items
        static let md: CGFloat = 40
        /// 56pt - Chat headers
        static let lg: CGFloat = 56
        /// 80pt - Profile view
        static let xl: CGFloat = 80
        /// 120pt - Large profile
        static let xxl: CGFloat = 120
    }
}

// MARK: - Shadow Style

struct ShadowStyle {
    let color: Color
    let radius: CGFloat
    let x: CGFloat
    let y: CGFloat
}

// MARK: - View Extensions

extension View {
    func themeShadow(_ style: ShadowStyle) -> some View {
        shadow(color: style.color, radius: style.radius, x: style.x, y: style.y)
    }

    func cardStyle() -> some View {
        self
            .background(Theme.Colors.surface)
            .cornerRadius(Theme.CornerRadius.md)
            .themeShadow(Theme.Shadow.small)
    }
}

// MARK: - Button Styles

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.Typography.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, Theme.Spacing.md)
            .background(
                isEnabled ? Theme.Colors.primary : Theme.Colors.primary.opacity(0.5)
            )
            .cornerRadius(Theme.CornerRadius.md)
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(Theme.Animation.fast, value: configuration.isPressed)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.Typography.headline)
            .foregroundColor(isEnabled ? Theme.Colors.primary : Theme.Colors.primary.opacity(0.5))
            .frame(maxWidth: .infinity)
            .padding(.vertical, Theme.Spacing.md)
            .background(
                RoundedRectangle(cornerRadius: Theme.CornerRadius.md)
                    .stroke(isEnabled ? Theme.Colors.primary : Theme.Colors.primary.opacity(0.5), lineWidth: 2)
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(Theme.Animation.fast, value: configuration.isPressed)
    }
}

struct DestructiveButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.Typography.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, Theme.Spacing.md)
            .background(
                isEnabled ? Theme.Colors.error : Theme.Colors.error.opacity(0.5)
            )
            .cornerRadius(Theme.CornerRadius.md)
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(Theme.Animation.fast, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PrimaryButtonStyle {
    static var primary: PrimaryButtonStyle { PrimaryButtonStyle() }
}

extension ButtonStyle where Self == SecondaryButtonStyle {
    static var secondary: SecondaryButtonStyle { SecondaryButtonStyle() }
}

extension ButtonStyle where Self == DestructiveButtonStyle {
    static var destructive: DestructiveButtonStyle { DestructiveButtonStyle() }
}
