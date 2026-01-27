import Link from "next/link";
import { Metadata } from "next";

export const metadata: Metadata = {
  title: "Terms of Service - Whisper2",
  description: "Terms of Service for Whisper2 messaging application",
};

export default function TermsOfService() {
  return (
    <main className="min-h-screen bg-gray-950">
      {/* Header */}
      <header className="fixed top-0 left-0 right-0 z-50 bg-gray-950/80 backdrop-blur-md border-b border-gray-800">
        <div className="max-w-4xl mx-auto px-6 py-4">
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

      <article className="max-w-4xl mx-auto px-6 pt-32 pb-12">
        <h1 className="text-4xl font-bold text-white mb-4">Terms of Service</h1>
        <p className="text-gray-400 mb-12">Last updated: January 27, 2026</p>

        <div className="space-y-10">
          {/* Acceptance */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">1. Acceptance of Terms</h2>
            <p className="text-gray-300 leading-relaxed">
              By downloading, installing, or using Whisper2 (&quot;the App&quot;), you agree to be bound by these Terms of Service (&quot;Terms&quot;). If you do not agree to these Terms, please do not use the App. We reserve the right to modify these Terms at any time, and your continued use of the App constitutes acceptance of any modifications.
            </p>
          </section>

          {/* Description */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">2. Description of Service</h2>
            <p className="text-gray-300 leading-relaxed">
              Whisper2 is a privacy-focused messaging application that provides end-to-end encrypted communication. The App allows users to send text messages, images, voice messages, and files securely without requiring personal information such as phone numbers or email addresses. All communications are encrypted on your device before transmission.
            </p>
          </section>

          {/* Account Registration */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">3. Account Registration</h2>
            <p className="text-gray-300 leading-relaxed mb-4">
              When you create an account with Whisper2:
            </p>
            <ul className="space-y-2 text-gray-300 list-disc list-inside ml-4">
              <li>You will receive a unique Whisper ID</li>
              <li>You will be provided with a 12-word recovery phrase</li>
              <li>You are solely responsible for keeping your recovery phrase secure</li>
              <li>You are responsible for all activity that occurs under your account</li>
              <li>You understand that losing your recovery phrase means permanent loss of account access</li>
            </ul>
          </section>

          {/* User Responsibilities */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">4. User Responsibilities</h2>
            <p className="text-gray-300 leading-relaxed mb-4">
              You agree to:
            </p>
            <ul className="space-y-2 text-gray-300 list-disc list-inside ml-4">
              <li>Use the App in compliance with all applicable local, state, national, and international laws</li>
              <li>Not use the App for any illegal or unauthorized purpose</li>
              <li>Not transmit any content that is harmful, threatening, abusive, harassing, defamatory, or otherwise objectionable</li>
              <li>Not attempt to interfere with the proper functioning of the App</li>
              <li>Not attempt to gain unauthorized access to other users&apos; accounts or data</li>
              <li>Keep your recovery phrase confidential and secure</li>
            </ul>
          </section>

          {/* Prohibited Activities */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">5. Prohibited Activities</h2>
            <p className="text-gray-300 leading-relaxed mb-4">
              You may not use Whisper2 to:
            </p>
            <ul className="space-y-2 text-gray-300 list-disc list-inside ml-4">
              <li>Distribute malware, viruses, or other harmful code</li>
              <li>Engage in spam or unauthorized advertising</li>
              <li>Impersonate another person or entity</li>
              <li>Violate the intellectual property rights of others</li>
              <li>Engage in any illegal activities including but not limited to fraud, terrorism, or child exploitation</li>
              <li>Harass, bully, stalk, or threaten other users</li>
              <li>Distribute content that exploits minors in any way</li>
              <li>Attempt to circumvent any security features of the App</li>
            </ul>
          </section>

          {/* Privacy */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">6. Privacy</h2>
            <p className="text-gray-300 leading-relaxed">
              Your use of Whisper2 is also governed by our <Link href="/privacy" className="text-purple-400 hover:text-purple-300">Privacy Policy</Link>. Please review our Privacy Policy to understand our practices regarding your information. By using the App, you consent to the practices described in the Privacy Policy.
            </p>
          </section>

          {/* Encryption Notice */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">7. Encryption and Security</h2>
            <p className="text-gray-300 leading-relaxed">
              Whisper2 uses end-to-end encryption for all messages. This means that we cannot access the content of your communications. While we implement strong security measures, no system is completely secure. You acknowledge that you use the App at your own risk and are responsible for maintaining the security of your device and recovery phrase.
            </p>
          </section>

          {/* Intellectual Property */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">8. Intellectual Property</h2>
            <p className="text-gray-300 leading-relaxed">
              The App and its original content, features, and functionality are owned by Whisper2 and are protected by international copyright, trademark, patent, trade secret, and other intellectual property laws. You may not copy, modify, distribute, sell, or lease any part of the App without our prior written consent.
            </p>
          </section>

          {/* Disclaimer */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">9. Disclaimer of Warranties</h2>
            <p className="text-gray-300 leading-relaxed">
              THE APP IS PROVIDED &quot;AS IS&quot; AND &quot;AS AVAILABLE&quot; WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NON-INFRINGEMENT. WE DO NOT WARRANT THAT THE APP WILL BE UNINTERRUPTED, SECURE, OR ERROR-FREE, OR THAT DEFECTS WILL BE CORRECTED.
            </p>
          </section>

          {/* Limitation of Liability */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">10. Limitation of Liability</h2>
            <p className="text-gray-300 leading-relaxed">
              TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, WHISPER2 AND ITS AFFILIATES, OFFICERS, EMPLOYEES, AGENTS, AND LICENSORS SHALL NOT BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, OR ANY LOSS OF PROFITS OR REVENUES, WHETHER INCURRED DIRECTLY OR INDIRECTLY, OR ANY LOSS OF DATA, USE, GOODWILL, OR OTHER INTANGIBLE LOSSES RESULTING FROM YOUR USE OF THE APP.
            </p>
          </section>

          {/* Indemnification */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">11. Indemnification</h2>
            <p className="text-gray-300 leading-relaxed">
              You agree to indemnify, defend, and hold harmless Whisper2 and its affiliates, officers, directors, employees, and agents from and against any claims, liabilities, damages, losses, and expenses, including reasonable attorney fees, arising out of or in any way connected with your use of the App or violation of these Terms.
            </p>
          </section>

          {/* Termination */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">12. Termination</h2>
            <p className="text-gray-300 leading-relaxed">
              We reserve the right to suspend or terminate your access to the App at any time, without prior notice, for conduct that we believe violates these Terms or is harmful to other users, us, or third parties. Upon termination, your right to use the App will immediately cease.
            </p>
          </section>

          {/* Governing Law */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">13. Governing Law</h2>
            <p className="text-gray-300 leading-relaxed">
              These Terms shall be governed by and construed in accordance with the laws of Turkey, without regard to its conflict of law provisions. Any disputes arising from these Terms or your use of the App shall be resolved in the courts of Turkey.
            </p>
          </section>

          {/* Severability */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">14. Severability</h2>
            <p className="text-gray-300 leading-relaxed">
              If any provision of these Terms is found to be unenforceable or invalid, that provision shall be limited or eliminated to the minimum extent necessary so that the remaining provisions of these Terms shall remain in full force and effect.
            </p>
          </section>

          {/* Entire Agreement */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">15. Entire Agreement</h2>
            <p className="text-gray-300 leading-relaxed">
              These Terms, together with our Privacy Policy, constitute the entire agreement between you and Whisper2 regarding your use of the App and supersede any prior agreements between you and Whisper2 relating to the App.
            </p>
          </section>

          {/* Contact */}
          <section>
            <h2 className="text-2xl font-semibold text-white mb-4">16. Contact</h2>
            <p className="text-gray-300 leading-relaxed">
              If you have any questions about these Terms, please contact us at:
            </p>
            <p className="mt-2">
              <a href="mailto:legal@whisper2.aiakademiturkiye.com" className="text-purple-400 hover:text-purple-300 transition-colors">
                legal@whisper2.aiakademiturkiye.com
              </a>
            </p>
          </section>
        </div>
      </article>

      {/* Footer */}
      <footer className="py-8 px-6 border-t border-gray-800">
        <div className="max-w-4xl mx-auto text-center">
          <Link href="/" className="text-purple-400 hover:text-purple-300 transition-colors">
            &larr; Back to Home
          </Link>
        </div>
      </footer>
    </main>
  );
}
