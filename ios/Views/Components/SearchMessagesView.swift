import SwiftUI

/// Search bar overlay for searching messages in chat
struct SearchMessagesBar: View {
    @Binding var searchText: String
    @Binding var isSearching: Bool
    let resultsCount: Int
    let currentIndex: Int
    let onPrevious: () -> Void
    let onNext: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            // Search field
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.gray)

                TextField("Search messages", text: $searchText)
                    .textFieldStyle(.plain)
                    .foregroundColor(.white)
                    .autocorrectionDisabled()

                if !searchText.isEmpty {
                    Button(action: { searchText = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.gray)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.gray.opacity(0.2))
            .cornerRadius(10)

            // Results navigation
            if !searchText.isEmpty && resultsCount > 0 {
                HStack(spacing: 4) {
                    Text("\(currentIndex + 1)/\(resultsCount)")
                        .font(.caption)
                        .foregroundColor(.gray)
                        .monospacedDigit()

                    Button(action: onPrevious) {
                        Image(systemName: "chevron.up")
                            .font(.caption.bold())
                            .foregroundColor(.white)
                            .frame(width: 28, height: 28)
                            .background(Color.gray.opacity(0.3))
                            .cornerRadius(6)
                    }
                    .disabled(resultsCount <= 1)

                    Button(action: onNext) {
                        Image(systemName: "chevron.down")
                            .font(.caption.bold())
                            .foregroundColor(.white)
                            .frame(width: 28, height: 28)
                            .background(Color.gray.opacity(0.3))
                            .cornerRadius(6)
                    }
                    .disabled(resultsCount <= 1)
                }
            } else if !searchText.isEmpty {
                Text("No results")
                    .font(.caption)
                    .foregroundColor(.gray)
            }

            // Close button
            Button(action: {
                isSearching = false
                searchText = ""
            }) {
                Text("Cancel")
                    .font(.subheadline)
                    .foregroundColor(.blue)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color.black)
    }
}

/// View model helper for message search functionality
struct MessageSearchResult: Identifiable, Equatable {
    let id: String  // Message ID
    let messageIndex: Int
    let range: Range<String.Index>?

    static func == (lhs: MessageSearchResult, rhs: MessageSearchResult) -> Bool {
        lhs.id == rhs.id
    }
}

/// Extension to help with search highlighting in message content
extension String {
    /// Find all ranges of a search term (case-insensitive)
    func ranges(of searchTerm: String) -> [Range<String.Index>] {
        var ranges: [Range<String.Index>] = []
        var searchStartIndex = self.startIndex

        let lowercasedSelf = self.lowercased()
        let lowercasedSearch = searchTerm.lowercased()

        while searchStartIndex < self.endIndex {
            let searchRange = searchStartIndex..<self.endIndex
            if let range = lowercasedSelf.range(of: lowercasedSearch, range: searchRange) {
                // Convert to original string indices
                let originalStart = self.index(self.startIndex, offsetBy: lowercasedSelf.distance(from: lowercasedSelf.startIndex, to: range.lowerBound))
                let originalEnd = self.index(self.startIndex, offsetBy: lowercasedSelf.distance(from: lowercasedSelf.startIndex, to: range.upperBound))
                ranges.append(originalStart..<originalEnd)
                searchStartIndex = range.upperBound
            } else {
                break
            }
        }

        return ranges
    }

    /// Check if contains search term (case-insensitive)
    func containsIgnoringCase(_ searchTerm: String) -> Bool {
        self.lowercased().contains(searchTerm.lowercased())
    }
}

/// Highlighted text view for search results
struct HighlightedText: View {
    let text: String
    let searchTerm: String
    let highlightColor: Color
    let textColor: Color

    init(text: String, searchTerm: String, highlightColor: Color = .yellow, textColor: Color = .white) {
        self.text = text
        self.searchTerm = searchTerm
        self.highlightColor = highlightColor
        self.textColor = textColor
    }

    var body: some View {
        if searchTerm.isEmpty {
            Text(text)
                .foregroundColor(textColor)
        } else {
            buildHighlightedText()
        }
    }

    /// Build highlighted text using Text concatenation for better compatibility
    private func buildHighlightedText() -> Text {
        let lowercasedText = text.lowercased()
        let lowercasedSearch = searchTerm.lowercased()

        var result = Text("")
        var currentIndex = text.startIndex

        while let range = lowercasedText.range(of: lowercasedSearch, range: currentIndex..<text.endIndex) {
            // Add non-highlighted part
            if currentIndex < range.lowerBound {
                let normalText = String(text[currentIndex..<range.lowerBound])
                result = result + Text(normalText).foregroundColor(textColor)
            }

            // Add highlighted part (using bold + color since .background() returns View, not Text)
            let highlightedText = String(text[range])
            result = result + Text(highlightedText)
                .bold()
                .foregroundColor(highlightColor)

            currentIndex = range.upperBound
        }

        // Add remaining text
        if currentIndex < text.endIndex {
            let remainingText = String(text[currentIndex...])
            result = result + Text(remainingText).foregroundColor(textColor)
        }

        return result
    }
}

#Preview {
    VStack {
        SearchMessagesBar(
            searchText: .constant("hello"),
            isSearching: .constant(true),
            resultsCount: 5,
            currentIndex: 2,
            onPrevious: {},
            onNext: {}
        )

        Spacer()

        HighlightedText(
            text: "Hello world, this is a hello test message with hello.",
            searchTerm: "hello",
            highlightColor: .yellow,
            textColor: .white
        )
        .padding()
    }
    .background(Color.black)
}
