import SwiftUI

/// Add contact by WhisperID
struct AddContactView: View {
    @Bindable var viewModel: ContactsViewModel
    @Binding var isPresented: Bool
    @FocusState private var focusedField: Field?

    enum Field {
        case whisperId
        case displayName
    }

    var body: some View {
        NavigationStack {
            Form {
                // Instructions
                Section {
                    VStack(alignment: .leading, spacing: Theme.Spacing.sm) {
                        Image(systemName: "person.badge.plus")
                            .font(.system(size: 40))
                            .foregroundColor(Theme.Colors.primary)

                        Text("Add a new contact by entering their WhisperID. You can optionally set a display name.")
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                    .padding(.vertical, Theme.Spacing.sm)
                }
                .listRowBackground(Color.clear)

                // WhisperID input
                Section("WhisperID") {
                    TextField("WH2-XXXXXXXX", text: $viewModel.newContactWhisperId)
                        .font(Theme.Typography.monospaced)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .focused($focusedField, equals: .whisperId)
                        .submitLabel(.next)
                        .onSubmit {
                            focusedField = .displayName
                        }
                }

                // Display name input (optional)
                Section {
                    TextField("Display Name (optional)", text: $viewModel.newContactDisplayName)
                        .focused($focusedField, equals: .displayName)
                        .submitLabel(.done)
                        .onSubmit {
                            if viewModel.canAddContact {
                                viewModel.addContact()
                            }
                        }
                } header: {
                    Text("Display Name")
                } footer: {
                    Text("If left empty, the WhisperID will be used as the display name.")
                        .font(Theme.Typography.caption1)
                }

                // QR Code option
                Section {
                    Button {
                        // Open QR scanner
                    } label: {
                        Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                    }
                } footer: {
                    Text("Ask your contact to share their QR code for quick adding.")
                        .font(Theme.Typography.caption1)
                }
            }
            .navigationTitle("Add Contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        viewModel.resetAddContactForm()
                        isPresented = false
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Add") {
                        viewModel.addContact()
                    }
                    .disabled(!viewModel.canAddContact || viewModel.isAddingContact)
                }
            }
            .loading(viewModel.isAddingContact, message: "Adding contact...")
            .alert("Error", isPresented: Binding(
                get: { viewModel.addContactError != nil },
                set: { if !$0 { viewModel.clearAddContactError() } }
            )) {
                Button("OK") { viewModel.clearAddContactError() }
            } message: {
                Text(viewModel.addContactError ?? "")
            }
            .onChange(of: viewModel.contacts.count) { oldValue, newValue in
                // Contact was added successfully
                if newValue > oldValue && !viewModel.isAddingContact {
                    isPresented = false
                }
            }
            .onAppear {
                focusedField = .whisperId
            }
        }
    }
}

// MARK: - QR Code Scanner View

struct QRScannerView: View {
    @Environment(\.dismiss) private var dismiss
    var onScan: (String) -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                // Camera preview would go here
                Theme.Colors.background

                VStack(spacing: Theme.Spacing.xl) {
                    Spacer()

                    // Scan frame
                    RoundedRectangle(cornerRadius: Theme.CornerRadius.lg)
                        .stroke(Theme.Colors.primary, lineWidth: 3)
                        .frame(width: 250, height: 250)
                        .overlay(
                            VStack {
                                HStack {
                                    scanCorner(rotation: 0)
                                    Spacer()
                                    scanCorner(rotation: 90)
                                }
                                Spacer()
                                HStack {
                                    scanCorner(rotation: -90)
                                    Spacer()
                                    scanCorner(rotation: 180)
                                }
                            }
                        )

                    Text("Point the camera at a WhisperID QR code")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textSecondary)

                    Spacer()
                }
                .padding(Theme.Spacing.xl)
            }
            .navigationTitle("Scan QR Code")
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

    private func scanCorner(rotation: Double) -> some View {
        Path { path in
            path.move(to: CGPoint(x: 0, y: 20))
            path.addLine(to: CGPoint(x: 0, y: 0))
            path.addLine(to: CGPoint(x: 20, y: 0))
        }
        .stroke(Theme.Colors.primary, style: StrokeStyle(lineWidth: 4, lineCap: .round))
        .frame(width: 20, height: 20)
        .rotationEffect(.degrees(rotation))
    }
}

// MARK: - Share WhisperID View

struct ShareWhisperIDView: View {
    let whisperId: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: Theme.Spacing.xl) {
                Spacer()

                // QR Code placeholder
                VStack(spacing: Theme.Spacing.md) {
                    RoundedRectangle(cornerRadius: Theme.CornerRadius.lg)
                        .fill(Theme.Colors.surface)
                        .frame(width: 200, height: 200)
                        .overlay(
                            Image(systemName: "qrcode")
                                .font(.system(size: 120))
                                .foregroundColor(Theme.Colors.textPrimary)
                        )

                    Text(whisperId)
                        .font(Theme.Typography.monospaced)
                        .foregroundColor(Theme.Colors.primary)
                }

                Text("Others can scan this QR code to add you as a contact")
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textSecondary)
                    .multilineTextAlignment(.center)

                Spacer()

                // Actions
                VStack(spacing: Theme.Spacing.sm) {
                    Button {
                        UIPasteboard.general.string = whisperId
                    } label: {
                        Text("Copy WhisperID")
                    }
                    .buttonStyle(.primary)

                    Button {
                        // Share sheet
                    } label: {
                        Text("Share")
                    }
                    .buttonStyle(.secondary)
                }
            }
            .padding(Theme.Spacing.xl)
            .navigationTitle("Your WhisperID")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Preview

#Preview {
    AddContactView(
        viewModel: ContactsViewModel(),
        isPresented: .constant(true)
    )
}
