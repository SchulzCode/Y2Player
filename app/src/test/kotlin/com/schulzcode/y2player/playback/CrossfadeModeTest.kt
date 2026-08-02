package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeModeTest {
    @Test
    fun `always mode uses the configured duration regardless of shuffle`() {
        assertEquals(4_000L, CrossfadeMode.ALWAYS.effectiveMs(4_000, shuffleEnabled = false))
        assertEquals(4_000L, CrossfadeMode.ALWAYS.effectiveMs(4_000, shuffleEnabled = true))
    }

    @Test
    fun `shuffle-only mode crossfades a shuffled queue`() {
        assertEquals(4_000L, CrossfadeMode.WHILE_SHUFFLING.effectiveMs(4_000, shuffleEnabled = true))
    }

    @Test
    fun `shuffle-only mode leaves an ordered album gapless`() {
        assertEquals(0L, CrossfadeMode.WHILE_SHUFFLING.effectiveMs(4_000, shuffleEnabled = false))
    }

    @Test
    fun `crossfade off wins over every mode`() {
        for (mode in CrossfadeMode.entries) {
            assertEquals(0L, mode.effectiveMs(0, shuffleEnabled = true))
            assertEquals(0L, mode.effectiveMs(0, shuffleEnabled = false))
        }
    }

    @Test
    fun `negative durations are treated as off`() {
        assertEquals(0L, CrossfadeMode.ALWAYS.effectiveMs(-1, shuffleEnabled = true))
    }

    @Test
    fun `default is always, so existing behaviour is unchanged`() {
        assertEquals(CrossfadeMode.ALWAYS, CrossfadeMode.fromStorage(null))
        assertEquals(CrossfadeMode.ALWAYS, CrossfadeMode.fromStorage("nonsense"))
    }

    @Test
    fun `storage ids round trip`() {
        for (mode in CrossfadeMode.entries) {
            assertEquals(mode, CrossfadeMode.fromStorage(mode.storageId))
            assertEquals(mode, CrossfadeMode.fromStorage(mode.name))
        }
    }

    @Test
    fun `cycling returns to the start`() {
        var mode = CrossfadeMode.ALWAYS
        repeat(CrossfadeMode.entries.size) { mode = mode.next() }
        assertEquals(CrossfadeMode.ALWAYS, mode)
    }

    @Test
    fun `every mode has a distinct storage id and label`() {
        assertEquals(CrossfadeMode.entries.size, CrossfadeMode.entries.map { it.storageId }.toSet().size)
        assertEquals(CrossfadeMode.entries.size, CrossfadeMode.entries.map { it.label }.toSet().size)
    }
}
