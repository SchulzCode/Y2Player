package com.schulzcode.y2player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTransitionPolicyTest {
    @Test fun unmountDuringPlaybackOrScanningIsClassifiedImmediately() {
        val changes = StorageTransitionPolicy.classify(
            previous = mapOf("sdcard" to true),
            current = mapOf("sdcard" to false),
            firstSnapshot = false
        )
        assertEquals(setOf("sdcard"), changes.becameUnavailable)
        assertTrue(changes.becameAvailable.isEmpty())
    }

    @Test fun missingStorageAtStartupUsesGracePath() {
        val changes = StorageTransitionPolicy.classify(
            previous = emptyMap(),
            current = mapOf("sdcard" to false),
            firstSnapshot = true
        )
        assertEquals(setOf("sdcard"), changes.missingAtStartup)
        assertTrue(changes.becameUnavailable.isEmpty())
    }

    @Test fun storageReturningAfterStartupSchedulesRecovery() {
        val changes = StorageTransitionPolicy.classify(
            previous = mapOf("sdcard" to false),
            current = mapOf("sdcard" to true),
            firstSnapshot = false
        )
        assertEquals(setOf("sdcard"), changes.becameAvailable)
    }
}
