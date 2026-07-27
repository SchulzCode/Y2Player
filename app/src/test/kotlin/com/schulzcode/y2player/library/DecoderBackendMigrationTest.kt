package com.schulzcode.y2player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderBackendMigrationTest {
    @Test
    fun ffmpegMigrationClearsFrameworkVerdictsAndProbeResults() {
        assertEquals(9, DecoderBackendMigration.VERSION)
        assertTrue(
            DecoderBackendMigration.RESET_STATEMENTS.any {
                it.contains("playback_error = NULL")
            }
        )
        assertTrue(
            DecoderBackendMigration.RESET_STATEMENTS.any {
                it == "DELETE FROM format_probe"
            }
        )
    }
}
