package com.schulzcode.y2player.settings

import com.schulzcode.y2player.core.state.ScreenContent
import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessConversionTest {
    @Test fun everyPresetSurvivesAndroidBrightnessRoundTrip() {
        val presetsAndMinimum = listOf(0) + ScreenContent.BRIGHTNESS_LEVELS

        presetsAndMinimum.forEach { percent ->
            assertEquals(percent, BrightnessConversion.toPercent(BrightnessConversion.toRaw(percent)))
        }
        assertEquals(0, BrightnessConversion.toPercent(-1))
        assertEquals(100, BrightnessConversion.toPercent(256))
        assertEquals(0, BrightnessConversion.toRaw(-1))
        assertEquals(255, BrightnessConversion.toRaw(101))
    }
}
