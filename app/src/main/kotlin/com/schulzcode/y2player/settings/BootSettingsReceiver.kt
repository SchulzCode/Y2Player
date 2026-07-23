package com.schulzcode.y2player.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.schulzcode.y2player.Y2Application

/** Reapplies external Android state after system services have completed boot. */
class BootSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = AppPreferences(context).snapshot().uiSoundEffectsEnabled
        val result = UiSoundEffectsController(context).apply(enabled)
        val application = context.applicationContext as? Y2Application
        application?.container?.logger?.info(
            "Settings",
            "boot UI sounds enabled=$enabled applied=${result.success}"
        )
    }
}
