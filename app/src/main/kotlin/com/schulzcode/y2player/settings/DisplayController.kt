package com.schulzcode.y2player.settings

import android.app.Activity
import android.provider.Settings
import com.schulzcode.y2player.core.state.DisplayState
import com.schulzcode.y2player.core.state.ScreenContent
import kotlin.math.roundToInt

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
            brightnessPercent = sessionBrightnessPercent ?: BrightnessConversion.toPercent(brightness),
            screenTimeoutMs = timeout
        )
    }

    fun setBrightness(percent: Int): String {
        val safePercent = percent.coerceIn(5, 100)
        val raw = BrightnessConversion.toRaw(safePercent)
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
        return if (persisted) "Brightness set to $safePercent%" else {
            "Brightness changed for this session; firmware permission is still required for persistence"
        }
    }

    fun setTimeout(timeoutMs: Int): String {
        val persisted = runCatching {
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                timeoutMs
            )
        }.getOrDefault(false)
        return if (persisted) {
            "Screen timeout set to ${ScreenContent.timeoutLabel(timeoutMs)}"
        } else {
            "Unable to change timeout; install Y2Player as a system app with WRITE_SETTINGS"
        }
    }
}

internal object BrightnessConversion {
    private const val MAX_RAW = 255
    private const val MAX_PERCENT = 100

    fun toPercent(raw: Int): Int = ((raw.coerceIn(0, MAX_RAW) * MAX_PERCENT.toFloat()) / MAX_RAW)
        .roundToInt()
        .coerceIn(0, MAX_PERCENT)

    fun toRaw(percent: Int): Int = ((percent.coerceIn(0, MAX_PERCENT) / MAX_PERCENT.toFloat()) * MAX_RAW)
        .toInt()
        .coerceIn(0, MAX_RAW)
}
