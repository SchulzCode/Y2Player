package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionPolicyTest {
    private val minute = 60_000L

    @Test
    fun `restored position is kept in the middle of a track`() {
        assertEquals(90_000L, PlaybackPositionPolicy.clampRestored(90_000L, 5 * minute))
    }

    @Test
    fun `restored position near the end is treated as finished`() {
        val duration = 5 * minute
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(duration - 1_000L, duration))
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(duration, duration))
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(duration + 1_000L, duration))
    }

    @Test
    fun `restored position rejects nonsense input`() {
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(-1L, minute))
        assertEquals(0L, PlaybackPositionPolicy.clampRestored(1_000L, 0L))
    }

    @Test
    fun `seek never targets decoder EOF`() {
        assertEquals(30_000L, PlaybackPositionPolicy.clampSeek(30_000L, minute))
        assertEquals(59_750L, PlaybackPositionPolicy.clampSeek(minute, minute))
        assertEquals(59_750L, PlaybackPositionPolicy.clampSeek(minute + 5_000L, minute))
        assertEquals(0L, PlaybackPositionPolicy.clampSeek(10_000L, 200L))
    }

    @Test
    fun `audiobook position below the minimum is stored as the chapter start`() {
        assertEquals(0L, PlaybackPositionPolicy.audiobookSavePosition(5_000L, 30 * minute))
        assertEquals(0L, PlaybackPositionPolicy.audiobookSavePosition(0L, 30 * minute))
    }

    @Test
    fun `audiobook position at or above the minimum is stored verbatim`() {
        assertEquals(
            PlaybackPositionPolicy.AUDIOBOOK_MIN_SAVE_MS,
            PlaybackPositionPolicy.audiobookSavePosition(
                PlaybackPositionPolicy.AUDIOBOOK_MIN_SAVE_MS, 30 * minute
            )
        )
        assertEquals(12 * minute, PlaybackPositionPolicy.audiobookSavePosition(12 * minute, 30 * minute))
    }

    @Test
    fun `audiobook position near the end of a chapter is stored as zero`() {
        val duration = 30 * minute
        assertEquals(0L, PlaybackPositionPolicy.audiobookSavePosition(duration - 1_000L, duration))
        assertEquals(0L, PlaybackPositionPolicy.audiobookSavePosition(duration, duration))
    }

    @Test
    fun `audiobook position with unknown duration is still saved`() {
        assertEquals(12 * minute, PlaybackPositionPolicy.audiobookSavePosition(12 * minute, 0L))
        assertEquals(0L, PlaybackPositionPolicy.audiobookSavePosition(2_000L, 0L))
    }

    @Test
    fun `audiobook resume applies the rewind`() {
        assertEquals(
            12 * minute - PlaybackPositionPolicy.AUDIOBOOK_REWIND_MS,
            PlaybackPositionPolicy.audiobookResumePosition(12 * minute, 30 * minute)
        )
    }

    @Test
    fun `audiobook resume never goes negative`() {
        assertEquals(0L, PlaybackPositionPolicy.audiobookResumePosition(1_000L, 30 * minute))
        assertEquals(0L, PlaybackPositionPolicy.audiobookResumePosition(0L, 30 * minute))
    }

    @Test
    fun `audiobook resume past the end restarts the chapter`() {
        val duration = 30 * minute
        assertEquals(0L, PlaybackPositionPolicy.audiobookResumePosition(duration, duration))
        assertEquals(0L, PlaybackPositionPolicy.audiobookResumePosition(duration + minute, duration))
    }

    @Test
    fun `audiobook resume tolerates unknown duration`() {
        assertEquals(
            12 * minute - PlaybackPositionPolicy.AUDIOBOOK_REWIND_MS,
            PlaybackPositionPolicy.audiobookResumePosition(12 * minute, 0L)
        )
    }

    @Test
    fun `save then resume lands slightly before where the listener stopped`() {
        val duration = 45 * minute
        val stoppedAt = 20 * minute
        val saved = PlaybackPositionPolicy.audiobookSavePosition(stoppedAt, duration)
        val resumed = PlaybackPositionPolicy.audiobookResumePosition(saved, duration)
        assertEquals(stoppedAt - PlaybackPositionPolicy.AUDIOBOOK_REWIND_MS, resumed)
    }
}
