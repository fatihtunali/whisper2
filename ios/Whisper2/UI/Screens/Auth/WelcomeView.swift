import SwiftUI

/// Initial welcome screen with Create/Recover options - WhatsApp style
struct WelcomeView: View {
    @Bindable var viewModel: AuthViewModel
    @Environment(\.verticalSizeClass) var verticalSizeClass
    @Environment(\.horizontalSizeClass) var horizontalSizeClass

    private let primaryGreen = Color(red: 7/255, green: 94/255, blue: 84/255)
    private let secondaryGreen = Color(red: 18/255, green: 140/255, blue: 126/255)
    private let accentGreen = Color(red: 37/255, green: 211/255, blue: 102/255)

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let isCompact = geo.size.height < 700
                let logoSize: CGFloat = isCompact ? 80 : 100
                let titleSize: CGFloat = isCompact ? 28 : 32
                let buttonHeight: CGFloat = isCompact ? 46 : 52
                let horizontalPadding: CGFloat = min(geo.size.width * 0.08, 32)

                ZStack {
                    // Full screen gradient
                    LinearGradient(
                        colors: [primaryGreen, secondaryGreen],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .ignoresSafeArea()

                    VStack(spacing: 0) {
                        // Logo section
                        Spacer()
                            .frame(minHeight: isCompact ? 20 : 40)

                        logoSection(logoSize: logoSize, titleSize: titleSize)

                        Spacer()
                            .frame(minHeight: isCompact ? 16 : 32, maxHeight: isCompact ? 24 : 50)

                        // Features section
                        featuresSection(isCompact: isCompact)
                            .padding(.horizontal, horizontalPadding)

                        Spacer()

                        // Buttons section
                        buttonsSection(buttonHeight: buttonHeight)
                            .padding(.horizontal, horizontalPadding)
                            .padding(.bottom, geo.safeAreaInsets.bottom > 0 ? 16 : 24)
                    }
                    .padding(.top, geo.safeAreaInsets.top > 0 ? 0 : 20)
                }
            }
            .ignoresSafeArea()
            .navigationBarHidden(true)
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

    @ViewBuilder
    private func logoSection(logoSize: CGFloat, titleSize: CGFloat) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.white)
                    .frame(width: logoSize, height: logoSize)

                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: logoSize * 0.45))
                    .foregroundColor(primaryGreen)
            }

            VStack(spacing: 4) {
                Text("Whisper2")
                    .font(.system(size: titleSize, weight: .bold))
                    .foregroundColor(.white)

                Text("Secure. Private. Encrypted.")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundColor(.white.opacity(0.85))
            }
        }
    }

    @ViewBuilder
    private func featuresSection(isCompact: Bool) -> some View {
        VStack(spacing: isCompact ? 8 : 12) {
            FeatureRow(
                icon: "lock.fill",
                title: "End-to-End Encrypted",
                description: "Your messages are always private",
                accentColor: accentGreen,
                isCompact: isCompact
            )

            FeatureRow(
                icon: "key.fill",
                title: "Self-Custody Keys",
                description: "You control your identity",
                accentColor: accentGreen,
                isCompact: isCompact
            )

            FeatureRow(
                icon: "person.badge.shield.checkmark.fill",
                title: "No Phone Number",
                description: "No personal data required",
                accentColor: accentGreen,
                isCompact: isCompact
            )
        }
    }

    @ViewBuilder
    private func buttonsSection(buttonHeight: CGFloat) -> some View {
        VStack(spacing: 12) {
            Button {
                viewModel.startCreateAccount()
            } label: {
                Text("Create New Account")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(primaryGreen)
                    .frame(maxWidth: .infinity)
                    .frame(height: buttonHeight)
                    .background(Color.white)
                    .clipShape(Capsule())
            }

            Button {
                viewModel.startRecoverAccount()
            } label: {
                Text("Recover Existing Account")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: buttonHeight)
                    .background(Color.white.opacity(0.2))
                    .clipShape(Capsule())
                    .overlay(
                        Capsule()
                            .stroke(Color.white.opacity(0.4), lineWidth: 1)
                    )
            }
        }
    }
}

// MARK: - Feature Row

private struct FeatureRow: View {
    let icon: String
    let title: String
    let description: String
    let accentColor: Color
    let isCompact: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: isCompact ? 18 : 20, weight: .medium))
                .foregroundColor(accentColor)
                .frame(width: isCompact ? 36 : 40, height: isCompact ? 36 : 40)
                .background(Color.white.opacity(0.15))
                .clipShape(RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: isCompact ? 14 : 15, weight: .semibold))
                    .foregroundColor(.white)

                Text(description)
                    .font(.system(size: isCompact ? 12 : 13, weight: .regular))
                    .foregroundColor(.white.opacity(0.75))
            }

            Spacer()
        }
    }
}

// MARK: - Preview

#Preview {
    WelcomeView(viewModel: AuthViewModel())
}
