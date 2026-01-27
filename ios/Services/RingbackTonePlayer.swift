import Foundation
import AVFoundation

/// Plays a ringback tone during outgoing calls
/// Uses AVAudioEngine to generate a synthetic ringback tone
final class RingbackTonePlayer {
    static let shared = RingbackTonePlayer()

    private var audioEngine: AVAudioEngine?
    private var tonePlayer: AVAudioPlayerNode?
    private var isPlaying = false
    private var playbackTimer: Timer?

    // Ringback tone pattern: 2 seconds on, 4 seconds off (standard pattern)
    private let toneOnDuration: TimeInterval = 2.0
    private let toneOffDuration: TimeInterval = 4.0

    private init() {}

    /// Start playing the ringback tone
    func start() {
        guard !isPlaying else { return }
        isPlaying = true

        // Start the pattern
        playTonePattern()
    }

    /// Stop playing the ringback tone
    func stop() {
        isPlaying = false
        playbackTimer?.invalidate()
        playbackTimer = nil
        stopTone()
    }

    private func playTonePattern() {
        guard isPlaying else { return }

        // Play tone
        playTone()

        // Schedule stop after toneOnDuration
        DispatchQueue.main.asyncAfter(deadline: .now() + toneOnDuration) { [weak self] in
            guard let self = self, self.isPlaying else { return }
            self.stopTone()

            // Schedule next tone after toneOffDuration
            DispatchQueue.main.asyncAfter(deadline: .now() + self.toneOffDuration) { [weak self] in
                self?.playTonePattern()
            }
        }
    }

    private func playTone() {
        // Use system sound for simplicity - this is the "Tock" sound
        // Sound ID 1057 is a good ringback-like tone
        AudioServicesPlaySystemSound(1057)

        // Schedule additional tones during the "on" period to create continuous effect
        var delay: TimeInterval = 0.5
        while delay < toneOnDuration {
            let capturedDelay = delay
            DispatchQueue.main.asyncAfter(deadline: .now() + capturedDelay) { [weak self] in
                guard let self = self, self.isPlaying else { return }
                AudioServicesPlaySystemSound(1057)
            }
            delay += 0.5
        }
    }

    private func stopTone() {
        // System sounds stop automatically
    }
}

// MARK: - Alternative: Custom Tone Generator

extension RingbackTonePlayer {
    /// Creates a proper ringback tone using AVAudioEngine
    /// Call this instead of playTone() for a smoother sound
    private func playGeneratedTone() {
        guard audioEngine == nil else { return }

        do {
            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()

            engine.attach(player)

            let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
            engine.connect(player, to: engine.mainMixerNode, format: format)

            // Generate a 440Hz sine wave (standard ringback frequency)
            let sampleRate = 44100.0
            let frequency = 440.0
            let duration = toneOnDuration
            let frameCount = AVAudioFrameCount(sampleRate * duration)

            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
                return
            }

            buffer.frameLength = frameCount
            let data = buffer.floatChannelData![0]

            for i in 0..<Int(frameCount) {
                let sample = sin(2.0 * .pi * frequency * Double(i) / sampleRate)
                data[i] = Float(sample * 0.3) // 0.3 volume
            }

            try engine.start()
            player.scheduleBuffer(buffer, at: nil, options: .loops, completionHandler: nil)
            player.play()

            self.audioEngine = engine
            self.tonePlayer = player
        } catch {
            print("Failed to start audio engine: \(error)")
        }
    }

    private func stopGeneratedTone() {
        tonePlayer?.stop()
        audioEngine?.stop()
        audioEngine = nil
        tonePlayer = nil
    }
}
