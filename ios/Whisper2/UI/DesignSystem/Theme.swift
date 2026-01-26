import SwiftUI

/// Whisper2 Design System
/// Matching the original Whisper Expo app UI

// MARK: - Theme Manager

/// ThemeManager handles app-wide theming
class ThemeManager: ObservableObject {
    static let shared = ThemeManager()

    @Published var themeMode: ThemeMode = .dark {
        didSet {
            UserDefaults.standard.set(themeMode.rawValue, forKey: "whisper_theme_mode")
        }
    }

    var colorScheme: ColorScheme? {
        switch themeMode {
        case .light: return .light
        case .dark: return .dark
        case .system: return nil
        }
    }

    var isDark: Bool {
        switch themeMode {
        case .dark: return true
        case .light: return false
        case .system: return UITraitCollection.current.userInterfaceStyle == .dark
        }
    }

    init() {
        if let savedMode = UserDefaults.standard.string(forKey: "whisper_theme_mode"),
           let mode = ThemeMode(rawValue: savedMode) {
            themeMode = mode
        } else {
            themeMode = .dark // Default to dark like Expo
        }
    }
}

enum ThemeMode: String, CaseIterable {
    case light
    case dark
    case system

    var displayName: String {
        switch self {
        case .light: return "Light"
        case .dark: return "Dark"
        case .system: return "System"
        }
    }
}

// MARK: - Whisper Theme Colors (Matching Expo App)

struct WhisperTheme {
    // Dark theme colors (default, matching Expo)
    struct Dark {
        static let primary = Color(hex: "6366f1")        // Indigo-500
        static let primaryDark = Color(hex: "4f46e5")    // Indigo-600
        static let primaryLight = Color(hex: "818cf8")   // Indigo-400

        static let background = Color(hex: "030712")     // Gray-950
        static let surface = Color(hex: "111827")        // Gray-900
        static let surfaceLight = Color(hex: "1f2937")   // Gray-800

        static let text = Color.white
        static let textSecondary = Color(hex: "9ca3af")  // Gray-400
        static let textMuted = Color(hex: "6b7280")      // Gray-500

        static let border = Color(hex: "374151")         // Gray-700
        static let borderLight = Color(hex: "4b5563")    // Gray-600

        static let success = Color(hex: "22c55e")        // Green-500
        static let error = Color(hex: "ef4444")          // Red-500
        static let warning = Color(hex: "f59e0b")        // Amber-500

        static let messageSent = Color(hex: "6366f1")    // Indigo-500
        static let messageReceived = Color(hex: "1f2937") // Gray-800
    }

    // Light theme colors
    struct Light {
        static let primary = Color(hex: "6366f1")        // Indigo-500
        static let primaryDark = Color(hex: "4f46e5")    // Indigo-600
        static let primaryLight = Color(hex: "818cf8")   // Indigo-400

        static let background = Color(hex: "f9fafb")     // Gray-50
        static let surface = Color.white
        static let surfaceLight = Color(hex: "f3f4f6")   // Gray-100

        static let text = Color(hex: "111827")           // Gray-900
        static let textSecondary = Color(hex: "4b5563")  // Gray-600
        static let textMuted = Color(hex: "6b7280")      // Gray-500

        static let border = Color(hex: "e5e7eb")         // Gray-200
        static let borderLight = Color(hex: "d1d5db")    // Gray-300

        static let success = Color(hex: "22c55e")        // Green-500
        static let error = Color(hex: "ef4444")          // Red-500
        static let warning = Color(hex: "f59e0b")        // Amber-500

        static let messageSent = Color(hex: "6366f1")    // Indigo-500
        static let messageReceived = Color(hex: "e5e7eb") // Gray-200
    }
}

// MARK: - Color Extensions

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (1, 1, 1, 0)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }

    // Helper to check if system is in dark mode
    private static var isSystemDark: Bool {
        UITraitCollection.current.userInterfaceStyle == .dark
    }

    // Helper to check current theme
    private static var isDarkTheme: Bool {
        switch ThemeManager.shared.themeMode {
        case .dark: return true
        case .light: return false
        case .system: return isSystemDark
        }
    }

    // Dynamic colors that respect theme
    static var whisperPrimary: Color {
        isDarkTheme ? WhisperTheme.Dark.primary : WhisperTheme.Light.primary
    }

    static var whisperPrimaryDark: Color {
        isDarkTheme ? WhisperTheme.Dark.primaryDark : WhisperTheme.Light.primaryDark
    }

    static var whisperPrimaryLight: Color {
        isDarkTheme ? WhisperTheme.Dark.primaryLight : WhisperTheme.Light.primaryLight
    }

    static var whisperBackground: Color {
        isDarkTheme ? WhisperTheme.Dark.background : WhisperTheme.Light.background
    }

    static var whisperSurface: Color {
        isDarkTheme ? WhisperTheme.Dark.surface : WhisperTheme.Light.surface
    }

    static var whisperSurfaceLight: Color {
        isDarkTheme ? WhisperTheme.Dark.surfaceLight : WhisperTheme.Light.surfaceLight
    }

    static var whisperText: Color {
        isDarkTheme ? WhisperTheme.Dark.text : WhisperTheme.Light.text
    }

    static var whisperTextSecondary: Color {
        isDarkTheme ? WhisperTheme.Dark.textSecondary : WhisperTheme.Light.textSecondary
    }

    static var whisperTextMuted: Color {
        isDarkTheme ? WhisperTheme.Dark.textMuted : WhisperTheme.Light.textMuted
    }

    static var whisperBorder: Color {
        isDarkTheme ? WhisperTheme.Dark.border : WhisperTheme.Light.border
    }

    static var whisperBorderLight: Color {
        isDarkTheme ? WhisperTheme.Dark.borderLight : WhisperTheme.Light.borderLight
    }

    static var whisperSuccess: Color {
        isDarkTheme ? WhisperTheme.Dark.success : WhisperTheme.Light.success
    }

    static var whisperError: Color {
        isDarkTheme ? WhisperTheme.Dark.error : WhisperTheme.Light.error
    }

    static var whisperWarning: Color {
        isDarkTheme ? WhisperTheme.Dark.warning : WhisperTheme.Light.warning
    }

    static var whisperMessageSent: Color {
        isDarkTheme ? WhisperTheme.Dark.messageSent : WhisperTheme.Light.messageSent
    }

    static var whisperMessageReceived: Color {
        isDarkTheme ? WhisperTheme.Dark.messageReceived : WhisperTheme.Light.messageReceived
    }

    // Legacy compatibility aliases
    static var whisperAccent: Color { whisperPrimary }
    static var sentMessageBubble: Color { whisperMessageSent }
    static var receivedMessageBubble: Color { whisperMessageReceived }
    static var onlineGreen: Color { whisperSuccess }
    static var offlineGray: Color { whisperTextMuted }
}

// MARK: - Spacing (Matching Expo)

struct WhisperSpacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
    static let xxl: CGFloat = 48
}

// MARK: - Font Sizes (Matching Expo)

struct WhisperFontSize {
    static let xs: CGFloat = 12
    static let sm: CGFloat = 14
    static let md: CGFloat = 16
    static let lg: CGFloat = 18
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 24
    static let xxxl: CGFloat = 32
}

// MARK: - Border Radius

struct WhisperRadius {
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let full: CGFloat = 9999
}

// MARK: - Font Extensions

extension Font {
    /// Whisper font (uses system font with proper weights)
    static func whisper(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        return .system(size: size, weight: weight)
    }

    // Whisper-specific font styles
    static let whisperTitle = Font.system(size: WhisperFontSize.xxl, weight: .bold)
    static let whisperHeadline = Font.system(size: WhisperFontSize.xl, weight: .semibold)
    static let whisperSubheadline = Font.system(size: WhisperFontSize.lg, weight: .semibold)
    static let whisperBody = Font.system(size: WhisperFontSize.md, weight: .regular)
    static let whisperBodyMedium = Font.system(size: WhisperFontSize.md, weight: .medium)
    static let whisperCaption = Font.system(size: WhisperFontSize.sm, weight: .regular)
    static let whisperCaptionMedium = Font.system(size: WhisperFontSize.sm, weight: .medium)
    static let whisperSmall = Font.system(size: WhisperFontSize.xs, weight: .regular)
    static let whisperSmallMedium = Font.system(size: WhisperFontSize.xs, weight: .medium)
}

// MARK: - Legacy Theme Compatibility

enum Theme {
    enum Colors {
        static var primary: Color { .whisperPrimary }
        static var secondary: Color { .whisperPrimaryLight }
        static var background: Color { .whisperBackground }
        static var surface: Color { .whisperSurface }
        static var error: Color { .whisperError }
        static var textPrimary: Color { .whisperText }
        static var textSecondary: Color { .whisperTextSecondary }
        static var textTertiary: Color { .whisperTextMuted }
        static var bubbleSent: Color { .whisperMessageSent }
        static var bubbleReceived: Color { .whisperMessageReceived }
        static var success: Color { .whisperSuccess }
        static var warning: Color { .whisperWarning }
        static var separator: Color { .whisperBorder }
        static let overlay = Color.black.opacity(0.4)
    }

    enum Typography {
        static let largeTitle = Font.system(size: 34, weight: .bold)
        static let title1 = Font.system(size: 28, weight: .bold)
        static let title2 = Font.system(size: 22, weight: .bold)
        static let title3 = Font.system(size: 20, weight: .semibold)
        static let headline = Font.system(size: 17, weight: .semibold)
        static let body = Font.system(size: 17, weight: .regular)
        static let callout = Font.system(size: 16, weight: .regular)
        static let subheadline = Font.system(size: 15, weight: .regular)
        static let footnote = Font.system(size: 13, weight: .regular)
        static let caption1 = Font.system(size: 12, weight: .regular)
        static let caption2 = Font.system(size: 11, weight: .regular)
        static let monospaced = Font.system(size: 14, weight: .medium, design: .monospaced)
    }

    enum Spacing {
        static let xxs: CGFloat = 4
        static let xs: CGFloat = 8
        static let sm: CGFloat = 12
        static let md: CGFloat = 16
        static let lg: CGFloat = 20
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        static let xxxl: CGFloat = 40
        static let huge: CGFloat = 48
    }

    enum CornerRadius {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
        static let full: CGFloat = 9999
    }

    enum Shadow {
        static let small = ShadowStyle(color: .black.opacity(0.1), radius: 2, x: 0, y: 1)
        static let medium = ShadowStyle(color: .black.opacity(0.15), radius: 4, x: 0, y: 2)
        static let large = ShadowStyle(color: .black.opacity(0.2), radius: 8, x: 0, y: 4)
    }

    enum Animation {
        static let fast = SwiftUI.Animation.easeInOut(duration: 0.15)
        static let normal = SwiftUI.Animation.easeInOut(duration: 0.25)
        static let slow = SwiftUI.Animation.easeInOut(duration: 0.4)
        static let spring = SwiftUI.Animation.spring(response: 0.3, dampingFraction: 0.7)
    }

    enum IconSize {
        static let sm: CGFloat = 16
        static let md: CGFloat = 20
        static let lg: CGFloat = 24
        static let xl: CGFloat = 32
        static let xxl: CGFloat = 48
    }

    enum AvatarSize {
        static let sm: CGFloat = 32
        static let md: CGFloat = 40
        static let lg: CGFloat = 56
        static let xl: CGFloat = 80
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
            .background(Color.whisperSurface)
            .cornerRadius(WhisperRadius.md)
            .themeShadow(Theme.Shadow.small)
    }
}

// MARK: - Button Styles

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, WhisperSpacing.md)
            .background(
                isEnabled ? Color.whisperPrimary : Color.whisperPrimary.opacity(0.5)
            )
            .cornerRadius(WhisperRadius.md)
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(Theme.Animation.fast, value: configuration.isPressed)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
            .foregroundColor(isEnabled ? .whisperPrimary : .whisperPrimary.opacity(0.5))
            .frame(maxWidth: .infinity)
            .padding(.vertical, WhisperSpacing.md)
            .background(
                RoundedRectangle(cornerRadius: WhisperRadius.md)
                    .stroke(isEnabled ? Color.whisperPrimary : Color.whisperPrimary.opacity(0.5), lineWidth: 2)
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(Theme.Animation.fast, value: configuration.isPressed)
    }
}

struct DestructiveButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, WhisperSpacing.md)
            .background(
                isEnabled ? Color.whisperError : Color.whisperError.opacity(0.5)
            )
            .cornerRadius(WhisperRadius.md)
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
