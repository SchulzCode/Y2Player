package com.schulzcode.y2player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemHapticsPolicyTest {
    @Test fun platformHapticsAreAlwaysTargetedOff() {
        assertEquals(0, SystemHapticsPolicy.TARGET_DISABLED)
    }

    @Test fun disabledSystemValueNeedsNoWrite() {
        assertFalse(SystemHapticsPolicy.needsWrite(0))
    }

    @Test fun missingOrEnabledSystemValueIsReconciled() {
        assertTrue(SystemHapticsPolicy.needsWrite(null))
        assertTrue(SystemHapticsPolicy.needsWrite(1))
    }
}
