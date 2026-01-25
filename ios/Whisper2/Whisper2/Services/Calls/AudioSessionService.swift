import Foundation
import AVFoundation
import WebRTC

// MARK: - Audio Route

/// Represents the current audio output route
enum AudioRoute {
    case earpiece
    case speaker
    case bluetooth
    case headphones
    case unknown
}

// MARK: - Audio Session Service Delegate

protocol AudioSessionServiceDelegate: AnyObject {
    /// Called when the audio route changes
    func audioSessionService(_ service: AudioSessionService, didChangeRoute route: AudioRoute)

    /// Called when audio is interrupted (e.g., phone call)
    func audioSessionService(_ service: AudioSessionService, wasInterrupted: Bool)
}

// MARK: - Audio Session Service

/// Service for managing audio routing during calls
final class AudioSessionService {

    // MARK: - Properties

    weak var delegate: AudioSessionServiceDelegate?

    private let audioSession = AVAudioSession.sharedInstance()
    private let rtcAudioSession = RTCAudioSession.sharedInstance()

    private(set) var isConfigured = false
    private(set) var isActive = false
    private(set) var currentRoute: AudioRoute = .earpiece

    /// Whether speaker is enabled
    private(set) var isSpeakerEnabled = false

    // MARK: - Initialization

    init() {
        setupNotifications()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Public API

    /// Configure the audio session for VoIP
    func configureForVoIP() throws {
        logger.debug("Configuring audio session for VoIP", category: .calls)

        rtcAudioSession.lockForConfiguration()
        defer { rtcAudioSession.unlockForConfiguration() }

        do {
            // Use WebRTC's audio session configuration
            rtcAudioSession.useManualAudio = true
            rtcAudioSession.isAudioEnabled = false

            // Configure AVAudioSession
            try audioSession.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetooth, .allowBluetoothA2DP, .duckOthers]
            )

            try audioSession.setPreferredSampleRate(48000)
            try audioSession.setPreferredIOBufferDuration(0.01) // 10ms

            isConfigured = true
            logger.info("Audio session configured for VoIP", category: .calls)

        } catch {
            logger.error(error, message: "Failed to configure audio session", category: .calls)
            throw CallError.webRTCFailed(reason: "Audio configuration failed: \(error.localizedDescription)")
        }
    }

    /// Activate the audio session
    func activateAudioSession() throws {
        guard isConfigured else {
            try configureForVoIP()
        }

        logger.debug("Activating audio session", category: .calls)

        do {
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
            rtcAudioSession.isAudioEnabled = true

            isActive = true
            updateCurrentRoute()

            logger.info("Audio session activated", category: .calls)

        } catch {
            logger.error(error, message: "Failed to activate audio session", category: .calls)
            throw CallError.webRTCFailed(reason: "Audio activation failed: \(error.localizedDescription)")
        }
    }

    /// Deactivate the audio session
    func deactivateAudioSession() {
        logger.debug("Deactivating audio session", category: .calls)

        rtcAudioSession.lockForConfiguration()
        defer { rtcAudioSession.unlockForConfiguration() }

        rtcAudioSession.isAudioEnabled = false

        do {
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            logger.warning("Failed to deactivate audio session: \(error.localizedDescription)", category: .calls)
        }

        isActive = false
        isConfigured = false
        isSpeakerEnabled = false

        logger.info("Audio session deactivated", category: .calls)
    }

    /// Enable or disable the speaker
    /// - Parameter enabled: Whether to use the speaker
    func setSpeakerEnabled(_ enabled: Bool) {
        logger.debug("Setting speaker enabled: \(enabled)", category: .calls)

        do {
            if enabled {
                try audioSession.overrideOutputAudioPort(.speaker)
            } else {
                try audioSession.overrideOutputAudioPort(.none)
            }

            isSpeakerEnabled = enabled
            updateCurrentRoute()

        } catch {
            logger.error(error, message: "Failed to set speaker", category: .calls)
        }
    }

    /// Toggle the speaker
    func toggleSpeaker() {
        setSpeakerEnabled(!isSpeakerEnabled)
    }

    /// Get available audio outputs
    func availableOutputs() -> [AVAudioSession.Port] {
        var outputs: [AVAudioSession.Port] = [.builtInSpeaker, .builtInReceiver]

        // Add available Bluetooth devices
        if let availableInputs = audioSession.availableInputs {
            for input in availableInputs {
                if input.portType == .bluetoothHFP || input.portType == .bluetoothA2DP {
                    outputs.append(input.portType)
                }
            }
        }

        // Check for wired headphones
        let currentOutputs = audioSession.currentRoute.outputs
        for output in currentOutputs {
            if output.portType == .headphones || output.portType == .headsetMic {
                outputs.append(output.portType)
            }
        }

        return outputs
    }

    // MARK: - Private Methods

    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        logger.debug("Audio route changed, reason: \(reason.rawValue)", category: .calls)

        switch reason {
        case .newDeviceAvailable:
            logger.info("New audio device available", category: .calls)
            updateCurrentRoute()

        case .oldDeviceUnavailable:
            logger.info("Audio device unavailable", category: .calls)
            updateCurrentRoute()

        case .categoryChange:
            logger.debug("Audio category changed", category: .calls)

        case .override:
            logger.debug("Audio route override", category: .calls)
            updateCurrentRoute()

        default:
            break
        }

        delegate?.audioSessionService(self, didChangeRoute: currentRoute)
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            logger.warning("Audio session interrupted", category: .calls)
            delegate?.audioSessionService(self, wasInterrupted: true)

        case .ended:
            logger.info("Audio session interruption ended", category: .calls)

            // Check if we should resume
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    try? activateAudioSession()
                }
            }

            delegate?.audioSessionService(self, wasInterrupted: false)

        @unknown default:
            break
        }
    }

    private func updateCurrentRoute() {
        let outputs = audioSession.currentRoute.outputs

        if outputs.isEmpty {
            currentRoute = .unknown
            return
        }

        let portType = outputs[0].portType

        switch portType {
        case .builtInReceiver:
            currentRoute = .earpiece

        case .builtInSpeaker:
            currentRoute = .speaker

        case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
            currentRoute = .bluetooth

        case .headphones, .headsetMic:
            currentRoute = .headphones

        default:
            currentRoute = .unknown
        }

        logger.debug("Current audio route: \(currentRoute)", category: .calls)
    }
}

// MARK: - Audio Route Description

extension AudioRoute: CustomStringConvertible {
    var description: String {
        switch self {
        case .earpiece: return "Earpiece"
        case .speaker: return "Speaker"
        case .bluetooth: return "Bluetooth"
        case .headphones: return "Headphones"
        case .unknown: return "Unknown"
        }
    }

    var iconName: String {
        switch self {
        case .earpiece: return "ear"
        case .speaker: return "speaker.wave.3.fill"
        case .bluetooth: return "headphones"
        case .headphones: return "headphones"
        case .unknown: return "speaker.slash"
        }
    }
}
