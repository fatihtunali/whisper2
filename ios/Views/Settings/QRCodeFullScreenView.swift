import SwiftUI
import UIKit
import CoreImage.CIFilterBuiltins
import Photos

/// Full-screen QR code view with share and save functionality
struct QRCodeFullScreenView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var qrCodeImage: UIImage?
    @State private var showShareSheet = false
    @State private var showSaveSuccess = false
    @State private var showSaveError = false
    @State private var errorMessage = ""

    let whisperId: String
    let encPublicKey: Data

    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    var body: some View {
        ZStack {
            // Dark background
            Color.black.ignoresSafeArea()

            VStack(spacing: 32) {
                Spacer()

                // Title
                Text("My QR Code")
                    .font(.title)
                    .fontWeight(.bold)
                    .foregroundColor(.white)

                Text("Scan to add me on Whisper2")
                    .font(.subheadline)
                    .foregroundColor(.gray)

                // Large QR Code
                if let qrImage = qrCodeImage {
                    Image(uiImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 280, height: 280)
                        .background(Color.white)
                        .cornerRadius(16)
                        .shadow(color: .white.opacity(0.1), radius: 20)
                } else {
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color.white)
                        .frame(width: 280, height: 280)
                        .overlay(
                            ProgressView()
                                .tint(.black)
                        )
                }

                // Whisper ID below QR
                Text(whisperId)
                    .font(.system(.body, design: .monospaced))
                    .foregroundColor(.white.opacity(0.8))
                    .padding(.top, 8)

                Spacer()

                // Action buttons
                HStack(spacing: 40) {
                    // Share button
                    Button(action: shareQRCode) {
                        VStack(spacing: 8) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 24))
                            Text("Share")
                                .font(.caption)
                        }
                        .foregroundColor(.white)
                        .frame(width: 80, height: 60)
                    }

                    // Save to Photos button
                    Button(action: saveToPhotos) {
                        VStack(spacing: 8) {
                            Image(systemName: "square.and.arrow.down")
                                .font(.system(size: 24))
                            Text("Save")
                                .font(.caption)
                        }
                        .foregroundColor(.white)
                        .frame(width: 80, height: 60)
                    }
                }
                .padding(.bottom, 40)
            }

            // Close button
            VStack {
                HStack {
                    Spacer()
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.white.opacity(0.7))
                    }
                    .padding()
                }
                Spacer()
            }
        }
        .onAppear {
            generateQRCode()
        }
        .sheet(isPresented: $showShareSheet) {
            if let qrImage = qrCodeImage {
                ShareSheet(items: [qrImage, "Scan this to add me on Whisper2"])
            }
        }
        .alert("Saved", isPresented: $showSaveSuccess) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("QR code saved to Photos")
        }
        .alert("Error", isPresented: $showSaveError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
    }

    private func generateQRCode() {
        // QR data format: whisper2://add?id=WSP-XXXX-XXXX-XXXX&key=base64pubkey
        let qrData = "whisper2://add?id=\(whisperId)&key=\(encPublicKey.base64EncodedString())"

        filter.message = Data(qrData.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return }

        // Scale up the QR code for high quality
        let scale = 280.0 / outputImage.extent.width
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
            qrCodeImage = UIImage(cgImage: cgImage)
        }
    }

    private func shareQRCode() {
        showShareSheet = true
    }

    private func saveToPhotos() {
        guard let qrImage = qrCodeImage else { return }

        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            DispatchQueue.main.async {
                if status == .authorized || status == .limited {
                    UIImageWriteToSavedPhotosAlbum(qrImage, nil, nil, nil)
                    showSaveSuccess = true
                } else {
                    errorMessage = "Please allow access to Photos in Settings to save the QR code."
                    showSaveError = true
                }
            }
        }
    }
}

// MARK: - Share Sheet

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

#Preview {
    QRCodeFullScreenView(
        whisperId: "WSP-ABCD-EFGH-IJKL",
        encPublicKey: Data(repeating: 0, count: 32)
    )
}
