package com.whisper2.app.push

import com.whisper2.app.services.push.NotificationChannels
import org.junit.Assert.*
import org.junit.Test

/**
 * Gate 4: Notification Channels Create/Update
 *
 * Tests:
 * - Channels are defined correctly
 * - Channel IDs are correct
 * - Idempotent creation (no crash on multiple calls)
 *
 * Note: Full channel testing requires Android context (instrumented tests)
 * These are JVM tests for the static values and logic.
 */
class NotificationChannelsTest {

    // ==========================================================================
    // Gate 4: Channel constants are defined
    // ==========================================================================

    @Test
    fun `gate4 messages channel ID is defined`() {
        assertEquals("whisper2_messages", NotificationChannels.CHANNEL_MESSAGES)
    }

    @Test
    fun `gate4 calls channel ID is defined`() {
        assertEquals("whisper2_calls", NotificationChannels.CHANNEL_CALLS)
    }

    @Test
    fun `gate4 channel IDs are different`() {
        assertNotEquals(
            "Channel IDs should be unique",
            NotificationChannels.CHANNEL_MESSAGES,
            NotificationChannels.CHANNEL_CALLS
        )
    }

    // ==========================================================================
    // Gate 4: Channel IDs follow naming convention
    // ==========================================================================

    @Test
    fun `gate4 messages channel ID follows convention`() {
        assertTrue(
            "Should start with whisper2_",
            NotificationChannels.CHANNEL_MESSAGES.startsWith("whisper2_")
        )
    }

    @Test
    fun `gate4 calls channel ID follows convention`() {
        assertTrue(
            "Should start with whisper2_",
            NotificationChannels.CHANNEL_CALLS.startsWith("whisper2_")
        )
    }

    // ==========================================================================
    // Gate 4: Channel info structure
    // ==========================================================================

    @Test
    fun `gate4 ChannelInfo data class works correctly`() {
        val info = NotificationChannels.ChannelInfo(
            id = "test_channel",
            name = "Test Channel",
            importance = 3
        )

        assertEquals("test_channel", info.id)
        assertEquals("Test Channel", info.name)
        assertEquals(3, info.importance)
    }

    @Test
    fun `gate4 ChannelInfo equals works`() {
        val info1 = NotificationChannels.ChannelInfo("id", "name", 3)
        val info2 = NotificationChannels.ChannelInfo("id", "name", 3)

        assertEquals(info1, info2)
    }
}
