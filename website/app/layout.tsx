import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Whisper2 - Private. Secure. Anonymous.",
  description: "Whisper2 is an end-to-end encrypted messaging app. No phone number required. No tracking. No data collection. Just secure, anonymous conversations.",
  keywords: ["encrypted messaging", "private messaging", "secure chat", "anonymous messaging", "e2e encryption", "no phone number", "privacy"],
  authors: [{ name: "Whisper2" }],
  openGraph: {
    title: "Whisper2 - Private. Secure. Anonymous.",
    description: "End-to-end encrypted messaging app with no phone number required.",
    url: "https://whisper2.aiakademiturkiye.com",
    siteName: "Whisper2",
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title: "Whisper2 - Private. Secure. Anonymous.",
    description: "End-to-end encrypted messaging app with no phone number required.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        {children}
      </body>
    </html>
  );
}
