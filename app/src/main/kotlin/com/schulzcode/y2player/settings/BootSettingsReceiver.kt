package com.schulzcode.y2player.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.schulzcode.y2player.Y2Application

class BootSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = AppPreferences(context).snapshot().uiSoundEffectsEnabled
        val soundResult = UiSoundEffectsController(context).apply(enabled)
        val hapticResult = SystemHapticsController(context).suppress()
        val application = context.applicationContext as? Y2Application
        application?.container?.logger?.info(
            "Settings",
            "boot UI sounds enabled=$enabled applied=${soundResult.success}; " +
                "platform haptics disabled=${hapticResult.success} previous=${hapticResult.previousValue}"
        )
        if (!hapticResult.success) {
            application?.container?.logger?.warn("Settings", hapticResult.message)
        }
    }
}
