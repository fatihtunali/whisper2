import SwiftUI
import AVFoundation
import AudioToolbox
import CoreImage.CIFilterBuiltins

/// Add contact by WhisperID
struct AddContactView: View {
    @Bindable var viewModel: ContactsViewModel
    @Binding var isPresented: Bool
    @FocusState private var focusedField: Field?
    @State private var showingQRScanner = false

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
                    TextField("WSP-XXXX-XXXX-XXXX", text: $viewModel.newContactWhisperId)
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
                        showingQRScanner = true
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
            .sheet(isPresented: $showingQRScanner) {
                QRScannerView { scannedWhisperId in
                    viewModel.newContactWhisperId = scannedWhisperId
                    showingQRScanner = false
                    focusedField = .displayName
                }
            }
        }
    }
}

// MARK: - QR Code Scanner View

struct QRScannerView: View {
    @Environment(\.dismiss) private var dismiss
    var onScan: (String) -> Void
    @State private var scannedCode: String?
    @State private var showingPermissionAlert = false

    var body: some View {
        NavigationStack {
            ZStack {
                // Camera preview
                QRCameraPreview(onCodeScanned: { code in
                    // Only accept WhisperID format
                    if code.hasPrefix("WSP-") && scannedCode == nil {
                        scannedCode = code
                        onScan(code)
                    }
                })
                .ignoresSafeArea()

                // Overlay with scan frame
                VStack(spacing: Theme.Spacing.xl) {
                    Spacer()

                    // Scan frame
                    RoundedRectangle(cornerRadius: Theme.CornerRadius.lg)
                        .stroke(Theme.Colors.primary, lineWidth: 3)
                        .frame(width: 250, height: 250)
                        .background(Color.black.opacity(0.001)) // Tap through

                    Text("Point the camera at a WhisperID QR code")
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(.white)
                        .shadow(radius: 2)

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
                    .foregroundColor(.white)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
    }
}

// MARK: - QR Camera Preview (UIKit wrapper)

struct QRCameraPreview: UIViewControllerRepresentable {
    var onCodeScanned: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onCodeScanned = onCodeScanned
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {}
}

class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCodeScanned: ((String) -> Void)?
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        setupCamera()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession?.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    private func setupCamera() {
        let session = AVCaptureSession()

        guard let videoCaptureDevice = AVCaptureDevice.default(for: .video) else {
            showNoCameraUI()
            return
        }

        guard let videoInput = try? AVCaptureDeviceInput(device: videoCaptureDevice) else {
            showNoCameraUI()
            return
        }

        if session.canAddInput(videoInput) {
            session.addInput(videoInput)
        } else {
            showNoCameraUI()
            return
        }

        let metadataOutput = AVCaptureMetadataOutput()
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [.qr]
        } else {
            showNoCameraUI()
            return
        }

        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.frame = view.bounds
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)

        self.captureSession = session
        self.previewLayer = previewLayer

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    private func showNoCameraUI() {
        view.backgroundColor = .black
        let label = UILabel()
        label.text = "Camera not available"
        label.textColor = .white
        label.textAlignment = .center
        label.frame = view.bounds
        view.addSubview(label)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        if let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
           let stringValue = metadataObject.stringValue {
            // Vibrate
            AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
            captureSession?.stopRunning()
            onCodeScanned?(stringValue)
        }
    }
}

// MARK: - Share WhisperID View

struct ShareWhisperIDView: View {
    let whisperId: String
    @Environment(\.dismiss) private var dismiss
    @State private var qrCodeImage: UIImage?
    @State private var showingShareSheet = false
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(spacing: Theme.Spacing.xl) {
                Spacer()

                // QR Code
                VStack(spacing: Theme.Spacing.md) {
                    if let qrImage = qrCodeImage {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 200, height: 200)
                            .background(Color.white)
                            .cornerRadius(Theme.CornerRadius.lg)
                    } else {
                        RoundedRectangle(cornerRadius: Theme.CornerRadius.lg)
                            .fill(Theme.Colors.surface)
                            .frame(width: 200, height: 200)
                            .overlay(
                                ProgressView()
                            )
                    }

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
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            copied = false
                        }
                    } label: {
                        Text(copied ? "Copied!" : "Copy WhisperID")
                    }
                    .buttonStyle(.primary)

                    Button {
                        showingShareSheet = true
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
            .onAppear {
                generateQRCode()
            }
            .sheet(isPresented: $showingShareSheet) {
                if let qrImage = qrCodeImage {
                    ShareSheet(items: [qrImage, "Add me on Whisper2! My WhisperID: \(whisperId)"])
                }
            }
        }
    }

    private func generateQRCode() {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()

        filter.message = Data(whisperId.utf8)
        filter.correctionLevel = "M"

        if let outputImage = filter.outputImage {
            // Scale up the QR code
            let transform = CGAffineTransform(scaleX: 10, y: 10)
            let scaledImage = outputImage.transformed(by: transform)

            if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                qrCodeImage = UIImage(cgImage: cgImage)
            }
        }
    }
}

// MARK: - Share Sheet

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - Preview

#Preview {
    AddContactView(
        viewModel: ContactsViewModel(),
        isPresented: .constant(true)
    )
}
