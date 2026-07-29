package com.schulzcode.y2player.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePathCacheTest {
    @Test fun unreadTimestampIsNeverFresh() {
        assertFalse(isFreshUptimeReading(nowMs = 16_000, readAtMs = -1, maxAgeMs = 500))
    }

    @Test fun legacyMinimumTimestampCannotOverflowIntoFreshResult() {
        assertFalse(
            isFreshUptimeReading(
                nowMs = 16_000,
                readAtMs = Long.MIN_VALUE,
                maxAgeMs = 500
            )
        )
    }

    @Test fun readingExpiresAfterMaximumAge() {
        assertTrue(isFreshUptimeReading(nowMs = 1_500, readAtMs = 1_000, maxAgeMs = 500))
        assertFalse(isFreshUptimeReading(nowMs = 1_501, readAtMs = 1_000, maxAgeMs = 500))
    }

    @Test fun clockMovingBehindReadingInvalidatesIt() {
        assertFalse(isFreshUptimeReading(nowMs = 999, readAtMs = 1_000, maxAgeMs = 500))
    }
}
