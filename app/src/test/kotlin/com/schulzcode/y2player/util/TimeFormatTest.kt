package com.schulzcode.y2player.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("3:05", TimeFormat.duration(185_000))
    }

    @Test
    fun formatsHours() {
        assertEquals("1:02:03", TimeFormat.duration(3_723_000))
    }

    @Test
    fun clampsNegativeValues() {
        assertEquals("0:00", TimeFormat.duration(-1))
    }
}
