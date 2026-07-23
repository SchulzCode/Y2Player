package com.schulzcode.y2player.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.provider.Settings
import com.schulzcode.y2player.core.state.DisplayState
import com.schulzcode.y2player.core.state.ScreenContent

class DisplayController(private val activity: Activity) {
    private var sessionBrightnessPercent: Int? = null
    fun snapshot(): DisplayState {
        val resolver = activity.contentResolver
        val brightness = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
        val timeout = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            60_000
        )
        return DisplayState(
            brightnessPercent = sessionBrightnessPercent ?: brightnessToPercent(brightness),
            screenTimeoutMs = timeout,
            canWriteSystemSettings = activity.checkCallingOrSelfPermission(
                Manifest.permission.WRITE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    fun setBrightness(percent: Int): Result {
        val safePercent = percent.coerceIn(5, 100)
        val raw = percentToBrightness(safePercent)
        val windowApplied = runCatching {
            val attributes = activity.window.attributes
            attributes.screenBrightness = raw / 255f
            activity.window.attributes = attributes
        }.isSuccess
        val persisted = runCatching {
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            ) && Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                raw
            )
        }.getOrDefault(false)
        sessionBrightnessPercent = when {
            persisted -> null
            windowApplied -> safePercent
            else -> sessionBrightnessPercent
        }
        return Result(
            success = persisted || windowApplied,
            message = if (persisted) "Brightness set to $safePercent%" else {
                "Brightness changed for this session; firmware permission is still required for persistence"
            }
        )
    }

    fun setTimeout(timeoutMs: Int): Result {
        val persisted = runCatching {
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                timeoutMs
            )
        }.getOrDefault(false)
        return if (persisted) {
            // Single label owner: ScreenContent renders the same values in the UI.
            Result(true, "Screen timeout set to ${ScreenContent.timeoutLabel(timeoutMs)}")
        } else {
            Result(false, "Unable to change timeout; install Y2Player as a system app with WRITE_SETTINGS")
        }
    }

    data class Result(val success: Boolean, val message: String)

    companion object {
        private fun brightnessToPercent(value: Int): Int = ((value.coerceIn(1, 255) * 100f) / 255f)
            .toInt()
            .coerceIn(1, 100)

        private fun percentToBrightness(percent: Int): Int = ((percent.coerceIn(1, 100) / 100f) * 255f)
            .toInt()
            .coerceIn(1, 255)
    }
}
