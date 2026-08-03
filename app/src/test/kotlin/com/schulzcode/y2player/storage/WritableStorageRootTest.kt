package com.schulzcode.y2player.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WritableStorageRootTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `removable card is preferred for user-copyable exports`() {
        val internal = StorageRoot("internal", temporary.newFolder("internal"))
        val sdcard = StorageRoot("sdcard", temporary.newFolder("sdcard"))

        assertEquals(sdcard, preferredWritableRoot(listOf(internal, sdcard)))
    }
}
