import SwiftUI
import UIKit

/// Service for managing user avatars
final class AvatarService: ObservableObject {
    static let shared = AvatarService()

    @Published var myAvatar: UIImage?

    private let fileManager = FileManager.default
    private let avatarDirectory: URL

    private init() {
        // Create avatars directory in documents
        let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        avatarDirectory = documentsPath.appendingPathComponent("avatars", isDirectory: true)

        // Create directory if needed
        if !fileManager.fileExists(atPath: avatarDirectory.path) {
            try? fileManager.createDirectory(at: avatarDirectory, withIntermediateDirectories: true)
        }

        // Load my avatar on init
        loadMyAvatar()
    }

    // MARK: - My Avatar

    /// Save the current user's avatar
    func saveMyAvatar(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.8) else { return }

        let url = avatarDirectory.appendingPathComponent("my_avatar.jpg")
        try? data.write(to: url)

        DispatchQueue.main.async {
            self.myAvatar = image
        }
    }

    /// Load the current user's avatar
    private func loadMyAvatar() {
        let url = avatarDirectory.appendingPathComponent("my_avatar.jpg")
        if let data = try? Data(contentsOf: url),
           let image = UIImage(data: data) {
            myAvatar = image
        }
    }

    /// Delete my avatar
    func deleteMyAvatar() {
        let url = avatarDirectory.appendingPathComponent("my_avatar.jpg")
        try? fileManager.removeItem(at: url)
        DispatchQueue.main.async {
            self.myAvatar = nil
        }
    }

    // MARK: - Contact Avatars

    /// Save avatar for a contact
    func saveContactAvatar(_ image: UIImage, for whisperId: String) {
        guard let data = image.jpegData(compressionQuality: 0.8) else { return }

        let safeId = whisperId.replacingOccurrences(of: "-", with: "_")
        let url = avatarDirectory.appendingPathComponent("contact_\(safeId).jpg")
        try? data.write(to: url)
    }

    /// Get avatar for a contact
    func getContactAvatar(for whisperId: String) -> UIImage? {
        let safeId = whisperId.replacingOccurrences(of: "-", with: "_")
        let url = avatarDirectory.appendingPathComponent("contact_\(safeId).jpg")

        if let data = try? Data(contentsOf: url),
           let image = UIImage(data: data) {
            return image
        }
        return nil
    }

    /// Delete contact avatar
    func deleteContactAvatar(for whisperId: String) {
        let safeId = whisperId.replacingOccurrences(of: "-", with: "_")
        let url = avatarDirectory.appendingPathComponent("contact_\(safeId).jpg")
        try? fileManager.removeItem(at: url)
    }

    /// Clear all avatars
    func clearAll() {
        try? fileManager.removeItem(at: avatarDirectory)
        try? fileManager.createDirectory(at: avatarDirectory, withIntermediateDirectories: true)
        DispatchQueue.main.async {
            self.myAvatar = nil
        }
    }
}

// MARK: - Avatar Image Picker

struct AvatarImagePicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) private var dismiss
    var sourceType: UIImagePickerController.SourceType = .photoLibrary

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = sourceType
        picker.delegate = context.coordinator
        picker.allowsEditing = true
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: AvatarImagePicker

        init(_ parent: AvatarImagePicker) {
            self.parent = parent
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let edited = info[.editedImage] as? UIImage {
                parent.image = edited
            } else if let original = info[.originalImage] as? UIImage {
                parent.image = original
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Avatar View Component

struct AvatarView: View {
    let image: UIImage?
    let name: String
    let size: CGFloat
    var showEditBadge: Bool = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if let image = image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [.blue, .purple],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: size, height: size)
                    .overlay(
                        Text(String(name.prefix(1)).uppercased())
                            .font(.system(size: size * 0.4, weight: .semibold))
                            .foregroundColor(.white)
                    )
            }

            if showEditBadge {
                Circle()
                    .fill(Color.blue)
                    .frame(width: size * 0.3, height: size * 0.3)
                    .overlay(
                        Image(systemName: "camera.fill")
                            .font(.system(size: size * 0.12))
                            .foregroundColor(.white)
                    )
                    .offset(x: -2, y: -2)
            }
        }
    }
}
