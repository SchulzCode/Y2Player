package com.schulzcode.y2player.settings

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/**
 * Reconciles Y2Player's persisted preference with Android's global UI-sound
 * state. The Y2 can start the HOME activity before AudioService finishes boot,
 * so callers reapply this both on activity resume and after BOOT_COMPLETED.
 */
class UiSoundEffectsController(context: Context) {
    private val appContext = context.applicationContext

    fun apply(enabled: Boolean): Result {
        val resolver = appContext.contentResolver
        val target = UiSoundEffectsPolicy.targetValue(enabled)
        val current = runCatching {
            Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, DEFAULT_ENABLED)
        }.getOrNull()
        val persisted = if (UiSoundEffectsPolicy.needsWrite(current, enabled)) {
            runCatching {
                Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, target)
            }.getOrDefault(false)
        } else {
            true
        }

        // The setting database can already contain 0 while AudioService still
        // has samples cached from early boot. Always unload when Off is desired.
        if (!enabled) runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.unloadSoundEffects()
        }

        return if (persisted) {
            Result(true, if (enabled) "UI sounds enabled" else "UI sounds disabled")
        } else {
            Result(false, "Could not change UI sounds; firmware permission is required")
        }
    }

    data class Result(val success: Boolean, val message: String)

    private companion object {
        const val DEFAULT_ENABLED = 1
    }
}

internal object UiSoundEffectsPolicy {
    fun targetValue(enabled: Boolean): Int = if (enabled) 1 else 0
    fun needsWrite(current: Int?, enabled: Boolean): Boolean = current != targetValue(enabled)
}
