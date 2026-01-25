// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Whisper2",
    platforms: [
        .iOS(.v17)
    ],
    products: [
        .library(name: "Whisper2", targets: ["Whisper2"])
    ],
    dependencies: [
        // TweetNaCl - Pure Swift implementation of NaCl crypto
        // Provides XSalsa20-Poly1305 (Box/SecretBox) compatible with server's libsodium
        .package(url: "https://github.com/nicklockwood/TweetNaClx.git", from: "1.0.0"),
    ],
    targets: [
        .target(
            name: "Whisper2",
            dependencies: [
                .product(name: "TweetNaClx", package: "TweetNaClx"),
            ],
            path: ".",
            exclude: ["Tests", "Whisper2.xcodeproj", "Info.plist", "Whisper2.entitlements"]
        ),
        .testTarget(
            name: "Whisper2Tests",
            dependencies: ["Whisper2"],
            path: "Tests"
        )
    ]
)
