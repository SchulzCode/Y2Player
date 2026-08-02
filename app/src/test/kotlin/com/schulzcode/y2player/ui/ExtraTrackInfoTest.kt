package com.schulzcode.y2player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraTrackInfoTest {
    @Test
    fun `default output is unchanged when the setting is off`() {
        assertEquals(
            "FLAC · 44.1 kHz · 16-bit",
            Y2UiLogic.technicalLine("FLAC", 44_100, 16)
        )
    }

    @Test
    fun `whole-number sample rates lose the decimal`() {
        assertEquals("MP3 · 48 kHz", Y2UiLogic.technicalLine("MP3", 48_000, null))
    }

    @Test
    fun `extra fields extend the same line`() {
        assertEquals(
            "FLAC · 44.1 kHz · 16-bit · 932 kbps · Classical",
            Y2UiLogic.technicalLine("FLAC", 44_100, 16, bitrate = 932_000, genre = "Classical")
        )
    }

    @Test
    fun `missing extra fields are omitted rather than blank`() {
        assertEquals(
            "MP3 · 44.1 kHz · 320 kbps",
            Y2UiLogic.technicalLine("MP3", 44_100, null, bitrate = 320_000, genre = null)
        )
        assertEquals(
            "MP3 · 44.1 kHz · Rock",
            Y2UiLogic.technicalLine("MP3", 44_100, null, bitrate = null, genre = "Rock")
        )
    }

    @Test
    fun `zero and negative metadata is treated as absent`() {
        assertEquals("OPUS", Y2UiLogic.technicalLine("OPUS", 0, 0, bitrate = 0, genre = "   "))
        assertEquals("OPUS", Y2UiLogic.technicalLine("OPUS", -1, -1, bitrate = -1, genre = ""))
    }

    @Test
    fun `genre whitespace is trimmed`() {
        assertEquals("MP3 · Rock", Y2UiLogic.technicalLine("MP3", null, null, genre = "  Rock  "))
    }

    @Test
    fun `unicode and CJK genres survive intact`() {
        assertEquals("FLAC · 邦楽", Y2UiLogic.technicalLine("FLAC", null, null, genre = "邦楽"))
        assertEquals("FLAC · Café", Y2UiLogic.technicalLine("FLAC", null, null, genre = "Café"))
    }

    @Test
    fun `bitrate is rounded to the nearest kbps, not truncated`() {
        assertEquals("MP3 · 320 kbps", Y2UiLogic.technicalLine("MP3", null, null, bitrate = 319_999))
        assertEquals("MP3 · 192 kbps", Y2UiLogic.technicalLine("MP3", null, null, bitrate = 191_999))
        assertEquals("MP3 · 128 kbps", Y2UiLogic.technicalLine("MP3", null, null, bitrate = 128_000))
        assertEquals("FLAC · 1411 kbps", Y2UiLogic.technicalLine("FLAC", null, null, bitrate = 1_411_200))
        assertEquals("MP3 · 191 kbps", Y2UiLogic.technicalLine("MP3", null, null, bitrate = 191_400))
    }

    @Test
    fun `album is unchanged when the setting is off`() {
        assertEquals(
            "Kind of Blue",
            Y2UiLogic.albumLine("Kind of Blue", "Miles Davis", year = 1959, includeYear = false)
        )
    }

    @Test
    fun `album carries the year when the setting is on`() {
        assertEquals(
            "Kind of Blue (1959)",
            Y2UiLogic.albumLine("Kind of Blue", "Miles Davis", year = 1959, includeYear = true)
        )
    }

    @Test
    fun `a missing year leaves the album alone`() {
        assertEquals(
            "Kind of Blue",
            Y2UiLogic.albumLine("Kind of Blue", "Miles Davis", year = null, includeYear = true)
        )
        assertEquals(
            "Kind of Blue",
            Y2UiLogic.albumLine("Kind of Blue", "Miles Davis", year = 0, includeYear = true)
        )
    }

    @Test
    fun `an album that only repeats the artist stays suppressed`() {
        assertEquals("", Y2UiLogic.albumLine("Miles Davis", "Miles Davis", year = 1959, includeYear = true))
        assertEquals("", Y2UiLogic.albumLine("", "Miles Davis", year = 1959, includeYear = true))
    }
}
