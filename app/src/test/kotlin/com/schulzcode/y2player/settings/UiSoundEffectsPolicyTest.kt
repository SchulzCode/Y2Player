package com.schulzcode.y2player.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSoundEffectsPolicyTest {
    @Test fun matchingSystemValueNeedsNoWrite() {
        assertFalse(UiSoundEffectsPolicy.needsWrite(0, enabled = false))
        assertFalse(UiSoundEffectsPolicy.needsWrite(1, enabled = true))
    }

    @Test fun missingOrDifferentSystemValueIsReconciled() {
        assertTrue(UiSoundEffectsPolicy.needsWrite(null, enabled = false))
        assertTrue(UiSoundEffectsPolicy.needsWrite(1, enabled = false))
        assertTrue(UiSoundEffectsPolicy.needsWrite(0, enabled = true))
    }
}
