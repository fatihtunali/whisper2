# Whisper2 iOS - Xcode Project Setup

This document explains how to create and configure the Xcode project for Whisper2 iOS.

## Creating the Xcode Project

### Option 1: Create New Project in Xcode

1. Open Xcode
2. File > New > Project
3. Select "App" under iOS
4. Configure:
   - Product Name: `Whisper2`
   - Team: Your development team
   - Organization Identifier: `com.whisper2`
   - Bundle Identifier: `com.whisper2.app`
   - Interface: SwiftUI
   - Language: Swift
   - Storage: SwiftData
   - Include Tests: Yes

5. Save to: `ios/Whisper2/`

6. After creation, delete the auto-generated files:
   - `ContentView.swift`
   - `Whisper2App.swift` (will use our version)
   - `Item.swift` (if created)

7. Add existing files to project:
   - Drag `App/`, `Core/`, `Models/`, `Services/`, `Views/` folders into Xcode
   - Select "Create groups" and check "Copy items if needed"

### Option 2: Using Swift Package (Recommended)

The `Package.swift` is already at `ios/Whisper2/`:

```swift
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
        // TweetNaCl - Pure Swift NaCl implementation (server-compatible crypto)
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
```

### Adding TweetNaCl via Xcode (Alternative)

If not using Package.swift, add TweetNaCl directly in Xcode:

1. File > Add Package Dependencies
2. Enter URL: `https://github.com/nicklockwood/TweetNaClx.git`
3. Select version: 1.0.0 or later
4. Add to target: Whisper2

## Project Configuration

### 1. General Settings

In Xcode, select the project > Whisper2 target > General:

- **Display Name**: Whisper2
- **Bundle Identifier**: com.whisper2.app
- **Version**: 1.0.0
- **Build**: 1
- **Minimum Deployments**: iOS 17.0
- **Device Orientation**: Portrait, Landscape Left, Landscape Right

### 2. Signing & Capabilities

Add these capabilities in Xcode (Signing & Capabilities tab):

1. **Push Notifications**
   - Required for remote notifications

2. **Background Modes** (check these):
   - Audio, AirPlay, and Picture in Picture
   - Voice over IP
   - Background fetch
   - Remote notifications

3. **Associated Domains** (add these):
   - `applinks:whisper2.aiakademiturkiye.com`
   - `webcredentials:whisper2.aiakademiturkiye.com`

4. **Keychain Sharing** (add groups):
   - `$(AppIdentifierPrefix)com.whisper2.app`
   - `$(AppIdentifierPrefix)com.whisper2.app.share`
   - `$(AppIdentifierPrefix)com.whisper2.app.notification`

5. **App Groups** (add group):
   - `group.com.whisper2.app`

6. **Data Protection**:
   - Complete Until First User Authentication

### 3. Build Settings

Key build settings to configure:

```
PRODUCT_BUNDLE_IDENTIFIER = com.whisper2.app
DEVELOPMENT_TEAM = YOUR_TEAM_ID
CODE_SIGN_STYLE = Automatic
SWIFT_VERSION = 5.0
IPHONEOS_DEPLOYMENT_TARGET = 17.0
INFOPLIST_FILE = Whisper2/Info.plist
CODE_SIGN_ENTITLEMENTS = Whisper2/Whisper2.entitlements
ENABLE_USER_SCRIPT_SANDBOXING = NO (if using build scripts)
```

### 4. Info.plist Location

Make sure Build Settings > Packaging > Info.plist File points to:
```
Whisper2/Info.plist
```

### 5. Entitlements Location

Make sure Build Settings > Signing > Code Signing Entitlements points to:
```
Whisper2/Whisper2.entitlements
```

## Required Frameworks

Add these frameworks in Build Phases > Link Binary With Libraries:

- Foundation.framework
- UIKit.framework
- SwiftUI.framework
- SwiftData.framework
- Security.framework
- PushKit.framework
- UserNotifications.framework
- AVFoundation.framework
- WebRTC.framework (for calls - add via CocoaPods/SPM)

## Third-Party Dependencies

### Using Swift Package Manager (SPM)

Add these packages (File > Add Package Dependencies):

1. **TweetNacl** (for cryptography)
   - URL: https://github.com/nicklockwood/TweetNacl

2. **WebRTC** (for calls)
   - URL: https://github.com/nicklockwood/WebRTCiOS (or official Google WebRTC)

### Using CocoaPods (Alternative)

Create `Podfile`:

```ruby
platform :ios, '17.0'
use_frameworks!

target 'Whisper2' do
  pod 'TweetNacl'
  pod 'GoogleWebRTC'
end

target 'Whisper2Tests' do
  inherit! :search_paths
end
```

Then run:
```bash
cd ios/Whisper2
pod install
```

## Project Structure

After setup, your project structure should look like:

```
ios/
├── Whisper2/
│   ├── Whisper2.xcodeproj/      # Xcode project (created by Xcode)
│   ├── Whisper2/
│   │   ├── App/
│   │   │   ├── Whisper2App.swift
│   │   │   ├── AppEnvironment.swift
│   │   │   └── AppCoordinator.swift
│   │   ├── Core/
│   │   │   ├── Constants.swift
│   │   │   ├── Errors.swift
│   │   │   ├── Logger.swift
│   │   │   └── Utils/
│   │   ├── Models/
│   │   ├── Services/
│   │   ├── Views/
│   │   ├── Resources/
│   │   │   └── Assets.xcassets/
│   │   ├── Info.plist
│   │   └── Whisper2.entitlements
│   └── Whisper2Tests/
└── XCODE_PROJECT_SETUP.md
```

## Running the App

1. Open `Whisper2.xcodeproj` in Xcode
2. Select your development team in Signing & Capabilities
3. Select an iOS 17+ simulator or device
4. Press Cmd+R to build and run

## Common Issues

### "Signing for Whisper2 requires a development team"
- Select your team in Signing & Capabilities

### "No such module 'SwiftData'"
- Ensure minimum deployment target is iOS 17.0

### Push notifications not working
- Ensure Push Notifications capability is added
- Ensure entitlements file is properly linked
- For production: change aps-environment from "development" to "production"

### VoIP push not working
- Ensure Background Modes > Voice over IP is checked
- PushKit requires a physical device (not simulator)
- VoIP certificate must be configured in Apple Developer Portal

## Testing

Run tests with:
```bash
xcodebuild test -scheme Whisper2 -destination 'platform=iOS Simulator,name=iPhone 15'
```

Or in Xcode: Cmd+U
