package app.hoshira.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerBufferPolicyTest {
    @Test
    fun `default profile caps buffer at twenty four MiB`() {
        val profile = playerBufferProfile(isLowRamDevice = false)

        assertEquals(10_000, profile.minBufferMs)
        assertEquals(30_000, profile.maxBufferMs)
        assertEquals(1_500, profile.bufferForPlaybackMs)
        assertEquals(3_000, profile.bufferForPlaybackAfterRebufferMs)
        assertEquals(24 * 1024 * 1024, profile.targetBufferBytes)
        assertValid(profile)
    }

    @Test
    fun `low ram profile reduces time and byte limits`() {
        val regular = playerBufferProfile(isLowRamDevice = false)
        val lowRam = playerBufferProfile(isLowRamDevice = true)

        assertEquals(5_000, lowRam.minBufferMs)
        assertEquals(15_000, lowRam.maxBufferMs)
        assertEquals(1_000, lowRam.bufferForPlaybackMs)
        assertEquals(2_000, lowRam.bufferForPlaybackAfterRebufferMs)
        assertEquals(16 * 1024 * 1024, lowRam.targetBufferBytes)
        assertTrue(lowRam.maxBufferMs < regular.maxBufferMs)
        assertTrue(lowRam.targetBufferBytes < regular.targetBufferBytes)
        assertValid(lowRam)
    }

    private fun assertValid(profile: PlayerBufferProfile) {
        assertTrue(profile.minBufferMs <= profile.maxBufferMs)
        assertTrue(profile.bufferForPlaybackMs <= profile.minBufferMs)
        assertTrue(profile.bufferForPlaybackAfterRebufferMs <= profile.minBufferMs)
        assertTrue(profile.targetBufferBytes > 0)
    }
}
