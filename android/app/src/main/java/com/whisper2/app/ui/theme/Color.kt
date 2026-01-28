package com.whisper2.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Whisper2 Premium Metal Color Palette
 * iOS-inspired dark metal aesthetic with depth and elegance.
 */

// ═══════════════════════════════════════════════════════════════════
// METAL BASE COLORS - Deep, rich, premium feel
// ═══════════════════════════════════════════════════════════════════

// Deep metal backgrounds (darker than pure black, with depth)
val MetalBlack = Color(0xFF050810)        // Deepest background
val MetalDark = Color(0xFF0A0E1A)         // Primary background
val MetalNavy = Color(0xFF0F1629)         // Elevated surface
val MetalSlate = Color(0xFF151C32)        // Card background
val MetalCharcoal = Color(0xFF1A2340)     // Hover/pressed state

// Metal accent surfaces (for depth layers)
val MetalSurface1 = Color(0xFF1E2847)     // Surface level 1
val MetalSurface2 = Color(0xFF232F52)     // Surface level 2
val MetalSurface3 = Color(0xFF283660)     // Surface level 3

// ═══════════════════════════════════════════════════════════════════
// PRIMARY COLORS - Refined blue with metallic sheen
// ═══════════════════════════════════════════════════════════════════

val PrimaryBlue = Color(0xFF3B82F6)       // Core blue
val PrimaryBlueDark = Color(0xFF2563EB)   // Pressed state
val PrimaryBlueLight = Color(0xFF60A5FA)  // Hover state
val PrimaryBlueMuted = Color(0xFF1E40AF)  // Muted variant

// Secondary purple (for gradients)
val SecondaryPurple = Color(0xFF8B5CF6)
val SecondaryPurpleDark = Color(0xFF7C3AED)
val SecondaryPurpleLight = Color(0xFFA78BFA)

// ═══════════════════════════════════════════════════════════════════
// METALLIC ACCENT COLORS - Premium shimmer effect
// ═══════════════════════════════════════════════════════════════════

val MetalSilver = Color(0xFF94A3B8)       // Subtle metallic
val MetalTitanium = Color(0xFF7D8BA8)     // Darker metallic
val MetalSteel = Color(0xFF64748B)        // Steel gray
val MetalGlow = Color(0xFFBDCFE8)         // Highlight/glow

// Gradient endpoints for metallic effect
val MetalGradientStart = Color(0xFF3B82F6)
val MetalGradientMid = Color(0xFF6366F1)
val MetalGradientEnd = Color(0xFF8B5CF6)

// ═══════════════════════════════════════════════════════════════════
// MESSAGE BUBBLES - Premium glass-like appearance
// ═══════════════════════════════════════════════════════════════════

// Outgoing bubbles (sender) - vibrant gradient
val BubbleOutgoingStart = Color(0xFF3B82F6)
val BubbleOutgoingEnd = Color(0xFF7C3AED)

// Incoming bubbles (receiver) - subtle metal glass
val BubbleIncomingStart = Color(0xFF1E2847)
val BubbleIncomingEnd = Color(0xFF283660)

// Bubble overlay for glass effect
val BubbleGlassOverlay = Color(0x15FFFFFF)  // 8% white overlay

// ═══════════════════════════════════════════════════════════════════
// TEXT COLORS - High contrast with subtle hierarchy
// ═══════════════════════════════════════════════════════════════════

val TextPrimary = Color(0xFFF8FAFC)       // Primary text (bright white)
val TextSecondary = Color(0xFFCBD5E1)     // Secondary text
val TextTertiary = Color(0xFF94A3B8)      // Tertiary/muted text
val TextDisabled = Color(0xFF64748B)      // Disabled text
val TextInverse = Color(0xFF0F172A)       // Text on light backgrounds

// ═══════════════════════════════════════════════════════════════════
// STATUS & SEMANTIC COLORS - Refined, not harsh
// ═══════════════════════════════════════════════════════════════════

// Success (soft emerald)
val StatusSuccess = Color(0xFF10B981)
val StatusSuccessLight = Color(0xFF34D399)
val StatusSuccessMuted = Color(0xFF065F46)

// Error (refined red)
val StatusError = Color(0xFFEF4444)
val StatusErrorLight = Color(0xFFF87171)
val StatusErrorMuted = Color(0xFF991B1B)

// Warning (amber)
val StatusWarning = Color(0xFFF59E0B)
val StatusWarningLight = Color(0xFFFBBF24)
val StatusWarningMuted = Color(0xFF92400E)

// Info (cyan)
val StatusInfo = Color(0xFF06B6D4)
val StatusInfoLight = Color(0xFF22D3EE)
val StatusInfoMuted = Color(0xFF155E75)

// ═══════════════════════════════════════════════════════════════════
// PRESENCE & ACTIVITY STATES
// ═══════════════════════════════════════════════════════════════════

val PresenceOnline = Color(0xFF22C55E)    // Green dot
val PresenceAway = Color(0xFFF59E0B)      // Yellow dot
val PresenceOffline = Color(0xFF64748B)   // Gray dot
val PresenceBusy = Color(0xFFEF4444)      // Red dot

// Call UI
val CallAccept = Color(0xFF22C55E)
val CallDecline = Color(0xFFEF4444)
val CallMuted = Color(0xFF64748B)
val CallActive = Color(0xFF3B82F6)

// ═══════════════════════════════════════════════════════════════════
// BORDER & DIVIDER COLORS
// ═══════════════════════════════════════════════════════════════════

val BorderDefault = Color(0xFF1E293B)     // Subtle border
val BorderFocus = Color(0xFF3B82F6)       // Focus state
val BorderHover = Color(0xFF334155)       // Hover state
val DividerColor = Color(0xFF1E293B)      // Dividers

// ═══════════════════════════════════════════════════════════════════
// GLASSMORPHISM & OVERLAY COLORS
// ═══════════════════════════════════════════════════════════════════

val GlassWhite = Color(0x0DFFFFFF)        // 5% white
val GlassWhiteLight = Color(0x1AFFFFFF)   // 10% white
val GlassWhiteMedium = Color(0x26FFFFFF)  // 15% white
val GlassBlack = Color(0x4D000000)        // 30% black
val GlassBlur = Color(0x80000814)         // Blur backdrop

// ═══════════════════════════════════════════════════════════════════
// LEGACY COMPATIBILITY (for existing code)
// ═══════════════════════════════════════════════════════════════════

val Primary = PrimaryBlue
val PrimaryDark = PrimaryBlueDark
val PrimaryLight = PrimaryBlueLight
val Accent = SecondaryPurple
val AccentDark = SecondaryPurpleDark
val AccentLight = SecondaryPurpleLight
val Secondary = StatusSuccess
val SecondaryDark = StatusSuccessMuted
val SecondaryLight = StatusSuccessLight
val Background = Color(0xFFFAFAFA)
val BackgroundDark = MetalDark
val Surface = Color(0xFFFFFFFF)
val SurfaceDark = MetalSlate
val OutgoingBubble = BubbleOutgoingStart
val OutgoingBubbleDark = BubbleOutgoingEnd
val IncomingBubble = Color(0xFFE5E7EB)
val IncomingBubbleDark = BubbleIncomingStart
val OnPrimary = Color.White
val OnSecondary = Color.White
val OnBackground = Color(0xFF1F2937)
val OnBackgroundDark = TextPrimary
val OnSurface = Color(0xFF1F2937)
val OnSurfaceDark = TextPrimary
val Online = PresenceOnline
val Offline = PresenceOffline
val Error = StatusError
val Warning = StatusWarning
val GradientStart = MetalGradientStart
val GradientEnd = MetalGradientEnd
