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
    fun `format metadata is ordered for quick scanning`() {
        assertEquals(
            "FLAC · 932 kbps · 44.1 kHz · 16-bit",
            Y2UiLogic.technicalLine("FLAC", 44_100, 16, bitrate = 932_000)
        )
    }

    @Test
    fun `missing extra fields are omitted rather than blank`() {
        assertEquals(
            "MP3 · 320 kbps · 44.1 kHz",
            Y2UiLogic.technicalLine("MP3", 44_100, null, bitrate = 320_000)
        )
    }

    @Test
    fun `zero and negative metadata is treated as absent`() {
        assertEquals("OPUS", Y2UiLogic.technicalLine("OPUS", 0, 0, bitrate = 0))
        assertEquals("OPUS", Y2UiLogic.technicalLine("OPUS", -1, -1, bitrate = -1))
    }

    @Test
    fun `genre whitespace is trimmed`() {
        assertEquals("Rock", Y2UiLogic.genreLine("  Rock  "))
    }

    @Test
    fun `unicode and CJK genres survive intact`() {
        assertEquals("邦楽", Y2UiLogic.genreLine("邦楽"))
        assertEquals("Café", Y2UiLogic.genreLine("Café"))
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
            Y2UiLogic.albumLine("Kind of Blue", year = 1959, includeYear = false)
        )
    }

    @Test
    fun `album carries the year when the setting is on`() {
        assertEquals(
            "Kind of Blue (1959)",
            Y2UiLogic.albumLine("Kind of Blue", year = 1959, includeYear = true)
        )
    }

    @Test
    fun `a missing year leaves the album alone`() {
        assertEquals(
            "Kind of Blue",
            Y2UiLogic.albumLine("Kind of Blue", year = null, includeYear = true)
        )
        assertEquals(
            "Kind of Blue",
            Y2UiLogic.albumLine("Kind of Blue", year = 0, includeYear = true)
        )
    }

    @Test
    fun `an album that matches the artist remains visible`() {
        assertEquals("Miles Davis (1959)", Y2UiLogic.albumLine("Miles Davis", year = 1959, includeYear = true))
        assertEquals("", Y2UiLogic.albumLine("", year = 1959, includeYear = true))
    }
}
