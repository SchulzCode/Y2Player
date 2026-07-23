package com.schulzcode.y2player.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPressClassifierTest {
    @Test fun classifiesFrameworkRepeatAndElapsedLongPress() {
        assertTrue(InputPressClassifier.isLongPress(true, 0, 10))
        assertTrue(InputPressClassifier.isLongPress(false, 3, 100))
        assertTrue(InputPressClassifier.isLongPress(false, 0, 650))
        assertFalse(InputPressClassifier.isLongPress(false, 2, 649))
    }
}
