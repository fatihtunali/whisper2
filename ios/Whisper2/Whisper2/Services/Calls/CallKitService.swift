import Foundation
import CallKit
import AVFoundation
import UIKit

// MARK: - CallKit Service Delegate

protocol CallKitServiceDelegate: AnyObject {
    /// Called when user answers an incoming call via CallKit
    func callKitService(_ service: CallKitService, didAnswerCall uuid: UUID)

    /// Called when user ends a call via CallKit
    func callKitService(_ service: CallKitService, didEndCall uuid: UUID)

    /// Called when user mutes/unmutes via CallKit
    func callKitService(_ service: CallKitService, didMuteCall uuid: UUID, isMuted: Bool)

    /// Called when user puts call on hold via CallKit
    func callKitService(_ service: CallKitService, didHoldCall uuid: UUID, isOnHold: Bool)

    /// Called when the provider is reset (all calls should be ended)
    func callKitService(_ service: CallKitService, providerDidReset provider: Any)
}

// MARK: - CallKit Service

/// Service for integrating with iOS CallKit for native call UI
final class CallKitService: NSObject {

    // MARK: - Properties

    weak var delegate: CallKitServiceDelegate?

    private let provider: CXProvider
    private let callController: CXCallController

    /// Map of active call UUIDs to their handles
    private var activeCalls: [UUID: String] = [:]

    // MARK: - Initialization

    override init() {
        // Configure provider
        let config = CXProviderConfiguration()
        config.localizedName = "Whisper2"
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = true
        config.ringtoneSound = "ringtone.caf" // Custom ringtone if available

        // Configure icon
        if let iconImage = UIImage(named: "CallKitIcon") {
            config.iconTemplateImageData = iconImage.pngData()
        }

        provider = CXProvider(configuration: config)
        callController = CXCallController()

        super.init()

        provider.setDelegate(self, queue: nil)

        logger.debug("CallKit service initialized", category: .calls)
    }

    deinit {
        provider.invalidate()
    }

    // MARK: - Public API

    /// Report an incoming call to CallKit
    /// - Parameters:
    ///   - uuid: The call UUID
    ///   - handle: The caller's handle (WhisperID or display name)
    ///   - hasVideo: Whether this is a video call
    ///   - completion: Called with error if reporting failed
    func reportIncomingCall(
        uuid: UUID,
        handle: String,
        hasVideo: Bool = false,
        completion: @escaping (Error?) -> Void
    ) {
        logger.info("Reporting incoming call: \(uuid), from: \(handle)", category: .calls)

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = handle
        update.hasVideo = hasVideo
        update.supportsHolding = true
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false

        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            if let error = error {
                logger.error(error, message: "Failed to report incoming call", category: .calls)
                completion(error)
                return
            }

            self?.activeCalls[uuid] = handle
            logger.info("Incoming call reported successfully", category: .calls)
            completion(nil)
        }
    }

    /// Report an outgoing call starting
    /// - Parameters:
    ///   - uuid: The call UUID
    ///   - handle: The callee's handle
    func reportOutgoingCall(uuid: UUID, handle: String) {
        logger.info("Reporting outgoing call: \(uuid), to: \(handle)", category: .calls)

        activeCalls[uuid] = handle

        let handle = CXHandle(type: .generic, value: handle)
        let startCallAction = CXStartCallAction(call: uuid, handle: handle)
        startCallAction.isVideo = false

        let transaction = CXTransaction(action: startCallAction)
        callController.request(transaction) { error in
            if let error = error {
                logger.error(error, message: "Failed to start outgoing call", category: .calls)
                return
            }

            logger.debug("Outgoing call started", category: .calls)
        }
    }

    /// Report that an outgoing call is connecting
    /// - Parameter uuid: The call UUID
    func reportOutgoingCallConnecting(uuid: UUID) {
        logger.debug("Reporting outgoing call connecting: \(uuid)", category: .calls)
        provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
    }

    /// Report that an outgoing call has connected
    /// - Parameter uuid: The call UUID
    func reportOutgoingCallConnected(uuid: UUID) {
        logger.debug("Reporting outgoing call connected: \(uuid)", category: .calls)
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
    }

    /// Report that a call has ended
    /// - Parameters:
    ///   - uuid: The call UUID
    ///   - reason: The reason the call ended
    func reportCallEnded(uuid: UUID, reason: CXCallEndedReason = .remoteEnded) {
        logger.info("Reporting call ended: \(uuid), reason: \(reason.rawValue)", category: .calls)

        provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
        activeCalls.removeValue(forKey: uuid)
    }

    /// End a call via CallKit
    /// - Parameter uuid: The call UUID
    func endCall(uuid: UUID) {
        logger.debug("Ending call via CallKit: \(uuid)", category: .calls)

        let endCallAction = CXEndCallAction(call: uuid)
        let transaction = CXTransaction(action: endCallAction)

        callController.request(transaction) { error in
            if let error = error {
                logger.error(error, message: "Failed to end call via CallKit", category: .calls)
                return
            }

            logger.debug("Call ended via CallKit", category: .calls)
        }
    }

    /// Update call info (e.g., when caller name is resolved)
    /// - Parameters:
    ///   - uuid: The call UUID
    ///   - callerName: The resolved caller name
    func updateCall(uuid: UUID, callerName: String) {
        let update = CXCallUpdate()
        update.localizedCallerName = callerName
        provider.reportCall(with: uuid, updated: update)
    }

    /// Check if a call with the given UUID is active
    /// - Parameter uuid: The call UUID
    /// - Returns: True if the call is active
    func isCallActive(_ uuid: UUID) -> Bool {
        return activeCalls[uuid] != nil
    }
}

// MARK: - CXProviderDelegate

extension CallKitService: CXProviderDelegate {

    func providerDidReset(_ provider: CXProvider) {
        logger.warning("CallKit provider did reset", category: .calls)

        // End all calls
        activeCalls.removeAll()
        delegate?.callKitService(self, providerDidReset: provider)
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        logger.debug("CXStartCallAction for call: \(action.callUUID)", category: .calls)

        // Configure audio session
        configureAudioSession()

        // Report connecting
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        logger.info("CXAnswerCallAction for call: \(action.callUUID)", category: .calls)

        // Configure audio session
        configureAudioSession()

        // Notify delegate to answer the call
        delegate?.callKitService(self, didAnswerCall: action.callUUID)

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        logger.info("CXEndCallAction for call: \(action.callUUID)", category: .calls)

        // Notify delegate to end the call
        delegate?.callKitService(self, didEndCall: action.callUUID)

        activeCalls.removeValue(forKey: action.callUUID)

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        logger.debug("CXSetMutedCallAction for call: \(action.callUUID), muted: \(action.isMuted)", category: .calls)

        delegate?.callKitService(self, didMuteCall: action.callUUID, isMuted: action.isMuted)

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        logger.debug("CXSetHeldCallAction for call: \(action.callUUID), onHold: \(action.isOnHold)", category: .calls)

        delegate?.callKitService(self, didHoldCall: action.callUUID, isOnHold: action.isOnHold)

        action.fulfill()
    }

    func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        logger.warning("CallKit action timed out: \(type(of: action))", category: .calls)
        action.fail()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        logger.info("CallKit audio session activated", category: .calls)

        // Notify WebRTC that audio session is active
        RTCAudioSession.sharedInstance().audioSessionDidActivate(audioSession)
        RTCAudioSession.sharedInstance().isAudioEnabled = true
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        logger.info("CallKit audio session deactivated", category: .calls)

        // Notify WebRTC that audio session is deactivated
        RTCAudioSession.sharedInstance().audioSessionDidDeactivate(audioSession)
        RTCAudioSession.sharedInstance().isAudioEnabled = false
    }

    // MARK: - Private Methods

    private func configureAudioSession() {
        let audioSession = RTCAudioSession.sharedInstance()
        audioSession.lockForConfiguration()

        do {
            try audioSession.setCategory(AVAudioSession.Category.playAndRecord.rawValue)
            try audioSession.setMode(AVAudioSession.Mode.voiceChat.rawValue)
        } catch {
            logger.error(error, message: "Failed to configure audio session", category: .calls)
        }

        audioSession.unlockForConfiguration()
    }
}

// MARK: - Import WebRTC for RTCAudioSession

import WebRTC
