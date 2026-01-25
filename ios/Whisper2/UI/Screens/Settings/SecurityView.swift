import SwiftUI
import LocalAuthentication

/// App lock settings
struct SecurityView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var appLockEnabled = false
    @State private var biometricType: LABiometryType = .none
    @State private var lockTimeout: LockTimeout = .immediately
    @State private var showingSetupPIN = false
    @State private var showingChangePIN = false

    enum LockTimeout: String, CaseIterable {
        case immediately = "Immediately"
        case after1min = "After 1 minute"
        case after5min = "After 5 minutes"
        case after15min = "After 15 minutes"
        case after1hour = "After 1 hour"
    }

    var body: some View {
        NavigationStack {
            List {
                // Info
                Section {
                    HStack {
                        Image(systemName: "lock.shield.fill")
                            .font(.system(size: 40))
                            .foregroundColor(Theme.Colors.primary)

                        VStack(alignment: .leading, spacing: Theme.Spacing.xxs) {
                            Text("App Lock")
                                .font(Theme.Typography.headline)
                                .foregroundColor(Theme.Colors.textPrimary)

                            Text("Require authentication to open the app")
                                .font(Theme.Typography.caption1)
                                .foregroundColor(Theme.Colors.textSecondary)
                        }
                    }
                    .padding(.vertical, Theme.Spacing.xs)
                }

                // Enable/Disable
                Section {
                    Toggle(isOn: $appLockEnabled) {
                        HStack {
                            Image(systemName: biometricIcon)
                                .foregroundColor(Theme.Colors.primary)
                            Text("Enable App Lock")
                        }
                    }
                    .onChange(of: appLockEnabled) { _, newValue in
                        if newValue {
                            authenticateToEnable()
                        }
                    }
                } footer: {
                    Text(biometricDescription)
                }

                // Options (only when enabled)
                if appLockEnabled {
                    Section("Lock Options") {
                        Picker("Lock Timeout", selection: $lockTimeout) {
                            ForEach(LockTimeout.allCases, id: \.self) { timeout in
                                Text(timeout.rawValue).tag(timeout)
                            }
                        }

                        Button {
                            showingChangePIN = true
                        } label: {
                            HStack {
                                Image(systemName: "number.square")
                                    .foregroundColor(Theme.Colors.primary)
                                Text("Change PIN")
                                    .foregroundColor(Theme.Colors.textPrimary)
                            }
                        }
                    }

                    Section {
                        Toggle(isOn: .constant(true)) {
                            HStack {
                                Image(systemName: "eye.slash")
                                    .foregroundColor(Theme.Colors.primary)
                                Text("Hide Content in App Switcher")
                            }
                        }
                    } footer: {
                        Text("When enabled, the app content will be hidden when switching between apps.")
                    }
                }

                // Security tips
                Section("Security Tips") {
                    SecurityTipRow(
                        icon: "key.fill",
                        title: "Keep your recovery phrase safe",
                        description: "Never share it with anyone or store it digitally"
                    )

                    SecurityTipRow(
                        icon: "lock.fill",
                        title: "Use a strong device passcode",
                        description: "This protects your keychain data"
                    )

                    SecurityTipRow(
                        icon: "wifi.slash",
                        title: "Be cautious on public networks",
                        description: "Use a VPN when on untrusted networks"
                    )
                }
            }
            .navigationTitle("Security")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
            .sheet(isPresented: $showingSetupPIN) {
                PINSetupView(onComplete: { _ in
                    appLockEnabled = true
                })
            }
            .sheet(isPresented: $showingChangePIN) {
                PINSetupView(isChanging: true, onComplete: { _ in })
            }
            .onAppear {
                checkBiometricType()
            }
        }
    }

    private var biometricIcon: String {
        switch biometricType {
        case .faceID:
            return "faceid"
        case .touchID:
            return "touchid"
        case .opticID:
            return "opticid"
        default:
            return "lock.fill"
        }
    }

    private var biometricDescription: String {
        switch biometricType {
        case .faceID:
            return "Use Face ID to unlock the app."
        case .touchID:
            return "Use Touch ID to unlock the app."
        case .opticID:
            return "Use Optic ID to unlock the app."
        default:
            return "Use a PIN to unlock the app."
        }
    }

    private func checkBiometricType() {
        let context = LAContext()
        var error: NSError?

        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            biometricType = context.biometryType
        }
    }

    private func authenticateToEnable() {
        let context = LAContext()
        var error: NSError?

        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: "Enable app lock"
            ) { success, error in
                DispatchQueue.main.async {
                    if !success {
                        appLockEnabled = false
                    }
                }
            }
        } else {
            // Fall back to PIN setup
            showingSetupPIN = true
        }
    }
}

// MARK: - Security Tip Row

private struct SecurityTipRow: View {
    let icon: String
    let title: String
    let description: String

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Spacing.sm) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(Theme.Colors.primary)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textPrimary)

                Text(description)
                    .font(Theme.Typography.caption1)
                    .foregroundColor(Theme.Colors.textSecondary)
            }
        }
        .padding(.vertical, Theme.Spacing.xxs)
    }
}

// MARK: - PIN Setup View

private struct PINSetupView: View {
    var isChanging = false
    let onComplete: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var pin = ""
    @State private var confirmPin = ""
    @State private var step: Step = .enter
    @State private var error: String?

    enum Step {
        case enter
        case confirm
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: Theme.Spacing.xl) {
                Spacer()

                // Lock icon
                Image(systemName: "lock.fill")
                    .font(.system(size: 48))
                    .foregroundColor(Theme.Colors.primary)

                // Title
                VStack(spacing: Theme.Spacing.xs) {
                    Text(step == .enter ? "Enter PIN" : "Confirm PIN")
                        .font(Theme.Typography.title2)
                        .foregroundColor(Theme.Colors.textPrimary)

                    Text(step == .enter
                         ? "Create a 6-digit PIN"
                         : "Enter the PIN again to confirm")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textSecondary)
                }

                // PIN dots
                HStack(spacing: Theme.Spacing.md) {
                    ForEach(0..<6, id: \.self) { index in
                        Circle()
                            .fill(
                                (step == .enter ? pin : confirmPin).count > index
                                    ? Theme.Colors.primary
                                    : Theme.Colors.surface
                            )
                            .frame(width: 16, height: 16)
                            .overlay(
                                Circle()
                                    .stroke(Theme.Colors.textTertiary, lineWidth: 1)
                            )
                    }
                }

                // Error message
                if let error = error {
                    Text(error)
                        .font(Theme.Typography.caption1)
                        .foregroundColor(Theme.Colors.error)
                }

                Spacer()

                // Number pad
                NumberPad(
                    onDigit: { digit in
                        if step == .enter {
                            if pin.count < 6 {
                                pin += digit
                                if pin.count == 6 {
                                    step = .confirm
                                }
                            }
                        } else {
                            if confirmPin.count < 6 {
                                confirmPin += digit
                                if confirmPin.count == 6 {
                                    verifyPIN()
                                }
                            }
                        }
                        error = nil
                    },
                    onDelete: {
                        if step == .enter {
                            if !pin.isEmpty {
                                pin.removeLast()
                            }
                        } else {
                            if !confirmPin.isEmpty {
                                confirmPin.removeLast()
                            }
                        }
                    }
                )
            }
            .padding(Theme.Spacing.xl)
            .navigationTitle(isChanging ? "Change PIN" : "Set PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
            }
        }
    }

    private func verifyPIN() {
        if pin == confirmPin {
            onComplete(pin)
            dismiss()
        } else {
            error = "PINs don't match. Try again."
            confirmPin = ""
            step = .enter
            pin = ""
        }
    }
}

// MARK: - Number Pad

private struct NumberPad: View {
    let onDigit: (String) -> Void
    let onDelete: () -> Void

    private let numbers = [
        ["1", "2", "3"],
        ["4", "5", "6"],
        ["7", "8", "9"],
        ["", "0", "delete"]
    ]

    var body: some View {
        VStack(spacing: Theme.Spacing.sm) {
            ForEach(numbers, id: \.self) { row in
                HStack(spacing: Theme.Spacing.sm) {
                    ForEach(row, id: \.self) { item in
                        if item.isEmpty {
                            Color.clear
                                .frame(width: 80, height: 80)
                        } else if item == "delete" {
                            Button {
                                onDelete()
                            } label: {
                                Image(systemName: "delete.left")
                                    .font(.system(size: 24))
                                    .foregroundColor(Theme.Colors.textPrimary)
                                    .frame(width: 80, height: 80)
                            }
                        } else {
                            Button {
                                onDigit(item)
                            } label: {
                                Text(item)
                                    .font(.system(size: 32, weight: .light))
                                    .foregroundColor(Theme.Colors.textPrimary)
                                    .frame(width: 80, height: 80)
                                    .background(Theme.Colors.surface)
                                    .clipShape(Circle())
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Preview

#Preview {
    SecurityView()
}
