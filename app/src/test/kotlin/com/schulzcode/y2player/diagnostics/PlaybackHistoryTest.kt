package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.core.model.PlaybackExitReason
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryTest {
    private val minute = 60_000L

    private fun track(
        title: String = "Song",
        artist: String? = "Artist",
        durationMs: Long = 4 * minute,
        relativePath: String = "Music/Album/01.flac"
    ) = Track(
        id = 7,
        volumeId = "sdcard",
        absolutePath = "/storage/sdcard1/$relativePath",
        relativePath = relativePath,
        title = title,
        artist = artist,
        album = "Album",
        albumArtist = null,
        trackNumber = 1,
        discNumber = null,
        durationMs = durationMs,
        fileSize = 1,
        modifiedAt = 0,
        codec = "flac"
    )

    private fun session(
        track: Track = track(),
        listenedMs: Long = 3 * minute,
        exitReason: PlaybackExitReason = PlaybackExitReason.COMPLETED
    ) = PlaybackSession(
        track = track,
        startedAtUtcMs = 1_700_000_000_000,
        endedAtUptimeMs = 12_345,
        startPositionMs = 0,
        endPositionMs = listenedMs,
        listenedMs = listenedMs,
        exitReason = exitReason,
        shuffleEnabled = false,
        repeatMode = RepeatMode.OFF
    )

    private fun encode(session: PlaybackSession): String =
        PlaybackHistory.encode(session, appVersion = "2.1.1")

    @Test
    fun `half a track qualifies`() {
        assertTrue(PlaybackHistory.qualifies(listenedMs = 2 * minute, durationMs = 4 * minute))
    }

    @Test
    fun `just under half does not`() {
        assertFalse(PlaybackHistory.qualifies(listenedMs = 2 * minute - 1, durationMs = 4 * minute))
    }

    @Test
    fun `four minutes qualifies a long track without needing half of it`() {
        assertTrue(PlaybackHistory.qualifies(listenedMs = 4 * minute, durationMs = 60 * minute))
        assertFalse(PlaybackHistory.qualifies(listenedMs = 4 * minute - 1, durationMs = 60 * minute))
    }

    @Test
    fun `very short tracks never qualify`() {
        assertFalse(PlaybackHistory.qualifies(listenedMs = 29_000, durationMs = 29_000))
        assertTrue(PlaybackHistory.qualifies(listenedMs = 30_000, durationMs = 30_000))
    }

    @Test
    fun `an unknown duration cannot qualify`() {
        assertFalse(PlaybackHistory.qualifies(listenedMs = 10 * minute, durationMs = 0))
    }

    @Test
    fun `played fraction is capped at one`() {
        assertEquals(1.0, PlaybackHistory.playedFraction(10 * minute, 4 * minute), 0.0001)
        assertEquals(0.5, PlaybackHistory.playedFraction(2 * minute, 4 * minute), 0.0001)
        assertEquals(0.0, PlaybackHistory.playedFraction(0, 4 * minute), 0.0001)
        assertEquals(0.0, PlaybackHistory.playedFraction(minute, 0), 0.0001)
    }

    @Test
    fun `fraction formatting is locale independent`() {
        assertEquals("0.750", PlaybackHistory.formatFraction(0.75))
        assertTrue(PlaybackHistory.formatFraction(0.5).contains('.'))
    }

    @Test
    fun `record is a single line`() {
        val line = encode(session())
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
    }

    @Test
    fun `record carries the schema and version`() {
        val line = encode(session())
        assertTrue(line.contains("\"schema_version\":1"))
        assertTrue(line.contains("y2player.playback-history"))
    }

    @Test
    fun `music is labelled and carries no audiobook key`() {
        val line = encode(session())
        assertTrue(line.contains("\"media_type\":\"music\""))
        assertFalse(line.contains("audiobook_key"))
    }

    @Test
    fun `audiobook chapters are labelled and keyed for desktop filtering`() {
        val chapter = track(relativePath = "AUDIOBOOKS/Dune/01.mp3")
        val line = encode(session(track = chapter))
        assertTrue(line.contains("\"media_type\":\"audiobook\""))
        assertTrue(line.contains("\"audiobook_key\":\"sdcard|AUDIOBOOKS/Dune\""))
    }

    @Test
    fun `exit reason uses the stable code, never the enum name`() {
        val line = encode(session(exitReason = PlaybackExitReason.MANUAL_NEXT))
        assertTrue(line.contains("\"exit_reason\":\"manual_next\""))
        assertFalse(line.contains("MANUAL_NEXT"))
    }

    @Test
    fun `qualification result is recorded rather than filtering the row out`() {
        val skipped = session(listenedMs = 10_000)
        assertTrue(encode(skipped).contains("\"qualified\":false"))
        assertTrue(encode(session()).contains("\"qualified\":true"))
    }

    @Test
    fun `metadata with quotes tabs and newlines stays valid`() {
        val nasty = track(title = "He said \"hi\"\tthen\nleft", artist = "A\\B")
        val line = encode(session(track = nasty))
        assertFalse("raw newline would split the record", line.contains('\n'))
        assertFalse("raw tab would corrupt the field", line.contains('\t'))
        assertTrue(line.contains("\\\""))
        assertTrue(line.contains("\\n"))
        assertTrue(line.contains("\\t"))
    }

    @Test
    fun `unicode and emoji survive`() {
        val line = encode(session(track = track(title = "夜明け 🌅", artist = "Café")))
        assertTrue(line.contains("夜明け"))
        assertTrue(line.contains("Café"))
    }

    @Test
    fun `absent optional metadata is omitted rather than written as null`() {
        val line = encode(session(track = track(artist = null)))
        assertFalse(line.contains("\"artist\""))
        assertFalse(line.contains("null"))
    }

    @Test
    fun `relative path is recorded, not the absolute one`() {
        val line = encode(session())
        assertTrue(line.contains("\"path\":\"Music/Album/01.flac\""))
        assertFalse(line.contains("/storage/sdcard1/"))
    }

    @Test
    fun `record size stays within the budget the cap was derived from`() {
        val line = encode(session(track = track(relativePath = "AUDIOBOOKS/A Very Long Book Title/Chapter 12.mp3")))
        assertTrue("record was ${line.length} bytes", line.length < 900)
    }
}
