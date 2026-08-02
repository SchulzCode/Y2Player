package com.schulzcode.y2player.settings

import android.content.Context
import android.provider.Settings

@Suppress("DEPRECATION")
class SystemHapticsController(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun suppress(): Result {
        val current = runCatching {
            Settings.System.getInt(
                resolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                SystemHapticsPolicy.DEFAULT_ENABLED
            )
        }.getOrNull()
        val persisted = if (SystemHapticsPolicy.needsWrite(current)) {
            runCatching {
                Settings.System.putInt(
                    resolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    SystemHapticsPolicy.TARGET_DISABLED
                )
            }.getOrDefault(false)
        } else {
            true
        }
        return Result(persisted, current)
    }

    data class Result(val success: Boolean, val previousValue: Int?) {
        val message: String
            get() = if (success) {
                "Platform haptics disabled"
            } else {
                "Could not disable platform haptics; firmware WRITE_SETTINGS permission is required"
            }
    }
}

internal object SystemHapticsPolicy {
    const val TARGET_DISABLED = 0
    const val DEFAULT_ENABLED = 1

    fun needsWrite(current: Int?): Boolean = current != TARGET_DISABLED
}
