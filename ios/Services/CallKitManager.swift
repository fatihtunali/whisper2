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
/// Key principle: Same UUID must be used throughout call lifecycle
final class CallKitManager: NSObject {
    weak var delegate: CallKitManagerDelegate?

    private let provider: CXProvider
    private let callController = CXCallController()

    // Map UUID to callId for reverse lookup
    private var uuidToCallId: [UUID: String] = [:]

    private static func createProviderConfiguration() -> CXProviderConfiguration {
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

        return config
    }

    override init() {
        provider = CXProvider(configuration: Self.createProviderConfiguration())

        super.init()

        provider.setDelegate(self, queue: nil)
    }

    deinit {
        provider.invalidate()
    }

    /// Convert callId string to UUID
    /// CallId is already a valid UUID string (e.g., "c03cc225-aa9a-4325-81b4-d645d0eea495")
    private func uuid(from callId: String) -> UUID {
        guard let uuid = UUID(uuidString: callId) else {
            // Fallback: generate new UUID if callId is not valid UUID format
            print("WARNING: callId '\(callId)' is not a valid UUID, generating new one")
            return UUID()
        }
        return uuid
    }

    // MARK: - Public API

    /// Report an incoming call to the system
    func reportIncomingCall(callId: String, handle: String, displayName: String?, hasVideo: Bool) {
        let callUUID = uuid(from: callId)
        uuidToCallId[callUUID] = callId

        print("CallKitManager.reportIncomingCall - callId: \(callId), UUID: \(callUUID)")

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = displayName ?? handle
        update.hasVideo = hasVideo
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false

        provider.reportNewIncomingCall(with: callUUID, update: update) { [weak self] error in
            if let error = error {
                print("FAILED to report incoming call: \(error)")
                self?.uuidToCallId.removeValue(forKey: callUUID)
            } else {
                print("SUCCESS: Incoming call reported - callId: \(callId), UUID: \(callUUID)")
            }
        }
    }

    /// Start an outgoing call
    func startOutgoingCall(callId: String, handle: String, displayName: String?, hasVideo: Bool) {
        let callUUID = uuid(from: callId)
        uuidToCallId[callUUID] = callId

        print("CallKitManager.startOutgoingCall - callId: \(callId), UUID: \(callUUID)")

        let cxHandle = CXHandle(type: .generic, value: handle)
        let startCallAction = CXStartCallAction(call: callUUID, handle: cxHandle)
        startCallAction.isVideo = hasVideo
        startCallAction.contactIdentifier = displayName

        let transaction = CXTransaction(action: startCallAction)

        callController.request(transaction) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                print("Failed to start outgoing call: \(error)")
                self.uuidToCallId.removeValue(forKey: callUUID)
            } else {
                // Update call with display name so CallKit shows it
                let update = CXCallUpdate()
                update.remoteHandle = cxHandle
                update.localizedCallerName = displayName ?? handle
                update.hasVideo = hasVideo
                self.provider.reportCall(with: callUUID, updated: update)

                // Report started connecting immediately to show CallKit UI
                self.provider.reportOutgoingCall(with: callUUID, startedConnectingAt: Date())
                print("CallKit: Outgoing call started - callId: \(callId), UUID: \(callUUID)")
            }
        }
    }

    /// Report that call has connected
    func reportCallConnected(callId: String) {
        let callUUID = uuid(from: callId)
        print("CallKit: Call connected - callId: \(callId), UUID: \(callUUID)")
        provider.reportOutgoingCall(with: callUUID, connectedAt: Date())
    }

    /// Report that outgoing call started connecting
    func reportOutgoingCallStartedConnecting(callId: String) {
        let callUUID = uuid(from: callId)
        provider.reportOutgoingCall(with: callUUID, startedConnectingAt: Date())
    }

    /// End a call (local user initiated)
    func endCall(callId: String) {
        let callUUID = uuid(from: callId)
        print("CallKit.endCall - callId: \(callId), UUID: \(callUUID)")

        let endCallAction = CXEndCallAction(call: callUUID)
        let transaction = CXTransaction(action: endCallAction)

        callController.request(transaction) { [weak self] error in
            if let error = error {
                print("Failed to end call via transaction: \(error)")
                // Fallback: Report call ended directly to provider
                self?.provider.reportCall(with: callUUID, endedAt: Date(), reason: .remoteEnded)
            } else {
                print("Call ended successfully - callId: \(callId)")
            }
            self?.uuidToCallId.removeValue(forKey: callUUID)
        }
    }

    /// Report that the remote party ended the call
    func reportCallEnded(callId: String, reason: CXCallEndedReason = .remoteEnded) {
        let callUUID = uuid(from: callId)
        print("CallKit.reportCallEnded - callId: \(callId), UUID: \(callUUID), reason: \(reason.rawValue)")

        // Report the call ended to the provider (not through transaction)
        provider.reportCall(with: callUUID, endedAt: Date(), reason: reason)
        uuidToCallId.removeValue(forKey: callUUID)
    }

    /// Force end all calls (cleanup)
    func endAllCalls() {
        print("endAllCalls: Ending \(uuidToCallId.count) call(s)")
        for (callUUID, callId) in uuidToCallId {
            print("  Ending call \(callId) with UUID \(callUUID)")
            provider.reportCall(with: callUUID, endedAt: Date(), reason: .remoteEnded)
        }
        uuidToCallId.removeAll()
    }

    /// Update call info
    func updateCall(callId: String, displayName: String) {
        let callUUID = uuid(from: callId)

        // Only update if we're tracking this call
        guard uuidToCallId[callUUID] != nil else { return }

        let update = CXCallUpdate()
        update.localizedCallerName = displayName

        provider.reportCall(with: callUUID, updated: update)
    }

    /// Set mute state
    func setMuted(callId: String, muted: Bool) {
        let callUUID = uuid(from: callId)

        // Only mute if we're tracking this call
        guard uuidToCallId[callUUID] != nil else { return }

        let muteAction = CXSetMutedCallAction(call: callUUID, muted: muted)
        let transaction = CXTransaction(action: muteAction)

        callController.request(transaction) { error in
            if let error = error {
                print("Failed to mute call: \(error)")
            }
        }
    }

    // MARK: - Helpers

    private func getCallId(for uuid: UUID) -> String? {
        return uuidToCallId[uuid]
    }
}

// MARK: - CXProviderDelegate

extension CallKitManager: CXProviderDelegate {

    func providerDidReset(_ provider: CXProvider) {
        // End all calls
        for (_, callId) in uuidToCallId {
            delegate?.callKitDidEndCall(callId: callId)
        }
        uuidToCallId.removeAll()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        guard let callId = getCallId(for: action.callUUID) else {
            action.fail()
            return
        }

        // Do NOT configure audio session here - CallKit manages it
        // We'll get the didActivate callback when audio is ready

        // Notify delegate to start WebRTC connection
        delegate?.callKitDidStartCall(callId: callId)

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard let callId = getCallId(for: action.callUUID) else {
            action.fail()
            return
        }

        // Do NOT configure audio session here - CallKit manages it
        // We'll get the didActivate callback when audio is ready

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

        uuidToCallId.removeValue(forKey: action.callUUID)
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

    // NOTE: Audio session is managed by CallKit
    // Do NOT manually configure AVAudioSession when using CallKit
    // Use RTCAudioSession with useManualAudio=true and wait for didActivate callback
}
