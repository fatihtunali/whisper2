// swift-tools-version:5.9
import PackageDescription

// NOTE: WebRTC is not available via SPM from Google.
// For WebRTC support, you have two options:
// 1. Use CocoaPods: pod 'GoogleWebRTC' (recommended)
// 2. Use the community SPM package: https://github.com/nicklockwood/WebRTC (may lag behind)
//
// After adding WebRTC, uncomment the dependency in CallService.swift

let package = Package(
    name: "Whisper2",
    platforms: [
        .iOS(.v17)
    ],
    products: [
        .library(
            name: "Whisper2",
            targets: ["Whisper2"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/bitmark-inc/tweetnacl-swiftwrap.git", from: "1.1.0"),
    ],
    targets: [
        .target(
            name: "Whisper2",
            dependencies: [
                .product(name: "TweetNacl", package: "tweetnacl-swiftwrap"),
            ],
            path: ".",
            exclude: ["Package.swift", "Info.plist"],
            sources: [
                "App",
                "Core",
                "Crypto",
                "Models",
                "Protocol",
                "Services",
                "ViewModels",
                "Views"
            ]
        ),
    ]
)
