import SwiftUI

/// Initial welcome screen with Create/Recover options
struct WelcomeView: View {
    @Bindable var viewModel: AuthViewModel

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Spacer()

                // Logo and title
                VStack(spacing: Theme.Spacing.lg) {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 80))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [Theme.Colors.primary, Theme.Colors.secondary],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )

                    VStack(spacing: Theme.Spacing.xs) {
                        Text("Whisper2")
                            .font(Theme.Typography.largeTitle)
                            .foregroundColor(Theme.Colors.textPrimary)

                        Text("Secure. Private. Encrypted.")
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                }

                Spacer()

                // Feature highlights
                VStack(spacing: Theme.Spacing.md) {
                    FeatureRow(
                        icon: "lock.fill",
                        title: "End-to-End Encrypted",
                        description: "Your messages are always private"
                    )

                    FeatureRow(
                        icon: "key.fill",
                        title: "Self-Custody Keys",
                        description: "You control your identity"
                    )

                    FeatureRow(
                        icon: "server.rack",
                        title: "No Phone Number",
                        description: "No personal data required"
                    )
                }
                .padding(.horizontal, Theme.Spacing.xl)

                Spacer()

                // Action buttons
                VStack(spacing: Theme.Spacing.sm) {
                    Button {
                        viewModel.startCreateAccount()
                    } label: {
                        Text("Create New Account")
                    }
                    .buttonStyle(.primary)

                    Button {
                        viewModel.startRecoverAccount()
                    } label: {
                        Text("Recover Existing Account")
                    }
                    .buttonStyle(.secondary)
                }
                .padding(.horizontal, Theme.Spacing.xl)
                .padding(.bottom, Theme.Spacing.xxl)
            }
            .background(Theme.Colors.background)
            .navigationDestination(isPresented: Binding(
                get: { viewModel.state == .creatingAccount },
                set: { if !$0 { viewModel.reset() } }
            )) {
                CreateAccountView(viewModel: viewModel)
            }
            .navigationDestination(isPresented: Binding(
                get: { viewModel.state == .recoveringAccount },
                set: { if !$0 { viewModel.reset() } }
            )) {
                RecoverAccountView(viewModel: viewModel)
            }
        }
    }
}

// MARK: - Feature Row

private struct FeatureRow: View {
    let icon: String
    let title: String
    let description: String

    var body: some View {
        HStack(spacing: Theme.Spacing.md) {
            Image(systemName: icon)
                .font(.system(size: Theme.IconSize.lg))
                .foregroundColor(Theme.Colors.primary)
                .frame(width: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Theme.Typography.headline)
                    .foregroundColor(Theme.Colors.textPrimary)

                Text(description)
                    .font(Theme.Typography.caption1)
                    .foregroundColor(Theme.Colors.textSecondary)
            }

            Spacer()
        }
    }
}

// MARK: - Preview

#Preview {
    WelcomeView(viewModel: AuthViewModel())
}
