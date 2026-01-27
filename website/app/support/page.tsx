import Link from "next/link";

export default function Support() {
  return (
    <main className="min-h-screen bg-gray-950">
      {/* Header */}
      <header className="fixed top-0 left-0 right-0 z-50 bg-gray-950/80 backdrop-blur-md border-b border-gray-800">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <Link href="/" className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-600 rounded-xl flex items-center justify-center">
              <svg className="w-6 h-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              </svg>
            </div>
            <span className="text-xl font-bold text-white">Whisper2</span>
          </Link>
        </div>
      </header>

      {/* Content */}
      <section className="pt-32 pb-20 px-6">
        <div className="max-w-3xl mx-auto">
          <h1 className="text-4xl font-bold text-white mb-8">Support</h1>

          <div className="prose prose-invert max-w-none">
            <div className="bg-gray-900 border border-gray-800 rounded-2xl p-8 mb-8">
              <h2 className="text-2xl font-semibold text-white mb-4">Frequently Asked Questions</h2>

              <div className="space-y-6">
                <div>
                  <h3 className="text-lg font-medium text-white mb-2">How do I create an account?</h3>
                  <p className="text-gray-400">Simply download Whisper2 and tap &quot;Create Account&quot;. A unique Whisper ID and encryption keys will be generated automatically. Save your 12-word recovery phrase in a safe place.</p>
                </div>

                <div>
                  <h3 className="text-lg font-medium text-white mb-2">How do I add contacts?</h3>
                  <p className="text-gray-400">You can add contacts by scanning their QR code or by entering their Whisper ID manually. Both users need to exchange public keys to enable encrypted messaging.</p>
                </div>

                <div>
                  <h3 className="text-lg font-medium text-white mb-2">What if I lose my phone?</h3>
                  <p className="text-gray-400">Use your 12-word recovery phrase to restore your account on a new device. Without this phrase, your account cannot be recovered as we do not store your private keys.</p>
                </div>

                <div>
                  <h3 className="text-lg font-medium text-white mb-2">Can Whisper2 read my messages?</h3>
                  <p className="text-gray-400">No. All messages are end-to-end encrypted. We only relay encrypted data between users. Your private keys never leave your device.</p>
                </div>

                <div>
                  <h3 className="text-lg font-medium text-white mb-2">How do group chats work?</h3>
                  <p className="text-gray-400">Group chats use pairwise encryption - each message is encrypted separately for each member. This ensures maximum security while allowing group conversations.</p>
                </div>
              </div>
            </div>

            <div className="bg-gray-900 border border-gray-800 rounded-2xl p-8">
              <h2 className="text-2xl font-semibold text-white mb-4">Contact Us</h2>
              <p className="text-gray-400 mb-4">
                If you have questions, feedback, or need help with Whisper2, please reach out to us:
              </p>
              <div className="space-y-3">
                <p className="text-gray-300">
                  <span className="text-purple-400 font-medium">Email:</span> support@whisper2.aiakademiturkiye.com
                </p>
                <p className="text-gray-300">
                  <span className="text-purple-400 font-medium">Response Time:</span> We aim to respond within 24-48 hours
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="py-12 px-6 border-t border-gray-800">
        <div className="max-w-6xl mx-auto">
          <div className="flex flex-col md:flex-row items-center justify-between gap-6">
            <Link href="/" className="flex items-center gap-3">
              <div className="w-10 h-10 bg-purple-600 rounded-xl flex items-center justify-center">
                <svg className="w-6 h-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                </svg>
              </div>
              <span className="text-xl font-bold text-white">Whisper2</span>
            </Link>

            <nav className="flex items-center gap-8">
              <Link href="/privacy" className="text-gray-400 hover:text-white transition-colors">Privacy Policy</Link>
              <Link href="/terms" className="text-gray-400 hover:text-white transition-colors">Terms of Service</Link>
              <Link href="/support" className="text-gray-400 hover:text-white transition-colors">Support</Link>
            </nav>

            <p className="text-gray-500 text-sm">&copy; 2026 Whisper2. All rights reserved.</p>
          </div>
        </div>
      </footer>
    </main>
  );
}
