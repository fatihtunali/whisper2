import Foundation
import AVFoundation
import Combine

/// Service for recording and playing audio messages
@MainActor
final class AudioMessageService: NSObject, ObservableObject {
    static let shared = AudioMessageService()

    // MARK: - Published Properties
    @Published private(set) var isRecording = false
    @Published private(set) var isPlaying = false
    @Published private(set) var recordingDuration: TimeInterval = 0
    @Published private(set) var playbackProgress: Double = 0
    @Published private(set) var currentPlayingId: String?
    @Published var error: String?

    // MARK: - Private Properties
    private var audioRecorder: AVAudioRecorder?
    private var audioPlayer: AVAudioPlayer?
    private var recordingTimer: Timer?
    private var playbackTimer: Timer?
    private var currentRecordingURL: URL?

    // Max recording duration (5 minutes)
    private let maxDuration: TimeInterval = 300

    private override init() {
        super.init()
        setupAudioSession()
    }

    // MARK: - Audio Session Setup
    private func setupAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        } catch {
            self.error = "Failed to setup audio session: \(error.localizedDescription)"
        }
    }

    // MARK: - Recording
    func startRecording() {
        guard !isRecording else { return }

        // Request microphone permission
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            Task { @MainActor in
                guard granted else {
                    self?.error = "Microphone access denied"
                    return
                }
                self?.beginRecording()
            }
        }
    }

    private func beginRecording() {
        // Create temp file URL
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = "voice_\(UUID().uuidString).m4a"
        currentRecordingURL = tempDir.appendingPathComponent(fileName)

        guard let url = currentRecordingURL else { return }

        // Recording settings (AAC format, optimized for voice)
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            AVEncoderBitRateKey: 128000
        ]

        do {
            audioRecorder = try AVAudioRecorder(url: url, settings: settings)
            audioRecorder?.delegate = self
            audioRecorder?.isMeteringEnabled = true
            audioRecorder?.record()

            isRecording = true
            recordingDuration = 0

            // Start timer to track duration
            recordingTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self = self else { return }
                    self.recordingDuration = self.audioRecorder?.currentTime ?? 0

                    // Auto-stop at max duration
                    if self.recordingDuration >= self.maxDuration {
                        self.stopRecording()
                    }
                }
            }
        } catch {
            self.error = "Failed to start recording: \(error.localizedDescription)"
        }
    }

    func stopRecording() -> URL? {
        guard isRecording else { return nil }

        recordingTimer?.invalidate()
        recordingTimer = nil

        audioRecorder?.stop()
        audioRecorder = nil
        isRecording = false

        return currentRecordingURL
    }

    func cancelRecording() {
        guard isRecording else { return }

        recordingTimer?.invalidate()
        recordingTimer = nil

        audioRecorder?.stop()
        audioRecorder = nil
        isRecording = false

        // Delete the temp file
        if let url = currentRecordingURL {
            try? FileManager.default.removeItem(at: url)
        }
        currentRecordingURL = nil
        recordingDuration = 0
    }

    // MARK: - Playback
    func play(url: URL, messageId: String) {
        // Stop any current playback
        stop()

        // Verify file exists
        guard FileManager.default.fileExists(atPath: url.path) else {
            print("[AudioMessageService] ERROR: File does not exist at \(url.path)")
            self.error = "Audio file not found"
            return
        }

        // Check file size
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = attrs[.size] as? Int64 {
            print("[AudioMessageService] File size: \(size) bytes")
            if size == 0 {
                print("[AudioMessageService] ERROR: File is empty")
                self.error = "Audio file is empty"
                return
            }
        }

        do {
            // Configure audio session for playback
            let session = AVAudioSession.sharedInstance()
            print("[AudioMessageService] Current audio route: \(session.currentRoute)")
            print("[AudioMessageService] Setting up playback session...")

            do {
                // Simple playback category - audio will go through speaker by default
                try session.setCategory(.playback, mode: .default)
                print("[AudioMessageService] Category set to .playback")
            } catch {
                print("[AudioMessageService] setCategory failed: \(error), trying .ambient")
                // Fallback to ambient if playback fails
                try session.setCategory(.ambient, mode: .default)
                print("[AudioMessageService] Category set to .ambient")
            }

            try session.setActive(true)
            print("[AudioMessageService] Session activated")

            print("[AudioMessageService] Audio session configured. Route: \(session.currentRoute)")
            print("[AudioMessageService] Creating player for: \(url.lastPathComponent)")

            audioPlayer = try AVAudioPlayer(contentsOf: url)
            audioPlayer?.delegate = self
            audioPlayer?.volume = 1.0  // Ensure volume is at max

            print("[AudioMessageService] Player created. Duration: \(audioPlayer?.duration ?? 0)s, channels: \(audioPlayer?.numberOfChannels ?? 0)")

            let prepared = audioPlayer?.prepareToPlay() ?? false
            print("[AudioMessageService] Prepared: \(prepared)")

            let started = audioPlayer?.play() ?? false
            print("[AudioMessageService] Play called. Started: \(started), isPlaying: \(audioPlayer?.isPlaying ?? false)")

            isPlaying = true
            currentPlayingId = messageId
            playbackProgress = 0

            // Start progress timer
            playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self = self, let player = self.audioPlayer else { return }
                    self.playbackProgress = player.currentTime / player.duration
                }
            }
        } catch {
            print("[AudioMessageService] Failed to play audio: \(error)")
            self.error = "Failed to play audio: \(error.localizedDescription)"
        }
    }

    func stop() {
        playbackTimer?.invalidate()
        playbackTimer = nil

        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        currentPlayingId = nil
        playbackProgress = 0

        // Restore audio session for recording capability
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            print("[AudioMessageService] Audio session restored for recording")
        } catch {
            print("[AudioMessageService] Failed to restore audio session: \(error)")
        }
    }

    func pause() {
        audioPlayer?.pause()
        isPlaying = false
    }

    func resume() {
        audioPlayer?.play()
        isPlaying = true
    }

    func seek(to progress: Double) {
        guard let player = audioPlayer else { return }
        player.currentTime = progress * player.duration
        playbackProgress = progress
    }

    // MARK: - Utilities
    func formatDuration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", minutes, secs)
    }

    func getAudioDuration(url: URL) -> TimeInterval? {
        do {
            let player = try AVAudioPlayer(contentsOf: url)
            return player.duration
        } catch {
            return nil
        }
    }
}

// MARK: - AVAudioRecorderDelegate
extension AudioMessageService: AVAudioRecorderDelegate {
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor in
            if !flag {
                self.error = "Recording failed"
            }
        }
    }

    nonisolated func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        Task { @MainActor in
            self.error = error?.localizedDescription ?? "Recording encode error"
        }
    }
}

// MARK: - AVAudioPlayerDelegate
extension AudioMessageService: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.stop()
        }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor in
            self.error = error?.localizedDescription ?? "Playback decode error"
            self.stop()
        }
    }
}
