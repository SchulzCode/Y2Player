package com.schulzcode.y2player.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportTransactionCoordinatorTest {
    @Test fun failureAfterExternalApplyRollsBackDatabaseSettingsAndHistory() {
        var database = "old-db"
        var settings = "old-settings"
        var history = "old-history"

        assertThrows(IllegalStateException::class.java) {
            ImportTransactionCoordinator.apply(
                databaseTransaction = { applyExternal ->
                    val old = database
                    try {
                        database = "new-db"
                        applyExternal()
                        error("simulated commit failure")
                    } catch (error: Throwable) {
                        database = old
                        throw error
                    }
                },
                applyHistory = { history = "new-history"; true },
                applySettings = { settings = "new-settings"; true },
                rollbackHistory = { history = "old-history"; true },
                rollbackSettings = { settings = "old-settings"; true }
            )
        }

        assertEquals("old-db", database)
        assertEquals("old-settings", settings)
        assertEquals("old-history", history)
    }

    @Test fun settingsFailureAlsoRollsBackAnAlreadyStagedHistory() {
        var history = "old"
        assertThrows(IllegalStateException::class.java) {
            ImportTransactionCoordinator.apply(
                databaseTransaction = { it() },
                applyHistory = { history = "new"; true },
                applySettings = { false },
                rollbackHistory = { history = "old"; true },
                rollbackSettings = { true }
            )
        }
        assertEquals("old", history)
    }
}
