import SwiftUI

/// Message bubble for chat view
struct MessageBubble: View {
    let content: String
    let timestamp: Date
    let isSent: Bool
    var status: MessageStatus = .sent
    var showTimestamp: Bool = true

    enum MessageStatus {
        case sending
        case sent
        case delivered
        case read
        case failed
    }

    private var bubbleColor: Color {
        isSent ? Theme.Colors.bubbleSent : Theme.Colors.bubbleReceived
    }

    private var textColor: Color {
        isSent ? .white : Theme.Colors.textPrimary
    }

    private var alignment: HorizontalAlignment {
        isSent ? .trailing : .leading
    }

    private var timeFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter
    }

    var body: some View {
        HStack {
            if isSent { Spacer(minLength: 60) }

            VStack(alignment: alignment, spacing: Theme.Spacing.xxs) {
                // Message content
                Text(content)
                    .font(Theme.Typography.body)
                    .foregroundColor(textColor)
                    .padding(.horizontal, Theme.Spacing.sm)
                    .padding(.vertical, Theme.Spacing.xs)
                    .background(bubbleColor)
                    .clipShape(BubbleShape(isSent: isSent))

                // Timestamp and status
                if showTimestamp {
                    HStack(spacing: Theme.Spacing.xxs) {
                        Text(timeFormatter.string(from: timestamp))
                            .font(Theme.Typography.caption2)
                            .foregroundColor(Theme.Colors.textTertiary)

                        if isSent {
                            statusIcon
                        }
                    }
                }
            }

            if !isSent { Spacer(minLength: 60) }
        }
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch status {
        case .sending:
            Image(systemName: "clock")
                .font(.system(size: 10))
                .foregroundColor(Theme.Colors.textTertiary)
        case .sent:
            Image(systemName: "checkmark")
                .font(.system(size: 10))
                .foregroundColor(Theme.Colors.textTertiary)
        case .delivered:
            Image(systemName: "checkmark")
                .font(.system(size: 10))
                .foregroundColor(Theme.Colors.primary)
        case .read:
            HStack(spacing: -4) {
                Image(systemName: "checkmark")
                Image(systemName: "checkmark")
            }
            .font(.system(size: 10))
            .foregroundColor(Theme.Colors.primary)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 12))
                .foregroundColor(Theme.Colors.error)
        }
    }
}

// MARK: - Bubble Shape

struct BubbleShape: Shape {
    let isSent: Bool

    func path(in rect: CGRect) -> Path {
        let radius: CGFloat = 16
        let tailWidth: CGFloat = 8
        let tailHeight: CGFloat = 6

        var path = Path()

        if isSent {
            // Sent message - tail on right
            path.move(to: CGPoint(x: rect.minX + radius, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX - radius - tailWidth, y: rect.minY))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX - tailWidth, y: rect.minY + radius),
                control: CGPoint(x: rect.maxX - tailWidth, y: rect.minY)
            )
            // Tail
            path.addLine(to: CGPoint(x: rect.maxX - tailWidth, y: rect.maxY - tailHeight - radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX, y: rect.maxY - tailHeight),
                control: CGPoint(x: rect.maxX - tailWidth, y: rect.maxY - tailHeight)
            )
            path.addLine(to: CGPoint(x: rect.maxX - tailWidth, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX + radius, y: rect.maxY))
            path.addQuadCurve(
                to: CGPoint(x: rect.minX, y: rect.maxY - radius),
                control: CGPoint(x: rect.minX, y: rect.maxY)
            )
            path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.minX + radius, y: rect.minY),
                control: CGPoint(x: rect.minX, y: rect.minY)
            )
        } else {
            // Received message - tail on left
            path.move(to: CGPoint(x: rect.minX + tailWidth + radius, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX, y: rect.minY + radius),
                control: CGPoint(x: rect.maxX, y: rect.minY)
            )
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX - radius, y: rect.maxY),
                control: CGPoint(x: rect.maxX, y: rect.maxY)
            )
            path.addLine(to: CGPoint(x: rect.minX + tailWidth, y: rect.maxY))
            // Tail
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - tailHeight))
            path.addQuadCurve(
                to: CGPoint(x: rect.minX + tailWidth, y: rect.maxY - tailHeight - radius),
                control: CGPoint(x: rect.minX + tailWidth, y: rect.maxY - tailHeight)
            )
            path.addLine(to: CGPoint(x: rect.minX + tailWidth, y: rect.minY + radius))
            path.addQuadCurve(
                to: CGPoint(x: rect.minX + tailWidth + radius, y: rect.minY),
                control: CGPoint(x: rect.minX + tailWidth, y: rect.minY)
            )
        }

        path.closeSubpath()
        return path
    }
}

// MARK: - Image Message Bubble

struct ImageMessageBubble: View {
    let imageURL: URL
    let timestamp: Date
    let isSent: Bool
    var status: MessageBubble.MessageStatus = .sent
    var caption: String? = nil

    var body: some View {
        HStack {
            if isSent { Spacer(minLength: 60) }

            VStack(alignment: isSent ? .trailing : .leading, spacing: Theme.Spacing.xxs) {
                VStack(alignment: .leading, spacing: 0) {
                    AsyncImage(url: imageURL) { phase in
                        switch phase {
                        case .empty:
                            Rectangle()
                                .fill(Theme.Colors.surface)
                                .frame(width: 200, height: 150)
                                .overlay(ProgressView())
                        case .success(let image):
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                                .frame(maxWidth: 200, maxHeight: 300)
                        case .failure:
                            Rectangle()
                                .fill(Theme.Colors.surface)
                                .frame(width: 200, height: 150)
                                .overlay(
                                    Image(systemName: "photo")
                                        .foregroundColor(Theme.Colors.textTertiary)
                                )
                        @unknown default:
                            EmptyView()
                        }
                    }

                    if let caption = caption {
                        Text(caption)
                            .font(Theme.Typography.body)
                            .foregroundColor(isSent ? .white : Theme.Colors.textPrimary)
                            .padding(Theme.Spacing.xs)
                    }
                }
                .background(isSent ? Theme.Colors.bubbleSent : Theme.Colors.bubbleReceived)
                .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.lg))

                // Timestamp
                HStack(spacing: Theme.Spacing.xxs) {
                    Text(timestamp.formatted(date: .omitted, time: .shortened))
                        .font(Theme.Typography.caption2)
                        .foregroundColor(Theme.Colors.textTertiary)
                }
            }

            if !isSent { Spacer(minLength: 60) }
        }
    }
}

// MARK: - Preview

#Preview {
    ScrollView {
        VStack(spacing: Theme.Spacing.md) {
            MessageBubble(
                content: "Hello! How are you?",
                timestamp: Date(),
                isSent: false
            )

            MessageBubble(
                content: "I'm doing great, thanks for asking! How about you?",
                timestamp: Date(),
                isSent: true,
                status: .read
            )

            MessageBubble(
                content: "Pretty good! Want to grab coffee later?",
                timestamp: Date(),
                isSent: false
            )

            MessageBubble(
                content: "Sending...",
                timestamp: Date(),
                isSent: true,
                status: .sending
            )

            MessageBubble(
                content: "This message failed to send",
                timestamp: Date(),
                isSent: true,
                status: .failed
            )
        }
        .padding()
    }
}
