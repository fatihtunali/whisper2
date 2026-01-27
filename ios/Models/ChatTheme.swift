import SwiftUI
import UIKit

/// Chat theme with customizable colors
struct ChatTheme: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let backgroundColor: ThemeColor
    let outgoingBubbleColor: ThemeColor
    let incomingBubbleColor: ThemeColor
    let outgoingTextColor: ThemeColor
    let incomingTextColor: ThemeColor
    let accentColor: ThemeColor

    /// Codable color wrapper
    struct ThemeColor: Codable, Hashable {
        let red: Double
        let green: Double
        let blue: Double
        let opacity: Double

        init(_ color: Color) {
            // Convert SwiftUI Color to components
            let uiColor = UIColor(color)
            var r: CGFloat = 0
            var g: CGFloat = 0
            var b: CGFloat = 0
            var a: CGFloat = 0
            uiColor.getRed(&r, green: &g, blue: &b, alpha: &a)
            self.red = Double(r)
            self.green = Double(g)
            self.blue = Double(b)
            self.opacity = Double(a)
        }

        init(red: Double, green: Double, blue: Double, opacity: Double = 1.0) {
            self.red = red
            self.green = green
            self.blue = blue
            self.opacity = opacity
        }

        var color: Color {
            Color(red: red, green: green, blue: blue, opacity: opacity)
        }
    }

    /// Predefined themes
    static let themes: [ChatTheme] = [
        // Default dark theme
        ChatTheme(
            id: "default",
            name: "Default",
            backgroundColor: ThemeColor(red: 0, green: 0, blue: 0),
            outgoingBubbleColor: ThemeColor(red: 0.0, green: 0.48, blue: 1.0),  // Blue
            incomingBubbleColor: ThemeColor(red: 0.2, green: 0.2, blue: 0.2),  // Dark gray
            outgoingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            incomingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            accentColor: ThemeColor(red: 0.0, green: 0.48, blue: 1.0)
        ),

        // Purple Night
        ChatTheme(
            id: "purple_night",
            name: "Purple Night",
            backgroundColor: ThemeColor(red: 0.08, green: 0.05, blue: 0.15),
            outgoingBubbleColor: ThemeColor(red: 0.5, green: 0.25, blue: 0.8),  // Purple
            incomingBubbleColor: ThemeColor(red: 0.15, green: 0.12, blue: 0.22),
            outgoingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            incomingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            accentColor: ThemeColor(red: 0.7, green: 0.4, blue: 1.0)
        ),

        // Ocean Blue
        ChatTheme(
            id: "ocean_blue",
            name: "Ocean Blue",
            backgroundColor: ThemeColor(red: 0.02, green: 0.08, blue: 0.15),
            outgoingBubbleColor: ThemeColor(red: 0.0, green: 0.6, blue: 0.8),  // Teal
            incomingBubbleColor: ThemeColor(red: 0.1, green: 0.15, blue: 0.2),
            outgoingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            incomingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            accentColor: ThemeColor(red: 0.0, green: 0.8, blue: 0.9)
        ),

        // Forest Green
        ChatTheme(
            id: "forest_green",
            name: "Forest Green",
            backgroundColor: ThemeColor(red: 0.02, green: 0.08, blue: 0.05),
            outgoingBubbleColor: ThemeColor(red: 0.13, green: 0.55, blue: 0.13),  // Forest green
            incomingBubbleColor: ThemeColor(red: 0.1, green: 0.18, blue: 0.12),
            outgoingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            incomingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            accentColor: ThemeColor(red: 0.3, green: 0.8, blue: 0.3)
        ),

        // Sunset Orange
        ChatTheme(
            id: "sunset_orange",
            name: "Sunset",
            backgroundColor: ThemeColor(red: 0.1, green: 0.05, blue: 0.05),
            outgoingBubbleColor: ThemeColor(red: 0.9, green: 0.4, blue: 0.2),  // Orange
            incomingBubbleColor: ThemeColor(red: 0.2, green: 0.12, blue: 0.1),
            outgoingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            incomingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            accentColor: ThemeColor(red: 1.0, green: 0.5, blue: 0.2)
        ),

        // Rose Pink
        ChatTheme(
            id: "rose_pink",
            name: "Rose",
            backgroundColor: ThemeColor(red: 0.1, green: 0.05, blue: 0.08),
            outgoingBubbleColor: ThemeColor(red: 0.9, green: 0.3, blue: 0.5),  // Rose
            incomingBubbleColor: ThemeColor(red: 0.2, green: 0.1, blue: 0.15),
            outgoingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            incomingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            accentColor: ThemeColor(red: 1.0, green: 0.4, blue: 0.6)
        ),

        // Midnight Gold
        ChatTheme(
            id: "midnight_gold",
            name: "Midnight Gold",
            backgroundColor: ThemeColor(red: 0.05, green: 0.05, blue: 0.08),
            outgoingBubbleColor: ThemeColor(red: 0.8, green: 0.65, blue: 0.2),  // Gold
            incomingBubbleColor: ThemeColor(red: 0.15, green: 0.15, blue: 0.18),
            outgoingTextColor: ThemeColor(red: 0, green: 0, blue: 0),
            incomingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            accentColor: ThemeColor(red: 1.0, green: 0.84, blue: 0.0)
        ),

        // Cyberpunk
        ChatTheme(
            id: "cyberpunk",
            name: "Cyberpunk",
            backgroundColor: ThemeColor(red: 0.02, green: 0.02, blue: 0.05),
            outgoingBubbleColor: ThemeColor(red: 1.0, green: 0.0, blue: 0.5),  // Hot pink
            incomingBubbleColor: ThemeColor(red: 0.0, green: 0.8, blue: 0.8, opacity: 0.3),  // Cyan transparent
            outgoingTextColor: ThemeColor(red: 1, green: 1, blue: 1),
            incomingTextColor: ThemeColor(red: 0.0, green: 1.0, blue: 1.0),  // Cyan text
            accentColor: ThemeColor(red: 0.0, green: 1.0, blue: 1.0)
        )
    ]

    /// Get theme by ID
    static func getTheme(id: String?) -> ChatTheme {
        guard let id = id,
              let theme = themes.first(where: { $0.id == id }) else {
            return themes[0]  // Default theme
        }
        return theme
    }
}
