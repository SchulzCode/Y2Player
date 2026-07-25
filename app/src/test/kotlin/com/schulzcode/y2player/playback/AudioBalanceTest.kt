package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBalanceTest {
    /** Off by default, and "off" has to mean the signal is untouched. */
    @Test fun centreLeavesBothChannelsAtFullGain() {
        assertEquals(0, AudioBalance.CENTRE)
        assertEquals(1f, AudioBalance.leftGain(AudioBalance.CENTRE), 0f)
        assertEquals(1f, AudioBalance.rightGain(AudioBalance.CENTRE), 0f)
        assertTrue(AudioBalance.isCentred(AudioBalance.CENTRE))
    }

    /**
     * Balance attenuates the far channel and never boosts the near one, because
     * MediaPlayer volume saturates at 1.0. Leaning left must lower the right rather
     * than raise the left.
     */
    @Test fun leaningAttenuatesTheOppositeChannelOnly() {
        assertEquals(1f, AudioBalance.leftGain(-50), 0f)
        assertEquals(0.5f, AudioBalance.rightGain(-50), 1e-6f)

        assertEquals(0.5f, AudioBalance.leftGain(50), 1e-6f)
        assertEquals(1f, AudioBalance.rightGain(50), 0f)
    }

    @Test fun fullDeflectionSilencesTheOppositeChannel() {
        assertEquals(1f, AudioBalance.leftGain(-AudioBalance.RANGE), 0f)
        assertEquals(0f, AudioBalance.rightGain(-AudioBalance.RANGE), 0f)

        assertEquals(0f, AudioBalance.leftGain(AudioBalance.RANGE), 0f)
        assertEquals(1f, AudioBalance.rightGain(AudioBalance.RANGE), 0f)
    }

    /** No gain may ever exceed unity, whatever it is handed. */
    @Test fun gainsStayWithinUnityForEveryOfferedLevelAndBeyond() {
        (-500..500).forEach { value ->
            val left = AudioBalance.leftGain(value)
            val right = AudioBalance.rightGain(value)
            assertTrue("left gain $left out of range at $value", left in 0f..1f)
            assertTrue("right gain $right out of range at $value", right in 0f..1f)
            assertTrue("balance must never boost", left == 1f || right == 1f)
        }
    }

    @Test fun outOfRangeValuesClampRatherThanWrap() {
        assertEquals(-AudioBalance.RANGE, AudioBalance.clamp(-9_999))
        assertEquals(AudioBalance.RANGE, AudioBalance.clamp(9_999))
        assertEquals(0f, AudioBalance.rightGain(-9_999), 0f)
        assertFalse(AudioBalance.isCentred(-9_999))
    }

    /** A stored value from a future build must not silence a channel outright. */
    @Test fun theOfferedLevelsSpanTheRangeAndIncludeCentre() {
        val levels = AudioBalance.LEVELS
        assertEquals(21, levels.size)
        assertEquals(-AudioBalance.RANGE, levels.first())
        assertEquals(AudioBalance.RANGE, levels.last())
        assertTrue(AudioBalance.CENTRE in levels)
        assertEquals("levels must be ordered for the settings list", levels.sorted(), levels)
    }

    /** The label is the only thing telling the user which way round it is. */
    @Test fun labelsNameTheSideThatStaysLoud() {
        assertEquals("Centre · off", AudioBalance.label(0))
        assertEquals("Left only", AudioBalance.label(-100))
        assertEquals("Right only", AudioBalance.label(100))
        assertEquals("Left 30%", AudioBalance.label(-30))
        assertEquals("Right 30%", AudioBalance.label(30))
    }
}
