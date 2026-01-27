import SwiftUI
import CoreImage.CIFilterBuiltins

/// Profile view with QR code generation
struct ProfileView: View {
    @StateObject private var viewModel = SettingsViewModel()
    @State private var showCopiedAlert = false
    @State private var qrCodeImage: UIImage?
    
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 24) {
                    // Avatar
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [.blue, .purple],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 100, height: 100)
                        .overlay(
                            Image(systemName: "person.fill")
                                .font(.system(size: 40))
                                .foregroundColor(.white)
                        )
                        .padding(.top, 20)
                    
                    // Whisper ID
                    VStack(spacing: 8) {
                        Text("Your Whisper ID")
                            .font(.headline)
                            .foregroundColor(.gray)
                        
                        HStack {
                            Text(viewModel.whisperId ?? "Not registered")
                                .font(.system(.title3, design: .monospaced))
                                .foregroundColor(.white)
                            
                            Button(action: copyWhisperId) {
                                Image(systemName: "doc.on.doc")
                                    .foregroundColor(.blue)
                            }
                        }
                        .padding()
                        .background(Color.gray.opacity(0.2))
                        .cornerRadius(12)
                    }
                    
                    // QR Code
                    VStack(spacing: 8) {
                        Text("Share via QR Code")
                            .font(.headline)
                            .foregroundColor(.gray)
                        
                        Text("Others can scan this to add you")
                            .font(.caption)
                            .foregroundColor(.gray.opacity(0.7))
                        
                        if let qrImage = qrCodeImage {
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 200, height: 200)
                                .background(Color.white)
                                .cornerRadius(12)
                        } else {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color.white)
                                .frame(width: 200, height: 200)
                                .overlay(
                                    ProgressView()
                                        .tint(.black)
                                )
                        }
                    }
                    .padding(.top, 20)
                    
                    // Info
                    VStack(spacing: 12) {
                        InfoRow(title: "Registered", value: viewModel.registeredDate ?? "Unknown")
                        InfoRow(title: "Device ID", value: String(viewModel.deviceId?.prefix(8) ?? "Unknown") + "...")
                    }
                    .padding()
                    .background(Color.gray.opacity(0.15))
                    .cornerRadius(12)
                    .padding(.horizontal)
                    
                    Spacer(minLength: 40)
                }
            }
        }
        .navigationTitle("Profile")
        .onAppear {
            generateQRCode()
        }
        .alert("Copied", isPresented: $showCopiedAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Whisper ID copied to clipboard")
        }
    }
    
    private func copyWhisperId() {
        viewModel.copyWhisperId()
        showCopiedAlert = true
    }
    
    private func generateQRCode() {
        guard let whisperId = viewModel.whisperId else { return }
        
        // Get user's public key for QR code
        let keychain = KeychainService.shared
        guard let encPublicKey = keychain.getData(forKey: Constants.StorageKey.encPublicKey) else { return }
        
        // QR data format: whisper2://add?id=WSP-XXXX-XXXX-XXXX&key=base64pubkey
        let qrData = "whisper2://add?id=\(whisperId)&key=\(encPublicKey.base64EncodedString())"
        
        filter.message = Data(qrData.utf8)
        filter.correctionLevel = "M"
        
        guard let outputImage = filter.outputImage else { return }
        
        // Scale up the QR code
        let scale = 200.0 / outputImage.extent.width
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        
        if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
            qrCodeImage = UIImage(cgImage: cgImage)
        }
    }
}

struct InfoRow: View {
    let title: String
    let value: String
    
    var body: some View {
        HStack {
            Text(title)
                .foregroundColor(.gray)
            Spacer()
            Text(value)
                .foregroundColor(.white)
        }
    }
}

#Preview {
    NavigationStack {
        ProfileView()
    }
}
