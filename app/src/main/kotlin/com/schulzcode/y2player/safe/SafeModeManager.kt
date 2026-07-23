package com.schulzcode.y2player.safe

import android.content.Context
import com.schulzcode.y2player.diagnostics.DiagnosticLogger

class SafeModeManager(
    context: Context,
    private val logger: DiagnosticLogger
) {
    private val preferences = context.applicationContext.getSharedPreferences("safe_mode", Context.MODE_PRIVATE)
    @Volatile private var safeMode = false
    @Volatile private var uiStartupActive = false

    /** Reads persisted state without marking a service-only process start as a launcher failure. */
    @Synchronized
    fun initializeProcess(): Boolean {
        val failures = safeInt(KEY_FAILURES, 0).coerceAtLeast(0)
        val pendingFailure = if (safeBoolean(KEY_STARTUP_IN_PROGRESS, false)) 1 else 0
        safeMode = safeBoolean(KEY_FORCED, false) || failures + pendingFailure >= MAX_FAILURES
        logger.info("SafeMode", "process initialized failures=$failures pending=$pendingFailure safeMode=$safeMode")
        return safeMode
    }

    /** Call only when the launcher Activity starts. */
    @Synchronized
    fun beginUiStartup(): Boolean {
        if (uiStartupActive) return safeMode
        val previousIncomplete = safeBoolean(KEY_STARTUP_IN_PROGRESS, false)
        var failures = safeInt(KEY_FAILURES, 0).coerceAtLeast(0)
        if (previousIncomplete) failures += 1
        safeMode = safeBoolean(KEY_FORCED, false) || failures >= MAX_FAILURES
        preferences.edit()
            .putBoolean(KEY_STARTUP_IN_PROGRESS, true)
            .putInt(KEY_FAILURES, failures)
            .apply()
        uiStartupActive = true
        logger.info("SafeMode", "UI startup failures=$failures safeMode=$safeMode")
        return safeMode
    }

    @Synchronized
    fun markStartupStable() {
        uiStartupActive = false
        preferences.edit()
            .putBoolean(KEY_STARTUP_IN_PROGRESS, false)
            .putInt(KEY_FAILURES, 0)
            .apply()
        logger.info("SafeMode", "UI startup marked stable")
    }

    @Synchronized
    fun forceSafeMode() {
        safeMode = true
        uiStartupActive = false
        preferences.edit()
            .putBoolean(KEY_FORCED, true)
            .putBoolean(KEY_STARTUP_IN_PROGRESS, false)
            .apply()
        logger.warn("SafeMode", "safe mode forced by user")
    }

    @Synchronized
    fun exitSafeMode() {
        safeMode = false
        uiStartupActive = false
        preferences.edit()
            .putBoolean(KEY_FORCED, false)
            .putBoolean(KEY_STARTUP_IN_PROGRESS, false)
            .putInt(KEY_FAILURES, 0)
            .apply()
        logger.info("SafeMode", "safe mode disabled")
    }

    fun isSafeMode(): Boolean = safeMode

    private fun safeBoolean(key: String, fallback: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, fallback) }.getOrDefault(fallback)

    private fun safeInt(key: String, fallback: Int): Int =
        runCatching { preferences.getInt(key, fallback) }.getOrDefault(fallback)

    companion object {
        private const val KEY_STARTUP_IN_PROGRESS = "startup_in_progress"
        private const val KEY_FAILURES = "startup_failures"
        private const val KEY_FORCED = "forced"
        private const val MAX_FAILURES = 3
    }
}
