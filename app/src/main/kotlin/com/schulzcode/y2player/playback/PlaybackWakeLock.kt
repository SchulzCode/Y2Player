package com.schulzcode.y2player.playback

import android.content.Context
import android.os.PowerManager
import com.schulzcode.y2player.core.model.PlaybackStatus

internal object PlaybackWakeLockPolicy {
    fun shouldHold(status: PlaybackStatus): Boolean =
        status == PlaybackStatus.PREPARING || status == PlaybackStatus.PLAYING
}

/** Keeps the CPU awake across decoder-to-service handoffs while the display is asleep. */
internal class PlaybackWakeLock(context: Context) {
    private val wakeLock = (context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y2Player:PlaybackService")
        .apply { setReferenceCounted(false) }

    fun sync(status: PlaybackStatus) {
        val required = PlaybackWakeLockPolicy.shouldHold(status)
        when {
            required && !wakeLock.isHeld -> wakeLock.acquire()
            !required && wakeLock.isHeld -> wakeLock.release()
        }
    }

    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}
