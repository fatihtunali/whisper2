import SwiftUI
import AVFoundation
import AudioToolbox
import PhotosUI
import Vision

/// Add contact view with QR scanner
struct AddContactView: View {
    @ObservedObject var viewModel: ContactsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var whisperId = ""
    @State private var nickname = ""
    @State private var showQRScanner = false
    @State private var scannedPublicKey: Data?
    @State private var showManualEntry = false
    
    private var isValidWhisperId: Bool {
        whisperId.isValidWhisperId
    }
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                
                VStack(spacing: 24) {
                    // Scan QR Code button
                    Button(action: { showQRScanner = true }) {
                        HStack {
                            Image(systemName: "qrcode.viewfinder")
                                .font(.title2)
                            Text("Scan QR Code")
                                .font(.headline)
                        }
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(
                            LinearGradient(
                                colors: [.blue, .purple],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .cornerRadius(12)
                    }
                    
                    // Divider
                    HStack {
                        Rectangle()
                            .fill(Color.gray.opacity(0.3))
                            .frame(height: 1)
                        Text("OR")
                            .font(.caption)
                            .foregroundColor(.gray)
                        Rectangle()
                            .fill(Color.gray.opacity(0.3))
                            .frame(height: 1)
                    }
                    .padding(.vertical, 8)
                    
                    // Manual entry toggle
                    Button(action: { showManualEntry.toggle() }) {
                        HStack {
                            Image(systemName: showManualEntry ? "chevron.up" : "chevron.down")
                            Text("Enter Whisper ID manually")
                                .font(.subheadline)
                        }
                        .foregroundColor(.gray)
                    }
                    
                    if showManualEntry {
                        // Whisper ID input
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Whisper ID")
                                .font(.headline)
                                .foregroundColor(.white)
                            
                            TextField("WSP-XXXX-XXXX-XXXX", text: $whisperId)
                                .textFieldStyle(.plain)
                                .padding()
                                .background(Color.gray.opacity(0.2))
                                .cornerRadius(10)
                                .foregroundColor(.white)
                                .autocapitalization(.allCharacters)
                                .onChange(of: whisperId) { _, newValue in
                                    whisperId = newValue.uppercased()
                                }
                            
                            if !whisperId.isEmpty && !isValidWhisperId {
                                Text("Invalid format. Expected: WSP-XXXX-XXXX-XXXX")
                                    .font(.caption)
                                    .foregroundColor(.red)
                            }
                        }
                        
                        // Nickname input (optional)
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Nickname (optional)")
                                .font(.headline)
                                .foregroundColor(.white)
                            
                            TextField("Enter a nickname", text: $nickname)
                                .textFieldStyle(.plain)
                                .padding()
                                .background(Color.gray.opacity(0.2))
                                .cornerRadius(10)
                                .foregroundColor(.white)
                        }
                        
                        // Warning about manual entry
                        HStack {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)
                            Text("Manual entry requires scanning QR later to enable messaging")
                                .font(.caption)
                                .foregroundColor(.orange)
                        }
                        .padding()
                        .background(Color.orange.opacity(0.1))
                        .cornerRadius(8)
                    }
                    
                    Spacer()
                    
                    // Add button (for manual entry)
                    if showManualEntry {
                        Button(action: addContactManually) {
                            if viewModel.isLoading {
                                ProgressView()
                                    .tint(.white)
                            } else {
                                Text("Add Contact")
                                    .font(.headline)
                            }
                        }
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(
                            isValidWhisperId ?
                            LinearGradient(
                                colors: [.gray.opacity(0.6), .gray.opacity(0.8)],
                                startPoint: .leading,
                                endPoint: .trailing
                            ) :
                            LinearGradient(
                                colors: [.gray.opacity(0.3), .gray.opacity(0.3)],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .cornerRadius(12)
                        .disabled(!isValidWhisperId || viewModel.isLoading)
                    }
                }
                .padding()
            }
            .navigationTitle("Add Contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .sheet(isPresented: $showQRScanner) {
                QRScannerView { result in
                    handleQRScanResult(result)
                    showQRScanner = false
                }
            }
            .alert("Notice", isPresented: .constant(viewModel.error != nil && !viewModel.error!.contains("Error"))) {
                Button("OK") { 
                    let wasSuccess = viewModel.error?.contains("added") == true
                    viewModel.error = nil
                    if wasSuccess { dismiss() }
                }
            } message: {
                Text(viewModel.error ?? "")
            }
            .alert("Error", isPresented: .constant(viewModel.error != nil && viewModel.error!.contains("Error"))) {
                Button("OK") { viewModel.error = nil }
            } message: {
                Text(viewModel.error ?? "")
            }
        }
    }
    
    private func handleQRScanResult(_ result: String) {
        // Parse QR code: whisper2://add?id=WSP-XXXX-XXXX-XXXX&key=base64pubkey
        guard let url = URL(string: result),
              url.scheme == "whisper2",
              url.host == "add",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            viewModel.error = "Invalid QR code format"
            return
        }
        
        guard let idItem = queryItems.first(where: { $0.name == "id" }),
              let scannedId = idItem.value,
              scannedId.isValidWhisperId else {
            viewModel.error = "QR code doesn't contain valid Whisper ID"
            return
        }
        
        guard let keyItem = queryItems.first(where: { $0.name == "key" }),
              let keyBase64 = keyItem.value,
              let publicKey = Data(base64Encoded: keyBase64),
              publicKey.count == 32 else {
            viewModel.error = "QR code doesn't contain valid public key"
            return
        }
        
        // Add contact with full info
        viewModel.addContact(
            whisperId: scannedId,
            encPublicKey: publicKey,
            nickname: nil
        )
        
        if viewModel.error == nil {
            dismiss()
        }
    }
    
    private func addContactManually() {
        viewModel.addContactWithoutKey(
            whisperId: whisperId,
            nickname: nickname.isEmpty ? nil : nickname
        )
    }
}

// MARK: - QR Scanner View

struct QRScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    
    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onScan = onScan
        return controller
    }
    
    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {}
}

class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    var captureSession: AVCaptureSession?
    var previewLayer: AVCaptureVideoPreviewLayer?
    var onScan: ((String) -> Void)?
    private var galleryButton: UIButton?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        DispatchQueue.global(qos: .background).async { [weak self] in
            self?.captureSession?.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    private func setupCamera() {
        captureSession = AVCaptureSession()

        guard let videoCaptureDevice = AVCaptureDevice.default(for: .video),
              let videoInput = try? AVCaptureDeviceInput(device: videoCaptureDevice),
              let captureSession = captureSession,
              captureSession.canAddInput(videoInput) else {
            showCameraError()
            return
        }

        captureSession.addInput(videoInput)

        let metadataOutput = AVCaptureMetadataOutput()

        guard captureSession.canAddOutput(metadataOutput) else {
            showCameraError()
            return
        }

        captureSession.addOutput(metadataOutput)
        metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        metadataOutput.metadataObjectTypes = [.qr]

        previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
        previewLayer?.frame = view.layer.bounds
        previewLayer?.videoGravity = .resizeAspectFill

        if let previewLayer = previewLayer {
            view.layer.addSublayer(previewLayer)
        }

        // Add overlay
        addScanOverlay()
    }

    private func addScanOverlay() {
        let overlayView = UIView(frame: view.bounds)
        overlayView.backgroundColor = UIColor.black.withAlphaComponent(0.5)

        // Cut out center square
        let scanSize: CGFloat = 250
        let scanRect = CGRect(
            x: (view.bounds.width - scanSize) / 2,
            y: (view.bounds.height - scanSize) / 2,
            width: scanSize,
            height: scanSize
        )

        let path = UIBezierPath(rect: view.bounds)
        path.append(UIBezierPath(roundedRect: scanRect, cornerRadius: 12).reversing())

        let maskLayer = CAShapeLayer()
        maskLayer.path = path.cgPath
        overlayView.layer.mask = maskLayer

        view.addSubview(overlayView)

        // Add border to scan area
        let borderView = UIView(frame: scanRect)
        borderView.layer.borderColor = UIColor.white.cgColor
        borderView.layer.borderWidth = 2
        borderView.layer.cornerRadius = 12
        borderView.backgroundColor = .clear
        view.addSubview(borderView)

        // Add instruction label
        let label = UILabel()
        label.text = "Scan Whisper QR Code"
        label.textColor = .white
        label.font = .systemFont(ofSize: 16, weight: .medium)
        label.textAlignment = .center
        label.frame = CGRect(x: 0, y: scanRect.maxY + 20, width: view.bounds.width, height: 30)
        view.addSubview(label)

        // Add gallery button - position at bottom with safe area
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "photo.on.rectangle"), for: .normal)
        button.setTitle(" Pick from Gallery", for: .normal)
        button.tintColor = .white
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.backgroundColor = UIColor.systemBlue.withAlphaComponent(0.9)
        button.layer.cornerRadius = 12
        let safeBottom = view.safeAreaInsets.bottom > 0 ? view.safeAreaInsets.bottom : 20
        button.frame = CGRect(x: 40, y: view.bounds.height - 70 - safeBottom, width: view.bounds.width - 80, height: 50)
        button.addTarget(self, action: #selector(openGallery), for: .touchUpInside)
        view.addSubview(button)
        galleryButton = button
    }

    @objc private func openGallery() {
        captureSession?.stopRunning()
        let picker = UIImagePickerController()
        picker.delegate = self
        picker.sourceType = .photoLibrary
        picker.allowsEditing = false
        present(picker, animated: true)
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
        picker.dismiss(animated: true)

        guard let image = info[.originalImage] as? UIImage,
              let cgImage = image.cgImage else {
            resumeScanning()
            return
        }

        // Use Vision framework to detect QR codes
        let request = VNDetectBarcodesRequest { [weak self] request, error in
            guard let results = request.results as? [VNBarcodeObservation],
                  let qrCode = results.first(where: { $0.symbology == .qr }),
                  let payload = qrCode.payloadStringValue else {
                DispatchQueue.main.async {
                    self?.showNoQRFoundAlert()
                }
                return
            }

            DispatchQueue.main.async {
                AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
                self?.onScan?(payload)
            }
        }

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        DispatchQueue.global(qos: .userInitiated).async {
            try? handler.perform([request])
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        resumeScanning()
    }

    private func resumeScanning() {
        DispatchQueue.global(qos: .background).async { [weak self] in
            self?.captureSession?.startRunning()
        }
    }

    private func showNoQRFoundAlert() {
        let alert = UIAlertController(
            title: "No QR Code Found",
            message: "Could not find a QR code in the selected image.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.resumeScanning()
        })
        present(alert, animated: true)
    }

    private func showCameraError() {
        let label = UILabel(frame: view.bounds)
        label.text = "Camera access required\nGo to Settings > Privacy > Camera"
        label.textColor = .white
        label.numberOfLines = 0
        label.textAlignment = .center
        view.addSubview(label)

        // Still add gallery button even if camera fails
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "photo.on.rectangle"), for: .normal)
        button.setTitle(" Pick from Gallery", for: .normal)
        button.tintColor = .white
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        button.backgroundColor = UIColor.blue.withAlphaComponent(0.8)
        button.layer.cornerRadius = 12
        button.frame = CGRect(x: 40, y: view.bounds.height - 120, width: view.bounds.width - 80, height: 50)
        button.addTarget(self, action: #selector(openGallery), for: .touchUpInside)
        view.addSubview(button)
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              metadataObject.type == .qr,
              let stringValue = metadataObject.stringValue else {
            return
        }

        // Vibrate on scan
        AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))

        captureSession?.stopRunning()
        onScan?(stringValue)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds

        // Reposition gallery button with correct safe area
        if let button = galleryButton {
            let safeBottom = view.safeAreaInsets.bottom > 0 ? view.safeAreaInsets.bottom + 20 : 40
            button.frame = CGRect(x: 40, y: view.bounds.height - 60 - safeBottom, width: view.bounds.width - 80, height: 50)
        }
    }
}

#Preview {
    AddContactView(viewModel: ContactsViewModel())
}
