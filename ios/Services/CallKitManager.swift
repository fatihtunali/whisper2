import Foundation
import UIKit
import CallKit
import AVFoundation

/// Delegate protocol for CallKit events
protocol CallKitManagerDelegate: AnyObject {
    func callKitDidStartCall(callId: String)
    func callKitDidAnswerCall(callId: String)
    func callKitDidEndCall(callId: String)
    func callKitDidMuteCall(callId: String, muted: Bool)
    func callKitDidHoldCall(callId: String, onHold: Bool)
    func callKitAudioSessionDidActivate()
    func callKitAudioSessionDidDeactivate()
}

/// Manager for CallKit integration - provides native iOS call UI
final class CallKitManager: NSObject {
    weak var delegate: CallKitManagerDelegate?

    private let provider: CXProvider
    private let callController = CXCallController()

    // Map callId string to UUID
    private var callUUIDs: [String: UUID] = [:]

    override init() {
        let config = CXProviderConfiguration(localizedName: "Whisper2")
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = true

        // Set icon
        if let iconImage = UIImage(named: "CallKitIcon") {
            config.iconTemplateImageData = iconImage.pngData()
        }

        provider = CXProvider(configuration: config)

        super.init()

        provider.setDelegate(self, queue: nil)
    }

    deinit {
        provider.invalidate()
    }

    // MARK: - Public API

    /// Report an incoming call to the system
    func reportIncomingCall(callId: String, handle: String, displayName: String?, hasVideo: Bool) {
        let uuid = UUID()
        callUUIDs[callId] = uuid

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = displayName ?? handle
        update.hasVideo = hasVideo
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false

        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                print("Failed to report incoming call: \(error)")
                self.callUUIDs.removeValue(forKey: callId)
            }
        }
    }

    /// Start an outgoing call
    func startOutgoingCall(callId: String, handle: String, displayName: String?, hasVideo: Bool) {
        let uuid = UUID()
        callUUIDs[callId] = uuid

        let cxHandle = CXHandle(type: .generic, value: handle)
        let startCallAction = CXStartCallAction(call: uuid, handle: cxHandle)
        startCallAction.isVideo = hasVideo
        startCallAction.contactIdentifier = displayName

        let transaction = CXTransaction(action: startCallAction)

        callController.request(transaction) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                print("Failed to start outgoing call: \(error)")
                self.callUUIDs.removeValue(forKey: callId)
            } else {
                // Update call with display name so CallKit shows it
                let update = CXCallUpdate()
                update.remoteHandle = cxHandle
                update.localizedCallerName = displayName ?? handle
                update.hasVideo = hasVideo
                self.provider.reportCall(with: uuid, updated: update)

                // Report started connecting immediately to show CallKit UI
                self.provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
                print("CallKit: Outgoing call started, reported connecting")
            }
        }
    }

    /// Report that call has connected
    func reportCallConnected(callId: String) {
        guard let uuid = callUUIDs[callId] else { return }
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
    }

    /// Report that outgoing call started connecting
    func reportOutgoingCallStartedConnecting(callId: String) {
        guard let uuid = callUUIDs[callId] else { return }
        provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
    }

    /// End a call
    func endCall(callId: String) {
        guard let uuid = callUUIDs[callId] else { return }

        let endCallAction = CXEndCallAction(call: uuid)
        let transaction = CXTransaction(action: endCallAction)

        callController.request(transaction) { error in
            if let error = error {
                print("Failed to end call: \(error)")
            }
            self.callUUIDs.removeValue(forKey: callId)
        }
    }

    /// Update call info
    func updateCall(callId: String, displayName: String) {
        guard let uuid = callUUIDs[callId] else { return }

        let update = CXCallUpdate()
        update.localizedCallerName = displayName

        provider.reportCall(with: uuid, updated: update)
    }

    /// Set mute state
    func setMuted(callId: String, muted: Bool) {
        guard let uuid = callUUIDs[callId] else { return }

        let muteAction = CXSetMutedCallAction(call: uuid, muted: muted)
        let transaction = CXTransaction(action: muteAction)

        callController.request(transaction) { error in
            if let error = error {
                print("Failed to mute call: \(error)")
            }
        }
    }

    // MARK: - Helpers

    private func getCallId(for uuid: UUID) -> String? {
        return callUUIDs.first(where: { $0.value == uuid })?.key
    }
}

// MARK: - CXProviderDelegate

extension CallKitManager: CXProviderDelegate {

    func providerDidReset(_ provider: CXProvider) {
        // End all calls
        for callId in callUUIDs.keys {
            delegate?.callKitDidEndCall(callId: callId)
        }
        callUUIDs.removeAll()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        guard let callId = getCallId(for: action.callUUID) else {
            action.fail()
            return
        }

        // Configure audio session for outgoing call
        configureAudioSession()

        // Notify delegate to start WebRTC connection
        delegate?.callKitDidStartCall(callId: callId)

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard let callId = getCallId(for: action.callUUID) else {
            action.fail()
            return
        }

        // Configure audio session
        configureAudioSession()

        // Notify delegate
        delegate?.callKitDidAnswerCall(callId: callId)

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        guard let callId = getCallId(for: action.callUUID) else {
            action.fail()
            return
        }

        // Notify delegate
        delegate?.callKitDidEndCall(callId: callId)

        callUUIDs.removeValue(forKey: callId)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        guard let callId = getCallId(for: action.callUUID) else {
            action.fail()
            return
        }

        delegate?.callKitDidMuteCall(callId: callId, muted: action.isMuted)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        guard let callId = getCallId(for: action.callUUID) else {
            action.fail()
            return
        }

        delegate?.callKitDidHoldCall(callId: callId, onHold: action.isOnHold)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        // Audio session activated - WebRTC can now use audio
        print("CallKit audio session activated")
        delegate?.callKitAudioSessionDidActivate()
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        // Audio session deactivated
        print("CallKit audio session deactivated")
        delegate?.callKitAudioSessionDidDeactivate()
    }

    // MARK: - Audio Configuration

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP, .defaultToSpeaker])
            try session.setActive(true)
        } catch {
            print("Failed to configure audio session: \(error)")
        }
    }
}
