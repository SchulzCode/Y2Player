package com.schulzcode.y2player.backup

/** Pure coordination logic used by tests to exercise failures at every import boundary. */
internal object ImportTransactionCoordinator {
    fun apply(
        databaseTransaction: (applyExternal: () -> Unit) -> Unit,
        applyHistory: () -> Boolean,
        applySettings: () -> Boolean,
        rollbackHistory: () -> Boolean,
        rollbackSettings: () -> Boolean
    ) {
        try {
            databaseTransaction {
                if (!applyHistory()) error("Listening history could not be restored")
                if (!applySettings()) error("Settings could not be restored")
            }
        } catch (error: Throwable) {
            val settingsRestored = rollbackSettings()
            val historyRestored = rollbackHistory()
            if (!settingsRestored || !historyRestored) {
                throw IllegalStateException("Import failed and external rollback was incomplete", error)
            }
            throw error
        }
    }
}
